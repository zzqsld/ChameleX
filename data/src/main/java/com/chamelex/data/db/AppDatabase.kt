package com.chamelex.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DumpEntity::class, KeyGroupEntity::class, KeyEntity::class, CardKeysEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dumpDao(): DumpDao
    abstract fun keyDao(): KeyDao
    abstract fun cardKeysDao(): CardKeysDao
}
