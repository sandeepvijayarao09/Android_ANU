package io.github.sandeepvijayarao09.anu.connector.messages

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Android half of the messaging connector: reading `MessagingStyle` and replying through the
 * notification's `RemoteInput` action, the way a chat app's own reply button works.
 */
@RunWith(AndroidJUnit4::class)
class NotificationParserTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val action = "io.github.sandeepvijayarao09.anu.test.REPLY"

    @Test
    fun readsTheConversationAndRepliesThroughTheReplyButton() = runBlocking {
        val received = CountDownLatch(1)
        var replyText: CharSequence? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                replyText = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply_text")
                received.countDown()
            }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_EXPORTED)
        try {
            val me = Person.Builder().setName("Me").setKey("me").build()
            val maya = Person.Builder().setName("Maya").setKey("maya").build()
            val replyIntent = PendingIntent.getBroadcast(
                context, 0, Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val notification = NotificationCompat.Builder(context, "test")
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setStyle(
                    NotificationCompat.MessagingStyle(me)
                        .addMessage("Lunch at 1?", 1_000, maya)
                        .addMessage("Sure", 2_000, me),
                )
                .addAction(
                    NotificationCompat.Action.Builder(0, "Reply", replyIntent)
                        .addRemoteInput(RemoteInput.Builder("reply_text").setLabel("Reply").build())
                        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                        .build(),
                )
                .build()

            val snapshot = NotificationParser.parse(context, notification, "com.example.chat", "Chat", "com.example.chat:1")!!
            assertEquals("Maya", snapshot.title)
            assertEquals(listOf("Maya" to "Lunch at 1?", "You" to "Sure"), snapshot.lines.map { it.sender to it.text })
            assertTrue(snapshot.lines.last().fromMe)

            snapshot.reply!!.send("Yes, see you at 1")
            assertTrue("the reply button never received the text", received.await(5, TimeUnit.SECONDS))
            assertEquals("Yes, see you at 1", replyText?.toString())
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun ownMessagesWithoutASenderAreTheUsers() {
        val maya = Person.Builder().setName("Maya").build()
        val notification = NotificationCompat.Builder(context, "test")
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setStyle(
                NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
                    .setConversationTitle("Family")
                    .setGroupConversation(true)
                    .addMessage("Dinner at 7?", 1_000, maya)
                    .addMessage("I'll bring dessert", 2_000, null as Person?),
            )
            .build()
        val snapshot = NotificationParser.parse(context, notification, "com.example.chat", "Chat", "k")!!
        assertEquals("Family", snapshot.title)
        assertTrue(snapshot.isGroup)
        assertEquals(listOf("Maya" to false, "You" to true), snapshot.lines.map { it.sender to it.fromMe })
        assertNull(snapshot.reply) // no reply button, so Anu can't reply
    }

    @Test
    fun aMessageAndroidHidIsReceivedAndSaysSo() {
        val system = android.content.res.Resources.getSystem()
        val id = system.getIdentifier("redacted_notification_message", "string", "android")
        org.junit.Assume.assumeTrue("Android hides nothing before 15", id != 0)
        // What Android 15+ gives listeners for a one-time code: its stand-in text, with no sender.
        val notification = NotificationCompat.Builder(context, "test")
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("32665")
            .setStyle(
                NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
                    .addMessage(system.getString(id), 1_000, null as Person?),
            )
            .build()
            // Building sets the title to the newest sender, the user here. The system delivers the chat's.
            .apply { extras.putCharSequence(android.app.Notification.EXTRA_TITLE, "32665") }
        val line = NotificationParser.parse(context, notification, "com.example.sms", "Messages", "k")!!.lines.single()
        assertEquals(NotificationParser.HIDDEN, line.text)
        assertEquals("32665", line.sender)
        assertTrue(!line.fromMe)
    }

    @Test
    fun ordinaryNotificationsAreIgnored() {
        val plain = NotificationCompat.Builder(context, "test")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Backup finished")
            .setContentText("1,204 photos")
            .build()
        assertNull(NotificationParser.parse(context, plain, "com.example.backup", "Backup", "k"))
    }
}
