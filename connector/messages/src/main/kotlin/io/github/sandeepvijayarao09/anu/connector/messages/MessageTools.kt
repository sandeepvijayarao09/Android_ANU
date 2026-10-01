package io.github.sandeepvijayarao09.anu.connector.messages

import io.github.sandeepvijayarao09.anu.core.agent.EditableField
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Description
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import io.github.sandeepvijayarao09.anu.core.security.OneTimeCodes
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
private fun Instant.local(zone: ZoneId): String = LOCAL.format(atZone(zone))

@Serializable
data class ListConversationsInput(@Description("How many, 1-30") val max: Int = 10) {
    init {
        require(max in 1..30) { "max must be 1-30" }
    }
}

@Serializable
data class ConversationOut(
    val conversationId: String,
    val app: String,
    val title: String,
    val lastFrom: String,
    val lastMessage: String,
    val lastAt: String,
    val canReply: Boolean,
)

@Serializable
data class ConversationsOutput(val conversations: List<ConversationOut>)

/** Recent chats from messaging notifications. Others wrote them, so they are untrusted (P4). Codes are masked (ADR-032). */
class ListConversationsTool(private val store: ConversationStore, private val zone: () -> ZoneId) : Tool<ListConversationsInput, ConversationsOutput> {
    override val name = "messages_list_conversations"
    override val description = "List recent chats from messaging apps (WhatsApp, Messages, Telegram and others), newest first."
    override val input = ListConversationsInput.serializer()
    override val output = ConversationsOutput.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val untrustedContent = UntrustedContent.MESSAGE

    override fun risk(input: ListConversationsInput) = RiskTier.READ
    override fun describe(input: ListConversationsInput) = "Check your recent chats"

    override suspend fun invoke(input: ListConversationsInput, context: ToolContext): ConversationsOutput {
        val zone = zone()
        return ConversationsOutput(
            store.list().take(input.max).map { c ->
                val last = c.lines.lastOrNull()
                ConversationOut(
                    conversationId = c.key,
                    app = c.appLabel,
                    title = c.title,
                    lastFrom = last?.let { if (it.fromMe) "You" else it.sender }.orEmpty(),
                    lastMessage = OneTimeCodes.mask(last?.text.orEmpty()),
                    lastAt = last?.at?.local(zone).orEmpty(),
                    canReply = c.reply != null,
                )
            },
        )
    }
}

@Serializable
data class ReadConversationInput(
    @Description("The chat: a conversationId from messages_list_conversations, or the chat's name or phone number") val conversationId: String,
    @Description("How many recent messages, 1-50") val max: Int = 20,
) {
    init {
        require(conversationId.isNotBlank() && conversationId.length <= 300) { "name the chat with its conversationId, name or phone number" }
        require(max in 1..50) { "max must be 1-50" }
    }
}

@Serializable
data class LineOut(val from: String, val text: String, val at: String)

@Serializable
data class ConversationLinesOutput(val app: String, val title: String, val messages: List<LineOut>, val canReply: Boolean)

class ReadConversationTool(private val store: ConversationStore, private val zone: () -> ZoneId) : Tool<ReadConversationInput, ConversationLinesOutput> {
    override val name = "messages_read_conversation"
    override val description = "Read the recent messages of one chat."
    override val input = ReadConversationInput.serializer()
    override val output = ConversationLinesOutput.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val untrustedContent = UntrustedContent.MESSAGE

    override fun risk(input: ReadConversationInput) = RiskTier.READ
    override fun describe(input: ReadConversationInput) = "Read the chat \"${store.find(input.conversationId)?.title ?: input.conversationId}\""

    override suspend fun invoke(input: ReadConversationInput, context: ToolContext): ConversationLinesOutput {
        val c = store.find(input.conversationId) ?: error(unknownChat(store, input.conversationId))
        val zone = zone()
        return ConversationLinesOutput(
            app = c.appLabel,
            title = c.title,
            messages = c.lines.takeLast(input.max).map { LineOut(if (it.fromMe) "You" else it.sender, OneTimeCodes.mask(it.text), it.at.local(zone)) },
            canReply = c.reply != null,
        )
    }
}

@Serializable
data class ReplyInput(
    @Description("The chat: a conversationId from messages_list_conversations, or the chat's name or phone number") val conversationId: String,
    @Description("The reply to send") val text: String,
) {
    init {
        require(conversationId.isNotBlank() && conversationId.length <= 300) { "name the chat with its conversationId, name or phone number" }
        require(text.isNotBlank() && text.length <= 2_000) { "the reply must be 1-2000 characters" }
    }
}

@Serializable
data class ReplyOutput(val sent: Boolean, val said: String? = null)

/** Replies through the notification's reply button. Others receive it, so it always needs approval (I2). */
class ReplyTool(private val store: ConversationStore) : Tool<ReplyInput, ReplyOutput> {
    override val name = "messages_reply"
    override val description =
        "Send a reply in a chat, through that app's notification. Name the chat by its conversationId, its name or its phone number. The user approves the exact text first."
    override val input = ReplyInput.serializer()
    override val output = ReplyOutput.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val untrustedContent: UntrustedContent? = null

    override fun risk(input: ReplyInput) = RiskTier.WRITE_EXTERNAL
    // The reply's words can change on the approval sheet; the chat it goes to can't (§7.2).
    override val editable = listOf(EditableField("text", "Reply", EditableField.Kind.TEXT))

    /** Unknown chats are rejected here, before anyone is asked to approve. */
    override fun describe(input: ReplyInput): String {
        val c = requireNotNull(store.find(input.conversationId)) { unknownChat(store, input.conversationId) }
        return "Reply in ${c.appLabel} to \"${c.title}\": \"${input.text}\""
    }

    override suspend fun invoke(input: ReplyInput, context: ToolContext): ReplyOutput {
        val c = store.find(input.conversationId) ?: error("That chat is gone.")
        val reply = c.reply ?: error("This chat can't be answered from Anu right now: its notification is gone or has no reply button. Open ${c.appLabel} to reply.")
        reply.send(input.text)
        return ReplyOutput(sent = true, said = "Sent to ${c.title} in ${c.appLabel}.")
    }
}

/** Tells the model which chats exist, so it can correct itself in the same turn rather than ask the user for an id. */
internal fun unknownChat(store: ConversationStore, reference: String): String {
    val known = store.list().take(5).joinToString { "\"${it.title}\" (${it.appLabel}, conversationId ${it.key})" }
    return if (known.isEmpty()) {
        "There is no chat \"$reference\", and no chats have arrived since Anu started. Messages appear here when their notifications do."
    } else {
        "There is no chat \"$reference\". Recent chats: $known. Use one of those conversationIds."
    }
}
