package io.github.sandeepvijayarao09.anu.brain.litertlm

import io.github.sandeepvijayarao09.anu.core.model.Brain
import io.github.sandeepvijayarao09.anu.core.model.BrainCapabilities
import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import kotlinx.coroutines.flow.Flow
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
) {

    private class Slot(val id: BrainId, val holder: EngineHolder, val brain: LiteRtLmBrain)

    private val residency = Mutex()
    private var slot: Slot? = null

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
                residency.withLock { emitAll(loaded(id).stream(request)) }
            }
        }
    }

    /** Frees memory, for example when the app goes to the background (P8). */
    suspend fun unloadAll() = residency.withLock { release() }

    /** The brain for [id], loading it after releasing any other. Callers hold [residency]. */
    private suspend fun loaded(id: BrainId): LiteRtLmBrain {
        slot?.let { if (it.id == id) return it.brain }
        release()
        val config = models.engineConfig(id, maxNumTokens) ?: error("$id model is not installed")
        val holder = EngineHolder(config, models.engineOptions(id, benchmark = onTurn != null))
        val report = onTurn?.let { told -> { speed: TurnSpeed -> told(id, speed) } }
        return LiteRtLmBrain(id, capabilities, holder, onTurn = report).also { slot = Slot(id, holder, it) }
    }

    private suspend fun release() {
        slot?.let {
            it.brain.reset()
            it.holder.close()
        }
        slot = null
    }
}
