package com.smithswz.tsphone.data.repo

import com.smithswz.tsphone.data.db.BookmarkDao
import com.smithswz.tsphone.data.db.BookmarkEntity
import kotlinx.coroutines.flow.Flow

class BookmarkRepository(private val dao: BookmarkDao) {
    val allBookmarks: Flow<List<BookmarkEntity>> = dao.getAll()

    suspend fun insert(bookmark: BookmarkEntity): Long = dao.insert(bookmark)

    suspend fun update(bookmark: BookmarkEntity) = dao.update(bookmark)

    suspend fun delete(bookmark: BookmarkEntity) = dao.delete(bookmark)

    suspend fun touchLastConnected(id: Long) = dao.touchLastConnected(id, System.currentTimeMillis())
}
