package io.github.sandeepvijayarao09.anu.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Checks that encryption at rest (§14, ADR-024) really happens, with SQLCipher and Android Keystore.
 * Runs on a device dedicated to Anu. It needs no model.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val canary = "CANARY-db-7c1e plaintext that must not appear on disk"

    @Test
    fun theDatabaseFileIsEncryptedAndReopens() = runBlocking {
        File(context.noBackupFilesDir, "db").deleteRecursively()
        AnuDatabase.open(context).run {
            RoomTranscriptStore(transcripts(), "main").append(listOf(ChatMessage.User(canary)))
            close()
        }

        val file = File(context.noBackupFilesDir, "db/anu.db")
        val bytes = file.readBytes()
        assertFalse("plain SQLite header found", String(bytes.copyOfRange(0, 15)) == "SQLite format 3")
        assertFalse("canary found in the database file", String(bytes, Charsets.ISO_8859_1).contains(canary))

        AnuDatabase.open(context).run {
            assertEquals(listOf(ChatMessage.User(canary)), RoomTranscriptStore(transcripts(), "main").load())
            close()
        }
    }

    @Test
    fun aWrongKeyCannotOpenIt() {
        runBlocking { AnuDatabase.open(context).run { RoomTranscriptStore(transcripts(), "main").load(); close() } }
        val wrongKey = "x'${"00".repeat(32)}'".toByteArray()
        val intruder = Room.databaseBuilder(context, AnuDatabase::class.java, File(context.noBackupFilesDir, "db/anu.db").absolutePath)
            .openHelperFactory(SupportOpenHelperFactory(wrongKey))
            .build()
        assertThrows(Exception::class.java) { runBlocking { RoomTranscriptStore(intruder.transcripts(), "main").load() } }
        intruder.close()
    }
}
