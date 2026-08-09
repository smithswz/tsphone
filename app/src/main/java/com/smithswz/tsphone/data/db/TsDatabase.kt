package com.smithswz.tsphone.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [BookmarkEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class TsDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun messageDao(): MessageDao
}
