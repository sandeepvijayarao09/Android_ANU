package io.github.sandeepvijayarao09.anu.brain.litertlm

import io.github.sandeepvijayarao09.anu.core.model.Brain
import io.github.sandeepvijayarao09.anu.core.model.BrainCapabilities
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.BrainStatus
import io.github.sandeepvijayarao09.anu.core.model.PromptLab
import io.github.sandeepvijayarao09.anu.core.model.Recording
import io.github.sandeepvijayarao09.anu.core.model.Speed
import io.github.sandeepvijayarao09.anu.core.model.TestStep
import io.github.sandeepvijayarao09.anu.core.model.TestUpdate
import io.github.sandeepvijayarao09.anu.core.model.Transcriber
import io.github.sandeepvijayarao09.anu.core.model.Wav
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The residency policy for Phase 1 (§5): at most one on-device model is loaded at a time.
 * - Switching tiers waits for the running generation, closes the old engine, then loads the new one.
 * - Whether a 12–16 GB phone can keep Base and Pro both loaded is decided by S1 measurements, not
 *   assumed. The first S1 run needed about 9 GB for E4B alone.
 */
class OnDeviceBrains(
    private val models: ModelManager,
    private val maxNumTokens: Int = 4_096,
    /** Keeps the runtime's counters and is told each turn's speed, for the debug log (§15). Numbers only. */
    private val onTurn: ((BrainId, TurnSpeed) -> Unit)? = null,
) : Transcriber, PromptLab {

    /** The loaded model. [vision] says its engine has the vision encoder, which photos need (ADR-075). */
    private class Slot(val id: BrainId, val holder: EngineHolder, val brain: LiteRtLmBrain, val vision: Boolean)

    private val residency = Mutex()
    private var slot: Slot? = null

    private val mutableStatus = MutableStateFlow<BrainStatus>(BrainStatus.Idle)

    /** What the models are doing now: loading, the GPU's first setup, switching, or reading a chat again (ADR-074). */
    val status: StateFlow<BrainStatus> = mutableStatus.asStateFlow()

    private val capabilities = BrainCapabilities(
        location = BrainLocation.ON_DEVICE,
        maxContextTokens = maxNumTokens,
        toolCalling = true,
        structuredOutput = true,
    )

    /** A brain for [id], or null while its model isn't installed (the router then skips that tier). */
    fun brainFor(id: BrainId): Brain? {
        require(id == BrainId.BASE || id == BrainId.PRO) { "$id is not an on-device brain" }
        if (models.installedFile(id) == null) return null
        return object : Brain {
            override val id = id
            override val capabilities = this@OnDeviceBrains.capabilities

            // The lock is held for the whole generation, so a switch never closes an engine mid-decode.
            override fun stream(request: BrainRequest): Flow<BrainEvent> = flow {
                residency.withLock { emitAll(loaded(id, vision = request.hasPhotos).stream(request)) }
            }
        }
    }

    /**
     * Test speed (ADR-073): runs [request] on [id] under the same residency rule as chat, so it waits for the current
     * answer, and says each step: waiting, getting the model ready (or the GPU's first setup), reading, writing. The
     * chat's conversation is set aside, so its next turn reads the chat again. [clock] is in nanoseconds.
     */
    fun test(id: BrainId, request: BrainRequest, clock: () -> Long = System::nanoTime): Flow<TestUpdate> = flow {
        emit(TestUpdate(TestStep.WAITING))
        residency.withLock {
            if (slot?.id != id) emit(TestUpdate(if (models.info(id).variant == "GPU" && !models.prepared(id)) TestStep.GPU_SETUP else TestStep.LOADING))
            val brain = loaded(id)
            emit(TestUpdate(TestStep.READING))
            val sent = clock()
            var first = 0L
            var pieces = 0
            var usage: BrainEvent.Usage? = null
            brain.stream(request).collect { event ->
                when (event) {
                    is BrainEvent.TextDelta -> {
                        val now = clock()
                        if (first == 0L) first = now
                        pieces++
                        emit(TestUpdate(TestStep.WRITING, Speed((first - sent) / 1_000_000, readPerSecond = null, writePerSecond = perSecond(pieces, now - first))))
                    }
                    is BrainEvent.Usage -> usage = event
                    else -> Unit
                }
            }
            val done = usage
            val speed = Speed(
                firstWordMs = if (first > 0) (first - sent) / 1_000_000 else null,
                readPerSecond = done?.let { perSecond(it.inputTokens ?: 0, (it.readMs ?: 0) * 1_000_000) },
                writePerSecond = done?.let { perSecond(it.outputTokens ?: 0, (it.writeMs ?: 0) * 1_000_000) },
                counted = done?.counted ?: false,
                atMillis = System.currentTimeMillis(),
            )
            emit(TestUpdate(TestStep.DONE, speed))
        }
    }

    private fun perSecond(tokens: Int, nanos: Long): Double? = if (tokens <= 0 || nanos <= 0) null else tokens * 1e9 / nanos

    /**
     * Warm start (EXP-1, ADR-074): loads [id] before the first message. It takes the residency lock like a turn, so it
     * never unloads a model mid-answer; a turn that needs another model waits for it, then switches. A load can't be
     * stopped once it starts (A8), so callers decide whether to warm at all.
     */
    suspend fun warm(id: BrainId) {
        if (models.installedFile(id) == null) return
        residency.withLock { loaded(id) }
    }

    override fun available(): Boolean = hearing() != null

    /**
     * Writes [recording] down (G3, ADR-078) with the model already loaded if it can hear, else Pro, else Base, so a
     * voice note rarely switches models. It runs under the residency lock, like a turn, in conversations of its own
     * beside the chat's, which stays cached. A long recording goes in pieces of at most [PIECE_SECONDS], cut at quiet
     * moments, so each fits the window (S6: about 25 tokens a second); their words are joined with a space.
     */
    override fun transcribe(recording: Recording): Flow<String> = flow {
        residency.withLock {
            val id = hearing() ?: error("No installed model can hear")
            val brain = loaded(id)
            var wrote = false
            for (piece in Wav.split(recording.wav, PIECE_SECONDS)) {
                var first = true
                brain.transcribe(piece).collect { words ->
                    emit(if (first && wrote) " $words" else words)
                    first = false
                    wrote = true
                }
            }
        }
    }

    override fun models(): List<BrainId> = listOf(BrainId.BASE, BrainId.PRO).filter { models.installedFile(it) != null }

    /**
     * The prompt lab (G7, ADR-080): [request] on [id], under the residency lock like a turn, in a conversation of its
     * own beside the chat's, which stays cached. Nothing here reads memory, tools or the chat.
     */
    override fun run(id: BrainId, request: BrainRequest): Flow<BrainEvent> = flow {
        require(id == BrainId.BASE || id == BrainId.PRO) { "the lab runs only the models on this phone" }
        residency.withLock { emitAll(loaded(id).once(request)) }
    }

    /** The model that writes recordings down: the loaded one if it can hear, else Pro, else Base. */
    private fun hearing(): BrainId? =
        listOfNotNull(slot?.id, BrainId.PRO, BrainId.BASE).firstOrNull { models.installedFile(it) != null && models.hears(it) }

    /** Frees memory, for example when the app goes to the background (P8). */
    suspend fun unloadAll() = residency.withLock { release() }

    /**
     * The brain for [id], loaded, after releasing any other. Callers hold [residency]. The status says which wait it
     * is. A request with photos needs an engine with the vision encoder: a text-only one loads again with it, and
     * keeps it until the next unload (ADR-075).
     */
    private suspend fun loaded(id: BrainId, vision: Boolean = false): LiteRtLmBrain {
        val current = slot
        val switching = current != null && current.id != id
        val ready = if (current?.id == id && (current.vision || !vision)) {
            current
        } else {
            val keepVision = vision || (current?.id == id && current.vision)
            release()
            val config = models.engineConfig(id, maxNumTokens, keepVision) ?: error("$id model is not installed")
            val holder = EngineHolder(config, models.engineOptions(id, benchmark = onTurn != null))
            val report = onTurn?.let { told -> { speed: TurnSpeed -> told(id, speed) } }
            val reading = { now: Boolean -> mutableStatus.value = if (now) BrainStatus.Reading(id) else BrainStatus.Idle }
            val brain = LiteRtLmBrain(id, capabilities, holder, onTurn = report, backend = models.info(id).variant, onReading = reading)
            Slot(id, holder, brain, keepVision).also { slot = it }
        }
        if (!ready.holder.isLoaded) {
            mutableStatus.value = when {
                models.info(id).variant == "GPU" && !models.prepared(id) -> BrainStatus.SettingUpGpu(id)
                switching -> BrainStatus.Switching(id)
                else -> BrainStatus.Loading(id)
            }
            try {
                ready.holder.get()
            } finally {
                mutableStatus.value = BrainStatus.Idle
            }
        }
        return ready.brain
    }

    private companion object {
        /** The longest piece of a recording the model hears at once: the Gallery's clip length, well within the window. */
        const val PIECE_SECONDS = 30
    }

    private suspend fun release() {
        slot?.let {
            it.brain.reset()
            it.holder.close()
        }
        slot = null
    }
}
