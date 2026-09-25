package io.github.sandeepvijayarao09.anu.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every schema change keeps existing data (§14). v1 was the first shipped schema. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AnuDatabase::class.java)

    @Test
    fun v1ToV2KeepsTheTranscriptAndAddsToolTables() {
        helper.createDatabase("migration-test", 1).use { db ->
            db.execSQL("INSERT INTO messages (conversationId, seq, role, text, toolCallsJson, toolResultsJson, createdAtMillis) VALUES ('main', 0, 'user', 'hello', NULL, NULL, 1)")
        }
        helper.runMigrationsAndValidate("migration-test", 2, true).use { db ->
            db.query("SELECT text FROM messages").use { c ->
                c.moveToFirst()
                assertEquals("hello", c.getString(0))
            }
            db.query("SELECT COUNT(*) FROM tool_audit").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            db.query("SELECT COUNT(*) FROM tool_results").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        }
    }
}
