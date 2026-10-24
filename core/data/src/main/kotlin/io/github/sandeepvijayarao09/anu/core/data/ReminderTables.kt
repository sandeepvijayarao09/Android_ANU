package io.github.sandeepvijayarao09.anu.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import io.github.sandeepvijayarao09.anu.connector.phone.Reminder
import io.github.sandeepvijayarao09.anu.connector.phone.ReminderStore

/**
 * A reminder the user set (ADR-107), encrypted with the rest of the database. It's kept until it's shown, so it can be
 * set again after a reboot, which clears Android's alarms.
 */
@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val atMillis: Long,
    val createdAtMillis: Long,
)

@Dao
interface ReminderDao {
    @Insert
    suspend fun insert(reminder: ReminderEntity): Long

    @Query("SELECT * FROM reminders ORDER BY atMillis, id")
    suspend fun all(): List<ReminderEntity>

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)
}

/** The reminders' store (ADR-107). */
class RoomReminderStore(private val dao: ReminderDao) : ReminderStore {
    override suspend fun add(text: String, atMillis: Long, createdAtMillis: Long): Reminder {
        val id = dao.insert(ReminderEntity(text = text, atMillis = atMillis, createdAtMillis = createdAtMillis))
        return Reminder(id, text, atMillis)
    }

    override suspend fun all(): List<Reminder> = dao.all().map { Reminder(it.id, it.text, it.atMillis) }

    override suspend fun remove(id: Long) = dao.delete(id)
}
