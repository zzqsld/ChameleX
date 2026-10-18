package com.chamelex.connection.ble

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.flow.first

/**
 * 扫描并返回第一台 DFU（恢复）模式设备。
 *
 * @param expectedName 期望的 DFU 广播名（"CU"/"CL"），null=任意 DFU 设备
 */
@SuppressLint("MissingPermission")
suspend fun scanDfuDevice(context: Context, expectedName: String?): BleDevice {
    android.util.Log.d("ChameleX", "开始扫描 DFU 设备，期望名前缀=$expectedName")
    return BleScanner(context).scan(verbose = true).first { device ->
        device.isDfu && (expectedName == null || device.name.startsWith(expectedName))
    }
}
