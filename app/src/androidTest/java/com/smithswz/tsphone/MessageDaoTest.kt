package com.smithswz.tsphone

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smithswz.tsphone.data.db.MessageEntity
import com.smithswz.tsphone.data.db.TsDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageDaoTest {

    private lateinit var db: TsDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun message(id: Int) = MessageEntity(
        sessionKey = "c:1",
        sessionType = MessageEntity.TYPE_CHANNEL,
        direction = MessageEntity.DIRECTION_IN,
        senderName = "user$id",
        peerName = "user$id",
        body = "message $id",
        ts = id.toLong()
    )

    @Test
    fun pruneKeepsNewest500() = runBlocking {
        val dao = db.messageDao()
        repeat(600) { dao.insert(message(it)) }

        dao.prune("c:1", 500)

        assertEquals(500, dao.countBySession("c:1"))
        // 600 inserts → ids 0..599; the 500 survivors must be ids 100..599
        val survivors = dao.getRecent("c:1", 500)
        assertEquals(599, survivors.first().id.toInt())
        assertEquals(100, survivors.last().id.toInt())
    }

    @Test
    fun recentReturnsLast200NewestFirst() = runBlocking {
        val dao = db.messageDao()
        repeat(250) { dao.insert(message(it)) }

        val recent = dao.getRecent("c:1", 200)

        assertEquals(200, recent.size)
        assertEquals(249, recent.first().id.toInt())
        assertEquals(50, recent.last().id.toInt())
    }

    @Test
    fun sessionsAreIndependent() = runBlocking {
        val dao = db.messageDao()
        dao.insert(message(1).copy(sessionKey = "c:1"))
        dao.insert(message(1).copy(sessionKey = "p:uid-xyz"))

        assertEquals(1, dao.countBySession("c:1"))
        assertEquals(1, dao.countBySession("p:uid-xyz"))
        assertEquals(2, dao.conversationSummaries().size)
    }
}
