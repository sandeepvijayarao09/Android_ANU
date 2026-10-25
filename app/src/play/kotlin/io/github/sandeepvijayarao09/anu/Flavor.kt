package io.github.sandeepvijayarao09.anu

import android.content.Context
import io.github.sandeepvijayarao09.anu.connector.notes.NoteMirror
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.model.Brain
import java.time.ZoneId

/**
 * The Play build. SMS and app control are left out: Play restricts both, and a build check keeps their permissions and
 * services out of the manifest (ADR-012, ADR-098). So the screen tile works only where Anu is the assistant, and live
 * talk behind other apps shows its notification alone, with no pill (ADR-108).
 */
internal object Flavor {
    const val HAS_SMS = false
    const val HAS_APP_CONTROL = false

    val tileReader: TileReader? = null

    val floatingControl: FloatingControl? = null

    fun noteMirror(context: Context): NoteMirror? = null

    fun tools(context: Context, zone: () -> ZoneId, appBrain: () -> Brain?, broker: () -> ToolBroker): List<Tool<*, *>> = emptyList()
}
