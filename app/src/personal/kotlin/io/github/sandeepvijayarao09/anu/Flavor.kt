package io.github.sandeepvijayarao09.anu

import android.content.Context
import android.util.Log
import io.github.sandeepvijayarao09.anu.connector.apps.AccessibilityAppDriver
import io.github.sandeepvijayarao09.anu.connector.apps.LiveControl
import io.github.sandeepvijayarao09.anu.connector.apps.TileScreen
import io.github.sandeepvijayarao09.anu.connector.sms.ProviderSms
import io.github.sandeepvijayarao09.anu.connector.sms.SmsSearchTool
import io.github.sandeepvijayarao09.anu.core.agent.AppStepTool
import io.github.sandeepvijayarao09.anu.core.agent.AppTaskTool
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.model.Brain
import io.github.sandeepvijayarao09.anu.feature.home.ScreenLook
import java.time.ZoneId

/**
 * The owner's build. It includes the SMS connector (ADR-012, ADR-032) and app control (ADR-098), whose service also
 * reads the screen for the Quick Settings tile and shows live talk's pill over other apps (ADR-108).
 */
internal object Flavor {
    const val HAS_SMS = true
    const val HAS_APP_CONTROL = true

    val tileReader: TileReader? = object : TileReader {
        override val on: Boolean get() = TileScreen.ready

        override suspend fun read(ownPackage: String): ScreenLook = when (val look = TileScreen.read(ownPackage)) {
            is TileScreen.Look.Read -> {
                if (BuildConfig.DEBUG) Log.d("Anu", "Screen tile: the panel went in ${look.waitedMs} ms")
                ScreenHandoff.fromTile(look.packageName, look.texts, look.secrets, look.screenshot)
            }
            TileScreen.Look.Private -> ScreenLook.Unavailable(ScreenHandoff.PRIVATE)
            TileScreen.Look.Unavailable -> ScreenLook.Unavailable(TILE_UNREAD)
        }
    }

    val floatingControl: FloatingControl? = object : FloatingControl {
        override val available: Boolean get() = LiveControl.available

        override fun show(words: LiveWords, onOpen: () -> Unit, onStop: () -> Unit) {
            LiveControl.show(words.heading, words.line, words.description, onOpen, onStop)
        }

        override fun hide() = LiveControl.hide()
    }

    /** [appBrain] picks an app task's steps; [broker] runs them, the broker these tools join. */
    fun tools(context: Context, zone: () -> ZoneId, appBrain: () -> Brain?, broker: () -> ToolBroker): List<Tool<*, *>> {
        val apps = AccessibilityAppDriver(context)
        return listOf(SmsSearchTool(ProviderSms(context.contentResolver), zone), AppTaskTool(apps, appBrain, broker, log = { if (BuildConfig.DEBUG) Log.d("Anu", it) }), AppStepTool(apps))
    }
}
