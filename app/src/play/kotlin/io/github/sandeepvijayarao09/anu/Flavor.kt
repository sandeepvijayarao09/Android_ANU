package io.github.sandeepvijayarao09.anu

import android.content.Context
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.model.Brain
import java.time.ZoneId

/**
 * The Play build. SMS and app control are left out: Play restricts both, and a build check keeps their permissions and
 * services out of the manifest (ADR-012, ADR-098).
 */
internal object Flavor {
    const val HAS_SMS = false
    const val HAS_APP_CONTROL = false

    fun tools(context: Context, zone: () -> ZoneId, appBrain: () -> Brain?, broker: () -> ToolBroker): List<Tool<*, *>> = emptyList()
}
