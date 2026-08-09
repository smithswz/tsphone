package com.smithswz.tsphone.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Summary row for the private-chat / conversation list. */
data class ConversationSummary(
    val sessionKey: String,
    val sessionType: String,
    val peerName: String,
    val lastTs: Long
)

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    /** Newest first; callers reverse for chronological display. */
    @Query("SELECT * FROM messages WHERE sessionKey = :key ORDER BY id DESC LIMIT :limit")
    suspend fun getRecent(key: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionKey = :key ORDER BY id DESC LIMIT 1")
    suspend fun getLast(key: String): MessageEntity?

    /** Keeps only the newest [keep] rows for a session. */
    @Query(
        "DELETE FROM messages WHERE sessionKey = :key AND id NOT IN " +
            "(SELECT id FROM messages WHERE sessionKey = :key ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun prune(key: String, keep: Int)

    @Query("SELECT COUNT(*) FROM messages WHERE sessionKey = :key")
    suspend fun countBySession(key: String): Int

    @Query("SELECT sessionKey, sessionType, peerName, MAX(ts) AS lastTs FROM messages GROUP BY sessionKey ORDER BY lastTs DESC")
    suspend fun conversationSummaries(): List<ConversationSummary>
}
