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

    /** The flags are global, so engines load one at a time, each with its own options, and the old values come back. */
    @OptIn(ExperimentalApi::class)
    private fun load(): Engine = synchronized(FLAGS) {
        val speculative = ExperimentalFlags.enableSpeculativeDecoding
        val benchmark = ExperimentalFlags.enableBenchmark
        ExperimentalFlags.enableSpeculativeDecoding = options.speculativeDecoding
        ExperimentalFlags.enableBenchmark = options.benchmark
        try {
            Engine(config).also { it.initialize() }
        } finally {
            ExperimentalFlags.enableSpeculativeDecoding = speculative
            ExperimentalFlags.enableBenchmark = benchmark
        }
    }

    private companion object {
        val FLAGS = Any()
    }
}
