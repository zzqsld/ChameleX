package com.chamelex.app.ui.screens.firmware

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.chamelex.app.R
import com.chamelex.app.dfu.DfuFlashState
import com.chamelex.connection.ConnState
import com.chamelex.protocol.DeviceModel

/**
 * 固件升级页。
 *
 * @param dfuAddress 非空表示设备已在 DFU（恢复）模式（从设置页扫描列表进入）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirmwareScreen(
    viewModel: FirmwareViewModel,
    dfuAddress: String?,
    dfuName: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val connState by viewModel.connState.collectAsStateWithLifecycle()
    val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
    val fetchState by viewModel.fetchState.collectAsStateWithLifecycle()
    val firmwareFile by viewModel.firmwareFile.collectAsStateWithLifecycle()
    val flashStep by viewModel.flashStep.collectAsStateWithLifecycle()
    val dfuState by viewModel.dfuState.collectAsStateWithLifecycle()

    var showConfirm by remember { mutableStateOf(false) }

    val isDfuMode = dfuAddress != null
    val deviceModel = when {
        deviceInfo?.model?.contains("Lite") == true -> DeviceModel.LITE
        dfuName == "CL" -> DeviceModel.LITE
        else -> DeviceModel.ULTRA
    }

    // 通知权限（Android 13+）
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.loadRelease()
    }

    // 刷写/下载期间保持屏幕常亮
    val busy = flashStep is FlashStep.Flashing ||
            flashStep is FlashStep.EnteringBootloader ||
            flashStep is FlashStep.WaitingDfuDevice ||
            fetchState is FetchState.Downloading
    DisposableEffect(busy) {
        val window = (context as? Activity)?.window
        if (busy) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // 本地文件选择
    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.onLocalZipPicked(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.fw_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.fw_back),
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- 当前设备 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.fw_current_device), style = MaterialTheme.typography.titleMedium)
                    when {
                        isDfuMode -> Text(
                            "$dfuName ($dfuAddress) · ${stringResource(R.string.fw_dfu_device_badge)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )

                        connState is ConnState.Connected && deviceInfo != null -> Text(
                            "${deviceInfo!!.model} · v${deviceInfo!!.appVersion} · ${deviceInfo!!.gitVersion}",
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        else -> Text(
                            stringResource(R.string.fw_need_device),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---- 固件来源：GitHub ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.fw_source_github), style = MaterialTheme.typography.titleMedium)
                    when (val fs = fetchState) {
                        FetchState.Idle, is FetchState.ReleaseLoaded -> {
                            if (fs is FetchState.ReleaseLoaded) {
                                Text(
                                    stringResource(R.string.fw_release_version, fs.release.tagName),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    stringResource(R.string.fw_release_date, fs.release.publishedAt.take(10)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (fs.release.body.isNotBlank()) {
                                    Text(
                                        stringResource(R.string.fw_release_notes),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    Text(
                                        fs.release.body.trim(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .heightIn(max = 160.dp)
                                            .verticalScroll(rememberScrollState()),
                                    )
                                }
                                Button(
                                    onClick = { viewModel.downloadAsset(deviceModel) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = firmwareFile == null,
                                ) {
                                    Text(stringResource(R.string.fw_download, fs.release.tagName))
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.loadRelease() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.fw_load_release))
                                }
                            }
                        }

                        FetchState.LoadingRelease -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                            Text(stringResource(R.string.fw_load_release), style = MaterialTheme.typography.bodySmall)
                        }

                        is FetchState.Downloading -> {
                            val percent = if (fs.total > 0) (fs.downloaded * 100 / fs.total).toInt() else 0
                            LinearProgressIndicator(
                                progress = { percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(
                                    R.string.fw_downloading, percent,
                                    fs.downloaded / 1048576f, fs.total / 1048576f,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(
                                onClick = { viewModel.cancelDownload() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.fw_download_cancel))
                            }
                        }

                        is FetchState.ReleaseError -> Text(
                            fs.cause ?: "", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )

                        is FetchState.DownloadError -> Text(
                            fs.cause ?: "", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )

                        FetchState.DownloadCancelled -> Text(
                            stringResource(R.string.attack_cancelled),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // ---- 固件来源：本地文件 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { safLauncher.launch("application/zip") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.fw_source_local))
                    }
                }
            }

            // ---- 已就绪固件 + 刷机操作 ----
            firmwareFile?.let { fw ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.fw_file_ready, fw.sourceDesc),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (isDfuMode) {
                            Button(
                                onClick = { showConfirm = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.fw_flash_dfu_button))
                            }
                            OutlinedButton(
                                onClick = { viewModel.exitDfuMode() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.fw_exit_dfu))
                            }
                            Text(
                                stringResource(R.string.fw_exit_dfu_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Button(
                                onClick = { showConfirm = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = connState is ConnState.Connected,
                            ) {
                                Text(stringResource(R.string.fw_flash_button))
                            }
                        }
                    }
                }
            }

            // ---- 刷机流程状态 ----
            FlashStepCard(flashStep, dfuState, viewModel)
        }
    }

    // ---- 红色风险确认弹窗 ----
    if (showConfirm) {
        val current = deviceInfo?.let { "v${it.appVersion} (${it.gitVersion})" }
            ?: stringResource(R.string.fw_dfu_device_badge)
        val target = firmwareFile?.sourceDesc ?: ""
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = {
                Text(
                    stringResource(R.string.fw_confirm_title),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            text = { Text(stringResource(R.string.fw_confirm_msg, current, target)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        if (isDfuMode) {
                            viewModel.flashDfuDevice(dfuAddress!!, dfuName ?: "CU")
                        } else {
                            viewModel.flashConnectedDevice()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun FlashStepCard(
    flashStep: FlashStep,
    dfuState: DfuFlashState,
    viewModel: FirmwareViewModel,
) {
    when (flashStep) {
        FlashStep.Idle -> {}

        FlashStep.EnteringBootloader -> StatusCard(
            stringResource(R.string.fw_enter_bootloader), true
        )

        FlashStep.WaitingDfuDevice -> StatusCard(
            stringResource(R.string.fw_waiting_dfu), true
        )

        FlashStep.Flashing -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.fw_flashing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                when (val ds = dfuState) {
                    is DfuFlashState.Working -> {
                        if (ds.percent > 0) {
                            LinearProgressIndicator(
                                progress = { ds.percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("${ds.percent}%", style = MaterialTheme.typography.bodyLarge)
                            if (ds.partsTotal > 1) {
                                Text(
                                    stringResource(R.string.fw_progress_part, ds.part, ds.partsTotal),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (ds.speedKbps > 0) {
                                Text(
                                    stringResource(R.string.fw_progress_speed, ds.speedKbps),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator()
                                Text(ds.stage, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    DfuFlashState.Completed -> Text(
                        stringResource(R.string.fw_done),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    DfuFlashState.Aborted -> Text(
                        stringResource(R.string.fw_aborted),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    is DfuFlashState.Error -> Text(
                        stringResource(R.string.fw_error_fmt, ds.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    DfuFlashState.Idle -> {}
                }
                if (dfuState is DfuFlashState.Working) {
                    OutlinedButton(
                        onClick = { viewModel.cancelFlash() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.fw_cancel_flash))
                    }
                }
            }
        }

        FlashStep.Done -> StatusCard(stringResource(R.string.fw_done), false)

        is FlashStep.Error -> Text(
            stringResource(flashStep.msgRes, flashStep.cause ?: ""),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun StatusCard(text: String, spinner: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (spinner) CircularProgressIndicator()
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
