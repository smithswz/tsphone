package com.smithswz.tsphone.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val address: String,
    val port: Int = 9987,
    val password: String? = null,
    val nickname: String? = null,
    val lastConnectedAt: Long? = null
)
