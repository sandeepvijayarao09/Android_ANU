package io.github.sandeepvijayarao09.anu.brain.litertlm

import io.github.sandeepvijayarao09.anu.core.model.Brain
import io.github.sandeepvijayarao09.anu.core.model.BrainCapabilities
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.BrainStatus
import io.github.sandeepvijayarao09.anu.core.model.DocumentReader
import io.github.sandeepvijayarao09.anu.core.model.PromptLab
import io.github.sandeepvijayarao09.anu.core.model.Recording
import io.github.sandeepvijayarao09.anu.core.model.Speed
import io.github.sandeepvijayarao09.anu.core.model.TestStep
import io.github.sandeepvijayarao09.anu.core.model.TestUpdate
import io.github.sandeepvijayarao09.anu.core.model.Transcriber
import io.github.sandeepvijayarao09.anu.core.model.Wav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The residency policy (§5): which on-device models are loaded.
 * - One at a time, except Base on the TPU beside Pro on the GPU, which S1 measured together on the Pixel 10 Pro
 *   (ADR-106). Loading any other model lets the loaded ones go first.
 * - Each loaded model has its own lock, held while it loads and while it answers, so it's never closed mid-answer,
 *   and a long GPU load doesn't hold up the TPU's turns.
 */
class OnDeviceBrains(
    private val models: ModelManager,
    private val maxNumTokens: Int = 4_096,
    /** Keeps the runtime's counters and is told each turn's speed, for the debug log (§15). Numbers only. */
    private val onTurn: ((BrainId, TurnSpeed) -> Unit)? = null,
) : Transcriber, PromptLab, DocumentReader {

    /**
     * A loaded model. [placement] is where it runs ([ModelManager.placement]); [vision] says its engine has the vision
     * encoder, which photos need (ADR-075). [use] is held while it loads and while it answers. [closed] says it was let
     * go after it was looked up, so the caller looks again.
     */
    private class Slot(val id: BrainId, val placement: String, val holder: EngineHolder, val brain: LiteRtLmBrain, val vision: Boolean) {
        val use = Mutex()

        @Volatile
        var closed = false

        val tpu: Boolean get() = ModelVariant.valueOf(placement) in ModelCatalog.TPU_VERIFIED
        val gpu: Boolean get() = placement == ModelVariant.GPU.name
    }

    /** Guards [slots], the loaded models. Held briefly, or while it waits for a model it lets go to finish answering. */
    private val residency = Mutex()
    private var slots: List<Slot> = emptyList()

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

            override fun stream(request: BrainRequest): Flow<BrainEvent> = flow {
                using(id, vision = request.hasPhotos) { emitAll(it.stream(request)) }
            }
        }
    }

    /**
     * The brain memory's extraction uses (§8.2): Pro, else Base, always on the CPU where Pro's chat runs on the GPU,
     * because on the GPU it missed a fact the CPU finds (ADR-106). Loading it there lets the chat's engines go.
     */
    fun memoryBrain(): Brain? {
        val id = listOf(BrainId.PRO, BrainId.BASE).firstOrNull { models.installedFile(it) != null } ?: return null
        return object : Brain {
            override val id = id
            override val capabilities = this@OnDeviceBrains.capabilities

            override fun stream(request: BrainRequest): Flow<BrainEvent> = flow {
                using(id, cpu = true) { emitAll(it.stream(request)) }
            }
        }
    }

    /**
     * Test speed (ADR-073): runs [request] on [id] under the same residency rule as chat, so it waits for the current
     * answer, and says each step: waiting, getting the model ready (or the GPU's first setup), reading, writing. It
     * runs in a conversation of its own and the chat's stays cached (ADR-097). [clock] is in nanoseconds.
     */
    fun test(id: BrainId, request: BrainRequest, clock: () -> Long = System::nanoTime): Flow<TestUpdate> = flow {
        emit(TestUpdate(TestStep.WAITING))
        val setup = { if (models.placement(id) == ModelVariant.GPU.name && !models.prepared(id)) TestStep.GPU_SETUP else TestStep.LOADING }
        using(id, loading = { emit(TestUpdate(setup())) }) { brain ->
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
     * Warm start (EXP-1, ADR-074): loads [id] before the first message. It waits for the model's current answer like a
     * turn, so it never unloads a model mid-answer. Beside Base on the TPU, Pro then loads on the GPU too, without
     * naming the wait, so a hand-off finds it ready and Base's turns go on meanwhile (ADR-106). A load can't be stopped
     * once it starts (A8), so callers decide whether to warm at all.
     */
    suspend fun warm(id: BrainId) {
        if (models.installedFile(id) == null) return
        using(id) {}
        if (models.proOnGpu) listOf(BrainId.BASE, BrainId.PRO).filter { it != id }.forEach { using(it, quiet = true) {} }
    }

    override fun available(): Boolean = hearing() != null

    /** Whether a model on this phone can read a PDF's pieces (ADR-095): any installed one. */
    override fun canRead(): Boolean = reading() != null

    /**
     * Writes [recording] down (G3, ADR-078) with a model already loaded if it can hear, else Pro, else Base, so a
     * voice note rarely switches models. It waits for that model like a turn, in conversations of its own beside the
     * chat's, which stays cached. A long recording goes in pieces of at most [PIECE_SECONDS], cut at quiet moments, so
     * each fits the window (S6: about 25 tokens a second); their words are joined with a space.
     */
    override fun transcribe(recording: Recording): Flow<String> = flow {
        val id = hearing() ?: error("No installed model can hear")
        using(id) { brain ->
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
     * The prompt lab (G7, ADR-080): [request] on [id], waiting for that model like a turn, in a conversation of its own
     * beside the chat's, which stays cached. Nothing here reads memory, tools or the chat.
     */
    override fun run(id: BrainId, request: BrainRequest): Flow<BrainEvent> = flow {
        require(id == BrainId.BASE || id == BrainId.PRO) { "the lab runs only the models on this phone" }
        using(id) { emitAll(it.once(request)) }
    }

    /**
     * One piece of a PDF (ADR-095), with a model already loaded, else Pro, else Base, so reading rarely switches models.
     * It waits for that model like a turn, in a conversation of its own beside the chat's, which stays cached.
     */
    override fun read(request: BrainRequest): Flow<BrainEvent> = flow {
        val id = reading() ?: error("No model on this phone can read")
        using(id) { emitAll(it.once(request)) }
    }

    private fun loadedIds(): List<BrainId> = slots.filterNot { it.closed }.map { it.id }

    private fun reading(): BrainId? = (loadedIds() + BrainId.PRO + BrainId.BASE).firstOrNull { models.installedFile(it) != null }

    /** The model that writes recordings down: a loaded one if it can hear, else Pro, else Base. */
    private fun hearing(): BrainId? =
        (loadedIds() + BrainId.PRO + BrainId.BASE).firstOrNull { models.installedFile(it) != null && models.hears(it) }

    /** Frees memory, for example when the app goes to the background (P8). */
    suspend fun unloadAll() = residency.withLock {
        slots.forEach { letGo(it) }
        slots = emptyList()
    }

    /**
     * Runs [block] with the brain for [id], loaded, holding that model's lock, so it isn't let go mid-answer. With
     * [cpu], Pro runs on the CPU (memory's extraction). [loading] runs just before a load, and [quiet] keeps a load from
     * naming its wait in [status] (Pro's warm start beside Base). A model let go before its lock was taken is looked up
     * again, and so is one whose TPU or GPU didn't load, now on the CPU.
     */
    private suspend fun <T> using(
        id: BrainId,
        vision: Boolean = false,
        cpu: Boolean = false,
        quiet: Boolean = false,
        loading: suspend () -> Unit = {},
        block: suspend (LiteRtLmBrain) -> T,
    ): T {
        while (true) {
            val (slot, switching) = residency.withLock { slotFor(id, vision, cpu) }
            val done = slot.use.withLock {
                if (slot.closed) return@withLock null
                if (!slot.holder.isLoaded && !load(slot, switching, quiet, loading)) return@withLock null
                Done(block(slot.brain))
            }
            if (done != null) return done.value
        }
    }

    private class Done<T>(val value: T)

    /**
     * The slot for [id] at its placement, letting go of every loaded model that may not stay beside it (ADR-106). A
     * request with photos needs an engine with the vision encoder: a text-only one loads again with it, and keeps it
     * until the next unload (ADR-075). Callers hold [residency]; the second value says another model was let go.
     */
    private suspend fun slotFor(id: BrainId, vision: Boolean, cpu: Boolean): Pair<Slot, Boolean> {
        slots = slots.filterNot { it.closed }
        val placement = models.placement(id, cpu)
        slots.firstOrNull { it.id == id && it.placement == placement && (it.vision || !vision) }?.let { return it to false }
        val keepVision = vision || slots.any { it.id == id && it.placement == placement && it.vision }
        val stays = slots.filter { it.id != id && models.together(it.id to it.placement, id to placement) }
        val going = slots.filterNot { it in stays }
        going.forEach { letGo(it) }
        val config = models.engineConfig(id, maxNumTokens, keepVision, placement) ?: error("$id model is not installed")
        val holder = EngineHolder(config, models.engineOptions(id, benchmark = onTurn != null))
        val report = onTurn?.let { told -> { speed: TurnSpeed -> told(id, speed) } }
        val reading = { now: Boolean -> mutableStatus.value = if (now) BrainStatus.Reading(id) else BrainStatus.Idle }
        val brain = LiteRtLmBrain(id, capabilities, holder, onTurn = report, backend = placement, onReading = reading)
        val slot = Slot(id, placement, holder, brain, keepVision)
        slots = stays + slot
        return slot to going.any { it.id != id }
    }

    /** Lets [slot] go once its current answer is done. */
    private suspend fun letGo(slot: Slot) = slot.use.withLock {
        slot.closed = true
        slot.brain.reset()
        slot.holder.close()
    }

    /**
     * Loads [slot], naming the wait in [status] unless [quiet]. Returns false when its TPU or GPU didn't load (ADR-103,
     * ADR-106): that model then runs on the CPU for the rest of this run, and the caller looks it up again. Without
     * Base's CPU build installed, the turn fails and the router stops offering Base, so Pro answers.
     */
    private suspend fun load(slot: Slot, switching: Boolean, quiet: Boolean, loading: suspend () -> Unit): Boolean {
        loading()
        if (!quiet) {
            mutableStatus.value = when {
                slot.gpu && !models.prepared(slot.id) -> BrainStatus.SettingUpGpu(slot.id)
                switching -> BrainStatus.Switching(slot.id)
                else -> BrainStatus.Loading(slot.id)
            }
        }
        try {
            slot.holder.get()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when {
                slot.tpu -> models.tpuFailed()
                slot.gpu && slot.id == BrainId.PRO -> models.gpuFailed()
                else -> throw e
            }
            slot.closed = true
            slot.holder.close()
            if (models.installedFile(slot.id) == null) throw e
            return false
        } finally {
            if (!quiet) mutableStatus.value = BrainStatus.Idle
        }
    }

    private companion object {
        /** The longest piece of a recording the model hears at once: the Gallery's clip length, well within the window. */
        const val PIECE_SECONDS = 30
    }
}
