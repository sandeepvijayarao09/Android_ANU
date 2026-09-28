package io.github.sandeepvijayarao09.anu

import io.github.sandeepvijayarao09.anu.brain.litertlm.TurnSpeed
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import java.util.Locale

/** The debug log's line for one on-device turn (§15): token counts and speeds only, never content. */
internal fun speedLine(id: BrainId, variant: String, speed: TurnSpeed): String {
    val tier = if (id == BrainId.BASE) "Base" else "Pro"
    val first = if (speed.firstTokenMs >= 0) "first token after ${speed.firstTokenMs} ms" else "no token"
    return "$tier on $variant read ${speed.read} tokens at ${rate(speed.readPerSecond)} tok/s, $first, " +
        "wrote ${speed.written} at ${rate(speed.writtenPerSecond)} tok/s"
}

private fun rate(perSecond: Double) = String.format(Locale.ROOT, "%.1f", perSecond)
