package com.chamelex.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 按卡记忆的密钥映射：卡 UID → 各扇区 A/B 密钥。
 * keysData 布局同 DumpEntity.keys：每扇区 12 字节（KeyA 6 + KeyB 6，未知 0xFF）。
 */
@Entity(tableName = "card_keys")
data class CardKeysEntity(
    @PrimaryKey val uid: String,
    val sectorCount: Int,
    val keysData: ByteArray,
    val nickname: String? = null,
    val lastUsedAt: Long = System.currentTimeMillis(),
) {
    /** 已记忆密钥位数量（非 0xFF 的 KeyA/KeyB 个数）。 */
    val knownKeyCount: Int
        get() = (0 until sectorCount * 2).count { k ->
            val off = k * 6
            !(0 until 6).all { i -> keysData[off + i] == 0xFF.toByte() }
        }

    override fun equals(other: Any?): Boolean =
        other is CardKeysEntity && uid == other.uid && sectorCount == other.sectorCount &&
                keysData.contentEquals(other.keysData) && nickname == other.nickname &&
                lastUsedAt == other.lastUsedAt

    override fun hashCode(): Int = uid.hashCode()
}

@Dao
interface CardKeysDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: CardKeysEntity)

    @Query("SELECT * FROM card_keys WHERE uid = :uid")
    suspend fun getByUid(uid: String): CardKeysEntity?

    @Query("SELECT * FROM card_keys ORDER BY lastUsedAt DESC")
    fun observeAll(): Flow<List<CardKeysEntity>>

    @Query("DELETE FROM card_keys WHERE uid = :uid")
    suspend fun delete(uid: String)

    @Query("UPDATE card_keys SET nickname = :nickname WHERE uid = :uid")
    suspend fun rename(uid: String, nickname: String?)

    @Query("UPDATE card_keys SET lastUsedAt = :time WHERE uid = :uid")
    suspend fun touch(uid: String, time: Long = System.currentTimeMillis())
}
