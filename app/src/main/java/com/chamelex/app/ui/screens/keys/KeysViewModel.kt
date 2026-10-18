package com.chamelex.app.ui.screens.keys

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chamelex.data.db.CardKeysDao
import com.chamelex.data.db.CardKeysEntity
import com.chamelex.data.db.KeyDao
import com.chamelex.data.db.KeyEntity
import com.chamelex.data.db.KeyGroupEntity
import com.chamelex.data.keys.KeyProvider
import com.chamelex.protocol.KeyPair
import com.chamelex.protocol.parseKeyDictionary
import com.chamelex.protocol.unpackCardKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class KeysViewModel @Inject constructor(
    private val keyDao: KeyDao,
    private val cardKeysDao: CardKeysDao,
    private val keyProvider: KeyProvider,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val groups: StateFlow<List<KeyGroupEntity>> = keyDao.observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rememberedCards: StateFlow<List<CardKeysEntity>> = cardKeysDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    init {
        viewModelScope.launch { keyProvider.ensureSeeded() }
    }

    fun keysOf(groupId: Long): Flow<List<KeyEntity>> = keyDao.observeKeys(groupId)

    fun keyCountOf(groupId: Long): Flow<Int> = keyDao.observeGroupKeyCount(groupId)

    suspend fun getGroup(id: Long): KeyGroupEntity? = keyDao.getGroup(id)

    fun createGroup(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            keyProvider.getOrCreateGroup(name.trim())
        }
    }

    fun deleteGroup(group: KeyGroupEntity) {
        if (group.isBuiltin) return
        viewModelScope.launch {
            keyDao.deleteKeysOfGroup(group.id)
            keyDao.deleteGroup(group)
        }
    }

    /** 单条添加。返回 null=成功，否则错误文案。 */
    suspend fun addKey(groupId: Long, hex: String): String? {
        val normalized = hex.trim().uppercase()
        if (!normalized.matches(Regex("[0-9A-F]{12}"))) return "必须是 12 位十六进制"
        if (keyDao.countHex(normalized) > 0) return "密钥已存在（全局去重）"
        keyDao.insertKey(KeyEntity(groupId = groupId, keyHex = normalized))
        return null
    }

    fun deleteKey(id: Long) {
        viewModelScope.launch { keyDao.deleteKey(id) }
    }

    /** 导入 .keys/.dic/.txt（按内容解析）。返回 (新增数, 忽略数)。 */
    suspend fun importKeys(groupId: Long, uri: Uri): Pair<Int, Int> {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } ?: throw IllegalStateException("无法读取文件")
        val keys = parseKeyDictionary(text)
        val result = keyDao.insertKeys(
            keys.map { KeyEntity(groupId = groupId, keyHex = it.joinToString("") { b -> "%02X".format(b) }) }
        )
        val inserted = result.count { it > 0 }
        return inserted to (keys.size - inserted)
    }

    /** 导出分组为 .dic 文本。 */
    suspend fun exportGroup(groupId: Long): String =
        keyDao.getGroupKeyHexes(groupId).joinToString("\n") + "\n"

    // ---- 按卡记忆 ----

    fun deleteRememberedCard(uid: String) {
        viewModelScope.launch { cardKeysDao.delete(uid) }
    }

    fun renameRememberedCard(uid: String, nickname: String) {
        viewModelScope.launch { cardKeysDao.rename(uid, nickname.ifBlank { null }) }
    }

    fun cardKeyPairs(card: CardKeysEntity): List<KeyPair> = unpackCardKeys(card.keysData)
}
