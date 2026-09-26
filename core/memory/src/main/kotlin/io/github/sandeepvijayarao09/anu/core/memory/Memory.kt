package io.github.sandeepvijayarao09.anu.core.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** The subject Anu uses for the user themself. */
const val USER = "user"

/** Where an event came from (§8.1). v1 learns from the user's own words only. */
enum class EventSource {
    /** A message the user wrote in chat. */
    CHAT,

    /** "Remember that…": the user told Anu a fact directly. */
    STATEMENT,

    /** The user confirmed or corrected a fact in the Memory tab. */
    EDIT,
}

/** Where an event is in the pipeline (§8.2). */
enum class EventStatus { PENDING, DONE, FAILED }

/** An append-only record of something the user said (§8.1). Facts point back to these (I4). */
data class MemoryEvent(
    val id: Long,
    val at: Instant,
    val source: EventSource,
    /** What was said. Messages about passwords or codes are never kept, and card, bank and ID numbers are cut (I1). */
    val content: String,
    /** Where it came from, for example the chat turn "main:12". */
    val ref: String?,
    val status: EventStatus = EventStatus.PENDING,
    val attempts: Int = 0,
)

/** Who a fact came from. The user's own word outranks anything inferred (P5). */
enum class FactOrigin { USER_STATED, INFERRED }

enum class FactStatus { ACTIVE, SUPERSEDED }

/** The living profile's sections (§8.1). The serial names are what the extraction model writes. */
@Serializable
enum class ProfileSection(val title: String) {
    @SerialName("identity") IDENTITY("Identity and roles"),
    @SerialName("people") PEOPLE("People and relationships"),
    @SerialName("work") WORK("Work and projects"),
    @SerialName("routines") ROUTINES("Routines and schedule"),
    @SerialName("preferences") PREFERENCES("Preferences"),
    @SerialName("goals") GOALS("Goals and commitments"),
    @SerialName("places") PLACES("Places"),
}

/** One thing Anu knows (§8.1). Every fact has at least one source event (I4). */
data class Fact(
    val id: Long,
    /** [USER] for the user, otherwise the person's or thing's name. */
    val subject: String,
    /** A short snake_case relation, such as `manager` or `diet`. */
    val predicate: String,
    val value: String,
    val section: ProfileSection,
    val confidence: Double,
    val origin: FactOrigin,
    val status: FactStatus,
    /** S2: health, money, precise location, IDs or intimate details (§12.1). Memory never leaves the phone. */
    val sensitive: Boolean,
    /** The user's words that state it, shown as "because you said…" (P5). */
    val evidence: String?,
    val firstSeen: Instant,
    val lastConfirmed: Instant,
    val supersededBy: Long? = null,
    /** The events this fact came from (I4). */
    val sources: List<Long>,
) {
    val text: String get() = factText(subject, predicate, value)
}

/** How a fact reads, for example "manager: Maya Chen", or "Maya Chen · birthday: 12 March". */
fun factText(subject: String, predicate: String, value: String): String {
    val relation = predicate.replace('_', ' ')
    return if (subject == USER) "$relation: $value" else "$subject · $relation: $value"
}

/** One version of a profile section. Every version is kept, so changes can be compared later (§8.2). */
data class ProfileVersion(
    val section: ProfileSection,
    val version: Int,
    val text: String,
    val factIds: List<Long>,
    val at: Instant,
)
