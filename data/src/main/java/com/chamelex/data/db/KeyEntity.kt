package com.chamelex.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 密钥分组。内置组（默认字典）只读。 */
@Entity(tableName = "key_groups")
data class KeyGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 内置组（内置默认字典）只读，不可删改 */
    val isBuiltin: Boolean = false,
    val sortOrder: Int = 0,
)

/** 单个密钥（全局 hex 唯一）。 */
@Entity(
    tableName = "keys",
    indices = [Index(value = ["keyHex"], unique = true), Index(value = ["groupId"])],
)
data class KeyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    /** 12 位大写 hex */
    val keyHex: String,
    /** 来源卡 UID（已破解分组用），可空 */
    val sourceUid: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface KeyDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroup(group: KeyGroupEntity): Long

    @Query("SELECT * FROM key_groups ORDER BY sortOrder, id")
    fun observeGroups(): Flow<List<KeyGroupEntity>>

    @Query("SELECT * FROM key_groups")
    suspend fun getGroups(): List<KeyGroupEntity>

    @Query("SELECT * FROM key_groups WHERE name = :name LIMIT 1")
    suspend fun getGroupByName(name: String): KeyGroupEntity?

    @Query("SELECT * FROM key_groups WHERE id = :id")
    suspend fun getGroup(id: Long): KeyGroupEntity?

    @Delete
    suspend fun deleteGroup(group: KeyGroupEntity)

    @Query("DELETE FROM keys WHERE groupId = :groupId")
    suspend fun deleteKeysOfGroup(groupId: Long)

    /** 返回值 -1 的行表示 hex 重复被忽略。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKeys(keys: List<KeyEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKey(key: KeyEntity): Long

    @Query("SELECT * FROM keys WHERE groupId = :groupId ORDER BY keyHex")
    fun observeKeys(groupId: Long): Flow<List<KeyEntity>>

    @Query("SELECT COUNT(*) FROM keys WHERE groupId = :groupId")
    fun observeGroupKeyCount(groupId: Long): Flow<Int>

    @Query("SELECT keyHex FROM keys WHERE groupId = :groupId")
    suspend fun getGroupKeyHexes(groupId: Long): List<String>

    @Query("SELECT keyHex FROM keys WHERE groupId = :groupId AND sourceUid = :uid")
    suspend fun getGroupKeyHexesBySource(groupId: Long, uid: String): List<String>

    @Query("SELECT COUNT(*) FROM keys WHERE keyHex = :hex")
    suspend fun countHex(hex: String): Int

    @Query("DELETE FROM keys WHERE id = :id")
    suspend fun deleteKey(id: Long)

    @Query("UPDATE key_groups SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setGroupSortOrder(id: Long, sortOrder: Int)
}
