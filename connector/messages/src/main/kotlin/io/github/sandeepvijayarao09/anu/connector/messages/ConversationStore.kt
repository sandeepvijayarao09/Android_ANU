package io.github.sandeepvijayarao09.anu.connector.messages

import java.time.Instant

data class ChatLine(val sender: String, val text: String, val at: Instant, val fromMe: Boolean)

/** Sends a reply through the notification's own reply action (ADR-031). It exists only while that notification is showing. */
fun interface ReplySender {
    suspend fun send(text: String)
}

/** What one messaging notification showed. */
data class ConversationSnapshot(
    val key: String,
    val app: String,
    val appLabel: String,
    val title: String,
    val isGroup: Boolean,
    val lines: List<ChatLine>,
    val reply: ReplySender?,
)

data class Conversation(
    val key: String,
    val app: String,
    val appLabel: String,
    val title: String,
    val isGroup: Boolean,
    val lines: List<ChatLine>,
    val reply: ReplySender?,
    val updatedAt: Instant,
)

/**
 * Recent conversations seen in messaging notifications (ARCHITECTURE §9, ADR-031). It holds a bounded
 * in-memory window: the newest [maxConversations], with up to [maxLines] lines each. Notifications
 * repeat earlier lines, so lines are merged without duplicates. Thread-safe.
 */
class ConversationStore(private val maxConversations: Int = 30, private val maxLines: Int = 50) {
    private val conversations = LinkedHashMap<String, Conversation>()

    @Synchronized
    fun update(snapshot: ConversationSnapshot) {
        val previous = conversations.remove(snapshot.key)
        val merged = ((previous?.lines ?: emptyList()) + snapshot.lines)
            .distinctBy { Triple(it.at, it.sender, it.text) }
            .sortedBy { it.at }
            .takeLast(maxLines)
        conversations[snapshot.key] = Conversation(
            key = snapshot.key,
            app = snapshot.app,
            appLabel = snapshot.appLabel,
            title = snapshot.title,
            isGroup = snapshot.isGroup,
            lines = merged,
            reply = snapshot.reply ?: previous?.reply,
            updatedAt = merged.lastOrNull()?.at ?: Instant.EPOCH,
        )
        while (conversations.size > maxConversations) conversations.remove(conversations.keys.first())
    }

    /** The notification is gone, so its reply action may no longer work. The lines stay. */
    @Synchronized
    fun detachReply(key: String) {
        conversations[key]?.let { conversations[key] = it.copy(reply = null) }
    }

    /** Most recent first. */
    @Synchronized
    fun list(): List<Conversation> = conversations.values.sortedByDescending { it.updatedAt }

    @Synchronized
    fun get(key: String): Conversation? = conversations[key]

    /**
     * The chat a model means by [reference]: its id, or else the one chat whose name or phone number
     * matches. Small models often pass the name or number they just read instead of the id, as the
     * emulator showed. The approval sheet names the chat found, so the user checks it before sending.
     */
    @Synchronized
    fun find(reference: String): Conversation? {
        conversations[reference]?.let { return it }
        val wanted = reference.trim()
        val chats = conversations.values
        return chats.singleOrNull { it.title.equals(wanted, ignoreCase = true) }
            ?: chats.singleOrNull { samePhone(it.title, wanted) }
            // Part of a name, such as "Patel" for "Maya Patel". Numbers must match whole, above.
            ?: chats.takeIf { wanted.count(Char::isLetter) >= 3 }?.singleOrNull { it.title.contains(wanted, ignoreCase = true) }
    }

    @Synchronized
    fun clear() = conversations.clear()
}

/** One store per process, shared by the notification listener and the tools. */
object MessagingHub {
    val store = ConversationStore()
}

/** Two phone numbers match when their last digits agree, so "+1 555-123-4567" is "(555) 123-4567". */
internal fun samePhone(a: String, b: String): Boolean {
    val x = a.filter(Char::isDigit)
    val y = b.filter(Char::isDigit)
    if (x.length < 7 || y.length < 7) return false
    val n = minOf(x.length, y.length, 10)
    return x.takeLast(n) == y.takeLast(n)
}
