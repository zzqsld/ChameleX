package com.chamelex.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DumpDao {
    @Query("SELECT * FROM dumps ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DumpEntity>>

    @Query("SELECT * FROM dumps WHERE id = :id")
    suspend fun getById(id: Long): DumpEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dump: DumpEntity): Long

    @Query("DELETE FROM dumps WHERE id = :id")
    suspend fun deleteById(id: Long)
}
