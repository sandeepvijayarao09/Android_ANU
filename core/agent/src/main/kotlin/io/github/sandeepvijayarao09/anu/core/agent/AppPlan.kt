package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.Description
import kotlinx.serialization.Serializable

/** One step of an app task's plan, named by the words on its button or field rather than a number (ADR-098). */
@Serializable
data class PlanStep(
    val action: StepAction,
    @Description("The words on the button to tap, or the name of the field to type into or press enter in") val target: String = "",
    @Description("The text to type") val text: String = "",
)

/**
 * An app task's plan, written once from its first screen (ADR-098): the steps the user would take, the words that will
 * show when it's [done], and the [note] to tell the user then. Steps whose words match one element run with no model.
 */
@Serializable
data class AppPlan(
    // The note and the done words come first: written before the steps, they commit a small model to the goal. With
    // the steps first, E4B on the emulator closed the list at once.
    @Description("One short sentence for the user once it's done") val note: String = "",
    @Description("Words the screen will show once the goal is done, such as Added to cart") val done: String = "",
    @Description("The steps, in order, at least one") val steps: List<PlanStep> = emptyList(),
)

/**
 * Finds elements by their words, and the part of a screen that bears on a goal (ADR-098). Both work on the screen's text
 * alone, so they cost no model call.
 */
object ScreenMatch {
    /** A match must score at least this, and beat the next best by [MARGIN], or the model decides. */
    private const val GOOD = 0.75
    private const val MARGIN = 0.1

    /**
     * The element [step] means on [screen], or null when none or more than one fits well: then the model decides.
     * A step with no target, such as a scroll or back, needs none: it gets 0.
     */
    fun find(screen: AppScreen, step: PlanStep): Int? {
        if (step.action == StepAction.DONE || step.action == StepAction.STUCK) return null
        if (step.action !in TARGETED) return 0
        val wanted = words(step.target)
        if (wanted.isEmpty()) return null
        val fits = screen.elements.filter { element ->
            when (step.action) {
                StepAction.TAP -> element.kind != ScreenElement.Kind.FIELD && element.kind != ScreenElement.Kind.LIST
                else -> element.kind == ScreenElement.Kind.FIELD && !element.password
            }
        }
        // Something tappable wins over plain text with the same words, which taps whatever holds it.
        val scored = fits.map { it to score(wanted, words(it.label)) * if (it.kind == ScreenElement.Kind.TEXT) 0.85 else 1.0 }
            .sortedByDescending { it.second }
        val (best, top) = scored.firstOrNull() ?: return null
        val next = scored.getOrNull(1)?.second ?: 0.0
        return if (top >= GOOD && top - next >= MARGIN) best.id else null
    }

    /**
     * How well [label] fits [wanted]: 1 for the same words, then less for each extra word a label holds around all of
     * them ("Add to cart" in "Add to cart · $120"), and the share of words in common otherwise.
     */
    internal fun score(wanted: List<String>, label: List<String>): Double {
        if (label.isEmpty()) return 0.0
        if (label == wanted) return 1.0
        val inLabel = wanted.count { it in label }
        if (inLabel == wanted.size) return (0.95 - 0.04 * (label.size - wanted.size)).coerceAtLeast(0.6)
        return inLabel.toDouble() / (wanted.toSet() + label.toSet()).size
    }

    /** Whether [screen] shows [words] anywhere, as a run of words in one element. */
    fun shows(screen: AppScreen, words: String): Boolean {
        val wanted = words(words)
        if (wanted.isEmpty()) return false
        return screen.elements.any { element ->
            val text = words(listOfNotNull(element.label, element.value).joinToString(" "))
            text.windowed(wanted.size).any { it == wanted }
        }
    }

    /**
     * The part of [screen] the model needs: every element when there are at most [limit], else fields, then the
     * elements that share words with [about] (the goal and the plan's targets), then the first ones on screen, in
     * screen order and with their own numbers, so a step still names the element it means.
     */
    fun focus(screen: AppScreen, about: String, limit: Int = FOCUS_LIMIT): AppScreen {
        if (screen.elements.size <= limit) return screen
        val wanted = words(about).toSet() - FILLER
        val ranked = screen.elements.sortedWith(
            compareByDescending<ScreenElement> { it.kind == ScreenElement.Kind.FIELD }
                .thenByDescending { element -> words(element.label).count { it in wanted } }
                .thenBy { it.id },
        )
        val kept = ranked.take(limit).map { it.id }.toSet()
        return screen.copy(elements = screen.elements.filter { it.id in kept })
    }

    /** Lowercase words without punctuation, plurals made singular, so "Batteries" finds "battery". */
    internal fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.map(::singular)

    private fun singular(word: String): String = when {
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }

    /** About a screenful a small model reads well: a Test Shop screen fits, an Amazon page is cut to it. */
    const val FOCUS_LIMIT = 30
    private val TARGETED = setOf(StepAction.TAP, StepAction.TYPE, StepAction.ENTER)
    private val FILLER = setOf("the", "a", "an", "to", "my", "in", "of", "for", "and", "on", "it", "some", "me")
}
