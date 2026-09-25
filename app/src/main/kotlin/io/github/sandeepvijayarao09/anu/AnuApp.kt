package io.github.sandeepvijayarao09.anu

import android.app.Application
import android.content.ComponentCallbacks2
import kotlinx.coroutines.launch

class AnuApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    /** Models are unloaded when the app goes to the background (P8). They load again on the next request. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) graph.scope.launch { graph.onDevice.unloadAll() }
    }
}
