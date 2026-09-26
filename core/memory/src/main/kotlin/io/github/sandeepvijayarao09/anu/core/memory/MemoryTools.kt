package io.github.sandeepvijayarao09.anu.core.memory

import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Description
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class RememberInput(
    @Description("\"user\" when it is about the user, otherwise the person's or thing's name") val about: String = USER,
    @Description("A short snake_case relation, such as manager, diet, birthday or prefers_meeting_time") val what: String,
    @Description("Just the value, in a few words, such as \"Maya Chen\", \"vegetarian\" or \"mornings\"") val value: String,
    val section: ProfileSection,
    @Description("True for health, money, precise location, ID numbers or intimate details") val sensitive: Boolean = false,
) {
    init {
        require(about.isNotBlank() && about.length <= 60) { "about must name the user or someone, in up to 60 characters" }
        require(what.isNotBlank() && what.length <= 40) { "what must be a short relation" }
        require(value.isNotBlank() && value.length <= 120) { "value must be 1-120 characters" }
    }
}

@Serializable
data class RememberOutput(val remembered: String)

/**
 * Keeps a fact the user asked Anu to remember, as their own statement (P5). It changes only the user's
 * own data, so it runs without asking, unless the run has read content written by others (P4).
 */
class RememberTool(private val memory: MemoryPipeline) : Tool<RememberInput, RememberOutput> {
    override val name = "memory_remember"
    override val description = "Save a lasting fact the user asked you to remember. It stays on this phone, in the Memory tab."
    override val input = RememberInput.serializer()
    override val output = RememberOutput.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val returnsUntrustedContent = false

    override fun risk(input: RememberInput) = RiskTier.WRITE_PRIVATE

    /** Secrets are refused here, before anything runs (I1). */
    override fun describe(input: RememberInput): String {
        require(MemoryFilters.safeToKeep("${input.what} ${input.value}")) {
            "Anu doesn't keep passwords, codes, or card, bank or ID numbers in memory. Tell the user so."
        }
        return "Remember " + factText(Normalize.subject(input.about), Normalize.predicate(input.what), input.value.trim())
    }

    override suspend fun invoke(input: RememberInput, context: ToolContext): RememberOutput {
        val fact = memory.remember(input.about, input.what, input.value, input.section, input.sensitive, ref = context.runId)
        return RememberOutput(fact.text)
    }
}

@Serializable
data class SearchMemoryInput(
    @Description("Words to look for, such as a name or a topic. Leave empty for the most important facts.") val query: String = "",
) {
    init {
        require(query.length <= 200) { "query is too long" }
    }
}

@Serializable
data class MemoryFactOut(
    val id: Long,
    val fact: String,
    val section: String,
    @Description("\"the user said so\" or \"learned from chat\"") val source: String,
)

@Serializable
data class SearchMemoryOutput(val facts: List<MemoryFactOut>)

/**
 * Looks up what Anu remembers. In v1 every fact comes from the user's own words, so results are
 * trusted. That changes when facts are learned from connectors (§8.2).
 */
class SearchMemoryTool(private val memory: MemoryPipeline) : Tool<SearchMemoryInput, SearchMemoryOutput> {
    override val name = "memory_search"
    override val description = "Look up what you remember about the user or someone in their life. Each fact has an id for memory_forget."
    override val input = SearchMemoryInput.serializer()
    override val output = SearchMemoryOutput.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL, DataClass.SENSITIVE)
    override val returnsUntrustedContent = false

    override fun risk(input: SearchMemoryInput) = RiskTier.READ

    override fun describe(input: SearchMemoryInput) =
        if (input.query.isBlank()) "Look up what you remember" else "Look up what you remember about \"${input.query}\""

    override suspend fun invoke(input: SearchMemoryInput, context: ToolContext) = SearchMemoryOutput(
        memory.search(input.query).map { fact ->
            MemoryFactOut(fact.id, fact.text, fact.section.title, if (fact.origin == FactOrigin.USER_STATED) "the user said so" else "learned from chat")
        },
    )
}

@Serializable
data class ForgetInput(
    @Description("Fact ids from memory_search") val ids: List<Long> = emptyList(),
    @Description("Or words that match the facts to forget") val query: String = "",
) {
    init {
        require(ids.size <= 20) { "forget at most 20 facts at once" }
        require(ids.isNotEmpty() || query.isNotBlank()) { "say which facts to forget, by id or by words" }
    }
}

@Serializable
data class ForgetOutput(val forgotten: List<String>)

/**
 * Deletes facts. That can't be undone, so the user always approves the exact list first (I2). The list
 * the user approved is the list deleted, even if memory changes in between.
 */
class ForgetMemoryTool(private val memory: MemoryPipeline) : Tool<ForgetInput, ForgetOutput> {
    override val name = "memory_forget"
    override val description = "Delete facts the user wants forgotten, by id from memory_search, or by words. The user approves the exact list."
    override val input = ForgetInput.serializer()
    override val output = ForgetOutput.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val returnsUntrustedContent = false

    /** What each input resolved to when the user was asked, so exactly that is deleted. */
    private val approved = ConcurrentHashMap<ForgetInput, List<Fact>>()

    override fun risk(input: ForgetInput) = RiskTier.DESTRUCTIVE

    override fun describe(input: ForgetInput): String {
        val facts = resolve(input)
        require(facts.isNotEmpty()) { "Nothing in memory matches that. Call memory_search to find the facts and their ids." }
        approved[input] = facts
        return "Forget " + facts.joinToString("; ") { it.text }
    }

    override suspend fun invoke(input: ForgetInput, context: ToolContext): ForgetOutput {
        val facts = approved.remove(input) ?: resolve(input)
        facts.forEach { memory.delete(it.id) }
        return ForgetOutput(facts.map { it.text })
    }

    private fun resolve(input: ForgetInput): List<Fact> {
        val facts = memory.facts.value
        if (input.ids.isNotEmpty()) return facts.filter { it.id in input.ids }
        val words = Normalize.words(input.query)
        return facts.filter { fact -> words.all { it in Normalize.words("${fact.subject} ${fact.predicate.replace('_', ' ')} ${fact.value}") } }
    }
}
