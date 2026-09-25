package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain.Step
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.RouteRequest
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CascadeTest {

    private val base = ScriptedBrain(BrainId.BASE)
    private val pro = ScriptedBrain(BrainId.PRO)
    private val cloud = ScriptedBrain(BrainId.CLOUD_FLASH)
    private val brains = mapOf(Tier.BASE to base, Tier.PRO to pro, Tier.CLOUD to cloud)
    private val cascade = Cascade(Router(), BrainProvider { brains[it] })
    private val device = DeviceState(online = true, availableTiers = Tier.entries.toSet())

    private val simple = RouteRequest(text = "Reply with pong", promptTokens = 50)
    private val brainRequest = BrainRequest(transcript = listOf(ChatMessage.User("Reply with pong")))

    private suspend fun run(route: RouteRequest = simple, device: DeviceState = this.device) =
        cascade.run(route, device, brainRequest).toList()

    @Test
    fun `streams the routed tier and completes`() = runTest {
        base.enqueue(Step.Reply(listOf("po", "ng")))
        val events = run()
        assertEquals(Tier.BASE, (events.first() as CascadeEvent.Routed).decision.tier)
        assertEquals(listOf("po", "ng"), events.filterIsInstance<CascadeEvent.TextDelta>().map { it.text })
        assertEquals("pong", (events.last() as CascadeEvent.Completed).text)
        assertTrue(pro.requests.isEmpty())
    }

    @Test
    fun `an error escalates straight to the next tier`() = runTest {
        base.enqueue(Step.Fail(IllegalStateException("out of memory")))
        pro.enqueue(Step.Reply("pong"))
        val events = run()
        assertEquals(1, base.requests.size) // errors are not repaired
        assertTrue(events.contains(CascadeEvent.Discarded(Tier.BASE, "error: out of memory")))
        assertTrue(events.contains(CascadeEvent.Escalated(Tier.BASE, Tier.PRO, "error: out of memory")))
        assertEquals(Tier.PRO, (events.last() as CascadeEvent.Completed).tier)
    }

    @Test
    fun `an invalid answer is repaired once with greedy sampling, then escalated`() = runTest {
        base.enqueue(Step.Reply("  "), Step.Reply(""))
        pro.enqueue(Step.Reply("pong"))
        val events = run()
        assertEquals(2, base.requests.size)
        assertEquals(Sampling.Precise, base.requests[1].sampling)
        assertEquals(2, events.count { it is CascadeEvent.Discarded })
        assertEquals(Tier.PRO, (events.last() as CascadeEvent.Completed).tier)
    }

    @Test
    fun `a successful repair stays on the same tier`() = runTest {
        base.enqueue(Step.Reply(""), Step.Reply("pong"))
        val events = run()
        assertEquals(Tier.BASE, (events.last() as CascadeEvent.Completed).tier)
        assertTrue(pro.requests.isEmpty())
    }

    @Test
    fun `cloud with personal data stops for consent before anything is sent`() = runTest {
        val personal = simple.copy(dataClasses = setOf(DataClass.PERSONAL))
        val events = run(personal, DeviceState(online = true, availableTiers = setOf(Tier.CLOUD)))
        assertTrue(events.single() is CascadeEvent.ConsentNeeded)
        assertTrue(cloud.requests.isEmpty())
    }

    @Test
    fun `escalating into cloud also stops for consent`() = runTest {
        base.enqueue(Step.Fail(IllegalStateException("boom")))
        pro.enqueue(Step.Fail(IllegalStateException("boom")))
        val events = run(simple.copy(dataClasses = setOf(DataClass.PERSONAL)))
        assertTrue(events.last() is CascadeEvent.ConsentNeeded)
        assertTrue(cloud.requests.isEmpty())
    }

    @Test
    fun `running out of tiers fails with the reasons`() = runTest {
        base.enqueue(Step.Fail(IllegalStateException("boom")))
        val events = run(device = DeviceState(online = false, availableTiers = setOf(Tier.BASE)))
        val failed = events.last() as CascadeEvent.Failed
        assertTrue(failed.reasons.toString(), failed.reasons.last() == "BASE failed: error: boom")
    }

    @Test
    fun `nothing available fails without calling a brain`() = runTest {
        val events = run(device = DeviceState(online = false, availableTiers = emptySet()))
        assertTrue(events.single() is CascadeEvent.Failed)
    }

    @Test
    fun `cancelling the collector cancels the brain`() = runTest {
        base.enqueue(Step.Hang(listOf("once upon")))
        val firstDelta = cascade.run(simple, device, brainRequest).first { it is CascadeEvent.TextDelta }
        assertEquals("once upon", (firstDelta as CascadeEvent.TextDelta).text)
        assertEquals(1, base.cancellations)
    }
}
