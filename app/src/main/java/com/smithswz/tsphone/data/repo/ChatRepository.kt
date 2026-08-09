package com.smithswz.tsphone.data.repo

import com.smithswz.tsphone.data.db.ConversationSummary
import com.smithswz.tsphone.data.db.MessageDao
import com.smithswz.tsphone.data.db.MessageEntity

class ChatRepository(private val dao: MessageDao) {

    companion object {
        const val LOAD_LIMIT = 200
        const val PRUNE_LIMIT = 500
    }

    /** Inserts and prunes the session to [PRUNE_LIMIT] rows. */
    suspend fun insert(message: MessageEntity) {
        dao.insert(message)
        dao.prune(message.sessionKey, PRUNE_LIMIT)
    }

    /** Newest [LOAD_LIMIT] messages for a session, oldest first (chronological). */
    suspend fun recent(key: String): List<MessageEntity> =
        dao.getRecent(key, LOAD_LIMIT).asReversed()

    suspend fun lastMessage(key: String): MessageEntity? = dao.getLast(key)

    suspend fun conversations(): List<ConversationSummary> = dao.conversationSummaries()
}
