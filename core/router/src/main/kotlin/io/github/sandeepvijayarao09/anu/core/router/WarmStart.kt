package io.github.sandeepvijayarao09.anu.core.router

import io.github.sandeepvijayarao09.anu.core.model.BrainId

/**
 * Warm start's model (EXP-1, ADR-074): the one the last answer used on this phone, else Base, among those installed.
 * After a Gemini answer, Base, since the next request is most likely a light one. Null when none is installed.
 */
fun warmModel(lastTier: Tier?, installed: Set<BrainId>): BrainId? {
    val wanted = if (lastTier == Tier.PRO) BrainId.PRO else BrainId.BASE
    return wanted.takeIf { it in installed } ?: listOf(BrainId.BASE, BrainId.PRO).firstOrNull { it in installed }
}
