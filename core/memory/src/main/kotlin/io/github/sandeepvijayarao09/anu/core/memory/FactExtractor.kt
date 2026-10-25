package io.github.sandeepvijayarao09.anu.core.memory

import io.github.sandeepvijayarao09.anu.core.model.Brain
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Description
import io.github.sandeepvijayarao09.anu.core.model.JsonSchemas
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One fact as the extraction model writes it. The schema it is held to is generated from this class. */
@Serializable
data class ExtractedFact(
    @Description("\"user\" when it is about the user, otherwise the person's or thing's name") val subject: String,
    @Description("A short snake_case relation, such as works_at, job_title, manager, diet, likes or lives_in") val predicate: String,
    @Description("The value, in a few words") val value: String,
    val section: ProfileSection,
    @Description("The exact words in the message that say this") val quote: String,
    @Description("True for health, money, precise location, ID numbers or intimate details") val sensitive: Boolean = false,
    @Description("True when a person has only one at a time, such as an employer or a home city") val single: Boolean = false,
)

@Serializable
data class Extraction(val facts: List<ExtractedFact>)

/**
 * The on-device brain that extracts facts: Pro when it's installed, else Base (ADR-060). Learning runs after
 * the user leaves the app, so accuracy counts more than speed. On the reference phone, E2B kept nothing from
 * "I'm vegan", which E4B keeps.
 */
fun extractionBrain(brainFor: (BrainId) -> Brain?): Brain? = brainFor(BrainId.PRO) ?: brainFor(BrainId.BASE)

/**
 * Step 2 of the pipeline (§8.2): finds lasting facts in one message the user wrote.
 * - Runs only on an on-device brain, with no tools (P1). Memory is never extracted in the cloud.
 * - The answer is parsed strictly. One that doesn't parse gets one repair, held to the schema, then the
 *   event is retried later (P6). The first answer is free: held to the schema, E4B answered `{"facts":[]}`
 *   for facts told with a request, which it kept when free (ADR-123).
 * - A fact is kept only if its quote is really in the message and it carries no secret (I1).
 */
class FactExtractor(
    /** The on-device brain to use, or null while none is installed. */
    private val brain: () -> Brain?,
    private val today: () -> LocalDate = LocalDate::now,
) {
    sealed interface Result {
        data class Facts(val facts: List<ExtractedFact>) : Result

        /** No on-device model is installed, so the event stays pending. */
        data object NoModel : Result

        data class Failed(val reason: String) : Result
    }

    suspend fun extract(message: String): Result {
        val brain = brain()?.takeIf { it.capabilities.location == BrainLocation.ON_DEVICE } ?: return Result.NoModel
        val system = SYSTEM + "\nToday is ${DAY.format(today())}."
        val first = listOf(ChatMessage.User(message))
        return try {
            val raw = ask(brain, system, first, schema = null)
            val extraction = parse(raw) ?: run {
                // One repair attempt: show the model its own output and what was wrong (P6).
                val repair = first + ChatMessage.Model(raw) + ChatMessage.User(REPAIR)
                parse(ask(brain, system, repair, SCHEMA)) ?: return Result.Failed("the model's output didn't match the schema twice")
            }
            Result.Facts(extraction.facts.mapNotNull { accept(it, message) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private suspend fun ask(brain: Brain, system: String, transcript: List<ChatMessage>, schema: JsonObject?): String {
        val text = StringBuilder()
        brain.stream(
            BrainRequest(
                system = system,
                transcript = transcript,
                responseSchema = schema,
                maxOutputTokens = 512,
                sampling = Sampling.Precise,
                dataClasses = setOf(DataClass.PERSONAL),
                purpose = "memory",
            ),
        ).collect { event -> if (event is BrainEvent.TextDelta) text.append(event.text) }
        return text.toString()
    }

    companion object {
        private val SCHEMA = JsonSchemas.of(Extraction.serializer())
        private val JSON = Json { ignoreUnknownKeys = true }
        private val DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)

        /** The sections as the model writes them: the schema's enum. */
        private val SECTIONS = ProfileSection.serializer().descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName).toSet() }

        private val SYSTEM = """
            You keep the user's private memory on their phone. Read the user's message and list the lasting facts it states about the user, or about the people, places and things in their life.
            Include identity, work, relationships, places, routines, preferences, goals and commitments that will still be true next week.
            Skip requests to the assistant, questions, plans for today only, and anything the message doesn't say.
            Never include passwords, codes, or card or account numbers.
            Use "user" as the subject for the user. Copy the words that state each fact into quote.
            Example message: "My manager Maya wants the report by Friday. I'm vegetarian, so pick somewhere veggie."
            Example answer: {"facts":[{"subject":"user","predicate":"manager","value":"Maya","section":"people","quote":"My manager Maya","single":true},{"subject":"user","predicate":"diet","value":"vegetarian","section":"preferences","quote":"I'm vegetarian","single":true}]}
            If nothing qualifies, answer {"facts":[]}.
        """.trimIndent()

        private const val REPAIR = "That wasn't valid JSON for the schema. Answer with only the JSON object."

        /**
         * Reads the model's answer strictly, except for a section it made up. A free answer doesn't see the schema's
         * sections: on the emulator, one put an allergy in "health", which failed the whole answer, and its repair kept
         * nothing (ADR-123). Naming them in the prompt flipped another answer, so code files such a fact instead: in
         * identity when it's about the user, else in people.
         */
        internal fun parse(raw: String): Extraction? = runCatching {
            val answer = JSON.parseToJsonElement(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).jsonObject
            val facts = answer.getValue("facts").jsonArray.map { element ->
                val fact = element.jsonObject
                if (fact["section"]?.jsonPrimitive?.contentOrNull in SECTIONS) return@map fact
                val about = fact["subject"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val section = if (Normalize.subject(about) == USER) ProfileSection.IDENTITY else ProfileSection.PEOPLE
                JsonObject(fact + ("section" to JSON.encodeToJsonElement(ProfileSection.serializer(), section)))
            }
            JSON.decodeFromJsonElement(Extraction.serializer(), JsonObject(answer + ("facts" to JsonArray(facts))))
        }.getOrNull()

        /**
         * Keeps a fact only if the message really says it and it is safe to keep. Small models sometimes
         * invent facts; requiring the quote, or at least the value, to appear in the message catches most.
         */
        internal fun accept(fact: ExtractedFact, message: String): ExtractedFact? {
            val subject = Normalize.subject(fact.subject)
            val predicate = Normalize.predicate(fact.predicate)
            val value = fact.value.trim()
            if (subject.isEmpty() || predicate.isEmpty() || value.isEmpty()) return null
            if (subject.length > 60 || predicate.length > 40 || value.length > 120) return null
            if (!MemoryFilters.safeToKeep("$predicate $value")) return null
            val said = Normalize.words(message)
            val quoted = Normalize.words(fact.quote).isNotEmpty() && Normalize.words(fact.quote).all { it in said }
            val valueSaid = Normalize.words(value).all { it in said }
            if (!quoted && !valueSaid) return null
            return fact.copy(subject = subject, predicate = predicate, value = value, quote = fact.quote.trim())
        }
    }
}
