package com.chamelex.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.chamelex.data.settings.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class ChameleXApp : Application() {

    @Inject
    lateinit var settings: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        // 恢复用户选择的语言（等价于 autoStoreLocales 的持久化效果）
        val tag = runBlocking { settings.languageTag.first() }
        if (tag.isNotEmpty()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }
}
