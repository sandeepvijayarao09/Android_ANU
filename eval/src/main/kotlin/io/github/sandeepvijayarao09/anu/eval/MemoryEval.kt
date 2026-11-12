package io.github.sandeepvijayarao09.anu.eval

import io.github.sandeepvijayarao09.anu.core.memory.Fact
import io.github.sandeepvijayarao09.anu.core.memory.FactExtractor
import io.github.sandeepvijayarao09.anu.core.memory.InMemoryMemoryStore
import io.github.sandeepvijayarao09.anu.core.memory.MemoryPipeline
import io.github.sandeepvijayarao09.anu.core.memory.ProfileSection
import io.github.sandeepvijayarao09.anu.core.memory.USER
import io.github.sandeepvijayarao09.anu.core.model.Brain
import io.github.sandeepvijayarao09.anu.core.model.BrainCapabilities
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.Embedder
import io.github.sandeepvijayarao09.anu.core.model.StopReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A fact as the eval expects it: [subject] ("user" for the user), a [relation] compared as snake_case, and words the
 * value must hold. Its own matching, apart from memory's, so the eval doesn't grade memory by memory's own rules.
 */
data class Gold(val subject: String, val relation: String, val value: String, val section: String = "people") {
    fun matches(fact: Fact): Boolean =
        fact.subject.equals(subject, ignoreCase = true) && snake(fact.predicate) == snake(relation) &&
            fact.value.contains(value, ignoreCase = true)

    /** What a good extractor answers for it, as JSON. */
    internal fun json(quote: String) =
        """{"subject":"$subject","predicate":"$relation","value":"$value","section":"$section","quote":"${quote.replace("\"", "'")}","single":${snake(relation) in ONE_AT_A_TIME}}"""

    override fun toString() = "$subject.$relation=$value"
}

/** One thing that happens to memory, in order. */
sealed interface MemoryStep {
    /** The user says [text] in chat; a good extractor finds [facts] in it. */
    data class Says(val text: String, val facts: List<Gold> = emptyList()) : MemoryStep

    /** A notification from [sender] in [app]; a good extractor finds [facts] in it (ADR-127). */
    data class Notified(val app: String, val sender: String, val text: String, val facts: List<Gold> = emptyList()) : MemoryStep

    /** "Remember that…", through the memory tool. */
    data class Remember(val fact: Gold) : MemoryStep

    /** The user deletes the active fact matching [fact] in the Memory tab. */
    data class Delete(val fact: Gold) : MemoryStep

    /** The user corrects the active fact matching [fact] to [value]. */
    data class Correct(val fact: Gold, val value: String) : MemoryStep

    /** "Forget this message" on the [step]th step (0-based), which must be a [Says] or [Notified]. */
    data class Forget(val step: Int) : MemoryStep

    /** Time passes, as days between messages. */
    data class Later(val days: Long) : MemoryStep
}

/**
 * A question asked afterwards: the facts that should come with it (ADR-054). Empty: none should. A [meaning] probe
 * shares no word with its facts, so only search by meaning finds them: the JVM tier, without EmbeddingGemma, skips it.
 */
data class Probe(val question: String, val expect: List<Gold>, val meaning: Boolean = false)

/**
 * A made-up life in a few steps, and what memory should hold after them: [active] facts, words that must be [gone]
 * from every table (I5), and [probes]. [gap] names a known gap the scenario measures, so a miss there is expected
 * until it's built.
 */
data class MemoryScenario(
    val name: String,
    val steps: List<MemoryStep>,
    val active: List<Gold> = emptyList(),
    val gone: List<String> = emptyList(),
    /** Words that must be in no fact or profile, though the user's own message that said them may stay (a correction). */
    val wrong: List<String> = emptyList(),
    val probes: List<Probe> = emptyList(),
    val gap: String? = null,
)

/** One scenario's result. */
data class ScenarioScore(
    val scenario: MemoryScenario,
    val missing: List<Gold>,
    val extra: List<String>,
    val contradictions: List<String>,
    val leaks: List<String>,
    val probeHits: Int,
    val probeMisses: List<String>,
    val returned: Int,
    val wanted: Int,
    val skipped: Int = 0,
) {
    val clean: Boolean get() = missing.isEmpty() && contradictions.isEmpty() && leaks.isEmpty() && probeMisses.isEmpty()
}

/** A split's totals: memory's state after each life, and what each question brought along. */
data class MemoryScore(val scenarios: List<ScenarioScore>) {
    val expected: Int get() = scenarios.sumOf { it.scenario.active.size }
    val found: Int get() = expected - scenarios.sumOf { it.missing.size }
    val extra: Int get() = scenarios.sumOf { it.extra.size }
    val contradictions: Int get() = scenarios.sumOf { it.contradictions.size }
    val leaks: Int get() = scenarios.sumOf { it.leaks.size }
    val probes: Int get() = scenarios.sumOf { it.scenario.probes.size } - skipped
    val skipped: Int get() = scenarios.sumOf { it.skipped }
    val probeHits: Int get() = scenarios.sumOf { it.probeHits }

    /** Of the facts a question should bring, how many came; and of what came, how much was wanted. */
    val retrievalRecall: Double get() = ratio(scenarios.sumOf { it.wanted - it.probeMisses.size.coerceAtMost(it.wanted) }, scenarios.sumOf { it.wanted })
    val retrievalPrecision: Double get() = ratio(scenarios.sumOf { s -> (s.wanted - s.probeMisses.size).coerceAtLeast(0) }, scenarios.sumOf { it.returned })

    fun report(name: String): String = buildString {
        appendLine("Memory eval, $name: ${scenarios.count { it.clean }} of ${scenarios.size} lives exactly right")
        appendLine("  facts kept: $found of $expected (${pct(ratio(found, expected))}), $extra extra")
        appendLine("  contradictions left standing: $contradictions; forgotten words left behind: $leaks")
        appendLine("  questions: $probeHits of $probes brought the right facts; precision ${pct(retrievalPrecision)}" + if (skipped > 0) "; $skipped need search by meaning, not run" else "")
        for (s in scenarios.filterNot { it.clean }) {
            append("  ✗ ${s.scenario.name}")
            s.scenario.gap?.let { append(" [gap: $it]") }
            appendLine()
            s.missing.forEach { appendLine("      missing $it") }
            if (s.missing.isNotEmpty()) s.extra.forEach { appendLine("      kept instead $it") }
            s.contradictions.forEach { appendLine("      contradiction $it") }
            s.leaks.forEach { appendLine("      left behind \"$it\"") }
            s.probeMisses.forEach { appendLine("      question $it") }
        }
    }

    private fun ratio(a: Int, b: Int) = if (b == 0) 1.0 else a.toDouble() / b
    private fun pct(value: Double) = "%.0f%%".format(value * 100)
}

/** Relations a person has one of at a time, by the eval's own reckoning: two active values is a contradiction. */
internal val ONE_AT_A_TIME = setOf(
    "name", "birthday", "works_at", "employer", "company", "job_title", "manager", "lives_in", "home_city", "diet", "partner",
)

internal fun snake(relation: String) = relation.trim().lowercase().replace(Regex("[\\s-]+"), "_")

/** Words for one relation, by the eval's reckoning: two employers under two words is still a contradiction. */
private val SAME_RELATION = mapOf("works_at" to "employer", "company" to "employer", "home_city" to "lives_in", "city" to "lives_in")

internal fun relationOf(predicate: String) = snake(predicate).let { SAME_RELATION[it] ?: it }

/**
 * Runs memory scenarios through the real pipeline on an in-memory store. [extractor] gives the brain extraction runs
 * on: by default one that answers each step's gold facts, so the JVM tier scores memory's own logic (reconcile,
 * retrieval, forgetting) deterministically; a device test passes a real Gemma brain to score extraction too.
 */
object MemoryEval {
    private val START = Instant.parse("2026-10-05T17:00:00Z")

    fun run(scenarios: List<MemoryScenario>, brain: ((MemoryScenario) -> Brain)? = null, embedder: Embedder? = null): MemoryScore =
        MemoryScore(scenarios.map { kotlinx.coroutines.runBlocking { one(it, brain?.invoke(it) ?: Scripted(it), embedder) } })

    private suspend fun one(scenario: MemoryScenario, brain: Brain, embedder: Embedder?): ScenarioScore {
        val store = InMemoryMemoryStore()
        var now = START
        val memory = MemoryPipeline(
            store, FactExtractor({ brain }, { LocalDate.ofInstant(now, ZoneOffset.UTC) }), clock = { now }, zone = { ZoneOffset.UTC },
            embedder = { embedder },
        )
        val events = mutableMapOf<Int, Long>()
        scenario.steps.forEachIndexed { index, step ->
            now += Duration.ofMinutes(5)
            when (step) {
                is MemoryStep.Says -> memory.captureChat(step.text, "eval:$index")?.let { events[index] = it }
                is MemoryStep.Notified ->
                    memory.captureNotification("com.example.${step.app.lowercase()}", step.app, step.sender, listOf(step.text), now)?.let { events[index] = it }
                is MemoryStep.Remember -> memory.remember(
                    step.fact.subject, step.fact.relation, step.fact.value, section(step.fact.section), sensitive = false, ref = "eval:$index",
                )
                is MemoryStep.Delete -> store.activeFacts().firstOrNull(step.fact::matches)?.let { memory.delete(it.id) }
                is MemoryStep.Correct -> store.activeFacts().firstOrNull(step.fact::matches)?.let { memory.correct(it.id, step.value) }
                is MemoryStep.Forget -> events[step.step]?.let { memory.forgetSource(it) }
                is MemoryStep.Later -> now += Duration.ofDays(step.days)
            }
            // Learning runs between messages, as it does while the phone is idle and charging, and every night: a day
            // that passes brings its run, which is when upcoming facts come true (ADR-147).
            if (step is MemoryStep.Says || step is MemoryStep.Notified || step is MemoryStep.Later) memory.processPending()
        }
        val active = store.activeFacts()
        val missing = scenario.active.filter { gold -> active.none(gold::matches) }
        val extra = active.filter { fact -> scenario.active.none { it.matches(fact) } }.map { "${it.subject}.${it.predicate}=${it.value}" }
        val contradictions = active.filter { snake(it.predicate) in ONE_AT_A_TIME }
            .groupBy { it.subject.lowercase() to relationOf(it.predicate) }
            .filterValues { it.size > 1 }
            .map { (key, facts) -> "${key.first}.${key.second}: ${facts.joinToString(" / ") { it.value }}" }
        val everything = (
            store.allFacts().flatMap { listOf(it.value, it.evidence.orEmpty()) } + store.allEvents().map { it.content } +
                store.allProfileVersions().map { it.text }
            ).joinToString("\n")
        val kept = (store.allFacts().flatMap { listOf(it.value, it.evidence.orEmpty()) } + store.allProfileVersions().map { it.text }).joinToString("\n")
        val leaks = scenario.gone.filter { everything.contains(it, ignoreCase = true) } + scenario.wrong.filter { kept.contains(it, ignoreCase = true) }
        var hits = 0
        var returned = 0
        var wanted = 0
        val misses = mutableListOf<String>()
        var skipped = 0
        if (embedder != null) memory.index()
        for (probe in scenario.probes) {
            if (probe.meaning && embedder == null) {
                skipped++
                continue
            }
            val brought = memory.relevant(probe.question).mapNotNull { r -> active.firstOrNull { it.id == r.id } }
            returned += brought.size
            wanted += probe.expect.size
            val absent = probe.expect.filter { gold -> brought.none(gold::matches) }
            val unwanted = if (probe.expect.isEmpty()) brought else emptyList()
            if (absent.isEmpty() && unwanted.isEmpty()) {
                hits++
            } else {
                misses += "\"${probe.question}\": " + (absent.map { "didn't bring $it" } + unwanted.map { "brought ${it.text}" }).joinToString(", ")
            }
        }
        return ScenarioScore(scenario, missing, extra, contradictions, leaks, hits, misses, returned, wanted, skipped)
    }

    private fun section(name: String) = ProfileSection.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ProfileSection.PEOPLE

    /** Answers each extraction with the gold facts of the step whose words it was given: a perfect extractor. */
    private class Scripted(private val scenario: MemoryScenario) : Brain {
        override val id = BrainId.PRO
        override val capabilities = BrainCapabilities(BrainLocation.ON_DEVICE, maxContextTokens = 4096, toolCalling = false, structuredOutput = true)

        override fun stream(request: BrainRequest): Flow<BrainEvent> = flow {
            val asked = (request.transcript.lastOrNull { it is ChatMessage.User } as? ChatMessage.User)?.text.orEmpty()
            val step = scenario.steps.firstOrNull { step ->
                when (step) {
                    is MemoryStep.Says -> asked.contains(step.text)
                    is MemoryStep.Notified -> asked.contains(step.text)
                    else -> false
                }
            }
            val facts = when (step) {
                is MemoryStep.Says -> step.facts.map { it.json(step.text) }
                is MemoryStep.Notified -> step.facts.map { it.json(step.text) }
                else -> emptyList()
            }
            emit(BrainEvent.TextDelta("""{"facts":[${facts.joinToString(",")}]}"""))
            emit(BrainEvent.Finished(StopReason.COMPLETE))
        }
    }
}

/** Shorthand for the user's own facts. */
internal fun me(relation: String, value: String, section: String = "people") = Gold(USER, relation, value, section)
