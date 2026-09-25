package io.github.sandeepvijayarao09.anu.brain.litertlm

import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads one model lazily and keeps it loaded until [close]. The residency policy (§5), which
 * decides when to load and unload, is built on top of this in Phase 1.
 */
class EngineHolder(private val config: EngineConfig) : AutoCloseable {
    private val mutex = Mutex()
    private var engine: Engine? = null

    /** Loading takes seconds, so it runs on [Dispatchers.IO]. */
    suspend fun get(): Engine = mutex.withLock {
        engine ?: withContext(Dispatchers.IO) { Engine(config).also { it.initialize() } }.also { engine = it }
    }

    val isLoaded: Boolean get() = engine != null

    override fun close() {
        engine?.close()
        engine = null
    }
}
