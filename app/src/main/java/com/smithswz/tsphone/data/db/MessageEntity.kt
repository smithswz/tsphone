package com.smithswz.tsphone.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One chat message. A "session" is either a channel chat (`c:<channelId>`) or a
 * private conversation (`p:<peerUniqueId>`), keyed by [sessionKey].
 */
@Entity(tableName = "messages", indices = [Index("sessionKey")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionKey: String,
    val sessionType: String,
    val direction: String,
    val senderName: String,
    val peerName: String,
    val body: String,
    val ts: Long
) {
    companion object {
        const val TYPE_CHANNEL = "channel"
        const val TYPE_PRIVATE = "private"
        const val DIRECTION_IN = "in"
        const val DIRECTION_OUT = "out"

        fun channelKey(channelId: Int) = "c:$channelId"
        fun privateKey(peerUid: String) = "p:$peerUid"
    }
}
