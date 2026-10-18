package com.chamelex.app.ui.screens.reader

import androidx.annotation.StringRes
import com.chamelex.app.R
import com.chamelex.crypto.NativeCrypto
import com.chamelex.protocol.CrackCommands
import com.chamelex.protocol.HfTagInfo
import com.chamelex.protocol.Mf1Commands
import com.chamelex.protocol.MfcKeyType
import com.chamelex.protocol.MfcType
import com.chamelex.protocol.MifareClassicPrngType
import com.chamelex.protocol.ReaderCommands
import com.chamelex.protocol.SectorKeys
import com.chamelex.protocol.hardNestedNoncePairs
import com.chamelex.protocol.maskAllSet
import com.chamelex.protocol.maskSet
import com.chamelex.protocol.mfcBlocksInSector
import com.chamelex.protocol.mfcFirstBlockOfSector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** autopwn 进度上报。 */
data class AutopwnProgress(
    @StringRes val stageRes: Int,
    val detail: String = "",
    val keysFound: Int = 0,
    val keysTotal: Int = 0,
    val attempts: Int = 0,
    /** 密钥状态网格（扇区数 + 每密钥位是否命中），null=不展示 */
    val keyGrid: Pair<Int, List<Boolean>>? = null,
)

class AutopwnException(@StringRes val msgRes: Int, cause: String? = null) : Exception(cause)

/**
 * 自动破解管线（移植官方 CLI `hf mf autopwn` 编排）：
 * 字典检测 → 无已知密钥则 Darkside 拿首钥 → 按 PRNG 类型 Nested/StaticNested/HardNested
 * 扩展剩余密钥 → 每把新密钥做复用检测 → dump 全卡。
 */
class AutopwnRunner(
    private val mf1: Mf1Commands,
    private val crack: CrackCommands,
    private val reader: ReaderCommands,
    private val cacheDir: String,
    private val onProgress: suspend (AutopwnProgress) -> Unit,
) {
    companion object {
        /** hardnested_utils.hardnested_sums */
        val HARDNESTED_SUMS = intArrayOf(0, 32, 56, 64, 80, 96, 104, 112, 120, 128, 136, 144, 152, 160, 176, 192, 200, 224, 256)
        const val DARKSIDE_SYNC_MAX = 30
        const val DARKSIDE_MAX_RETRY = 24
        const val HN_MAX_RUNS = 200
        const val HN_MAX_ATTEMPTS = 3
        /** gen1a 静态卡已知后门密钥（CLI run_senested 提及） */
        val BACKDOOR_KEYS = listOf("A396EFA4E24F", "A31667A8CEC1", "518B3354E760")
    }

    private var attempts = 0
    private lateinit var mfcType: MfcType
    private lateinit var sectorKeys: MutableList<SectorKeys>
    private lateinit var mask: ByteArray
    private var keysFoundBits = 0
    private lateinit var uid: ByteArray

    val uidForNative: Long
        get() = ((uid[uid.size - 4].toLong() and 0xFF) shl 24) or
                ((uid[uid.size - 3].toLong() and 0xFF) shl 16) or
                ((uid[uid.size - 2].toLong() and 0xFF) shl 8) or
                (uid[uid.size - 1].toLong() and 0xFF)

    private fun keysTotalBits() = mfcType.sectorCount * 2

    /** 主流程。 */
    suspend fun run(tag: HfTagInfo, dictionary: List<ByteArray>): M1DumpResult {
        mfcType = MfcType.fromSak(tag.sak) ?: throw AutopwnException(R.string.autopwn_not_m1)
        uid = tag.uid
        sectorKeys = MutableList(mfcType.sectorCount) { SectorKeys() }
        mask = com.chamelex.protocol.createCheckMask(mfcType.sectorCount)
        keysFoundBits = 0
        attempts = 0

        // 1. 字典检测
        dictCheck(dictionary)
        if (keysFoundBits >= keysTotalBits()) return dumpAll()

        // 2. 无已知密钥 → Darkside 拿首钥
        if (keysFoundBits == 0) {
            val prng = crack.mf1DetectPrng()
            if (prng == MifareClassicPrngType.STATIC) {
                // 静态卡 darkside 无效，先试已知后门密钥（gen1a 后门卡）
                tryBackdoorKeys()
            }
            if (keysFoundBits == 0) {
                darksideFirstKey()
            }
        }
        if (keysFoundBits >= keysTotalBits()) return dumpAll()
        if (keysFoundBits == 0) throw AutopwnException(R.string.autopwn_no_first_key)

        // 3. 按 PRNG 类型扩展剩余密钥
        val prng = crack.mf1DetectPrng()
        for (sector in 0 until mfcType.sectorCount) {
            for (isKeyA in listOf(true, false)) {
                currentCoroutineContext().ensureActive()
                if (sectorKey(sector, isKeyA) != null) continue
                val keyType = if (isKeyA) MfcKeyType.A else MfcKeyType.B
                val key = when (prng) {
                    MifareClassicPrngType.WEAK -> {
                        nestedRecover(sector, keyType)
                            ?: hardnestedRecover(sector, keyType)
                    }
                    MifareClassicPrngType.HARD -> hardnestedRecover(sector, keyType)
                    MifareClassicPrngType.STATIC -> {
                        staticNestedRecover(sector, keyType)
                            ?: hardnestedRecover(sector, keyType)
                    }
                    null -> nestedRecover(sector, keyType)
                } ?: continue
                onRecoveredKey(sector, isKeyA, key)
            }
        }

        // 4. dump
        return dumpAll()
    }

    // ---- 阶段 1：字典检测（同 3b 逻辑） ----

    private suspend fun dictCheck(dictionary: List<ByteArray>) {
        for (batch in dictionary.chunked(Mf1Commands.CHECK_KEYS_BATCH_SIZE)) {
            currentCoroutineContext().ensureActive()
            if (maskAllSet(mask)) break
            onProgress(
                AutopwnProgress(
                    R.string.autopwn_stage_dict,
                    keysFound = keysFoundBits, keysTotal = keysTotalBits(), attempts = attempts,
                )
            )
            attempts++
            val result = mf1.mf1CheckKeysOfSectors(mask, batch)
            mergeCheckKeysResult(result)
            onProgress(
                AutopwnProgress(
                    R.string.autopwn_stage_dict,
                    keysFound = keysFoundBits, keysTotal = keysTotalBits(), attempts = attempts,
                    keyGrid = currentGrid(),
                )
            )
        }
    }

    private fun currentGrid(): Pair<Int, List<Boolean>> =
        mfcType.sectorCount to (0 until mfcType.sectorCount * 2).map { com.chamelex.protocol.maskGet(mask, it) }

    private fun mergeCheckKeysResult(result: com.chamelex.protocol.CheckKeysResult) {
        for (k in 0 until Mf1Commands.MASK_BITS) {
            if (!result.isFound(k)) continue
            maskSet(mask, k)
            keysFoundBits++
            val sector = k / 2
            if (sector >= mfcType.sectorCount) continue
            val key = result.keyAt(k)
            sectorKeys[sector] =
                if (k % 2 == 0) sectorKeys[sector].copy(keyA = key)
                else sectorKeys[sector].copy(keyB = key)
        }
    }

    // ---- Darkside 首钥 ----

    private suspend fun darksideFirstKey() {
        val acquisitions = mutableListOf<com.chamelex.protocol.DarksideAcquisition>()
        var firstRecover = true
        repeat(DARKSIDE_MAX_RETRY) {
            currentCoroutineContext().ensureActive()
            attempts++
            onProgress(
                AutopwnProgress(
                    R.string.autopwn_stage_darkside,
                    detail = "${it + 1}",
                    keysFound = keysFoundBits, keysTotal = keysTotalBits(), attempts = attempts,
                )
            )
            val resp = crack.mf1DarksideAcquire(
                MfcKeyType.A, 0x03, firstRecover, DARKSIDE_SYNC_MAX,
            )
            firstRecover = false
            val acq = resp.acquisition
            if (acq == null) {
                throw AutopwnException(R.string.autopwn_darkside_fail, resp.status?.name)
            }
            if (acq.par != 0L) {
                acquisitions.clear() // NXP tag workaround
            }
            acquisitions.add(acq)
            val flat = LongArray(acquisitions.size * 5)
            acquisitions.forEachIndexed { i, a ->
                flat[i * 5 + 0] = a.nt1
                flat[i * 5 + 1] = a.par
                flat[i * 5 + 2] = a.ks1
                flat[i * 5 + 3] = a.nr
                flat[i * 5 + 4] = a.ar
            }
            val candidates = withContext(Dispatchers.Default) {
                NativeCrypto.darksideRecover(acq.uid, flat)
            }
            for (keyLong in candidates) {
                currentCoroutineContext().ensureActive()
                val key = keyLong.toKeyBytes()
                if (mf1.mf1AuthOneKeyBlock(MfcKeyType.A, 0x03, key)) {
                    onRecoveredKey(0, true, key)
                    return
                }
            }
        }
        throw AutopwnException(R.string.autopwn_darkside_fail, "retry exhausted")
    }

    // ---- Nested ----

    private suspend fun nestedRecover(sector: Int, keyType: MfcKeyType): ByteArray? {
        val (knownBlock, knownType, knownKey) = firstKnownKey() ?: return null
        attempts++
        onProgress(
            AutopwnProgress(
                R.string.autopwn_stage_nested,
                detail = "$sector/${if (keyType == MfcKeyType.A) "A" else "B"}",
                keysFound = keysFoundBits, keysTotal = keysTotalBits(), attempts = attempts,
            )
        )
        val (distUid, dist) = crack.mf1DetectNtDist(knownType, knownBlock, knownKey)
        val nonces = crack.mf1NestedAcquire(
            knownType, knownBlock, knownKey, keyType, mfcFirstBlockOfSector(sector),
        )
        val flat = LongArray(nonces.size * 3)
        nonces.forEachIndexed { i, n ->
            flat[i * 3 + 0] = n.nt
            flat[i * 3 + 1] = n.ntEnc
            flat[i * 3 + 2] = n.par.toLong()
        }
        val candidates = withContext(Dispatchers.Default) {
            NativeCrypto.nestedRecover(distUid, dist, flat)
        }
        return authFirstCandidate(candidates, keyType, mfcFirstBlockOfSector(sector))
    }

    // ---- StaticNested ----

    private suspend fun staticNestedRecover(sector: Int, keyType: MfcKeyType): ByteArray? {
        val (knownBlock, knownType, knownKey) = firstKnownKey() ?: return null
        attempts++
        onProgress(
            AutopwnProgress(
                R.string.autopwn_stage_staticnested,
                detail = "$sector/${if (keyType == MfcKeyType.A) "A" else "B"}",
                keysFound = keysFoundBits, keysTotal = keysTotalBits(), attempts = attempts,
            )
        )
        val result = crack.mf1StaticNestedAcquire(
            knownType, knownBlock, knownKey, keyType, mfcFirstBlockOfSector(sector),
        )
        val flat = LongArray(result.nts.size * 2)
        result.nts.forEachIndexed { i, (nt, ntEnc) ->
            flat[i * 2 + 0] = nt
            flat[i * 2 + 1] = ntEnc
        }
        val candidates = withContext(Dispatchers.Default) {
            NativeCrypto.staticNestedRecover(result.uid, keyType.code, flat)
        }
        return authFirstCandidate(candidates, keyType, mfcFirstBlockOfSector(sector))
    }

    // ---- HardNested ----

    private suspend fun hardnestedRecover(sector: Int, keyType: MfcKeyType): ByteArray? {
        val (knownBlock, knownType, knownKey) = firstKnownKey() ?: return null
        val blockTarget = mfcFirstBlockOfSector(sector)
        val keyTypeNative = keyType.code and 0x01 // 0=A / 1=B

        var rawNonces: ByteArray? = null
        for (attempt in 1..HN_MAX_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            rawNonces = acquireHardNestedNonces(
                attempt, knownType, knownBlock, knownKey, keyType, blockTarget, sector,
            ) ?: continue
            break
        }
        if (rawNonces == null) {
            onProgress(
                AutopwnProgress(
                    R.string.autopwn_stage_hardnested,
                    detail = "acquire failed",
                    keysFound = keysFoundBits, keysTotal = keysTotalBits(), attempts = attempts,
                )
            )
            return null
        }

        // 组 nonce 文本（与官方 hardnested_main 转换一致：每对两行）
        val sb = StringBuilder()
        var i = 0
        while (i + 9 <= rawNonces.size) {
            val nt = readU32(rawNonces, i)
            val ntEnc = readU32(rawNonces, i + 4)
            val par = rawNonces[i + 8].toInt() and 0xFF
            sb.append(nt).append('|').append(par shr 4).append('\n')
            sb.append(ntEnc).append('|').append(par and 0x0F).append('\n')
            i += 9
        }

        attempts++
        onProgress(
            AutopwnProgress(
                R.string.autopwn_stage_hardnested,
                detail = "bruteforce",
                keysFound = keysFoundBits, keysTotal = keysTotalBits(), attempts = attempts,
            )
        )
        // native 运行期间轮询 bruteforce 进度
        val poller = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
            while (true) {
                delay(500)
                val p = NativeCrypto.hardnestedProgress()
                onProgress(
                    AutopwnProgress(
                        R.string.autopwn_stage_hardnested,
                        detail = p,
                        keysFound = keysFoundBits, keysTotal = keysTotalBits(), attempts = attempts,
                    )
                )
            }
        }
        val key = try {
            withContext(Dispatchers.Default) {
                NativeCrypto.hardnestedRecover(
                    uidForNative, blockTarget, keyTypeNative, sb.toString(), cacheDir,
                )
            }
        } finally {
            poller.cancel()
        }
        if (key < 0) return null
        val keyBytes = key.toKeyBytes()
        return if (mf1.mf1AuthOneKeyBlock(keyType, blockTarget, keyBytes)) keyBytes else null
    }

    /** 采集 HardNested nonces（MSB 唯一性 + parity sum 校验，移植 CLI 循环）。成功返回原始字节。 */
    private suspend fun acquireHardNestedNonces(
        attempt: Int,
        knownType: MfcKeyType,
        knownBlock: Int,
        knownKey: ByteArray,
        keyType: MfcKeyType,
        blockTarget: Int,
        sector: Int,
    ): ByteArray? {
        val seenMsbs = BooleanArray(256)
        var uniqueMsbCount = 0
        var msbParitySum = 0
        var totalBytes = ByteArray(0)
        var run = 0
        while (run < HN_MAX_RUNS) {
            currentCoroutineContext().ensureActive()
            run++
            attempts++
            onProgress(
                AutopwnProgress(
                    R.string.autopwn_stage_hn_acquire,
                    detail = "$uniqueMsbCount/256 ($attempt/$HN_MAX_ATTEMPTS)",
                    keysFound = keysFoundBits, keysTotal = keysTotalBits(), attempts = attempts,
                )
            )
            // 每轮前确认卡还在
            val scan = reader.hf14aScan()
            if (scan.isEmpty() || !scan[0].uid.contentEquals(uid)) {
                throw com.chamelex.protocol.CardLostException()
            }
            val raw = crack.mf1HardNestedAcquire(
                false, knownType, knownBlock, knownKey, keyType, blockTarget,
            )
            if (raw.isEmpty()) {
                delay(100)
                continue
            }
            totalBytes += raw
            val pairs = hardNestedNoncePairs(raw)
            for (p in 0 until pairs) {
                val off = p * 9
                val ntEnc = readU32(raw, off + 4).toLong()
                val par = raw[off + 8].toInt() and 0xFF
                val msb = ((ntEnc shr 24) and 0xFF).toInt()
                if (!seenMsbs[msb]) {
                    seenMsbs[msb] = true
                    uniqueMsbCount++
                    msbParitySum += Integer.bitCount(((ntEnc and 0xFF000000L) or (par.toLong() and 0x08L)).toInt()) % 2
                }
            }
            if (uniqueMsbCount == 256) {
                return if (msbParitySum in HARDNESTED_SUMS) totalBytes else null
            }
        }
        return null
    }

    // ---- 工具 ----

    private fun sectorKey(sector: Int, isKeyA: Boolean): ByteArray? =
        if (isKeyA) sectorKeys[sector].keyA else sectorKeys[sector].keyB

    private fun firstKnownKey(): Triple<Int, MfcKeyType, ByteArray>? {
        for (sector in 0 until mfcType.sectorCount) {
            sectorKeys[sector].keyA?.let {
                return Triple(mfcFirstBlockOfSector(sector), MfcKeyType.A, it)
            }
            sectorKeys[sector].keyB?.let {
                return Triple(mfcFirstBlockOfSector(sector), MfcKeyType.B, it)
            }
        }
        return null
    }

    /** 新密钥采信 + 复用检测（CLI try_key 语义）。 */
    private suspend fun onRecoveredKey(sector: Int, isKeyA: Boolean, key: ByteArray) {
        sectorKeys[sector] =
            if (isKeyA) sectorKeys[sector].copy(keyA = key)
            else sectorKeys[sector].copy(keyB = key)
        maskSet(mask, sector * 2 + if (isKeyA) 0 else 1)
        keysFoundBits++
        // 复用检测：对剩余未命中位跑单密钥 CHECK_KEYS
        if (!maskAllSet(mask)) {
            val result = mf1.mf1CheckKeysOfSectors(mask, listOf(key))
            mergeCheckKeysResult(result)
        }
        onProgress(
            AutopwnProgress(
                R.string.autopwn_stage_cracking,
                keysFound = keysFoundBits, keysTotal = keysTotalBits(), attempts = attempts,
            )
        )
    }

    private suspend fun tryBackdoorKeys() {
        for (hex in BACKDOOR_KEYS) {
            currentCoroutineContext().ensureActive()
            if (maskAllSet(mask)) return
            val key = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val result = mf1.mf1CheckKeysOfSectors(mask, listOf(key))
            mergeCheckKeysResult(result)
        }
    }

    private suspend fun authFirstCandidate(
        candidates: LongArray,
        keyType: MfcKeyType,
        block: Int,
    ): ByteArray? {
        for (c in candidates) {
            currentCoroutineContext().ensureActive()
            val key = c.toKeyBytes()
            if (mf1.mf1AuthOneKeyBlock(keyType, block, key)) {
                return key
            }
        }
        return null
    }

    private suspend fun dumpAll(): M1DumpResult {
        val blocks = ArrayList<M1Block>(mfcType.blockCount)
        var blocksRead = 0
        for (sector in 0 until mfcType.sectorCount) {
            val keys = sectorKeys[sector]
            val blockCount = mfcBlocksInSector(sector)
            val firstBlock = mfcFirstBlockOfSector(sector)
            for (i in 0 until blockCount) {
                currentCoroutineContext().ensureActive()
                val blockIndex = firstBlock + i
                val data = readBlockWithKeys(blockIndex, keys)
                blocks.add(M1Block(blockIndex, data, isTrailer = i == blockCount - 1))
                blocksRead++
                if (blocksRead % 8 == 0) {
                    onProgress(
                        AutopwnProgress(
                            R.string.autopwn_stage_dump,
                            detail = "$blocksRead/${mfcType.blockCount}",
                            keysFound = keysFoundBits, keysTotal = keysTotalBits(), attempts = attempts,
                        )
                    )
                }
            }
        }
        return M1DumpResult(
            uidHex = uid.joinToString("") { "%02X".format(it) },
            mfcType = mfcType,
            sectorKeys = sectorKeys,
            blocks = blocks,
        )
    }

    private suspend fun readBlockWithKeys(block: Int, keys: SectorKeys): ByteArray? {
        val keyA = keys.keyA
        val keyB = keys.keyB
        if (keyA != null) {
            try {
                return mf1.mf1ReadOneBlock(MfcKeyType.A, block, keyA)
            } catch (_: com.chamelex.protocol.AuthFailedException) {
            }
        }
        if (keyB != null) {
            try {
                return mf1.mf1ReadOneBlock(MfcKeyType.B, block, keyB)
            } catch (_: com.chamelex.protocol.AuthFailedException) {
            }
        }
        return null
    }

    private fun readU32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)

    private fun Long.toKeyBytes(): ByteArray {
        val out = ByteArray(6)
        for (i in 0 until 6) out[i] = (this shr (8 * (5 - i))).toByte()
        return out
    }
}
