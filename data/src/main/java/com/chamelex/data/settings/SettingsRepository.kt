package com.chamelex.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 主题模式。 */
enum class ThemeMode(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * 应用设置（DataStore）。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val keyLastBleAddress = stringPreferencesKey("last_ble_address")
    private val keyBuiltinDictImported = booleanPreferencesKey("builtin_dict_imported")
    private val keyThemeMode = stringPreferencesKey("theme_mode")
    private val keyLanguage = stringPreferencesKey("language_tag")
    private val keyProtocolLog = booleanPreferencesKey("protocol_log_enabled")

    /** 上次成功连接的 BLE 设备地址（自动重连用）。 */
    val lastBleAddress: Flow<String?> = context.dataStore.data.map { it[keyLastBleAddress] }

    /** 内置默认字典是否已导入密钥库。 */
    val builtinDictImported: Flow<Boolean> =
        context.dataStore.data.map { it[keyBuiltinDictImported] ?: false }

    /** 主题：system/light/dark。 */
    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { ThemeMode.fromKey(it[keyThemeMode]) }

    /** 语言标签："" = 跟随系统，"zh"/"en"。 */
    val languageTag: Flow<String> = context.dataStore.data.map { it[keyLanguage] ?: "" }

    /** 是否记录协议日志（默认开）。 */
    val protocolLogEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[keyProtocolLog] ?: true }

    suspend fun saveLastBleAddress(address: String) {
        context.dataStore.edit { it[keyLastBleAddress] = address }
    }

    suspend fun setBuiltinDictImported(imported: Boolean) {
        context.dataStore.edit { it[keyBuiltinDictImported] = imported }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[keyThemeMode] = mode.key }
    }

    suspend fun setLanguageTag(tag: String) {
        context.dataStore.edit { it[keyLanguage] = tag }
    }

    suspend fun setProtocolLogEnabled(enabled: Boolean) {
        context.dataStore.edit { it[keyProtocolLog] = enabled }
    }
}
