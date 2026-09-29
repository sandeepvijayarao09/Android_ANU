package io.github.sandeepvijayarao09.anu.connector.gmail

import io.github.sandeepvijayarao09.anu.core.agent.EditableField
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Description
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import kotlinx.serialization.Serializable

private val THREAD_ID = Regex("[A-Za-z0-9]{6,64}")

/** Shared checks for anything Anu writes as email. They run before approval, so bad input is never shown for it. */
private fun validateMessage(to: List<String>, cc: List<String>, subject: String, body: String, threadId: String?) {
    require(to.isNotEmpty()) { "at least one recipient is needed" }
    require(to.size + cc.size <= 20) { "at most 20 recipients" }
    (to + cc).forEach { require(EMAIL_ADDRESS.matches(it)) { "\"$it\" is not an email address" } }
    require(subject.length <= 200 && '\r' !in subject && '\n' !in subject) { "the subject must be one line of at most 200 characters" }
    require(body.isNotBlank() && body.length <= 20_000) { "the body must be 1-20000 characters" }
    threadId?.let { require(THREAD_ID.matches(it)) { "\"$it\" is not a thread id" } }
}

@Serializable
data class SearchInput(
    @Description("Gmail search, e.g. from:maya newer_than:7d or subject:invoice") val query: String,
    @Description("How many results, 1-20") val max: Int = 10,
) {
    init {
        require(query.isNotBlank() && query.length <= 500) { "query must be 1-500 characters" }
        require(max in 1..20) { "max must be 1-20" }
    }
}

@Serializable
data class FoundEmail(val threadId: String, val from: String, val subject: String, val date: String, val snippet: String)

@Serializable
data class SearchOutput(val emails: List<FoundEmail>)

/** Searches the user's mail. Results are written by other people, so they are untrusted (P4, ADR-030). */
class SearchEmailTool(private val gmail: GmailClient) : Tool<SearchInput, SearchOutput> {
    override val name = "email_search"
    override val description = "Search the user's Gmail. Returns thread ids with sender, subject, date and a snippet."
    override val input = SearchInput.serializer()
    override val output = SearchOutput.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val untrustedContent = UntrustedContent.EMAIL

    override fun risk(input: SearchInput) = RiskTier.READ
    override fun describe(input: SearchInput) = "Search your email for \"${input.query}\""

    override suspend fun invoke(input: SearchInput, context: ToolContext) =
        SearchOutput(gmail.search(input.query, input.max).map { FoundEmail(it.threadId, it.from, it.subject, it.date, it.snippet) })
}

@Serializable
data class ThreadInput(@Description("A threadId from email_search") val threadId: String) {
    init {
        require(THREAD_ID.matches(threadId)) { "\"$threadId\" is not a thread id" }
    }
}

@Serializable
data class ThreadMessageOut(val from: String, val to: String, val date: String, val text: String)

@Serializable
data class ThreadOutput(val subject: String, val messages: List<ThreadMessageOut>)

/** Reads one conversation. The bodies are untrusted, and each is cut to 4,000 characters. */
class GetThreadTool(private val gmail: GmailClient) : Tool<ThreadInput, ThreadOutput> {
    override val name = "email_get_thread"
    override val description = "Read an email conversation by threadId."
    override val input = ThreadInput.serializer()
    override val output = ThreadOutput.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val untrustedContent = UntrustedContent.EMAIL

    override fun risk(input: ThreadInput) = RiskTier.READ
    override fun describe(input: ThreadInput) = "Read an email conversation"

    override suspend fun invoke(input: ThreadInput, context: ToolContext): ThreadOutput {
        val thread = gmail.thread(input.threadId)
        return ThreadOutput(thread.subject, thread.messages.map { ThreadMessageOut(it.from, it.to, it.date, it.text) })
    }
}

@Serializable
data class EmailInput(
    @Description("Recipient email addresses") val to: List<String>,
    @Description("One line") val subject: String,
    @Description("Plain text") val body: String,
    @Description("Optional CC addresses") val cc: List<String> = emptyList(),
    @Description("To reply in a conversation, its threadId from email_search") val replyToThreadId: String? = null,
) {
    init {
        validateMessage(to, cc, subject, body, replyToThreadId)
    }
}

@Serializable
data class DraftOutput(val draftId: String)

@Serializable
data class SentOutput(val messageId: String)

/** Builds the message. A reply takes its In-Reply-To and References from the thread's last message. */
private suspend fun compose(gmail: GmailClient, input: EmailInput): Pair<String, String?> {
    val parent = input.replyToThreadId?.let { gmail.thread(it).messages.lastOrNull() }
    val subject = when {
        input.subject.isNotBlank() -> input.subject
        parent != null -> if (parent.subject.startsWith("Re:", ignoreCase = true)) parent.subject else "Re: ${parent.subject}"
        else -> ""
    }
    val email = OutgoingEmail(input.to, input.cc, subject, input.body, inReplyTo = parent?.messageId, references = parent?.messageId)
    return Mime.build(email) to input.replyToThreadId
}

private fun summary(verb: String, input: EmailInput): String = buildString {
    append(verb).append(" to ").append(input.to.joinToString())
    if (input.cc.isNotEmpty()) append(" (cc ").append(input.cc.joinToString()).append(')')
    if (input.subject.isNotBlank()) append(": \"").append(input.subject).append('"')
    if (input.replyToThreadId != null) append(", as a reply")
}

/** What the user may change on the approval sheet (§7.2). The thread a reply belongs to stays as it is. */
internal val EMAIL_EDITABLE = listOf(
    EditableField("to", "To", EditableField.Kind.LIST),
    EditableField("cc", "Cc", EditableField.Kind.LIST),
    EditableField("subject", "Subject", EditableField.Kind.LINE),
    EditableField("body", "Message", EditableField.Kind.TEXT),
)

/** Saves a draft in the user's own mailbox. Nobody else sees it, so it is WRITE_PRIVATE. */
class CreateDraftTool(private val gmail: GmailClient) : Tool<EmailInput, DraftOutput> {
    override val name = "email_create_draft"
    override val description = "Save an email as a draft in the user's Gmail, without sending it."
    override val input = EmailInput.serializer()
    override val output = DraftOutput.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val untrustedContent: UntrustedContent? = null

    override fun risk(input: EmailInput) = RiskTier.WRITE_PRIVATE
    override fun describe(input: EmailInput) = summary("Save a draft", input)
    override val editable = EMAIL_EDITABLE

    override suspend fun invoke(input: EmailInput, context: ToolContext): DraftOutput {
        val (raw, thread) = compose(gmail, input)
        return DraftOutput(gmail.createDraft(raw, thread))
    }
}

/** Sends an email. Other people receive it, so it always needs approval of the exact message (I2). */
class SendEmailTool(private val gmail: GmailClient) : Tool<EmailInput, SentOutput> {
    override val name = "email_send"
    override val description = "Send an email from the user's Gmail. The user approves the exact message first."
    override val input = EmailInput.serializer()
    override val output = SentOutput.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val untrustedContent: UntrustedContent? = null

    override fun risk(input: EmailInput) = RiskTier.WRITE_EXTERNAL
    override fun describe(input: EmailInput) = summary("Send an email", input)
    override val editable = EMAIL_EDITABLE

    override suspend fun invoke(input: EmailInput, context: ToolContext): SentOutput {
        val (raw, thread) = compose(gmail, input)
        return SentOutput(gmail.send(raw, thread))
    }
}
