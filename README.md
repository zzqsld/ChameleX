<div align="center">

# ChameleX

### 面向 ChameleonUltra / ChameleonLite 的 Android 客户端

BLE 与 USB OTG 双通道 · MIFARE Classic 工具 · 卡槽与数据管理

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36-4285F4?logo=android)](https://developer.android.com/about/versions/16)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)

</div>

ChameleX 是一个使用 Kotlin 和 Jetpack Compose 开发的 ChameleonUltra / ChameleonLite Android 客户端。它提供 BLE 与 USB OTG 连接，并将设备协议、传输、数据存储和 UI 拆分为独立模块。

> **项目状态：开发中。** APK 可以构建并安装；实际设备、固件版本、卡片和手机组合可能影响功能可用性。使用前请先备份重要卡槽与数据。

## 功能

- **设备连接**：BLE 扫描与连接、USB OTG 串口连接、设备信息和电量显示。
- **读卡器**：HF 14A 与 LF 卡片扫描；MIFARE Classic 字典检测及自动密钥恢复流程，并可查看读取结果。
- **卡槽管理**：查看和配置设备卡槽、昵称、启用状态及模拟参数。
- **数据管理**：本地保存 dump，查看和编辑块数据，支持 BIN、MCT、EML、JSON 导入/导出，并可写入模拟卡槽。
- **密钥管理**：导入和管理密钥字典、卡片密钥记忆及已恢复密钥。
- **其他**：设备设置、协议日志和 Nordic DFU 固件升级界面。

具体能力取决于设备型号与固件支持；部分高级卡片、恢复算法和写入路径尚未在所有设备上完成实机验证。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| UI 与应用 | Kotlin、Jetpack Compose、MVVM、Hilt、Coroutines |
| 协议 | 纯 Kotlin 帧编解码、指令封装与协议日志 |
| 连接 | Nordic Android BLE Library、usb-serial-for-android |
| 数据 | Room、DataStore |
| 密码算法 | C / JNI / Android NDK |

## 开始使用

### 环境要求

- Android Studio（支持当前 Android Gradle Plugin）
- JDK 17
- Android SDK Platform 36
- Android NDK 与 CMake 3.22.1（用于构建 native 算法库）
- Android 8.0（API 26）或更高版本的设备

### 构建

```powershell
git clone https://github.com/zzqsld/ChameleX.git
cd ChameleX
.\gradlew.bat :app:assembleDebug
```

APK 输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接的 Android 设备：

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Linux/macOS 可使用 `./gradlew :app:assembleDebug`。

### 连接设备

1. 在设备上启用 ChameleonUltra / Lite 的 BLE 广播，或通过 OTG 线连接设备。
2. 在应用的“设置”页面授予所需的蓝牙权限，扫描并选择设备；USB 连接时按系统提示授权。
3. 返回读卡器、卡槽、数据或密钥页面使用对应功能。

DFU 和卡片写入属于对设备有实际影响的操作。请使用官方兼容固件，并确认目标设备、卡片及数据后再执行。

## 项目结构

```text
app/         Android UI、ViewModel、导航与应用逻辑
connection/  BLE / USB OTG 传输
protocol/    ChameleonUltra 协议与 dump 格式
data/        Room 数据库、设置与密钥数据
crypto/      JNI 与 native 恢复算法
```

## 隐私与安全

- 卡片 dump、密钥与设备日志可能包含敏感信息；请妥善保管设备和备份，不要公开上传真实卡片数据、密钥或日志。
- 仓库不包含工作区外部参考目录和用户本地字典；应用内置默认字典仅用于合法授权的测试。
- 本工具仅供设备所有者或获得明确授权的测试、研究和互操作性用途。使用者须遵守所在地法律及相关设备、凭证的使用条款。

## 许可证与第三方声明

ChameleX 按 [GNU General Public License v3.0](LICENSE) 发布。仓库中包含带有各自版权与许可证声明的第三方组件；这些文件的声明仍然适用，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 及源码文件头。

## 致谢

- [RfidResearchGroup/ChameleonUltra](https://github.com/RfidResearchGroup/ChameleonUltra)：设备、协议文档与兼容性参考。
- [RfidResearchGroup/proxmark3](https://github.com/RfidResearchGroup/proxmark3)：部分 MIFARE Classic 算法实现来源。
- [xz-embedded](https://tukaani.org/xz/embedded.html)：native 组件中使用的解压代码。

本项目为独立的第三方客户端，与 ChameleonUltra 官方项目无隶属关系。
