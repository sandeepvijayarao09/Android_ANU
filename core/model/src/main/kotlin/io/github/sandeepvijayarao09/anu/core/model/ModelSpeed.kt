package io.github.sandeepvijayarao09.anu.core.model

import kotlinx.coroutines.flow.Flow

/**
 * How fast a model is on this phone, for its sheet on the Models page (ADR-073): from the user's own answers,
 * and from Test speed. Numbers only, never content.
 */
interface ModelSpeed {
    /** The middle of the last answers on [id]'s current build (G8, ENG-7), or null when there are none. */
    suspend fun realUse(id: BrainId): Speed?

    /** The last Test speed on [id], or null when it was never tested. */
    fun lastTest(id: BrainId): Speed?

    /**
     * Runs Test speed on [id] (design review D12): a fixed passage to read and a short answer to write. It waits for
     * the current answer, says each step, and ends with [TestStep.DONE]. Cancelling stops it (I7); the last update
     * holds what was measured so far.
     */
    fun test(id: BrainId): Flow<TestUpdate>

    /** Whether the phone is warm now, so a test wouldn't be comparable (ADR-072). */
    fun warm(): Boolean
}

/**
 * A model's speed: how long the first word took, and how fast it read and wrote. [counted] is true when the rates
 * are the runtime's own counts rather than estimates. [answers] is how many answers a real-use figure comes from.
 */
data class Speed(
    val firstWordMs: Long?,
    val readPerSecond: Double?,
    val writePerSecond: Double?,
    val counted: Boolean = false,
    val atMillis: Long = 0,
    val answers: Int = 0,
    /** The phone was warm, so the numbers aren't comparable with a cool phone's. */
    val warm: Boolean = false,
    /** The test was stopped before it finished: these are partial. */
    val stopped: Boolean = false,
)

enum class TestStep { WAITING, LOADING, GPU_SETUP, READING, WRITING, DONE }

/** Where a test is, and what it has measured so far. */
data class TestUpdate(val step: TestStep, val speed: Speed? = null)
