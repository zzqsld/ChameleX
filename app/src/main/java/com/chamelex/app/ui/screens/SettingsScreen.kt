package com.chamelex.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.material3.SnackbarHostState
import com.chamelex.app.ConnectionViewModel
import com.chamelex.app.R
import com.chamelex.connection.ConnState

@Composable
fun SettingsScreen(
    viewModel: ConnectionViewModel,
    onOpenFirmware: () -> Unit,
    onOpenDfuDevice: (address: String, name: String) -> Unit,
    onOpenAbout: () -> Unit,
    settingsViewModel: com.chamelex.app.ui.screens.settings.SettingsViewModel =
        androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(),
) {
    val connState by viewModel.connState.collectAsStateWithLifecycle()
    val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
    val scanResults by viewModel.scanResults.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val usbDevices by viewModel.usbDevices.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val blePermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun hasBlePermission(): Boolean = blePermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var permissionGranted by remember { mutableStateOf(hasBlePermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        permissionGranted = grants.values.all { it }
        if (permissionGranted) viewModel.startBleScan()
    }

    LaunchedEffect(Unit) {
        viewModel.refreshUsbDevices()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings_connection_title), style = MaterialTheme.typography.titleLarge)

        // ---- 已连接设备信息卡 ----
        if (connState is ConnState.Connected) {
            ConnectedDeviceCard(
                connState = connState as ConnState.Connected,
                deviceInfo = deviceInfo,
                onDisconnect = viewModel::disconnect,
            )
        }

        // ---- 蓝牙设备 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.ble_section), style = MaterialTheme.typography.titleMedium)
                    if (scanning) {
                        OutlinedButton(onClick = viewModel::stopBleScan) {
                            Text(stringResource(R.string.ble_scan_stop))
                        }
                    } else {
                        Button(
                            onClick = {
                                if (hasBlePermission()) {
                                    permissionGranted = true
                                    viewModel.startBleScan()
                                } else {
                                    permissionLauncher.launch(blePermissions)
                                }
                            },
                            enabled = connState !is ConnState.Connecting,
                        ) {
                            Text(stringResource(R.string.ble_scan))
                        }
                    }
                }

                when {
                    !permissionGranted && !hasBlePermission() -> Text(
                        stringResource(R.string.ble_permission_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    scanning && scanResults.isEmpty() -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.ble_scanning),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    !scanning && scanResults.isEmpty() -> Text(
                        stringResource(R.string.ble_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                scanResults.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (device.isDfu) {
                                    viewModel.stopBleScan()
                                    onOpenDfuDevice(device.address, device.name)
                                } else {
                                    viewModel.connectBle(device)
                                }
                            }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(device.name, style = MaterialTheme.typography.bodyLarge)
                                if (device.isDfu) {
                                    Text(
                                        stringResource(R.string.fw_dfu_device_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Text(
                                device.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            stringResource(R.string.rssi_fmt, device.rssi),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }

        // ---- USB 设备 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.usb_section), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = viewModel::refreshUsbDevices,
                        enabled = connState !is ConnState.Connecting,
                    ) {
                        Text(stringResource(R.string.usb_refresh))
                    }
                }

                if (usbDevices.isEmpty()) {
                    Text(
                        stringResource(R.string.usb_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                usbDevices.forEach { entry ->
                    Text(
                        entry.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.connectUsb(entry) }
                            .padding(vertical = 8.dp),
                    )
                    HorizontalDivider()
                }
            }
        }

        // ---- 通用设置（主题/语言/日志/关于） ----
        GeneralSettingsCard(settingsViewModel, snackbarHostState = null, onOpenAbout = onOpenAbout)

        // ---- 硬件设置 ----
        if (connState is ConnState.Connected) {
            HardwareSettingsCard(viewModel)
        }

        // ---- 固件升级入口 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedButton(
                    onClick = onOpenFirmware,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.fw_entry))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ConnectedDeviceCard(
    connState: ConnState.Connected,
    deviceInfo: com.chamelex.connection.DeviceInfo?,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.connected_device), style = MaterialTheme.typography.titleMedium)
                Button(onClick = onDisconnect) {
                    Text(stringResource(R.string.disconnect))
                }
            }
            Text(connState.deviceName, style = MaterialTheme.typography.bodyLarge)
            InfoRow(
                stringResource(R.string.device_link_type),
                stringResource(
                    when (connState.kind) {
                        com.chamelex.connection.TransportKind.BLE -> R.string.transport_ble
                        com.chamelex.connection.TransportKind.USB -> R.string.transport_usb
                    }
                ),
            )
            if (deviceInfo == null) {
                Text(
                    stringResource(R.string.device_info_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                InfoRow(stringResource(R.string.device_model), deviceInfo.model)
                InfoRow(stringResource(R.string.device_fw_version), "v${deviceInfo.appVersion}")
                InfoRow(stringResource(R.string.device_git_version), deviceInfo.gitVersion)
                InfoRow(stringResource(R.string.device_chip_id), deviceInfo.chipId)
                InfoRow(
                    stringResource(R.string.device_battery),
                    if (deviceInfo.batteryPercent == 0 && deviceInfo.batteryMv == 0) {
                        stringResource(R.string.battery_unknown)
                    } else {
                        stringResource(R.string.battery_percent_fmt, deviceInfo.batteryPercent, deviceInfo.batteryMv)
                    },
                )
                InfoRow(
                    stringResource(R.string.device_mode),
                    stringResource(
                        if (deviceInfo.isReaderMode) R.string.device_mode_reader
                        else R.string.device_mode_emulator
                    ),
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 硬件设置：动画模式 + 按键功能映射。 */
@Composable
private fun HardwareSettingsCard(viewModel: ConnectionViewModel) {
    val hw by viewModel.hwSettings.collectAsStateWithLifecycle()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.hw_section), style = MaterialTheme.typography.titleMedium)
            if (hw == null) {
                Text(
                    stringResource(R.string.device_info_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val current = hw!!
                OptionSelector(
                    label = stringResource(R.string.hw_animation),
                    options = com.chamelex.protocol.AnimationMode.entries.map { it to stringResource(animationNameRes(it)) },
                    selected = current.animation,
                    onSelect = { viewModel.setAnimation(it) },
                )
                OptionSelector(
                    label = stringResource(R.string.hw_btn_a_short),
                    options = buttonFunctionOptions(),
                    selected = current.shortA,
                    onSelect = { viewModel.setButtonConfig(com.chamelex.protocol.ButtonType.A, false, it) },
                )
                OptionSelector(
                    label = stringResource(R.string.hw_btn_b_short),
                    options = buttonFunctionOptions(),
                    selected = current.shortB,
                    onSelect = { viewModel.setButtonConfig(com.chamelex.protocol.ButtonType.B, false, it) },
                )
                OptionSelector(
                    label = stringResource(R.string.hw_btn_a_long),
                    options = buttonFunctionOptions(),
                    selected = current.longA,
                    onSelect = { viewModel.setButtonConfig(com.chamelex.protocol.ButtonType.A, true, it) },
                )
                OptionSelector(
                    label = stringResource(R.string.hw_btn_b_long),
                    options = buttonFunctionOptions(),
                    selected = current.longB,
                    onSelect = { viewModel.setButtonConfig(com.chamelex.protocol.ButtonType.B, true, it) },
                )
            }
        }
    }
}

@Composable
private fun buttonFunctionOptions(): List<Pair<com.chamelex.protocol.ButtonPressFunction, String>> =
    com.chamelex.protocol.ButtonPressFunction.entries.map { it to stringResource(buttonFunctionNameRes(it)) }

private fun animationNameRes(mode: com.chamelex.protocol.AnimationMode): Int = when (mode) {
    com.chamelex.protocol.AnimationMode.FULL -> R.string.anim_full
    com.chamelex.protocol.AnimationMode.MINIMAL -> R.string.anim_minimal
    com.chamelex.protocol.AnimationMode.NONE -> R.string.anim_none
    com.chamelex.protocol.AnimationMode.SYMMETRIC -> R.string.anim_symmetric
}

private fun buttonFunctionNameRes(func: com.chamelex.protocol.ButtonPressFunction): Int = when (func) {
    com.chamelex.protocol.ButtonPressFunction.NONE -> R.string.btn_func_none
    com.chamelex.protocol.ButtonPressFunction.NEXT_SLOT -> R.string.btn_func_next_slot
    com.chamelex.protocol.ButtonPressFunction.PREV_SLOT -> R.string.btn_func_prev_slot
    com.chamelex.protocol.ButtonPressFunction.CLONE -> R.string.btn_func_clone
    com.chamelex.protocol.ButtonPressFunction.BATTERY -> R.string.btn_func_battery
    com.chamelex.protocol.ButtonPressFunction.FIELD_GEN -> R.string.btn_func_fieldgen
}

/** 通用下拉选择行。 */
@Composable
private fun <T> OptionSelector(
    label: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Box {
            Text(
                options.firstOrNull { it.first == selected }?.second ?: "-",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            expanded = false
                            if (value != selected) onSelect(value)
                        },
                    )
                }
            }
        }
    }
}

/** 通用设置：主题/语言/协议日志/关于。 */
@Composable
private fun GeneralSettingsCard(
    viewModel: com.chamelex.app.ui.screens.settings.SettingsViewModel,
    snackbarHostState: SnackbarHostState?,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val languageTag by viewModel.languageTag.collectAsStateWithLifecycle()
    val logEnabled by viewModel.protocolLogEnabled.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                viewModel.exportProtocolLog(uri)
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_general), style = MaterialTheme.typography.titleMedium)

            OptionSelector(
                label = stringResource(R.string.settings_theme),
                options = listOf(
                    com.chamelex.data.settings.ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                    com.chamelex.data.settings.ThemeMode.LIGHT to stringResource(R.string.theme_light),
                    com.chamelex.data.settings.ThemeMode.DARK to stringResource(R.string.theme_dark),
                ),
                selected = themeMode,
                onSelect = { viewModel.setTheme(it) },
            )

            OptionSelector(
                label = stringResource(R.string.settings_language),
                options = listOf(
                    "" to stringResource(R.string.lang_system),
                    "zh" to "中文",
                    "en" to "English",
                ),
                selected = languageTag,
                onSelect = { viewModel.setLanguage(it) },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_protocol_log),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.settings_protocol_log_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = logEnabled,
                    onCheckedChange = { viewModel.setProtocolLogEnabled(it) },
                )
            }

            OutlinedButton(
                onClick = {
                    val time = java.text.SimpleDateFormat(
                        "yyyyMMdd-HHmmss", java.util.Locale.getDefault()
                    ).format(java.util.Date())
                    exportLauncher.launch("chamelex-protocol-$time.txt")
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = viewModel.protocolLogCount() > 0,
            ) {
                Text(stringResource(R.string.settings_export_log))
            }

            OutlinedButton(
                onClick = onOpenAbout,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_about))
            }
        }
    }
}
