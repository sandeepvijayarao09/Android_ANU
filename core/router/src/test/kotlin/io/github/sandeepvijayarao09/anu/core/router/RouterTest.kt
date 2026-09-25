package io.github.sandeepvijayarao09.anu.core.router

import io.github.sandeepvijayarao09.anu.core.model.DataClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {

    private val everything = DeviceState(online = true, availableTiers = Tier.entries.toSet())
    private val router = Router()

    private fun route(request: RouteRequest, device: DeviceState = everything, router: Router = this.router): RouteDecision =
        (router.route(request, device) as RouteResult.Routed).decision

    private fun request(text: String, skill: Skill = Skill.CHAT, tokens: Int = 200, data: Set<DataClass> = emptySet()) =
        RouteRequest(text = text, skill = skill, dataClasses = data, promptTokens = tokens)

    private val heavy = "Write a detailed proposal comparing our three vendors on cost and risk, " +
        "then draft an email to the team and schedule a review"

    @Test
    fun `simple chat goes to Base`() {
        assertEquals(Tier.BASE, route(request("Reply with just the word pong")).tier)
    }

    @Test
    fun `multi-step scheduling goes to Pro and explains why`() {
        val decision = route(request("Find a time next week for me, Maya and Raj, then email them an invite", Skill.SCHEDULING))
        assertEquals(Tier.PRO, decision.tier)
        assertTrue(decision.reasons.toString(), decision.reasons.any { it.startsWith("3 actions") })
    }

    @Test
    fun `short writing tasks stay on Base`() {
        assertEquals(Tier.BASE, route(request("Rewrite this to sound more formal", Skill.WRITING)).tier)
    }

    @Test
    fun `only very heavy work reaches Cloud, and without personal data it needs no consent`() {
        val decision = route(request(heavy, Skill.WRITING))
        assertEquals(Tier.CLOUD, decision.tier)
        assertEquals(Consent.NOT_NEEDED, decision.consent)
    }

    @Test
    fun `personal data bound for Cloud needs consent unless the skill allows it`() {
        val personal = request(heavy, Skill.WRITING, data = setOf(DataClass.PERSONAL))
        assertEquals(Consent.ASK, route(personal).consent)
        assertEquals(Consent.NOT_NEEDED, route(personal.copy(cloudConsented = true)).consent)
        val allowing = Router(cloudPolicy = { CloudPolicy.ALLOWED })
        assertEquals(Consent.NOT_NEEDED, route(personal, router = allowing).consent)
    }

    @Test
    fun `sensitive data stays on device unless consented for this request`() {
        val sensitive = request(heavy, Skill.WRITING, data = setOf(DataClass.SENSITIVE))
        val decision = route(sensitive, router = Router(cloudPolicy = { CloudPolicy.ALLOWED }))
        assertEquals(Tier.PRO, decision.tier)
        assertTrue(decision.reasons.contains("sensitive data: on-device only"))
        assertEquals(Tier.CLOUD, route(sensitive.copy(cloudConsented = true)).tier)
    }

    @Test
    fun `NEVER policy keeps personal data off the cloud`() {
        val decision = route(
            request(heavy, Skill.WRITING, data = setOf(DataClass.PERSONAL)),
            router = Router(cloudPolicy = { CloudPolicy.NEVER }),
        )
        assertEquals(Tier.PRO, decision.tier)
        assertTrue(Tier.CLOUD !in decision.allowed)
    }

    @Test
    fun `long prompts leave Base, and offline they fall back to Pro`() {
        assertEquals(Tier.PRO, route(request("Summarize this", tokens = 4_000)).tier)
        val offline = route(request("Summarize this", tokens = 7_000), everything.copy(online = false))
        assertEquals(Tier.PRO, offline.tier)
        assertTrue(offline.reasons.contains("CLOUD is unavailable, using PRO"))
    }

    @Test
    fun `constrained device avoids Pro`() {
        val decision = route(
            request("Find a time next week for me, Maya and Raj, then email them an invite", Skill.SCHEDULING),
            everything.copy(constrained = true, availableTiers = setOf(Tier.BASE, Tier.PRO)),
        )
        assertEquals(Tier.BASE, decision.tier)
    }

    @Test
    fun `pinned tier wins when available and is ignored otherwise`() {
        assertEquals(Tier.PRO, route(request("hi").copy(pinnedTier = Tier.PRO)).tier)
        val offline = route(request("hi").copy(pinnedTier = Tier.CLOUD), everything.copy(online = false))
        assertEquals(Tier.BASE, offline.tier)
        assertTrue(offline.reasons.contains("pinned tier CLOUD is unavailable"))
    }

    @Test
    fun `nothing available is reported, not guessed`() {
        val result = router.route(request("hi"), DeviceState(online = false, availableTiers = setOf(Tier.CLOUD)))
        assertTrue(result is RouteResult.Unavailable)
    }

    @Test
    fun `escalation climbs one allowed tier at a time and recomputes consent`() {
        val start = route(request("Reply with pong", data = setOf(DataClass.PERSONAL)))
        val pro = router.escalate(request("Reply with pong", data = setOf(DataClass.PERSONAL)), start, "empty answer")!!
        assertEquals(Tier.PRO, pro.tier)
        val cloud = router.escalate(request("Reply with pong", data = setOf(DataClass.PERSONAL)), pro, "empty answer")!!
        assertEquals(Tier.CLOUD, cloud.tier)
        assertEquals(Consent.ASK, cloud.consent)
        assertNull(router.escalate(request("Reply with pong"), cloud, "empty answer"))
    }

    @Test
    fun `skills are detected from the request, and follow-ups keep them`() {
        assertEquals(Skill.EMAIL, SkillDetector.detect("Reply to Maya's email and suggest Thursday"))
        assertEquals(Skill.EMAIL, SkillDetector.detect("Anything important in my inbox?"))
        assertEquals(Skill.SCHEDULING, SkillDetector.detect("Book a meeting with Raj on Monday"))
        assertEquals(Skill.CHAT, SkillDetector.detect("Tell me a joke"))
        assertEquals(Skill.EMAIL, SkillDetector.detect("Yes, send it", previous = Skill.EMAIL))
    }

    @Test
    fun `secrets are rejected before routing`() {
        assertThrows(IllegalArgumentException::class.java) { request("x", data = setOf(DataClass.SECRET)) }
    }
}
