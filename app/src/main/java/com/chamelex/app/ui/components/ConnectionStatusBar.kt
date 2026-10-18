package com.chamelex.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chamelex.app.ConnectionViewModel
import com.chamelex.app.R
import com.chamelex.connection.ConnState

/**
 * 顶部全局连接状态栏：未连接（灰）/ 连接中（转圈）/ 已连接（设备名+型号+固件+电量）。
 */
@Composable
fun ConnectionStatusBar(viewModel: ConnectionViewModel) {
    val state by viewModel.connState.collectAsStateWithLifecycle()
    val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (val s = state) {
                is ConnState.Connecting -> {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.conn_connecting_fmt, kindLabel(s.kind)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                is ConnState.Connected -> {
                    StatusDot(Color(0xFF4CAF50))
                    Spacer(Modifier.width(8.dp))
                    val info = deviceInfo
                    val text = buildString {
                        append(kindLabel(s.kind))
                        append(" · ").append(s.deviceName)
                        if (info != null) {
                            append(" · ").append(info.model)
                            append(" · v").append(info.appVersion)
                            if (info.batteryPercent == 0 && info.batteryMv == 0) {
                                append(" · ").append(stringResource(R.string.battery_unknown))
                            } else {
                                append(" · ").append(info.batteryPercent).append('%')
                            }
                        }
                    }
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }

                is ConnState.Error -> {
                    StatusDot(MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${stringResource(R.string.conn_error)}：${s.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                ConnState.Disconnected -> {
                    StatusDot(Color.Gray)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.conn_disconnected),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, CircleShape)
    )
}

@Composable
private fun kindLabel(kind: com.chamelex.connection.TransportKind): String = stringResource(
    when (kind) {
        com.chamelex.connection.TransportKind.BLE -> R.string.transport_ble
        com.chamelex.connection.TransportKind.USB -> R.string.transport_usb
    }
)
