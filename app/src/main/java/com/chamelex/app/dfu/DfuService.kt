package com.chamelex.app.dfu

import android.app.Activity
import com.chamelex.app.MainActivity
import no.nordicsemi.android.dfu.DfuBaseService

/** Nordic DFU 前台服务（targetSdk 36：库内已按 connectedDevice 类型启动前台服务）。 */
class DfuService : DfuBaseService() {

    override fun getNotificationTarget(): Class<out Activity> = MainActivity::class.java

    override fun isDebug(): Boolean = false
}
