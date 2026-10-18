package com.chamelex.protocol

/**
 * ChameleonUltra 全部指令码，对应官方 CLI chameleon_enum.py 的 Command 枚举。
 */
enum class Command(val code: Int) {
    // ---- 通用指令（1000 段） ----
    GET_APP_VERSION(1000),
    CHANGE_DEVICE_MODE(1001),
    GET_DEVICE_MODE(1002),
    SET_ACTIVE_SLOT(1003),
    SET_SLOT_TAG_TYPE(1004),
    SET_SLOT_DATA_DEFAULT(1005),
    SET_SLOT_ENABLE(1006),
    SET_SLOT_TAG_NICK(1007),
    GET_SLOT_TAG_NICK(1008),
    SLOT_DATA_CONFIG_SAVE(1009),
    ENTER_BOOTLOADER(1010),
    GET_DEVICE_CHIP_ID(1011),
    GET_DEVICE_ADDRESS(1012),
    SAVE_SETTINGS(1013),
    RESET_SETTINGS(1014),
    SET_ANIMATION_MODE(1015),
    GET_ANIMATION_MODE(1016),
    GET_GIT_VERSION(1017),
    GET_ACTIVE_SLOT(1018),
    GET_SLOT_INFO(1019),
    WIPE_FDS(1020),
    DELETE_SLOT_TAG_NICK(1021),
    GET_ENABLED_SLOTS(1023),
    DELETE_SLOT_SENSE_TYPE(1024),
    GET_BATTERY_INFO(1025),
    GET_BUTTON_PRESS_CONFIG(1026),
    SET_BUTTON_PRESS_CONFIG(1027),
    GET_LONG_BUTTON_PRESS_CONFIG(1028),
    SET_LONG_BUTTON_PRESS_CONFIG(1029),
    SET_BLE_PAIRING_KEY(1030),
    GET_BLE_PAIRING_KEY(1031),
    DELETE_ALL_BLE_BONDS(1032),
    GET_DEVICE_MODEL(1033),
    GET_DEVICE_SETTINGS(1034),
    GET_DEVICE_CAPABILITIES(1035),
    GET_BLE_PAIRING_ENABLE(1036),
    SET_BLE_PAIRING_ENABLE(1037),
    GET_ALL_SLOT_NICKS(1038),
    GET_SLEEP_TIMEOUT(1039),
    SET_SLEEP_TIMEOUT(1040),
    GET_LONG_PRESS_THRESHOLD(1041),
    SET_LONG_PRESS_THRESHOLD(1042),

    // ---- HF 指令（2000 段） ----
    HF14A_SCAN(2000),
    MF1_DETECT_SUPPORT(2001),
    MF1_DETECT_PRNG(2002),
    MF1_STATIC_NESTED_ACQUIRE(2003),
    MF1_DARKSIDE_ACQUIRE(2004),
    MF1_DETECT_NT_DIST(2005),
    MF1_NESTED_ACQUIRE(2006),
    MF1_AUTH_ONE_KEY_BLOCK(2007),
    MF1_READ_ONE_BLOCK(2008),
    MF1_WRITE_ONE_BLOCK(2009),
    HF14A_RAW(2010),
    MF1_MANIPULATE_VALUE_BLOCK(2011),
    MF1_CHECK_KEYS_OF_SECTORS(2012),
    MF1_HARDNESTED_ACQUIRE(2013),
    MF1_ENC_NESTED_ACQUIRE(2014),
    MF1_CHECK_KEYS_ON_BLOCK(2015),
    HF14A_SCAN_KEEP(2016),
    HF14A_AUTH_TRACE(2017),
    MF0_ULC_AUTH(2018),
    MF0_ULC_READ(2019),
    HF14A_SNIFF(2020),
    MF0_ULC_WRITE(2021),
    MF0_ULC_SET_KEY(2022),
    HF14A_GET_CONFIG(2200),
    HF14A_SET_CONFIG(2201),

    // ---- LF 指令（3000 段） ----
    EM410X_SCAN(3000),
    EM410X_WRITE_TO_T55XX(3001),
    HIDPROX_SCAN(3002),
    HIDPROX_WRITE_TO_T55XX(3003),
    VIKING_SCAN(3004),
    VIKING_WRITE_TO_T55XX(3005),
    EM410X_ELECTRA_WRITE_TO_T55XX(3006),
    ADC_GENERIC_READ(3009),
    IOPROX_SCAN(3010),
    IOPROX_WRITE_TO_T55XX(3011),
    IOPROX_DECODE_RAW(3012),
    IOPROX_COMPOSE_ID(3013),
    PAC_SCAN(3014),
    PAC_WRITE_TO_T55XX(3015),
    LF_T55XX_WRITE(3016),
    IDTECK_WRITE_TO_T55XX(3018),
    JABLOTRON_SCAN(3019),
    JABLOTRON_WRITE_TO_T55XX(3020),
    EM4X05_SCAN(3030),
    LF_SNIFF(3031),
    EM4X05_READSNIFF(3032),

    // ---- 模拟指令（4000 段） ----
    MF1_WRITE_EMU_BLOCK_DATA(4000),
    HF14A_SET_ANTI_COLL_DATA(4001),
    MF1_SET_DETECTION_ENABLE(4004),
    MF1_GET_DETECTION_COUNT(4005),
    MF1_GET_DETECTION_LOG(4006),
    MF1_GET_DETECTION_ENABLE(4007),
    MF1_READ_EMU_BLOCK_DATA(4008),
    MF1_GET_EMULATOR_CONFIG(4009),
    MF1_GET_GEN1A_MODE(4010),
    MF1_SET_GEN1A_MODE(4011),
    MF1_GET_GEN2_MODE(4012),
    MF1_SET_GEN2_MODE(4013),
    MF1_GET_BLOCK_ANTI_COLL_MODE(4014),
    MF1_SET_BLOCK_ANTI_COLL_MODE(4015),
    MF1_GET_WRITE_MODE(4016),
    MF1_SET_WRITE_MODE(4017),
    HF14A_GET_ANTI_COLL_DATA(4018),
    MF0_NTAG_GET_UID_MAGIC_MODE(4019),
    MF0_NTAG_SET_UID_MAGIC_MODE(4020),
    MF0_NTAG_READ_EMU_PAGE_DATA(4021),
    MF0_NTAG_WRITE_EMU_PAGE_DATA(4022),
    MF0_NTAG_GET_VERSION_DATA(4023),
    MF0_NTAG_SET_VERSION_DATA(4024),
    MF0_NTAG_GET_SIGNATURE_DATA(4025),
    MF0_NTAG_SET_SIGNATURE_DATA(4026),
    MF0_NTAG_GET_COUNTER_DATA(4027),
    MF0_NTAG_SET_COUNTER_DATA(4028),
    MF0_NTAG_RESET_AUTH_CNT(4029),
    MF0_NTAG_GET_PAGE_COUNT(4030),
    MF0_NTAG_GET_WRITE_MODE(4031),
    MF0_NTAG_SET_WRITE_MODE(4032),
    MF0_NTAG_SET_DETECTION_ENABLE(4033),
    MF0_NTAG_GET_DETECTION_COUNT(4034),
    MF0_NTAG_GET_DETECTION_LOG(4035),
    MF0_NTAG_GET_DETECTION_ENABLE(4036),
    MF0_NTAG_GET_EMULATOR_CONFIG(4037),
    MF1_SET_FIELD_OFF_DO_RESET(4038),
    MF1_GET_FIELD_OFF_DO_RESET(4039),
    MF1_GET_PRNG_TYPE(4040),
    MF1_SET_PRNG_TYPE(4041),
    SEOS_READ_EMU_DATA(4042),
    SEOS_WRITE_EMU_DATA(4043),
    SEOS_WRITE_EMU_KEYS(4044),

    // ---- LF 模拟指令（5000 段） ----
    EM410X_SET_EMU_ID(5000),
    EM410X_GET_EMU_ID(5001),
    HIDPROX_SET_EMU_ID(5002),
    HIDPROX_GET_EMU_ID(5003),
    VIKING_SET_EMU_ID(5004),
    VIKING_GET_EMU_ID(5005),
    PAC_SET_EMU_ID(5006),
    PAC_GET_EMU_ID(5007),
    IOPROX_SET_EMU_ID(5008),
    IOPROX_GET_EMU_ID(5009),
    JABLOTRON_SET_EMU_ID(5010),
    JABLOTRON_GET_EMU_ID(5011),
    IDTECK_SET_EMU_ID(5012),
    IDTECK_GET_EMU_ID(5013),

    // ---- ISO14443-4 T=CL 模拟（6000 段） ----
    HF14A_4_APDU_RECV(6000),
    HF14A_4_APDU_SEND(6001),
    HF14A_4_SET_ANTI_COLL(6002),
    HF14A_4_STATIC_RESP(6003),
    HF14A_4_READER_APDU(6004),
    HF14A_4_EMV_SCAN(6005);

    companion object {
        fun fromCode(code: Int): Command? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 设备状态码，对应官方 CLI chameleon_enum.py 的 Status 枚举。
 */
enum class Status(val code: Int) {
    HF_TAG_OK(0x00),
    HF_TAG_NO(0x01),
    HF_ERR_STAT(0x02),
    HF_ERR_CRC(0x03),
    HF_COLLISION(0x04),
    HF_ERR_BCC(0x05),
    MF_ERR_AUTH(0x06),
    HF_ERR_PARITY(0x07),
    HF_ERR_ATS(0x08),

    LF_TAG_OK(0x40),
    LF_TAG_NO_FOUND(0x41),

    PAR_ERR(0x60),
    DEVICE_MODE_ERROR(0x66),
    INVALID_CMD(0x67),
    SUCCESS(0x68),
    NOT_IMPLEMENTED(0x69),
    FLASH_WRITE_FAIL(0x70),
    FLASH_READ_FAIL(0x71),
    INVALID_SLOT_TYPE(0x72);

    val isDeviceSuccess: Boolean get() = this == SUCCESS || this == HF_TAG_OK || this == LF_TAG_OK

    companion object {
        fun fromCode(code: Int): Status? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 卡槽编号。UI 侧 1-based（SLOT_1..SLOT_8），固件侧 0-based，经 toFw/fromFw 转换。
 */
enum class SlotNumber(val uiIndex: Int) {
    SLOT_1(1), SLOT_2(2), SLOT_3(3), SLOT_4(4),
    SLOT_5(5), SLOT_6(6), SLOT_7(7), SLOT_8(8);

    /** UI/枚举值 -> 固件 0-based 索引 */
    fun toFw(): Int = uiIndex - 1

    companion object {
        /** 固件 0-based 索引 -> 枚举 */
        fun fromFw(index: Int): SlotNumber =
            entries.firstOrNull { it.uiIndex == index + 1 }
                ?: throw IllegalArgumentException("Invalid fw slot index: $index")

        fun fromUi(index: Int): SlotNumber =
            entries.firstOrNull { it.uiIndex == index }
                ?: throw IllegalArgumentException("Invalid ui slot index: $index")
    }
}

enum class TagSenseType(val code: Int) {
    UNDEFINED(0),
    LF(1),
    HF(2);

    companion object {
        fun fromCode(code: Int): TagSenseType = entries.firstOrNull { it.code == code } ?: UNDEFINED
    }
}

enum class TagSpecificType(val code: Int) {
    UNDEFINED(0),

    OLD_EM410X(1),
    OLD_MIFARE_MINI(2),
    OLD_MIFARE_1024(3),
    OLD_MIFARE_2048(4),
    OLD_MIFARE_4096(5),
    OLD_NTAG_213(6),
    OLD_NTAG_215(7),
    OLD_NTAG_216(8),
    OLD_TAG_TYPES_END(9),

    EM410X(100),
    EM410X_16(101),
    EM410X_32(102),
    EM410X_64(103),
    EM410X_ELECTRA(104),
    PAC(150),
    VIKING(170),
    JABLOTRON(180),

    HID_PROX(200),
    IO_PROX(201),

    IDTECK(310),

    TAG_TYPES_LF_END(999),

    MIFARE_MINI(1000),
    MIFARE_1024(1001),
    MIFARE_2048(1002),
    MIFARE_4096(1003),

    NTAG_213(1100),
    NTAG_215(1101),
    NTAG_216(1102),
    MF0ICU1(1103),
    MF0ICU2(1104),
    MF0UL11(1105),
    MF0UL21(1106),
    NTAG_210(1107),
    NTAG_212(1108),

    HF14A_4(3000),
    SEOS(3001);

    val isLf: Boolean get() = this > UNDEFINED && this < TAG_TYPES_LF_END
    val isHf: Boolean get() = this > TAG_TYPES_LF_END

    companion object {
        fun fromCode(code: Int): TagSpecificType = entries.firstOrNull { it.code == code } ?: UNDEFINED
    }
}

enum class MifareClassicWriteMode(val code: Int) {
    NORMAL(0),
    DENIED(1),
    DECEIVE(2),
    SHADOW(3),
    SHADOW_REQ(4);

    companion object {
        fun fromCode(code: Int): MifareClassicWriteMode? = entries.firstOrNull { it.code == code }
    }
}

enum class MifareUltralightWriteMode(val code: Int) {
    NORMAL(0),
    DENIED(1),
    DECEIVE(2),
    SHADOW(3),
    SHADOW_REQ(4);

    companion object {
        fun fromCode(code: Int): MifareUltralightWriteMode? = entries.firstOrNull { it.code == code }
    }
}

enum class MifareClassicPrngType(val code: Int) {
    STATIC(0),
    WEAK(1),
    HARD(2);

    companion object {
        fun fromCode(code: Int): MifareClassicPrngType? = entries.firstOrNull { it.code == code }
    }
}

enum class MifareClassicDarksideStatus(val code: Int) {
    OK(0),
    CANT_FIX_NT(1),
    LUCKY_AUTH_OK(2),
    NO_NAK_SENT(3),
    TAG_CHANGED(4);

    companion object {
        fun fromCode(code: Int): MifareClassicDarksideStatus? = entries.firstOrNull { it.code == code }
    }
}

enum class AnimationMode(val code: Int) {
    FULL(0),
    MINIMAL(1),
    NONE(2),
    SYMMETRIC(3);

    companion object {
        fun fromCode(code: Int): AnimationMode? = entries.firstOrNull { it.code == code }
    }
}

enum class ButtonType(val code: Int) {
    A('A'.code),
    B('B'.code);

    companion object {
        fun fromCode(code: Int): ButtonType? = entries.firstOrNull { it.code == code }
    }
}

enum class MfcKeyType(val code: Int) {
    A(0x60),
    B(0x61);

    companion object {
        fun fromCode(code: Int): MfcKeyType? = entries.firstOrNull { it.code == code }
    }
}

enum class ButtonPressFunction(val code: Int) {
    NONE(0),
    NEXT_SLOT(1),
    PREV_SLOT(2),
    CLONE(3),
    BATTERY(4),
    FIELD_GEN(5);

    companion object {
        fun fromCode(code: Int): ButtonPressFunction? = entries.firstOrNull { it.code == code }
    }
}

enum class MfcValueBlockOperator(val code: Int) {
    DECREMENT(0xC0),
    INCREMENT(0xC1),
    RESTORE(0xC2);

    companion object {
        fun fromCode(code: Int): MfcValueBlockOperator? = entries.firstOrNull { it.code == code }
    }
}

enum class DeviceModel(val code: Int) {
    ULTRA(0),
    LITE(1);

    companion object {
        fun fromCode(code: Int): DeviceModel? = entries.firstOrNull { it.code == code }
    }
}

/** HID Prox 卡格式（对应 chameleon_enum.py 的 HIDFormat；扫描时 format=0 表示自动识别）。 */
enum class HIDFormat(val code: Int, val label: String, val bits: Int) {
    H10301(1, "H10301", 26),
    IND26(2, "Indala", 26),
    IND27(3, "Indala", 27),
    INDASC27(4, "Indala ASC", 27),
    TECOM27(5, "Tecom", 27),
    W2804(6, "2804 Wiegand", 28),
    IND29(7, "Indala", 29),
    ATSW30(8, "ATS Wiegand", 30),
    ADT31(9, "HID ADT", 31),
    HCP32(10, "HID Check Point", 32),
    HPP32(11, "HID HP", 32),
    KASTLE(12, "Kastle", 32),
    KANTECH(13, "Indala/Kantech KFS", 32),
    WIE32(14, "Wiegand", 32),
    D10202(15, "HID D10202", 33),
    H10306(16, "HID H10306", 34),
    N10002(17, "Honeywell N10002", 34),
    OPTUS34(18, "Indala Optus", 34),
    SMP34(19, "Cardkey Smartpass", 34),
    BQT34(20, "BQT", 34),
    C1K35S(21, "HID Corporate 1000", 35),
    C15001(22, "HID KeyScan", 36),
    S12906(23, "HID Simplex", 36),
    ACTPHID(42, "HID ACTProx", 36),
    SIE36(24, "Siemens", 36),
    H10320(25, "HID H10320", 37),
    H10302(26, "HID H10302", 37),
    H10304(27, "HID H10304", 37),
    P10004(28, "HID P10004", 37),
    HGEN37(29, "HID Generic", 37),
    MDI37(30, "PointGuard MDI", 37);

    companion object {
        fun fromCode(code: Int): HIDFormat? = entries.firstOrNull { it.code == code }
    }
}
