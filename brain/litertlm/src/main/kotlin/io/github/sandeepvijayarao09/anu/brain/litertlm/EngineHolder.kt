package io.github.sandeepvijayarao09.anu.brain.litertlm

import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runtime switches that LiteRT-LM reads from process-wide flags when an engine loads (§5).
 * - [speculativeDecoding]: Gemma 4's multi-token prediction, which its files carry. Null leaves the runtime's default.
 * - [benchmark]: the runtime's own counters, read after each turn for the debug speed log. Numbers only.
 */
data class EngineOptions(val speculativeDecoding: Boolean? = null, val benchmark: Boolean = false)

/**
 * Loads one model lazily and keeps it loaded until [close]. The residency policy (§5), which
 * decides when to load and unload, is built on top of this in Phase 1.
 */
class EngineHolder(private val config: EngineConfig, val options: EngineOptions = EngineOptions()) : AutoCloseable {
    private val mutex = Mutex()
    private var engine: Engine? = null

    /** Loading takes seconds, so it runs on [Dispatchers.IO]. */
    suspend fun get(): Engine = mutex.withLock {
        engine ?: withContext(Dispatchers.IO) { load() }.also { engine = it }
    }

    val isLoaded: Boolean get() = engine != null

    override fun close() {
        engine?.close()
        engine = null
    }

    /**
     * The flags are global, and the runtime reads them only as an engine loads. An engine whose options differ from them
     * sets them while holding [FLAGS] and leaves them set. Every engine the app loads has the same options, so after the
     * first, engines load without holding [FLAGS], and a long GPU load doesn't stop another engine's turn from creating
     * its conversation (ADR-106).
     */
    @OptIn(ExperimentalApi::class)
    private fun load(): Engine {
        val unchanged = synchronized(FLAGS) {
            ExperimentalFlags.enableSpeculativeDecoding == options.speculativeDecoding && ExperimentalFlags.enableBenchmark == options.benchmark
        }
        if (unchanged) return Engine(config).also { it.initialize() }
        return synchronized(FLAGS) {
            ExperimentalFlags.enableSpeculativeDecoding = options.speculativeDecoding
            ExperimentalFlags.enableBenchmark = options.benchmark
            Engine(config).also { it.initialize() }
        }
    }

    internal companion object {
        /** Guards LiteRT-LM's process-wide flags, which engines and conversations read when they are created. */
        val FLAGS = Any()
    }
}
