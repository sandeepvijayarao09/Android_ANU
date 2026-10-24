package io.github.sandeepvijayarao09.anu

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import io.github.sandeepvijayarao09.anu.feature.home.ScreenLook
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Ask Anu about this screen" in Quick Settings (ADR-108): one tap from any app, even when Anu isn't the assistant.
 * Nothing is read until the tap. Then the panel closes, the screen is read on the phone with its codes and password
 * fields masked, and the assistant card opens with it attached for the next question, held in memory only (ADR-083).
 */
class ScreenTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Ask Anu"
            subtitle = "This screen"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // Opening an activity is how a tile closes the panel; the step activity draws nothing and goes at once.
        val step = Intent(this, ScreenTileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, step, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(step)
        }
    }
}

/** How the tile gets the screen to the card (ADR-108). */
internal sealed interface TileRoute {
    /** Through app control's service, in the personal build (`TileScreen`). */
    data object Read : TileRoute

    /** Through the assistant's own hand-over, as the power button does (ADR-083), where Anu is the assistant. */
    data object Assistant : TileRoute

    /** Neither is there: the card opens and says what to turn on. */
    data class Explain(val note: String) : TileRoute
}

/** App control first, where the build has it and the user turned it on; then the assistant; else what to turn on. */
internal fun tileRoute(hasReader: Boolean, readerOn: Boolean, assistant: Boolean): TileRoute = when {
    hasReader && readerOn -> TileRoute.Read
    assistant -> TileRoute.Assistant
    hasReader -> TileRoute.Explain(TILE_TURN_ON)
    else -> TileRoute.Explain(TILE_ASSISTANT_FIRST)
}

internal const val TILE_TURN_ON =
    "To read the screen from this tile, turn on Anu app control in Android's Accessibility settings, or make Anu your assistant in Settings → Voice."
internal const val TILE_ASSISTANT_FIRST = "To read the screen from this tile, make Anu your phone's assistant in Settings → Voice."
internal const val TILE_UNREAD = "Anu couldn't read this screen. Try the tile again."

/**
 * The tile's step: started by the tile, which closes Quick Settings, it finishes at once and hands the screen to the
 * card by the [tileRoute]. It's never in Recents and draws nothing.
 */
class ScreenTileActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AnuApp
        val reader = Flavor.tileReader
        val route = tileRoute(hasReader = reader != null, readerOn = reader?.on == true, assistant = AnuVoiceInteractionService.active)
        if (BuildConfig.DEBUG) Log.d("Anu", "Screen tile: ${route.javaClass.simpleName}")
        when (route) {
            TileRoute.Read -> app.graph.scope.launch {
                val look = reader!!.read(packageName)
                // Whether it was read and how much was masked is logged by ScreenHandoff, never what it says.
                if (BuildConfig.DEBUG) Log.d("Anu", "Screen tile: ${if (look is ScreenLook.Ready) "read ${look.screen.app}" else "not read"}")
                ScreenHandoff.give(look)
                openCard(app)
            }
            TileRoute.Assistant -> app.graph.scope.launch {
                // Once the panel and this step are gone, so Android hands over the app's screen, not theirs.
                delay(ASSIST_AFTER_MS)
                AnuVoiceInteractionService.show(null, useScreen = true)
            }
            is TileRoute.Explain -> {
                ScreenHandoff.give(ScreenLook.Unavailable(route.note))
                openCard(this)
            }
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val ASSIST_AFTER_MS = 450L

        /**
         * The card, ready for a question about the screen. From the app after the read, Android allows it because app
         * control's service is bound (an accessibility service may open its app's screens).
         */
        fun openCard(context: Context) {
            context.startActivity(
                Intent(context, AssistantActivity::class.java)
                    .putExtra(AnuVoiceInteractionService.EXTRA_USE_SCREEN, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** Reads the screen for the tile; only the personal build has one, through app control (ADR-108). */
internal interface TileReader {
    /** Whether the user has app control on. */
    val on: Boolean

    /** The screen below, masked, once the panel and [ownPackage]'s screens are gone; or why it can't be used. */
    suspend fun read(ownPackage: String): ScreenLook
}
