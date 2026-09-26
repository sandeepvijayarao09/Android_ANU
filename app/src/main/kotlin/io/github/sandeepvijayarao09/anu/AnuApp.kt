package io.github.sandeepvijayarao09.anu

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AnuApp : Application() {
    lateinit var graph: AppGraph
        private set

    private val visible = MutableStateFlow(false)

    /** Whether any Anu screen is showing. Background work gives way when it is (P8). */
    val foreground: StateFlow<Boolean> = visible.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Off the main thread: this also opens the database, and it gives the memory tools their facts.
        graph.scope.launch { graph.memory.start() }
        MemoryLearning.scheduleCatchUp(this)
        registerActivityLifecycleCallbacks(Visibility())
    }

    /** Models are unloaded when the app goes to the background (P8). They load again on the next request. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) graph.scope.launch { graph.onDevice.unloadAll() }
    }

    /** Counts started screens. Leaving the app, but not rotating it, starts memory learning (ADR-037). */
    private inner class Visibility : ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            started++
            visible.value = true
        }

        override fun onActivityStopped(activity: Activity) {
            started--
            if (started == 0 && !activity.isChangingConfigurations) {
                visible.value = false
                MemoryLearning.soon(this@AnuApp)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
