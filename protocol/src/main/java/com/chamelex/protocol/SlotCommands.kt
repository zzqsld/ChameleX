package com.chamelex.protocol

/**
 * 卡槽管理相关指令封装（阶段 2）。
 * 线上槽位编号均为 0-based，统一经 [SlotNumber.toFw] / [SlotNumber.fromFw] 转换。
 */
class SlotCommands(private val protocol: ChameleonProtocol) {

    // ---- 读取 ----

    /** GET_SLOT_INFO=1019：8×(hf u16, lf u16)。 */
    suspend fun getSlotInfo(): List<Pair<TagSpecificType, TagSpecificType>> =
        parseSlotInfo(protocol.sendCommandExpect(Command.GET_SLOT_INFO).data)

    /** GET_ACTIVE_SLOT=1018：1 字节 0-based 槽位。 */
    suspend fun getActiveSlot(): SlotNumber =
        SlotNumber.fromFw(protocol.sendCommandExpect(Command.GET_ACTIVE_SLOT).data[0].toInt() and 0xFF)

    /** GET_ENABLED_SLOTS=1023：8×(hf_enabled u8, lf_enabled u8)。 */
    suspend fun getEnabledSlots(): List<Pair<Boolean, Boolean>> =
        parseEnabledSlots(protocol.sendCommandExpect(Command.GET_ENABLED_SLOTS).data)

    /** GET_ALL_SLOT_NICKS=1038：8 槽×(hf_len + hf_name + lf_len + lf_name)。 */
    suspend fun getAllSlotNicks(): List<SlotNick> =
        parseAllSlotNicks(protocol.sendCommandExpect(Command.GET_ALL_SLOT_NICKS).data)

    /** 聚合读取 8 槽完整信息。 */
    suspend fun getSlots(): List<SlotInfo> {
        val info = getSlotInfo()
        val enabled = getEnabledSlots()
        val nicks = getAllSlotNicks()
        val active = getActiveSlot()
        return (0 until 8).map { i ->
            SlotInfo(
                slot = SlotNumber.fromFw(i),
                hfType = info.getOrNull(i)?.first ?: TagSpecificType.UNDEFINED,
                lfType = info.getOrNull(i)?.second ?: TagSpecificType.UNDEFINED,
                hfEnabled = enabled.getOrNull(i)?.first == true,
                lfEnabled = enabled.getOrNull(i)?.second == true,
                hfNick = nicks.getOrNull(i)?.hf.orEmpty(),
                lfNick = nicks.getOrNull(i)?.lf.orEmpty(),
                isActive = active.uiIndex == i + 1,
            )
        }
    }

    // ---- 槽位操作 ----

    /** SET_ACTIVE_SLOT=1003：`!B` 槽位(0-based)。 */
    suspend fun setActiveSlot(slot: SlotNumber) {
        protocol.sendCommandExpect(Command.SET_ACTIVE_SLOT, byteArrayOf(slot.toFw().toByte()))
    }

    /** SET_SLOT_ENABLE=1006：`!BBB` (slot, sense_type, enabled)。 */
    suspend fun setSlotEnable(slot: SlotNumber, senseType: TagSenseType, enabled: Boolean) {
        protocol.sendCommandExpect(
            Command.SET_SLOT_ENABLE,
            byteArrayOf(slot.toFw().toByte(), senseType.code.toByte(), if (enabled) 1 else 0),
        )
    }

    /** SET_SLOT_TAG_TYPE=1004：`!BH` (slot, tag_type)。 */
    suspend fun setSlotTagType(slot: SlotNumber, tagType: TagSpecificType) {
        protocol.sendCommandExpect(Command.SET_SLOT_TAG_TYPE, packSlotAndType(slot, tagType))
    }

    /**
     * SET_SLOT_TAG_NICK=1007：`!BB{name}`，UTF-8 名字 ≤32 字节，超长客户端拒绝。
     */
    suspend fun setSlotTagNick(slot: SlotNumber, senseType: TagSenseType, name: String) {
        val encoded = name.encodeToByteArray()
        require(encoded.size <= MAX_NICK_BYTES) { "昵称超过 $MAX_NICK_BYTES 字节" }
        protocol.sendCommandExpect(
            Command.SET_SLOT_TAG_NICK,
            byteArrayOf(slot.toFw().toByte(), senseType.code.toByte()) + encoded,
        )
    }

    /** DELETE_SLOT_TAG_NICK=1021：`!BB` (slot, sense_type)。 */
    suspend fun deleteSlotTagNick(slot: SlotNumber, senseType: TagSenseType) {
        protocol.sendCommandExpect(
            Command.DELETE_SLOT_TAG_NICK,
            byteArrayOf(slot.toFw().toByte(), senseType.code.toByte()),
        )
    }

    /** DELETE_SLOT_SENSE_TYPE=1024：`!BB` (slot, sense_type)，删除该槽某频段配置数据。 */
    suspend fun deleteSlotSenseType(slot: SlotNumber, senseType: TagSenseType) {
        protocol.sendCommandExpect(
            Command.DELETE_SLOT_SENSE_TYPE,
            byteArrayOf(slot.toFw().toByte(), senseType.code.toByte()),
        )
    }

    /** SET_SLOT_DATA_DEFAULT=1005：`!BH` (slot, tag_type)，重置为指定卡型默认数据（写 flash）。 */
    suspend fun setSlotDataDefault(slot: SlotNumber, tagType: TagSpecificType) {
        protocol.sendCommandExpect(Command.SET_SLOT_DATA_DEFAULT, packSlotAndType(slot, tagType))
    }

    /** SLOT_DATA_CONFIG_SAVE=1009：把当前槽配置持久化到 flash。 */
    suspend fun slotDataConfigSave() {
        protocol.sendCommandExpect(Command.SLOT_DATA_CONFIG_SAVE)
    }

    /** EM410X_SET_EMU_ID=5000：设置当前激活槽模拟的 EM410X 卡号（5 字节，ELECTRA 13 字节）。 */
    suspend fun em410xSetEmuId(id: ByteArray) {
        require(id.size == 5 || id.size == 13) { "EM410X id 必须 5 字节（Electra 13 字节）" }
        protocol.sendCommandExpect(Command.EM410X_SET_EMU_ID, id)
    }

    /** HIDPROX_SET_EMU_ID=5002：设置当前激活槽模拟的 HIDProx 卡号（13 字节）。 */
    suspend fun hidProxSetEmuId(id: ByteArray) {
        require(id.size == 13) { "HIDProx id 必须 13 字节" }
        protocol.sendCommandExpect(Command.HIDPROX_SET_EMU_ID, id)
    }

    // ---- MF1 模拟参数（作用于当前激活槽） ----

    /** MF1_GET_EMULATOR_CONFIG=4009：[detection, gen1a, gen2, anticoll_from_block0, write_mode]。 */
    suspend fun mf1GetEmulatorConfig(): EmulatorConfig =
        parseEmulatorConfig(protocol.sendCommandExpect(Command.MF1_GET_EMULATOR_CONFIG).data)

    /** MF1_SET_GEN1A_MODE=4011：`!B` 布尔。 */
    suspend fun mf1SetGen1aMode(enabled: Boolean) = setBool(Command.MF1_SET_GEN1A_MODE, enabled)

    /** MF1_SET_DETECTION_ENABLE=4004：`!B` 布尔（侦测模式，阶段 6 读取日志）。 */
    suspend fun mf1SetDetectionEnable(enabled: Boolean) = setBool(Command.MF1_SET_DETECTION_ENABLE, enabled)

    /** MF1_SET_GEN2_MODE=4013：`!B` 布尔。 */
    suspend fun mf1SetGen2Mode(enabled: Boolean) = setBool(Command.MF1_SET_GEN2_MODE, enabled)

    /** MF1_SET_BLOCK_ANTI_COLL_MODE=4015：`!B` 布尔。 */
    suspend fun mf1SetBlockAntiCollMode(enabled: Boolean) =
        setBool(Command.MF1_SET_BLOCK_ANTI_COLL_MODE, enabled)

    /** MF1_SET_WRITE_MODE=4017：`!B`（0 Normal/1 Denied/2 Deceive/3 Shadow）。 */
    suspend fun mf1SetWriteMode(mode: MifareClassicWriteMode) {
        require(mode != MifareClassicWriteMode.SHADOW_REQ) { "SHADOW_REQ 仅固件内部使用" }
        protocol.sendCommandExpect(Command.MF1_SET_WRITE_MODE, byteArrayOf(mode.code.toByte()))
    }

    // ---- NTAG / Ultralight 模拟参数 ----

    /** MF0_NTAG_GET_UID_MAGIC_MODE=4019：1 字节布尔。 */
    suspend fun mf0NtagGetUidMagicMode(): Boolean =
        protocol.sendCommandExpect(Command.MF0_NTAG_GET_UID_MAGIC_MODE).data[0].toInt() != 0

    /** MF0_NTAG_SET_UID_MAGIC_MODE=4020：`!B` 布尔。 */
    suspend fun mf0NtagSetUidMagicMode(enabled: Boolean) =
        setBool(Command.MF0_NTAG_SET_UID_MAGIC_MODE, enabled)

    private suspend fun setBool(cmd: Command, enabled: Boolean) {
        protocol.sendCommandExpect(cmd, byteArrayOf(if (enabled) 1 else 0))
    }

    private fun packSlotAndType(slot: SlotNumber, tagType: TagSpecificType) = byteArrayOf(
        slot.toFw().toByte(),
        (tagType.code shr 8).toByte(),
        tagType.code.toByte(),
    )

    companion object {
        const val MAX_NICK_BYTES = 32
    }
}

/** 一个卡槽的昵称对。 */
data class SlotNick(val hf: String, val lf: String)

/** 卡槽聚合信息。 */
data class SlotInfo(
    val slot: SlotNumber,
    val hfType: TagSpecificType,
    val lfType: TagSpecificType,
    val hfEnabled: Boolean,
    val lfEnabled: Boolean,
    val hfNick: String,
    val lfNick: String,
    val isActive: Boolean,
)

/** MF1 模拟参数（detection 阶段 6 才用）。 */
data class EmulatorConfig(
    val detection: Boolean,
    val gen1a: Boolean,
    val gen2: Boolean,
    val antiCollFromBlock0: Boolean,
    val writeMode: MifareClassicWriteMode?,
)

// ---- 纯解析函数（可单测） ----

/** GET_SLOT_INFO 响应：8×(`!HH`) = (hf_tag_type, lf_tag_type)。 */
fun parseSlotInfo(data: ByteArray): List<Pair<TagSpecificType, TagSpecificType>> {
    require(data.size % 4 == 0) { "GET_SLOT_INFO 响应长度 ${data.size} 不是 4 的倍数" }
    return (data.indices step 4).map { i ->
        val hf = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
        val lf = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
        TagSpecificType.fromCode(hf) to TagSpecificType.fromCode(lf)
    }
}

/** GET_ENABLED_SLOTS 响应：8×(`!BB`) = (hf_enabled, lf_enabled)。 */
fun parseEnabledSlots(data: ByteArray): List<Pair<Boolean, Boolean>> {
    require(data.size % 2 == 0) { "GET_ENABLED_SLOTS 响应长度 ${data.size} 不是 2 的倍数" }
    return (data.indices step 2).map { i ->
        (data[i].toInt() != 0) to (data[i + 1].toInt() != 0)
    }
}

/**
 * GET_ALL_SLOT_NICKS 响应：8 槽×(hf_len u8 + hf_name + lf_len u8 + lf_name) 顺序拼接。
 * 移植 chameleon_cmd.py get_all_slot_nicks 的防御性解析（长度越界时跳过但照常前进）。
 */
fun parseAllSlotNicks(data: ByteArray): List<SlotNick> {
    val slots = mutableListOf<SlotNick>()
    var i = 0
    while (i < data.size && slots.size < 8) {
        var hf = ""
        var lf = ""
        if (i < data.size) {
            val hfLen = data[i].toInt() and 0xFF
            i++
            if (hfLen > 0 && i + hfLen <= data.size) {
                hf = data.decodeToString(i, i + hfLen)
                i += hfLen
            } else {
                i += hfLen
            }
        }
        if (i < data.size) {
            val lfLen = data[i].toInt() and 0xFF
            i++
            if (lfLen > 0 && i + lfLen <= data.size) {
                lf = data.decodeToString(i, i + lfLen)
                i += lfLen
            } else {
                i += lfLen
            }
        }
        slots.add(SlotNick(hf, lf))
    }
    return slots
}

/** MF1_GET_EMULATOR_CONFIG 响应：5 字节 [detection, gen1a, gen2, anticoll, write_mode]。 */
fun parseEmulatorConfig(data: ByteArray): EmulatorConfig {
    require(data.size >= 5) { "MF1_GET_EMULATOR_CONFIG 响应长度 ${data.size} < 5" }
    return EmulatorConfig(
        detection = data[0].toInt() != 0,
        gen1a = data[1].toInt() != 0,
        gen2 = data[2].toInt() != 0,
        antiCollFromBlock0 = data[3].toInt() != 0,
        writeMode = MifareClassicWriteMode.fromCode(data[4].toInt() and 0xFF),
    )
}
