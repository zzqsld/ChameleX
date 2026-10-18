package com.chamelex.app.ui.screens.slots

import com.chamelex.app.R
import com.chamelex.protocol.TagSpecificType

/** TagSpecificType 的显示名资源映射。 */
fun TagSpecificType.nameRes(): Int = when (this) {
    TagSpecificType.UNDEFINED -> R.string.tag_type_empty
    TagSpecificType.EM410X -> R.string.tag_type_em410x
    TagSpecificType.EM410X_16 -> R.string.tag_type_em410x_16
    TagSpecificType.EM410X_32 -> R.string.tag_type_em410x_32
    TagSpecificType.EM410X_64 -> R.string.tag_type_em410x_64
    TagSpecificType.EM410X_ELECTRA -> R.string.tag_type_em410x_electra
    TagSpecificType.PAC -> R.string.tag_type_pac
    TagSpecificType.VIKING -> R.string.tag_type_viking
    TagSpecificType.JABLOTRON -> R.string.tag_type_jablotron
    TagSpecificType.HID_PROX -> R.string.tag_type_hidprox
    TagSpecificType.IO_PROX -> R.string.tag_type_ioprox
    TagSpecificType.IDTECK -> R.string.tag_type_idteck
    TagSpecificType.MIFARE_MINI -> R.string.tag_type_mifare_mini
    TagSpecificType.MIFARE_1024 -> R.string.tag_type_mifare_1024
    TagSpecificType.MIFARE_2048 -> R.string.tag_type_mifare_2048
    TagSpecificType.MIFARE_4096 -> R.string.tag_type_mifare_4096
    TagSpecificType.NTAG_213 -> R.string.tag_type_ntag213
    TagSpecificType.NTAG_215 -> R.string.tag_type_ntag215
    TagSpecificType.NTAG_216 -> R.string.tag_type_ntag216
    TagSpecificType.NTAG_210 -> R.string.tag_type_ntag210
    TagSpecificType.NTAG_212 -> R.string.tag_type_ntag212
    TagSpecificType.MF0ICU1 -> R.string.tag_type_mful
    TagSpecificType.MF0ICU2 -> R.string.tag_type_mfulc
    TagSpecificType.MF0UL11 -> R.string.tag_type_mful11
    TagSpecificType.MF0UL21 -> R.string.tag_type_mful21
    TagSpecificType.HF14A_4 -> R.string.tag_type_hf14a_4
    TagSpecificType.SEOS -> R.string.tag_type_seos
    TagSpecificType.OLD_TAG_TYPES_END, TagSpecificType.TAG_TYPES_LF_END -> R.string.tag_type_unknown
    else -> R.string.tag_type_old
}

/** 支持 MF1 模拟参数（Gen1A/Gen2/AntiColl/写入模式）的卡型。 */
val MF1_CONFIG_TYPES = setOf(
    TagSpecificType.MIFARE_MINI,
    TagSpecificType.MIFARE_1024,
    TagSpecificType.MIFARE_2048,
    TagSpecificType.MIFARE_4096,
)

/** 支持 UID Magic 开关的 NTAG/Ultralight 系卡型。 */
val NTAG_CONFIG_TYPES = setOf(
    TagSpecificType.NTAG_213, TagSpecificType.NTAG_215, TagSpecificType.NTAG_216,
    TagSpecificType.NTAG_210, TagSpecificType.NTAG_212,
    TagSpecificType.MF0ICU1, TagSpecificType.MF0ICU2,
    TagSpecificType.MF0UL11, TagSpecificType.MF0UL21,
)

/** 重置卡型选择器中展示的类型。 */
val RESETTABLE_TYPES = listOf(
    TagSpecificType.MIFARE_1024,
    TagSpecificType.MIFARE_4096,
    TagSpecificType.MIFARE_MINI,
    TagSpecificType.MIFARE_2048,
    TagSpecificType.NTAG_213,
    TagSpecificType.NTAG_215,
    TagSpecificType.NTAG_216,
    TagSpecificType.MF0ICU1,
    TagSpecificType.EM410X,
    TagSpecificType.HID_PROX,
)
