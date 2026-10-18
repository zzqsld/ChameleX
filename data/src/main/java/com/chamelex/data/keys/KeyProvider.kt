package com.chamelex.data.keys

import android.content.Context
import com.chamelex.data.db.CardKeysDao
import com.chamelex.data.db.CardKeysEntity
import com.chamelex.data.db.KeyDao
import com.chamelex.data.db.KeyEntity
import com.chamelex.data.db.KeyGroupEntity
import com.chamelex.data.settings.SettingsRepository
import com.chamelex.protocol.KeyPair
import com.chamelex.protocol.orderKeys
import com.chamelex.protocol.packCardKeys
import com.chamelex.protocol.parseKeyDictionary
import com.chamelex.protocol.unpackCardKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 密钥统一供给接口（文档阶段 5）：字典攻击按顺序喂钥。
 * 顺序：该卡记忆密钥 → 用户自定义组 → 已破解组 → 内置默认组。
 */
@Singleton
class KeyProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyDao: KeyDao,
    private val cardKeysDao: CardKeysDao,
    private val settings: SettingsRepository,
) {
    companion object {
        const val BUILTIN_GROUP_NAME = "内置默认"
        const val CRACKED_GROUP_NAME = "已破解"
    }

    private val seedMutex = Mutex()
    private var seeded = false

    /** 首次使用播种：内置默认组（assets 字典，DataStore 记 flag）+ 已破解组。 */
    suspend fun ensureSeeded() {
        if (seeded) return
        seedMutex.withLock {
            if (seeded) return
            val needImport = !settings.builtinDictImported.first()
            if (needImport) {
                val text = withContext(Dispatchers.IO) {
                    context.assets.open("dictionaries/mfc_default_keys.dic")
                        .bufferedReader().use { it.readText() }
                }
                val keys = parseKeyDictionary(text)
                val groupId = getOrCreateGroup(BUILTIN_GROUP_NAME, isBuiltin = true, sortOrder = 1000)
                keyDao.insertKeys(keys.map { KeyEntity(groupId = groupId, keyHex = it.toHexUpper()) })
                settings.setBuiltinDictImported(true)
            }
            getOrCreateGroup(CRACKED_GROUP_NAME, sortOrder = 100)
            seeded = true
        }
    }

    suspend fun getOrCreateGroup(name: String, isBuiltin: Boolean = false, sortOrder: Int = 0): Long {
        keyDao.getGroupByName(name)?.let { return it.id }
        val id = keyDao.insertGroup(KeyGroupEntity(name = name, isBuiltin = isBuiltin, sortOrder = sortOrder))
        return if (id > 0) id else keyDao.getGroupByName(name)!!.id
    }

    /** 该卡记忆的密钥（按扇区顺序去重后的唯一密钥列表）。 */
    suspend fun rememberedKeysFor(uid: String): List<ByteArray> {
        val card = cardKeysDao.getByUid(uid) ?: return emptyList()
        return unpackCardKeys(card.keysData)
            .flatMap { (a, b) -> listOfNotNull(a, b) }
            .distinctBy { it.toHexUpper() }
    }

    /** 有序字典（nextBatch 语义：一次返回完整有序列表，调用方分批）。 */
    suspend fun orderedDictionary(uid: String?, limit: Int? = null): List<ByteArray> {
        ensureSeeded()
        val remembered = uid?.let { rememberedKeysFor(it) } ?: emptyList()
        val groups = keyDao.getGroups()
        val crackedId = groups.firstOrNull { it.name == CRACKED_GROUP_NAME }?.id
        val userGroups = groups
            .filter { !it.isBuiltin && it.id != crackedId }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .map { g -> keyDao.getGroupKeyHexes(g.id).map { it.hexToBytes() } }
        val cracked = crackedId?.let { id -> keyDao.getGroupKeyHexes(id).map { it.hexToBytes() } } ?: emptyList()
        val builtin = groups.firstOrNull { it.isBuiltin }
            ?.let { g -> keyDao.getGroupKeyHexes(g.id).map { it.hexToBytes() } } ?: emptyList()
        val ordered = orderKeys(remembered, userGroups, cracked, builtin)
        return limit?.let { ordered.take(it) } ?: ordered
    }

    /**
     * 破解成功自动入库：已破解分组记录密钥+来源 UID，同时存 UID→扇区密钥映射。
     * 同 UID 已有记忆时按扇区合并（新密钥覆盖同位置，不影响其他扇区）。
     */
    suspend fun recordCrackedKeys(uid: String, sectorKeys: List<KeyPair>) {
        mergeCardKeys(
            uid,
            sectorKeys.mapIndexed { sector, pair -> sector to pair },
        )
    }

    /**
     * 按扇区合并入库（侦测反推也用这条路径）：
     * @param sectorKeys (扇区号, (KeyA?, KeyB?)) 列表；null 表示该位置不更新
     */
    suspend fun mergeCardKeys(uid: String, sectorKeys: List<Pair<Int, KeyPair>>) {
        ensureSeeded()
        val groupId = getOrCreateGroup(CRACKED_GROUP_NAME, sortOrder = 100)
        val newHexes = sectorKeys
            .flatMap { (_, pair) -> listOfNotNull(pair.first, pair.second) }
            .map { it.toHexUpper() }
            .distinct()
        keyDao.insertKeys(
            newHexes.map { KeyEntity(groupId = groupId, keyHex = it, sourceUid = uid) }
        )

        // 合并既有记忆
        val existing = cardKeysDao.getByUid(uid)
        val merged = (existing?.let { unpackCardKeys(it.keysData) } ?: emptyList()).toMutableList()
        sectorKeys.forEach { (sector, pair) ->
            while (merged.size <= sector) merged.add(null to null)
            val (oldA, oldB) = merged[sector]
            merged[sector] = (pair.first ?: oldA) to (pair.second ?: oldB)
        }
        cardKeysDao.upsert(
            CardKeysEntity(
                uid = uid,
                sectorCount = merged.size,
                keysData = packCardKeys(merged),
                nickname = existing?.nickname,
                lastUsedAt = System.currentTimeMillis(),
            )
        )
    }

    /** 读卡用到记忆密钥后刷新使用时间。 */
    suspend fun touchCard(uid: String) = cardKeysDao.touch(uid)

    private fun ByteArray.toHexUpper(): String = joinToString("") { "%02X".format(it) }
    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
