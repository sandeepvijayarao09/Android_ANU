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
        assertEquals(Tier.PRO, route(request("Summarize this", tokens = 2_300)).tier)
        assertEquals(Tier.CLOUD, route(request("Summarize this", tokens = 4_000)).tier)
        val offline = route(request("Summarize this", tokens = 7_000), everything.copy(online = false))
        assertEquals(Tier.PRO, offline.tier)
        assertTrue(offline.reasons.contains("CLOUD is unavailable, using PRO"))
    }

    @Test
    fun `budgets come from the on-device context`() {
        assertEquals(TierBudgets(base = 2_048, pro = 2_560), TierBudgets.forContext(4_096))
        assertEquals(TierBudgets(base = 5_324, pro = 6_656), TierBudgets.forContext(8_192))
    }

    private val proRequest = request("Find a time next week for me, Maya and Raj, then email them an invite", Skill.SCHEDULING)

    @Test
    fun `a guarded phone sends a Pro request to Base when it fits, and says why`() {
        for (guard in Guard.entries) {
            val decision = route(proRequest, everything.copy(guard = guard))
            assertEquals(Tier.BASE, decision.tier)
            assertTrue(decision.guarded)
            assertEquals(null, decision.maxOutputTokens)
            assertTrue(decision.reasons.toString(), decision.reasons.contains("${guard.reason}: Base instead of Pro"))
        }
        assertEquals("not guarded, it stays on Pro", Tier.PRO, route(proRequest).tier)
    }

    @Test
    fun `a guarded Pro request that doesn't fit Base, or has no Base, stays on Pro with a shorter answer`() {
        val long = route(proRequest.copy(promptTokens = 2_300), everything.copy(guard = Guard.HEAT))
        assertEquals(Tier.PRO, long.tier)
        assertEquals(GuardLimits.ANSWER_TOKENS, long.maxOutputTokens)
        assertTrue(long.guarded)
        val noBase = route(proRequest, everything.copy(guard = Guard.BATTERY, availableTiers = setOf(Tier.PRO, Tier.CLOUD)))
        assertEquals(Tier.PRO, noBase.tier)
        assertEquals(GuardLimits.ANSWER_TOKENS, noBase.maxOutputTokens)
    }

    @Test
    fun `with Pro on the GPU beside Base on the TPU, a Pro request stays on Pro (ADR-106)`() {
        val decision = route(proRequest, everything.copy(baseOnTpu = true, proOnGpu = true))
        assertEquals(Tier.PRO, decision.tier)
        assertEquals("a Base request stays on Base", Tier.BASE, route(request("Reply with just the word pong"), everything.copy(baseOnTpu = true, proOnGpu = true)).tier)
    }

    @Test
    fun `with Base on the TPU, a Pro request goes to Base when it fits, and a failed answer still moves up to Pro`() {
        val tpu = everything.copy(baseOnTpu = true)
        val decision = route(proRequest, tpu)
        assertEquals(Tier.BASE, decision.tier)
        assertEquals(false, decision.guarded)
        assertTrue(decision.reasons.toString(), decision.reasons.contains("Base runs on the TPU: Base instead of Pro"))
        assertEquals(Tier.PRO, router.escalate(proRequest, decision, "invalid tool call")?.tier)
        assertEquals("too long for Base", Tier.PRO, route(proRequest.copy(promptTokens = 2_300), tpu).tier)
        assertEquals("no Base installed", Tier.PRO, route(proRequest, tpu.copy(availableTiers = setOf(Tier.PRO))).tier)
        assertEquals("pinned by the user", Tier.PRO, route(proRequest.copy(pinnedTier = Tier.PRO), tpu).tier)
    }

    @Test
    fun `a Base request isn't changed by the guard`() {
        val decision = route(request("Reply with just the word pong"), everything.copy(guard = Guard.HEAT))
        assertEquals(Tier.BASE, decision.tier)
        assertEquals(false, decision.guarded)
    }

    @Test
    fun `retrying on this phone while guarded runs Pro with the shorter answer, and asking Gemini goes through consent`() {
        // ENG-5's regression contract, as design review D21 amended it.
        val personal = request("Explain tides", data = setOf(DataClass.PERSONAL))
        val onPhone = route(personal.copy(pinnedTier = Tier.PRO), everything.copy(guard = Guard.HEAT))
        assertEquals(Tier.PRO, onPhone.tier)
        assertEquals(GuardLimits.ANSWER_TOKENS, onPhone.maxOutputTokens)
        val gemini = route(personal.copy(pinnedTier = Tier.CLOUD), everything.copy(guard = Guard.HEAT))
        assertEquals(Tier.CLOUD, gemini.tier)
        assertEquals(Consent.ASK, gemini.consent)
        val noCloud = route(personal.copy(pinnedTier = Tier.PRO), everything.copy(guard = Guard.BATTERY, online = false))
        assertEquals(Tier.PRO, noCloud.tier)
        assertEquals(GuardLimits.ANSWER_TOKENS, noCloud.maxOutputTokens)
        val unguarded = route(personal.copy(pinnedTier = Tier.PRO))
        assertEquals(null, unguarded.maxOutputTokens)
        assertEquals(false, unguarded.guarded)
    }

    @Test
    fun `a guarded phone never moves a failed turn to the cloud by itself`() {
        val start = route(request("Reply with pong"), everything.copy(guard = Guard.BATTERY))
        val pro = router.escalate(request("Reply with pong"), start, "empty answer")!!
        assertEquals(Tier.PRO, pro.tier)
        assertEquals("Pro keeps the shorter answer", GuardLimits.ANSWER_TOKENS, pro.maxOutputTokens)
        assertNull(router.escalate(request("Reply with pong"), pro, "empty answer"))
    }

    @Test
    fun `the guard is heat first, then a low battery, and severe heat counts without a forecast`() {
        assertEquals(Guard.HEAT, guardFor(0.85f, 0.8f, severe = false, batteryPercent = 10, charging = false))
        assertEquals(Guard.HEAT, guardFor(null, 0.8f, severe = true, batteryPercent = 80, charging = false))
        assertEquals(null, guardFor(null, 0.8f, severe = false, batteryPercent = 80, charging = false))
        assertEquals(null, guardFor(0.5f, 0.8f, severe = false, batteryPercent = 80, charging = false))
        assertEquals(Guard.BATTERY, guardFor(0.5f, 0.8f, severe = false, batteryPercent = 15, charging = false))
        assertEquals(null, guardFor(0.5f, 0.8f, severe = false, batteryPercent = 15, charging = true))
    }

    @Test
    fun `the heat forecast is read at most every 10 s, and a listener's readings replace asking`() {
        var now = 0L
        val readings = ArrayDeque(listOf(0.5f, Float.NaN, 0.9f))
        var reads = 0
        val forecast = HeatForecast(read = { reads++; readings.removeFirst() }, clock = { now })
        assertEquals(0.5f, forecast.current())
        now = 9_999
        assertEquals("reused within 10 s", 0.5f, forecast.current())
        assertEquals(1, reads)
        now = 10_000
        assertEquals("NaN keeps the last reading", 0.5f, forecast.current())
        now = 20_000
        assertEquals(0.9f, forecast.current())
        assertEquals(3, reads)
        forecast.push(0.3f)
        now = 60_000
        assertEquals(0.3f, forecast.current())
        assertEquals("pushed readings are never polled", 3, reads)
        assertNull(HeatForecast(read = { Float.NaN }, clock = { 0L }).current())
    }

    @Test
    fun `a pinned tier wins when available, an unavailable Pro is ignored, and Gemini says it cannot`() {
        assertEquals(Tier.PRO, route(request("hi").copy(pinnedTier = Tier.PRO)).tier)
        val noPro = route(request("hi").copy(pinnedTier = Tier.PRO), everything.copy(availableTiers = setOf(Tier.BASE)))
        assertEquals(Tier.BASE, noPro.tier)
        assertTrue(noPro.reasons.contains("pinned tier PRO is unavailable"))
        // Asking Gemini by name never quietly runs on the phone instead.
        val offline = router.route(request("hi").copy(pinnedTier = Tier.CLOUD), everything.copy(online = false))
        assertTrue(offline is RouteResult.Unavailable)
        assertTrue((offline as RouteResult.Unavailable).reasons.contains("Gemini can't be asked here"))
        val photo = router.route(request("hi").copy(pinnedTier = Tier.CLOUD, hasPhotos = true), everything)
        assertTrue((photo as RouteResult.Unavailable).reasons.contains("a photo is in view: on-device only"))
    }

    @Test
    fun `a photo in view keeps the turn on the phone, whatever the score or consent`() {
        val heavy = route(request(heavy).copy(hasPhotos = true, cloudConsented = true))
        assertTrue(heavy.tier != Tier.CLOUD)
        assertTrue(Tier.CLOUD !in heavy.allowed)
    }

    @Test
    fun `a voice note in view keeps the turn on the phone, whatever the score or consent, and says why`() {
        val heavy = route(request(heavy).copy(hasVoiceNote = true, cloudConsented = true))
        assertTrue(heavy.tier != Tier.CLOUD)
        assertTrue(Tier.CLOUD !in heavy.allowed)
        assertTrue(heavy.reasons.contains("a voice note is in view: on-device only"))
    }

    @Test
    fun `a PDF in view keeps the turn on the phone, whatever the score or consent, and says why`() {
        val heavy = route(request(heavy).copy(hasDocument = true, cloudConsented = true))
        assertTrue(Tier.CLOUD !in heavy.allowed)
        assertTrue(heavy.reasons.contains("a PDF is in view: on-device only"))
    }

    @Test
    fun `a voice note of the user's own goes to Pro, where several tasks and dates are worked out`() {
        assertEquals(Tier.PRO, route(request("Call Maya on Thursday.").copy(skill = Skill.VOICE_NOTE, hasVoiceNote = true)).tier)
    }

    @Test
    fun `a turn with a fixed skill counts in the replay, so a short follow-up keeps a voice note's tools`() {
        val turns = listOf(SkillDetector.Turn("Lunch with Maya on Friday.", Skill.VOICE_NOTE), SkillDetector.Turn("yes please"))
        assertEquals(Skill.VOICE_NOTE, SkillDetector.replay(turns))
        assertEquals(Skill.CHAT, SkillDetector.replay(listOf(SkillDetector.Turn("Check my calendar", Skill.CHAT))))
        assertEquals(null, SkillDetector.keywords("Lunch with Maya on Friday."))
    }

    @Test
    fun `a request that uses the phone's controls but is more than one command gets them, on Base`() {
        assertEquals(Skill.PHONE, SkillDetector.detect("Wake me at 6:30 and turn the flashlight off"))
        assertEquals(Skill.PHONE, SkillDetector.detect("Start a timer for the pasta, about eleven minutes"))
        assertEquals("email still wins when it's asked for", Skill.EMAIL, SkillDetector.detect("Set an alarm and email Maya the agenda"))
        assertEquals(Tier.BASE, route(request("Turn the torch on and set a timer").copy(skill = Skill.PHONE)).tier)
    }

    @Test
    fun `notes and contacts get the device skill, on Base, and memory still comes first (ADR-093)`() {
        assertEquals(Skill.PHONE, SkillDetector.detect("Add eggs to my groceries note"))
        assertEquals(Skill.PHONE, SkillDetector.detect("What's in my notes about the trip?"))
        assertEquals("not scheduling's \"what's on my\"", Skill.PHONE, SkillDetector.detect("What's on my groceries note?"))
        assertEquals(Skill.SCHEDULING, SkillDetector.detect("Add a note to my 3pm meeting on the calendar"))
        assertEquals(Skill.SCHEDULING, SkillDetector.detect("What's on my calendar today?"))
        assertEquals(Skill.PHONE, SkillDetector.detect("Save Raj's number, it's 555 0100"))
        assertEquals(Skill.PHONE, SkillDetector.detect("Add Priya Shah to my contacts, priya@example.com"))
        assertEquals(Skill.MEMORY, SkillDetector.detect("Note that I'm vegetarian"))
        assertEquals(Skill.CHAT, SkillDetector.detect("I noted the time"))
        assertEquals(Tier.BASE, route(request("Set an alarm for 6 and add eggs to my groceries note").copy(skill = Skill.PHONE)).tier)
    }

    @Test
    fun `brightness and volume are the phone's, and a text draft is messages' (ADR-099)`() {
        assertEquals(Skill.PHONE, SkillDetector.detect("Make the screen a bit brighter and turn the volume down"))
        assertEquals(Skill.PHONE, SkillDetector.detect("Set the ringer to half and wake me at 7"))
        assertEquals(Skill.MESSAGES, SkillDetector.detect("Write a draft for a message to Maya saying I'm running late"))
        assertEquals(Skill.MESSAGES, SkillDetector.detect("Draft a text to Raj about dinner"))
        assertEquals("an email stays email", Skill.EMAIL, SkillDetector.detect("Draft an email to Maya about the message she sent"))
    }

    @Test
    fun `a brief, and a request across areas, go to ADK's agents (ADR-104)`() {
        assertEquals(Skill.BRIEF, SkillDetector.detect("Brief me"))
        assertEquals(Skill.BRIEF, SkillDetector.detect("What does my day look like?"))
        assertEquals(Skill.AGENTS, SkillDetector.detect("Check my calendar for Friday and text Maya I'm free"))
        assertEquals(Skill.AGENTS, SkillDetector.detect("Set an alarm for 7 and find a free slot tomorrow for the dentist"))
        assertEquals("one area stays its own skill", Skill.PHONE, SkillDetector.detect("Wake me at 6:30 and turn the flashlight off"))
        assertEquals("email keeps its calendar", Skill.EMAIL, SkillDetector.detect("Set an alarm and email Maya the agenda"))
        assertEquals("a note isn't the calendar", Skill.PHONE, SkillDetector.detect("What's on my groceries note?"))
        assertEquals("an agent on the network (ADR-105)", Skill.AGENTS, SkillDetector.detect("Ask the Home Lab agent what the temperature is"))
        assertEquals(Skill.AGENTS, SkillDetector.detect("Tell my robot agent to wave"))
        // A follow-up never stays with the agents: they run once and don't see the chat.
        assertEquals(Skill.CHAT, SkillDetector.detect(listOf("Ask the Home Lab agent to turn on the desk lamp", "What is a good name for a cat?", "Give me one more")))
        assertEquals(Skill.CHAT, SkillDetector.detect("Thanks!", previous = Skill.BRIEF))
        assertEquals(Tier.PRO, route(request("Check my calendar for Friday and text Maya I'm free").copy(skill = Skill.AGENTS)).tier)
    }

    @Test
    fun `a request that names an app to act in gets app control, on Base (ADR-098)`() {
        assertEquals(Skill.APPS, SkillDetector.detect("In the Test Shop app, add the trail runner to my cart"))
        assertEquals(Skill.APPS, SkillDetector.detect("Open the Clock app and set an alarm for 7"))
        assertEquals("before email", Skill.APPS, SkillDetector.detect("In the Gmail app, archive the newsletter"))
        assertEquals("before memory only", Skill.MEMORY, SkillDetector.detect("Remember that I use the Shop app"))
        assertEquals("no app named, so the user's stores", Skill.SHOPPING, SkillDetector.detect("Add it to my cart"))
        assertEquals(Skill.CHAT, SkillDetector.detect("I'm working on an app"))
        assertEquals(Skill.CHAT, SkillDetector.detect("What can I do in the app?"))
        assertEquals(Tier.BASE, route(request("In the Test Shop app, add milk to my cart").copy(skill = Skill.APPS)).tier)
    }

    @Test
    fun `explanations, advice and time arithmetic go to Pro, and a detailed report to the cloud (ADR-084)`() {
        assertEquals(Tier.PRO, route(request("Explain how a heat pump works in simple terms.")).tier)
        assertEquals(Tier.PRO, route(request("What are three ways to lower my electricity bill?")).tier)
        assertEquals(Tier.PRO, route(request("The bus leaves at 8:10 and takes 45 minutes. When will I get there?")).tier)
        assertEquals(Tier.BASE, route(request("What's the capital of Portugal?")).tier)
        val report = route(request("Write a detailed report comparing four phones, with sources."))
        assertEquals(Tier.CLOUD, report.tier)
        assertTrue(report.reasons.any { it.startsWith("long-form output (3)") })
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
        assertEquals(Skill.MESSAGES, SkillDetector.detect("What did Maya text me?"))
        assertEquals(Skill.MESSAGES, SkillDetector.detect("Summarize the family group chat"))
        assertEquals(Skill.MESSAGES, SkillDetector.detect("Any new messages on WhatsApp?"))
        assertEquals(Skill.MESSAGES, SkillDetector.detect("Draft a text to Mom saying I'm late"))
        assertEquals(Skill.MESSAGES, SkillDetector.detect("What was Raj's last text about?"))
        // The emulator showed "Text the 555 number…" getting no tools, so Anu only asked.
        assertEquals(Skill.MESSAGES, SkillDetector.detect("Text the 555 number that I am running late"))
        assertEquals(Skill.MESSAGES, SkillDetector.detect("please dm Raj the address"))
        assertEquals(Skill.MESSAGES, SkillDetector.detect("Reply to +1 555-123-4567 saying yes"))
        assertEquals(Skill.EMAIL, SkillDetector.detect("Anything important in my inbox?"))
        assertEquals(Skill.SCHEDULING, SkillDetector.detect("Book a meeting with Raj on Monday"))
        assertEquals(Skill.CHAT, SkillDetector.detect("Tell me a joke"))
        assertEquals(Skill.EMAIL, SkillDetector.detect("Yes, send it", previous = Skill.EMAIL))
        assertEquals(Skill.SHOPPING, SkillDetector.detect("Buy me running shoes under 100 dollars"))
        assertEquals(Skill.SHOPPING, SkillDetector.detect("How much are the blue runners at the shoe store?"))
        assertEquals(Skill.SHOPPING, SkillDetector.detect("Order another pack of coffee filters"))
        assertEquals(Skill.CHAT, SkillDetector.detect("In order to sleep better, what should I change in my evening routine?"))
        assertEquals(Skill.CHAT, SkillDetector.detect("Check out this idea: a picnic by the river on the weekend. Thoughts?"))
    }

    @Test
    fun `remembering, recalling and forgetting offer the memory tools`() {
        assertEquals(Skill.MEMORY, SkillDetector.detect("Remember that my manager is Maya Chen"))
        assertEquals(Skill.MEMORY, SkillDetector.detect("What do you know about me?"))
        assertEquals(Skill.MEMORY, SkillDetector.detect("Forget that I'm vegetarian"))
        assertEquals(Skill.MEMORY, SkillDetector.detect("Remember Maya's email is maya@example.com"))
        assertEquals(Skill.CHAT, SkillDetector.detect("Forget it, never mind. Let's talk about something else for a bit instead"))
        assertEquals(Skill.CHAT, SkillDetector.detect("I can't remember the name of that café near the station, any ideas how to find it?"))
    }

    @Test
    fun `writing about text and chatting in general don't offer message tools`() {
        assertEquals(Skill.CHAT, SkillDetector.detect("Shorten this text so it fits in a tweet, and keep the tone friendly please"))
        assertEquals(Skill.CHAT, SkillDetector.detect("Can we chat about dinner ideas? Something quick and vegetarian would be great"))
        assertEquals(Skill.CHAT, SkillDetector.detect("I'm really looking forward to the long weekend, any ideas for a day trip nearby?"))
        assertEquals(Skill.CHAT, SkillDetector.detect("Text of the speech is below. Can you make it shorter and punchier for a wedding toast?"))
    }

    @Test
    fun `a reply follows the conversation it belongs to`() {
        assertEquals(Skill.MESSAGES, SkillDetector.detect("Reply to her that 7 works", previous = Skill.MESSAGES))
        assertEquals(Skill.EMAIL, SkillDetector.detect("Reply to her that 7 works", previous = Skill.EMAIL))
        assertEquals(Skill.EMAIL, SkillDetector.detect("Reply to her that 7 works"))
        assertEquals(Skill.EMAIL, SkillDetector.detect("Reply to Raj that 2026-09-26 works for the offsite"))
        // Before scheduling, so a reply that proposes a meeting keeps its reply tools.
        assertEquals(Skill.MESSAGES, SkillDetector.detect("Reply and suggest a meeting on Monday", previous = Skill.MESSAGES))
    }

    @Test
    fun `replaying the conversation settles replies and ends a run of follow-ups`() {
        val chat = listOf("Any new texts from Maya?", "Reply that 1 works", "Actually make it 1:30", "Thanks", "Great", "One more thing")
        assertEquals(
            listOf(Skill.MESSAGES, Skill.MESSAGES, Skill.MESSAGES, Skill.MESSAGES, Skill.MESSAGES, Skill.CHAT),
            chat.indices.map { SkillDetector.detect(chat.take(it + 1)) },
        )
        assertEquals(Skill.EMAIL, SkillDetector.detect(listOf("Anything from Raj in my inbox?", "Reply that 1 works")))
    }

    @Test
    fun `secrets are rejected before routing`() {
        assertThrows(IllegalArgumentException::class.java) { request("x", data = setOf(DataClass.SECRET)) }
    }

    @Test
    fun `warm start loads the model the last answer used, else Base, among those installed`() {
        val both = setOf(io.github.sandeepvijayarao09.anu.core.model.BrainId.BASE, io.github.sandeepvijayarao09.anu.core.model.BrainId.PRO)
        assertEquals(io.github.sandeepvijayarao09.anu.core.model.BrainId.PRO, warmModel(Tier.PRO, both))
        assertEquals(io.github.sandeepvijayarao09.anu.core.model.BrainId.BASE, warmModel(Tier.BASE, both))
        assertEquals("no history", io.github.sandeepvijayarao09.anu.core.model.BrainId.BASE, warmModel(null, both))
        assertEquals("after Gemini", io.github.sandeepvijayarao09.anu.core.model.BrainId.BASE, warmModel(Tier.CLOUD, both))
        assertEquals("only Pro installed", io.github.sandeepvijayarao09.anu.core.model.BrainId.PRO, warmModel(null, setOf(io.github.sandeepvijayarao09.anu.core.model.BrainId.PRO)))
        assertNull(warmModel(Tier.PRO, emptySet()))
    }
}
