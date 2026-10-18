package com.chamelex.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chamelex.data.settings.SettingsRepository
import com.chamelex.data.settings.ThemeMode
import com.chamelex.protocol.ProtocolLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    val languageTag: StateFlow<String> = settings.languageTag
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val protocolLogEnabled: StateFlow<Boolean> = settings.protocolLogEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        // 同步开关到协议日志缓冲
        viewModelScope.launch {
            settings.protocolLogEnabled.collect { ProtocolLogger.enabled = it }
        }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    /** 语言切换："" = 跟随系统（per-app locale，立即生效）。 */
    fun setLanguage(tag: String) {
        viewModelScope.launch { settings.setLanguageTag(tag) }
        val locales = if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun setProtocolLogEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setProtocolLogEnabled(enabled) }
        ProtocolLogger.enabled = enabled
    }

    /** 导出协议日志到 SAF 目标。 */
    suspend fun exportProtocolLog(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(ProtocolLogger.exportText().toByteArray())
            } != null
        }.getOrDefault(false)
    }

    fun protocolLogCount(): Int = ProtocolLogger.snapshot().size
}
