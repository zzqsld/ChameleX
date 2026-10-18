# 第三方组件与许可证

ChameleX 包含或链接以下第三方软件。各组件保留其原有版权声明；本文件不取代随源码提供的许可证与版权声明。

| 组件 | 用途 | 许可证 / 声明 |
| --- | --- | --- |
| Proxmark3 `crapto1` 及 hardnested 相关实现 | MIFARE Classic 密码算法 | 源文件声明 GNU GPL v2 或更高版本，或 GNU GPL v3 或更高版本；以各文件头为准 |
| xz-embedded | native 组件中的解压支持 | 0BSD；以 `crypto/src/main/cpp/xz/` 中的 SPDX 标识为准 |
| Nordic Android BLE Library | BLE 通信 | BSD-3-Clause |
| Nordic Android DFU Library | BLE DFU | BSD-3-Clause |
| usb-serial-for-android | USB 串口 | MIT |
| AndroidX、Jetpack Compose、Hilt、Room、Kotlin Coroutines | Android 应用基础设施 | 各项目所附许可证，通常为 Apache-2.0 |
| `app/src/main/assets/dictionaries/mfc_default_keys.dic` | 内置 MIFARE Classic 默认密钥列表 | 文件头标注 “ICEMAN FORK VERSION”；保留原始文件头，来源与具体权利信息以该文件及上游项目为准 |

Proxmark3 相关源码保留上游版权和许可说明，来源项目为 [RfidResearchGroup/proxmark3](https://github.com/RfidResearchGroup/proxmark3)。具体文件的许可范围和额外声明应以相应源码文件头及上游许可证文件为准。

设备协议与兼容性参考 [RfidResearchGroup/ChameleonUltra](https://github.com/RfidResearchGroup/ChameleonUltra)。本仓库不重新分发其 `_reference/official` 工作区副本。
