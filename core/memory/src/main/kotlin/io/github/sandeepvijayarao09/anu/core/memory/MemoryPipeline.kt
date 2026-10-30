package io.github.sandeepvijayarao09.anu.core.memory

import io.github.sandeepvijayarao09.anu.core.model.RelevantFact
import kotlinx.coroutines.CancellationException
import io.github.sandeepvijayarao09.anu.core.model.EmbeddingKind
import io.github.sandeepvijayarao09.anu.core.model.Embedder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** What the Memory tab shows (§8.4). */
data class MemoryState(
    val facts: List<Fact> = emptyList(),
    /** The events behind [facts], for "because you said…". */
    val sources: Map<Long, MemoryEvent> = emptyMap(),
    /** Messages waiting to be learned from. */
    val pending: Int = 0,
    val learning: Boolean = false,
    val lastRun: LearnResult? = null,
)

/** The outcome of one learning run. */
data class LearnResult(
    val processed: Int = 0,
    val learned: Int = 0,
    val failed: Int = 0,
    /** No on-device model is installed, so nothing could be learned yet. */
    val noModel: Boolean = false,
    /** Another run was already going. */
    val busy: Boolean = false,
)

/** The Memory tab's view of memory, so `feature:memory` depends on a contract, not the pipeline (§4.2). */
interface MemoryInspector {
    val state: Flow<MemoryState>

    suspend fun learnNow(): LearnResult

    /**
     * Something the user typed in the Memory tab, such as "I'm vegetarian". The on-device model finds
     * the facts in it, and they are kept as the user's own word (P5). Returns how many were kept.
     */
    suspend fun add(text: String): Int

    /** Deletes the fact, and remembers only its fingerprint so it isn't learned again (§8.4). */
    suspend fun delete(factId: Long)

    /** The user says the fact is right. It becomes theirs (P5). */
    suspend fun confirm(factId: Long)

    /** The user's own value replaces the fact (P5). */
    suspend fun correct(factId: Long, value: String)

    /** Forgets a message and everything learned only from it (I5). */
    suspend fun forgetSource(eventId: Long)
}

/**
 * The memory pipeline (ARCHITECTURE §8.2), v1:
 * 1. **Capture:** the user's own chat messages and statements become events (I1 filters first), and so do
 *    notifications from apps the user chose, a day's from one sender in one event (ADR-127).
 * 2. **Extract:** an on-device model finds candidate facts ([FactExtractor]).
 * 3. **Reconcile:** a repeated fact gains confidence and provenance; a new value for a one-at-a-time
 *    relation supersedes the old fact, which is kept in history. Inferred facts never override the
 *    user's own word (P5).
 * 4. **Profile:** each section is rendered from its facts, and a new version is saved when it changes.
 *
 * A fact from notifications is [FactOrigin.NOTICED]: someone else wrote it, so it never replaces a fact from the
 * user's own words, stays out of the memory block, and taints a turn it is shown with until the user confirms it (P4).
 *
 * Every fact keeps its source events (I4). Forgetting a source removes what came only from it and
 * restores anything it had superseded (I5).
 */
class MemoryPipeline(
    private val store: MemoryStore,
    private val extractor: FactExtractor,
    private val clock: () -> Instant = Instant::now,
    /** Search by meaning (ADR-055), when its model is installed. Asked each time, so a model installed later counts. */
    private val embedder: () -> Embedder? = { null },
    /** Where the user is, so a day's notifications gather in one event by their own calendar day. */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : MemoryInspector {

    private val running = Mutex()
    private val learning = MutableStateFlow(false)
    private val lastRun = MutableStateFlow<LearnResult?>(null)

    /** The active facts, kept current by every change the pipeline makes, for tools that need them at once. */
    private val current = MutableStateFlow<List<Fact>>(emptyList())
    val facts: StateFlow<List<Fact>> = current.asStateFlow()

    override val state: Flow<MemoryState> =
        combine(store.observeFacts(), store.observePendingCount(), learning, lastRun) { facts, pending, busy, last ->
            MemoryState(facts, store.events(facts.flatMap { it.sources }.distinct()).associateBy { it.id }, pending, busy, last)
        }

    /** Loads the active facts. Call once at startup. */
    suspend fun start() = reload()

    /** 1. Capture: keeps a chat message to learn from later, if it is safe to keep and about someone. */
    suspend fun captureChat(text: String, ref: String): Long? {
        val content = MemoryFilters.forChat(text) ?: return null
        return store.addEvent(MemoryEvent(0, clock(), EventSource.CHAT, content, ref))
    }

    /**
     * 1. Capture, for notifications (ADR-127): the [lines] of one notification from [conversation] (its sender or chat)
     * in [app], which the user chose to learn from. A day's lines from one conversation gather in one pending event,
     * so a busy chat is one model call, not dozens. A line about a password or code is never kept, card, bank and ID
     * numbers are cut (I1), and a line already kept is skipped: apps post a chat's earlier messages again.
     * Returns the event's id, or null when nothing new was kept.
     */
    suspend fun captureNotification(app: String, appLabel: String, conversation: String, lines: List<String>, at: Instant): Long? {
        val kept = lines.mapNotNull { line -> line.trim().take(MAX_LINE).takeIf { it.length >= MIN_LINE }?.let(MemoryFilters::cleaned) }
        if (kept.isEmpty()) return null
        val ref = notificationRef(app, conversation, at)
        return store.atomically {
            val open = store.pendingWithRef(ref)
            if (open == null) {
                val content = (listOf(notificationHeading(appLabel, conversation)) + kept.distinct()).joinToString("\n").take(MAX_NOTIFICATION_CHARS)
                return@atomically store.addEvent(MemoryEvent(0, at, EventSource.NOTIFICATION, content, ref))
            }
            val have = open.content.lines().toSet()
            val fresh = kept.distinct().filter { it !in have }
            val content = (listOf(open.content) + fresh).joinToString("\n")
            // A day's notifications are capped, so one chatty sender can't fill the learning run.
            if (fresh.isEmpty() || content.length > MAX_NOTIFICATION_CHARS) return@atomically null
            store.setContent(open.id, content)
            open.id
        }
    }

    /** One event per app, conversation and day. The conversation is hashed, so the ref never holds a name. */
    private fun notificationRef(app: String, conversation: String, at: Instant): String {
        val day = at.atZone(zone()).toLocalDate()
        val who = MessageDigest.getInstance("SHA-256").digest(conversation.lowercase().toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        return "notification:$app:$who:$day"
    }

    private fun notificationHeading(appLabel: String, conversation: String): String =
        if (conversation.equals(appLabel, ignoreCase = true)) appLabel else "$appLabel · $conversation"

    /**
     * Clears the words of notifications older than [NOTIFICATION_RETENTION] (ADR-127). The event stays, so the facts
     * learned from it keep their source (I4), and one still waiting is dropped: its day is long past.
     */
    private suspend fun expireNotifications() {
        val before = clock() - NOTIFICATION_RETENTION
        for (event in store.eventsBefore(EventSource.NOTIFICATION, before)) {
            if (event.content == CLEARED) continue
            store.setContent(event.id, CLEARED)
            if (event.status == EventStatus.PENDING) store.setStatus(event.id, EventStatus.FAILED, event.attempts)
        }
    }

    /**
     * 2–4. Learns from every pending event, one at a time, while [shouldContinue] says so. Only one run
     * goes at a time. An event whose extraction fails waits for a later run, since extraction is
     * deterministic and an immediate retry would fail the same way. After [MAX_ATTEMPTS] it is dropped.
     */
    suspend fun processPending(shouldContinue: () -> Boolean = { true }): LearnResult {
        if (!running.tryLock()) return LearnResult(busy = true)
        learning.value = true
        var result = LearnResult()
        val tried = mutableSetOf<Long>()
        var notifications = 0
        try {
            expireNotifications()
            loop@ while (shouldContinue()) {
                val batch = store.pending(BATCH + tried.size).filter { it.id !in tried }
                if (batch.isEmpty()) break
                for (event in batch) {
                    if (!shouldContinue()) break@loop
                    tried += event.id
                    val noticed = event.source == EventSource.NOTIFICATION
                    // Each run reads so many days of notifications; the rest wait for the next (P8).
                    if (noticed && notifications >= MAX_NOTIFICATIONS_PER_RUN) continue
                    if (noticed) notifications++
                    val attempts = event.attempts + 1
                    when (val extracted = extractor.extract(event.content, fromOthers = noticed)) {
                        FactExtractor.Result.NoModel -> {
                            result = result.copy(noModel = true)
                            break@loop
                        }
                        is FactExtractor.Result.Failed -> {
                            store.setStatus(event.id, if (attempts >= MAX_ATTEMPTS) EventStatus.FAILED else EventStatus.PENDING, attempts)
                            result = result.copy(failed = result.failed + 1)
                        }
                        is FactExtractor.Result.Facts -> {
                            val learned = store.atomically {
                                reconcile(event, extracted.facts, if (noticed) FactOrigin.NOTICED else FactOrigin.INFERRED).also { store.setStatus(event.id, EventStatus.DONE, attempts) }
                            }
                            result = result.copy(processed = result.processed + 1, learned = result.learned + learned)
                        }
                    }
                }
            }
            if (result.learned > 0) refreshProfile()
            return result
        } finally {
            lastRun.value = result
            learning.value = false
            running.unlock()
        }
    }

    override suspend fun learnNow(): LearnResult = processPending()

    override suspend fun add(text: String): Int {
        val content = text.trim().takeIf(String::isNotEmpty)?.let(MemoryFilters::cleaned)
        require(content != null) { NOT_KEPT }
        val facts = when (val extracted = extractor.extract(content)) {
            FactExtractor.Result.NoModel -> throw IllegalStateException("Download a model in Settings first. Anu learns only on this phone.")
            is FactExtractor.Result.Failed -> throw IllegalStateException("Anu couldn't read that. Try saying it more simply.")
            is FactExtractor.Result.Facts -> extracted.facts
        }
        if (facts.isEmpty()) return 0
        val kept = store.atomically {
            val event = MemoryEvent(0, clock(), EventSource.STATEMENT, content, null, EventStatus.DONE, 1)
            val id = store.addEvent(event)
            reconcile(event.copy(id = id), facts, FactOrigin.USER_STATED)
        }
        refreshProfile()
        return kept
    }

    /** "Remember that…": the user's own statement, kept at once as their fact (P5). Returns what was kept. */
    suspend fun remember(
        subject: String,
        predicate: String,
        value: String,
        section: ProfileSection,
        sensitive: Boolean,
        ref: String?,
    ): Fact {
        val candidate = ExtractedFact(Normalize.subject(subject), Normalize.predicate(predicate), value.trim(), section, "", sensitive, single = false)
        require(candidate.subject.isNotEmpty() && candidate.predicate.isNotEmpty() && candidate.value.isNotEmpty()) { "Say who it's about, what, and the value." }
        require(MemoryFilters.safeToKeep("${candidate.predicate} ${candidate.value}")) { NOT_KEPT }
        val statement = "You asked Anu to remember: " + factText(candidate.subject, candidate.predicate, candidate.value)
        val kept = store.atomically {
            val event = MemoryEvent(0, clock(), EventSource.STATEMENT, statement, ref, EventStatus.DONE, 1)
            val id = store.addEvent(event)
            reconcile(event.copy(id = id), listOf(candidate.copy(quote = statement)), FactOrigin.USER_STATED)
            store.activeFacts().first { it.subject.equals(candidate.subject, ignoreCase = true) && it.predicate == candidate.predicate && Normalize.value(it.value) == Normalize.value(candidate.value) }
        }
        refreshProfile()
        return kept
    }

    /**
     * Facts that match [query]'s words, most important first. An empty query returns the most important facts. Facts
     * noticed in notifications and not yet confirmed are left out: `memory_search` is a trusted tool, and someone
     * else wrote them (ADR-127).
     */
    suspend fun search(query: String, limit: Int = 10): List<Fact> {
        val facts = store.activeFacts().filter { it.origin != FactOrigin.NOTICED }.sortedWith(IMPORTANCE)
        val words = Normalize.words(query).map { if (it in SELF_WORDS) USER else it }.filter { it !in STOP_WORDS }
        if (words.isEmpty()) return facts.take(limit)
        return facts
            .map { fact -> fact to words.count { word -> word in Normalize.words("${fact.subject} ${fact.predicate.replace('_', ' ')} ${fact.value}") } }
            .filter { (_, hits) -> hits > 0 }
            .sortedByDescending { (_, hits) -> hits }
            .take(limit)
            .map { (fact, _) -> fact }
    }

    override suspend fun delete(factId: Long) {
        store.atomically {
            val fact = store.activeFacts().firstOrNull { it.id == factId } ?: return@atomically
            store.reject(Normalize.fingerprint(fact.subject, fact.predicate, fact.value))
            store.deleteFact(fact.id)
        }
        refreshProfile()
    }

    override suspend fun confirm(factId: Long) {
        store.atomically {
            val fact = store.activeFacts().firstOrNull { it.id == factId } ?: return@atomically
            val event = store.addEvent(MemoryEvent(0, clock(), EventSource.EDIT, "You confirmed: ${fact.text}", null, EventStatus.DONE, 1))
            store.updateFact(fact.copy(origin = FactOrigin.USER_STATED, confidence = 1.0, lastConfirmed = clock(), sources = fact.sources + event))
        }
        refreshProfile()
    }

    override suspend fun correct(factId: Long, value: String) {
        val newValue = value.trim()
        require(newValue.isNotEmpty()) { "The new value is empty." }
        require(MemoryFilters.safeToKeep(newValue)) { "Anu doesn't keep passwords, codes, or card, bank or ID numbers in memory." }
        store.atomically {
            val fact = store.activeFacts().firstOrNull { it.id == factId } ?: return@atomically
            val corrected = fact.copy(value = newValue)
            val event = MemoryEvent(0, clock(), EventSource.EDIT, "You corrected: ${corrected.text}", null, EventStatus.DONE, 1)
            val eventId = store.addEvent(event)
            // The old value is wrong, not just outdated, so it is never learned again.
            store.reject(Normalize.fingerprint(fact.subject, fact.predicate, fact.value))
            val id = store.insertFact(
                corrected.copy(
                    id = 0, origin = FactOrigin.USER_STATED, confidence = 1.0, evidence = event.content,
                    firstSeen = clock(), lastConfirmed = clock(), supersededBy = null, sources = listOf(eventId),
                ),
            )
            store.updateFact(fact.copy(status = FactStatus.SUPERSEDED, supersededBy = id))
        }
        refreshProfile()
    }

    override suspend fun forgetSource(eventId: Long) {
        store.atomically {
            val derived = store.factsFrom(eventId)
            store.deleteEvent(eventId)
            for (fact in derived) {
                val remaining = fact.sources - eventId
                if (remaining.isNotEmpty()) {
                    store.updateFact(fact.copy(sources = remaining))
                    continue
                }
                store.deleteFact(fact.id)
                // Re-derive: what this fact had replaced stands again, as if the message was never said (I5).
                store.factsSupersededBy(fact.id).forEach { store.updateFact(it.copy(status = FactStatus.ACTIVE, supersededBy = null)) }
            }
        }
        refreshProfile()
    }

    /**
     * The facts that bear on [message], most relevant first (§8.3, ADR-054): by the words they share and,
     * with search by meaning installed, by how close they are in meaning (ADR-055). The message is read on
     * its own, unless it points back to someone ("and her birthday?"): then after the [previous] one, which
     * says who. Otherwise the last topic would ride along with the next, unrelated message.
     */
    suspend fun relevant(message: String, previous: String? = null): List<RelevantFact> {
        val about = if (previous != null && Relevance.pointsBack(message)) "$previous\n$message" else message
        return Relevance.pick(about, store.activeFacts(), similarities(about)).map { RelevantFact(it.text, it.sensitive, it.id, noticed = it.origin == FactOrigin.NOTICED) }
    }

    /**
     * Gives every active fact a vector for search by meaning (ADR-055), if its model is installed. Only facts
     * without a vector for the current model are embedded, a few at a time. Returns how many were.
     */
    suspend fun index(): Int {
        val embedder = embedder() ?: return 0
        val have = store.vectors(embedder.model).keys
        val missing = store.activeFacts().filter { it.id !in have }
        for (batch in missing.chunked(INDEX_BATCH)) {
            val vectors = embedder.embed(batch.map(::documentText), EmbeddingKind.DOCUMENT)
            store.putVectors(embedder.model, batch.map { it.id }.zip(vectors).toMap())
        }
        return missing.size
    }

    /** How many active facts have a vector for the installed model, and how many there are. Null without the model. */
    suspend fun indexed(): Pair<Int, Int>? {
        val embedder = embedder() ?: return null
        return store.vectors(embedder.model).size to store.activeFacts().size
    }

    /** How close each fact is in meaning to [message]. Empty without the model or vectors, and on any failure. */
    private suspend fun similarities(message: String): Map<Long, Float> {
        val embedder = embedder() ?: return emptyMap()
        if (!Relevance.hasContent(message)) return emptyMap()
        return try {
            val vectors = store.vectors(embedder.model)
            if (vectors.isEmpty()) return emptyMap()
            val query = embedder.embed(listOf(message), EmbeddingKind.QUERY).single()
            vectors.mapValues { (_, vector) -> Vectors.cosine(query, vector) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** How a fact reads to the embedder: in the user's own voice for their facts, to match how they ask. */
    private fun documentText(fact: Fact): String {
        val relation = fact.predicate.replace('_', ' ')
        return if (fact.subject == USER) "My $relation: ${fact.value}" else "${fact.subject}'s $relation: ${fact.value}"
    }

    /**
     * The memory block on-device brains add to the system prompt (§8.3). The most important facts come
     * first, within [budgetChars]. Null when there is nothing to say. Facts noticed in notifications stay out:
     * the block goes with every turn, and they would taint them all (ADR-127). They come only with a message they bear on.
     */
    suspend fun memoryBlock(budgetChars: Int = 1_500): String? {
        val facts = store.activeFacts().filter { it.origin != FactOrigin.NOTICED }.sortedWith(IMPORTANCE)
        if (facts.isEmpty()) return null
        val kept = mutableListOf<Fact>()
        var used = HEADER.length
        for (fact in facts) {
            val cost = fact.text.length + 4
            if (used + cost > budgetChars) break
            kept += fact
            used += cost
        }
        val lines = ProfileSection.entries.mapNotNull { section ->
            kept.filter { it.section == section }.takeIf { it.isNotEmpty() }?.let { inSection ->
                "- ${section.title}: " + inSection.joinToString("; ") { it.text }
            }
        }
        return (listOf(HEADER) + lines).joinToString("\n")
    }

    /** Step 3. Returns how many facts were added or confirmed. Callers hold [MemoryStore.atomically]. */
    private suspend fun reconcile(event: MemoryEvent, candidates: List<ExtractedFact>, origin: FactOrigin): Int {
        var changed = 0
        val now = clock()
        for (candidate in candidates) {
            val subject = Normalize.subject(candidate.subject)
            val predicate = Normalize.predicate(candidate.predicate)
            val value = candidate.value.trim()
            val fingerprint = Normalize.fingerprint(subject, predicate, value)
            if (store.isRejected(fingerprint)) {
                // A deleted or corrected fact is never learned again from chat, but the user's own word brings it back (P5).
                if (origin != FactOrigin.USER_STATED) continue
                store.forgive(fingerprint)
            }
            val related = store.activeFacts().filter { it.subject.equals(subject, ignoreCase = true) && it.predicate == predicate }
            val same = related.firstOrNull { Normalize.value(it.value) == Normalize.value(value) }
            if (same != null) {
                store.updateFact(
                    same.copy(
                        // The stronger origin stands: the user's word, then their own messages, then notifications.
                        origin = minOf(origin, same.origin),
                        confidence = when {
                            origin == FactOrigin.USER_STATED -> 1.0
                            // A notification adds its source, but no more confidence to what the user's words said.
                            origin == FactOrigin.NOTICED && same.origin != FactOrigin.NOTICED -> same.confidence
                            else -> minOf(CONFIRMED_CEILING, same.confidence + CONFIRMATION_STEP).coerceAtLeast(same.confidence)
                        },
                        sensitive = same.sensitive || candidate.sensitive,
                        evidence = same.evidence ?: candidate.quote.ifEmpty { null },
                        lastConfirmed = now,
                        sources = (same.sources + event.id).distinct(),
                    ),
                )
                changed++
                continue
            }
            val replaced = if (candidate.single || predicate in Normalize.SINGLE_VALUED) related else emptyList()
            // The user's own word stands until they change it themself (P5).
            if (origin == FactOrigin.INFERRED && replaced.any { it.origin == FactOrigin.USER_STATED }) continue
            // Someone else's words never replace a fact from the user's own (ADR-127).
            if (origin == FactOrigin.NOTICED && replaced.any { it.origin != FactOrigin.NOTICED }) continue
            val id = store.insertFact(
                Fact(
                    id = 0, subject = subject, predicate = predicate, value = value, section = candidate.section,
                    confidence = when (origin) {
                        FactOrigin.USER_STATED -> 1.0
                        FactOrigin.INFERRED -> INFERRED_CONFIDENCE
                        FactOrigin.NOTICED -> NOTICED_CONFIDENCE
                    },
                    origin = origin, status = FactStatus.ACTIVE, sensitive = candidate.sensitive,
                    evidence = candidate.quote.ifEmpty { null }, firstSeen = now, lastConfirmed = now, sources = listOf(event.id),
                ),
            )
            replaced.forEach { store.updateFact(it.copy(status = FactStatus.SUPERSEDED, supersededBy = id)) }
            changed++
        }
        return changed
    }

    /** Step 4: renders each section from its facts, and saves a new version only when it changed. */
    private suspend fun refreshProfile() {
        val facts = store.activeFacts().sortedWith(IMPORTANCE)
        val latest = store.latestProfile().associateBy { it.section }
        for (section in ProfileSection.entries) {
            val inSection = facts.filter { it.section == section }
            val text = inSection.joinToString("\n") { "- ${it.text}" }
            val previous = latest[section]
            if (previous?.text == text || (previous == null && text.isEmpty())) continue
            store.saveProfile(ProfileVersion(section, (previous?.version ?: 0) + 1, text, inSection.map { it.id }, clock()))
        }
        reload()
        // New and corrected facts get their vectors now. Search by meaning must never break memory.
        try {
            index()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun reload() {
        current.value = store.activeFacts()
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        private const val NOT_KEPT = "Anu doesn't keep passwords, codes, or card, bank or ID numbers in memory."
        private const val BATCH = 10
        private const val INFERRED_CONFIDENCE = 0.6

        /** A fact noticed in notifications starts lower: someone else wrote it (ADR-127). */
        private const val NOTICED_CONFIDENCE = 0.4

        /** Days of notifications read in one learning run (ADR-127). E4B on a phone's CPU writes 6 to 7 tokens a second. */
        const val MAX_NOTIFICATIONS_PER_RUN = 40

        /** How long a notification's words are kept (ADR-127). */
        val NOTIFICATION_RETENTION: Duration = Duration.ofDays(30)

        /** What a notification event says once its words are cleared. */
        const val CLEARED = "(Cleared after 30 days.)"

        /** A day's notifications from one sender, at most. */
        private const val MAX_NOTIFICATION_CHARS = 2_000
        private const val MAX_LINE = 400
        private const val MIN_LINE = 4
        private const val CONFIRMATION_STEP = 0.15
        private const val CONFIRMED_CEILING = 0.95

        /**
         * The emulator showed the model trusting an old chat message over the user's own correction in the
         * Memory tab, so the header says which wins.
         */
        private const val HEADER = "What you know about the user, from their own words and their edits in the Memory tab. " +
            "It is kept up to date: when it disagrees with older messages in this chat, trust it, unless the user has just said otherwise."

        /** Facts embedded per call when indexing (ADR-055). */
        private const val INDEX_BATCH = 8

        private val SELF_WORDS = setOf("me", "i", "my", "myself", "user")
        private val STOP_WORDS = setOf("what", "do", "you", "know", "about", "the", "a", "an", "is", "are", "of", "any", "anything", "remember")

        /** The user's own facts first, then the surest, then the most recently confirmed. */
        val IMPORTANCE: Comparator<Fact> =
            compareBy<Fact> { it.origin != FactOrigin.USER_STATED }.thenByDescending { it.confidence }.thenByDescending { it.lastConfirmed }
    }
}
