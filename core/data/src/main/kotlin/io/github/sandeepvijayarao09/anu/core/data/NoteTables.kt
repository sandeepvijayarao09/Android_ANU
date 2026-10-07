package io.github.sandeepvijayarao09.anu.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import io.github.sandeepvijayarao09.anu.connector.notes.Note
import io.github.sandeepvijayarao09.anu.connector.notes.NoteStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A note the user keeps in Anu (ADR-093), encrypted with the rest of the database. It never leaves the phone. */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val text: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Query("SELECT * FROM notes ORDER BY updatedAtMillis DESC, id DESC")
    suspend fun all(): List<NoteEntity>

    @Query("SELECT * FROM notes ORDER BY updatedAtMillis DESC, id DESC")
    fun observe(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): NoteEntity?

    @Query("UPDATE notes SET text = :text, updatedAtMillis = :atMillis WHERE id = :id")
    suspend fun setText(id: Long, text: String, atMillis: Long)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)

    @Transaction
    suspend fun append(id: Long, text: String, atMillis: Long): NoteEntity {
        val note = get(id) ?: error("that note was deleted")
        val joined = if (note.text.isEmpty()) text else note.text.trimEnd() + "\n" + text
        setText(id, joined, atMillis)
        return note.copy(text = joined, updatedAtMillis = atMillis)
    }
}

/**
 * The notes tools' store (ADR-093). A search reads every note and matches words in Kotlin: a person's notes are few,
 * and SQLite's LIKE can't take one parameter per word.
 */
class RoomNoteStore(private val dao: NoteDao) : NoteStore {
    override suspend fun create(title: String, text: String, atMillis: Long): Note {
        val entity = NoteEntity(title = title, text = text, createdAtMillis = atMillis, updatedAtMillis = atMillis)
        return entity.copy(id = dao.insert(entity)).toNote()
    }

    override suspend fun search(query: String, limit: Int): List<Note> {
        val words = query.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        return dao.all().asSequence()
            .filter { note -> "${note.title}\n${note.text}".lowercase().let { all -> words.all(all::contains) } }
            .take(limit)
            .map { it.toNote() }
            .toList()
    }

    override suspend fun append(id: Long, text: String, atMillis: Long): Note = dao.append(id, text, atMillis).toNote()

    /** Every note, newest first, for the Notes view. */
    fun observe(): Flow<List<Note>> = dao.observe().map { list -> list.map { it.toNote() } }

    suspend fun delete(id: Long) = dao.delete(id)

    private fun NoteEntity.toNote() = Note(id, title, text, updatedAtMillis)
}
