package com.smithswz.tsphone.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY lastConnectedAt DESC, name ASC")
    fun getAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun getById(id: Long): BookmarkEntity?

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Update
    suspend fun update(bookmark: BookmarkEntity)

    @Delete
    suspend fun delete(bookmark: BookmarkEntity)

    @Query("UPDATE bookmarks SET lastConnectedAt = :ts WHERE id = :id")
    suspend fun touchLastConnected(id: Long, ts: Long)
}
