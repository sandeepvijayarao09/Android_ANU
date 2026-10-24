package io.github.sandeepvijayarao09.anu.core.router

import io.github.sandeepvijayarao09.anu.core.model.BrainId
import org.junit.Assert.assertEquals
import org.junit.Test

class WarmStartTest {

    private val both = setOf(BrainId.BASE, BrainId.PRO)

    @Test
    fun `with Base on the TPU, warm start loads Base even after a Pro answer (ADR-103)`() {
        assertEquals(BrainId.BASE, warmModel(Tier.PRO, both, baseOnTpu = true))
        assertEquals("without the TPU, the last answer's model", BrainId.PRO, warmModel(Tier.PRO, both, baseOnTpu = false))
        assertEquals("only Pro installed", BrainId.PRO, warmModel(Tier.PRO, setOf(BrainId.PRO), baseOnTpu = true))
    }
}
