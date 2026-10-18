package com.chamelex.protocol

/**
 * 阶段 1 用到的设备信息指令封装，请求/响应结构与官方 CLI chameleon_cmd.py 一致。
 */
class ChameleonCommands(private val protocol: ChameleonProtocol) {

    /** GET_APP_VERSION=1000：响应 2 字节 (major, minor)。 */
    suspend fun getAppVersion(): Pair<Int, Int> {
        val resp = protocol.sendCommandExpect(Command.GET_APP_VERSION)
        return (resp.data[0].toInt() and 0xFF) to (resp.data[1].toInt() and 0xFF)
    }

    /** GET_GIT_VERSION=1017：响应 UTF-8 字符串。 */
    suspend fun getGitVersion(): String =
        protocol.sendCommandExpect(Command.GET_GIT_VERSION).data.decodeToString()

    /** GET_DEVICE_MODEL=1033：响应 1 字节（0=Ultra, 1=Lite）。 */
    suspend fun getDeviceModel(): DeviceModel? =
        DeviceModel.fromCode(protocol.sendCommandExpect(Command.GET_DEVICE_MODEL).data[0].toInt() and 0xFF)

    /** GET_DEVICE_CHIP_ID=1011：响应 8 字节，hex 显示。 */
    suspend fun getDeviceChipId(): String =
        protocol.sendCommandExpect(Command.GET_DEVICE_CHIP_ID).data.toHex()

    /** GET_BATTERY_INFO=1025：响应 !HB 大端 = 电压 mV(u16) + 百分比(u8)。 */
    suspend fun getBatteryInfo(): Pair<Int, Int> {
        val data = protocol.sendCommandExpect(Command.GET_BATTERY_INFO).data
        val voltage = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val percent = data[2].toInt() and 0xFF
        return voltage to percent
    }

    /** GET_DEVICE_MODE=1002：响应 1 字节布尔，true=读卡器模式。 */
    suspend fun isDeviceReaderMode(): Boolean =
        protocol.sendCommandExpect(Command.GET_DEVICE_MODE).data[0].toInt() != 0

    /** CHANGE_DEVICE_MODE=1001：1 字节布尔，true=读卡器模式。 */
    suspend fun changeDeviceMode(readerMode: Boolean) {
        protocol.sendCommandExpect(Command.CHANGE_DEVICE_MODE, byteArrayOf(if (readerMode) 1 else 0))
    }

    /** ENTER_BOOTLOADER=1010：设备重启进 DFU bootloader（可能不应答直接断开）。 */
    suspend fun enterBootloader() {
        protocol.sendCommandExpect(Command.ENTER_BOOTLOADER)
    }

    // ---- 硬件设置（阶段 4 补充） ----

    /** GET_ANIMATION_MODE=1016：动画模式。 */
    suspend fun getAnimationMode(): AnimationMode? =
        AnimationMode.fromCode(protocol.sendCommandExpect(Command.GET_ANIMATION_MODE).data[0].toInt() and 0xFF)

    /** SET_ANIMATION_MODE=1015：`!B`。 */
    suspend fun setAnimationMode(mode: AnimationMode) {
        protocol.sendCommandExpect(Command.SET_ANIMATION_MODE, byteArrayOf(mode.code.toByte()))
    }

    /** GET_BUTTON_PRESS_CONFIG=1026：请求 `!B` 按键，响应 1 字节功能码。 */
    suspend fun getButtonPressConfig(button: ButtonType): ButtonPressFunction? =
        ButtonPressFunction.fromCode(
            protocol.sendCommandExpect(Command.GET_BUTTON_PRESS_CONFIG, byteArrayOf(button.code.toByte()))
                .data[0].toInt() and 0xFF
        )

    /** SET_BUTTON_PRESS_CONFIG=1027：请求 `!BB` (button, function)。 */
    suspend fun setButtonPressConfig(button: ButtonType, function: ButtonPressFunction) {
        protocol.sendCommandExpect(
            Command.SET_BUTTON_PRESS_CONFIG,
            byteArrayOf(button.code.toByte(), function.code.toByte()),
        )
    }

    /** GET_LONG_BUTTON_PRESS_CONFIG=1028：请求 `!B` 按键，响应 1 字节功能码。 */
    suspend fun getLongButtonPressConfig(button: ButtonType): ButtonPressFunction? =
        ButtonPressFunction.fromCode(
            protocol.sendCommandExpect(Command.GET_LONG_BUTTON_PRESS_CONFIG, byteArrayOf(button.code.toByte()))
                .data[0].toInt() and 0xFF
        )

    /** SET_LONG_BUTTON_PRESS_CONFIG=1029：请求 `!BB` (button, function)。 */
    suspend fun setLongButtonPressConfig(button: ButtonType, function: ButtonPressFunction) {
        protocol.sendCommandExpect(
            Command.SET_LONG_BUTTON_PRESS_CONFIG,
            byteArrayOf(button.code.toByte(), function.code.toByte()),
        )
    }

    companion object {
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
