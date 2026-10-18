package com.chamelex.data.di

import android.content.Context
import androidx.room.Room
import com.chamelex.data.db.AppDatabase
import com.chamelex.data.db.CardKeysDao
import com.chamelex.data.db.DumpDao
import com.chamelex.data.db.KeyDao
import com.chamelex.data.db.MIGRATION_1_2
import com.chamelex.data.db.MIGRATION_2_3
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "chamelex.db")
            // 数据结构已稳定：正式迁移，保护用户数据
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides
    fun provideDumpDao(db: AppDatabase): DumpDao = db.dumpDao()

    @Provides
    fun provideKeyDao(db: AppDatabase): KeyDao = db.keyDao()

    @Provides
    fun provideCardKeysDao(db: AppDatabase): CardKeysDao = db.cardKeysDao()
}
