package io.github.sandeepvijayarao09.anu

import android.content.Context
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import java.time.ZoneId

/** The Play build. SMS is left out, and a build check keeps its permissions out of the manifest (ADR-012). */
internal object Flavor {
    const val HAS_SMS = false

    fun tools(context: Context, zone: () -> ZoneId): List<Tool<*, *>> = emptyList()
}
