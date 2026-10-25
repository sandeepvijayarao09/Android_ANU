package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.FACTS_HEADER
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain.Step
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.factsBlock
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.RouteRequest
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Skill
import io.github.sandeepvijayarao09.anu.core.router.Tier
import java.time.LocalDate
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionClaimsTest {

    @Test
    fun `completed-action phrasing is a claim, proposals and finds are not`() {
        listOf(
            "I've scheduled the Design Review for tomorrow at 12:00 PM PDT and sent an invitation to Maya Chen.",
            "I have booked the table.",
            "Done! I sent Raj the notes.",
            "The invite has been sent to Maya.",
            "It's now on your calendar.",
            "I added it to your calendar for Monday.",
        ).forEach { assertTrue(it, ActionClaims.claimsAction(it)) }
        listOf(
            "I've found a 30-minute slot tomorrow at 14:00. Shall I book it?",
            "I can schedule this for Monday at 10:00 if you like.",
            "Here are three free times: 10:00, 13:30 and 16:00.",
            "Maya's email is maya.chen@example.com.",
        ).forEach { assertFalse(it, ActionClaims.claimsAction(it)) }
    }

    @Test
    fun `saying something was noted or will be remembered is a memory claim, other uses of the words are not`() {
        listOf(
            // E4B's answer on the emulator after the user declined memory_remember.
            "I've noted that you typically buy oat milk every week. Is there anything else you'd like me to do with your notes or plans?",
            "I’ll remember that you buy oat milk every week.",
            "I've made a note of it.",
            "Noted!",
            "**Noted.** Anything else?",
            "Okay, noted.",
            "Duly noted.",
            "Thanks for telling me. Noted that you buy oat milk weekly.",
            "Got it, I'll keep that in mind.",
            "I'll keep in mind that you're vegan.",
            "I will remember your preference for mornings.",
            "I've saved that to your memory.",
            "I've added a new fact to your Memory tab.",
            "That's been noted.",
            "Your preference has been noted.",
            "I've booked lunch with Maya on Friday and noted that she's vegetarian.",
            "I'll add it to your calendar and keep in mind that you prefer mornings.",
            // E4B's repairs on the emulator, with five and six words before "in mind" (RepairNoteProbeTest).
            "Got it. I'll keep your weekend night shift schedule in mind for planning.",
            "I'll keep Alex's usual 6 PM finish time in mind for evening plans.",
        ).forEach { assertTrue(it, ActionClaims.claimsMemory(it)) }
        listOf(
            "As you noted, the store closes at 5.",
            "As I've noted before, Friday is busy.",
            "Maya noted that the meeting moved to Friday.",
            "The email just says \"Noted, thanks.\"",
            "The noted chef opened a new place.",
            "Shall I remember that you buy oat milk every week?",
            "I can remember that for you if you like.",
            "Okay, I won't remember that.",
            "I remember that you're vegetarian, so here's a mushroom risotto.",
            "I don't have that in memory yet.",
            "Keep in mind that the store closes at 5.",
            "Please note that the meeting moved.",
            "I've added the event and noted the address in its description.",
            "Got it!",
        ).forEach { assertFalse(it, ActionClaims.claimsMemory(it)) }
    }

    @Test
    fun `a memory claim needs memory_remember to have run, while any write backs an action claim`() {
        val declined = ActionRecord(ActionClaims.REMEMBER_TOOL, "Remember buys: oat milk", ActionRecord.Status.NOT_DONE, false, "declined")
        val remembered = declined.copy(status = ActionRecord.Status.DONE, wrote = true, reason = null)
        val scheduled = ActionRecord("calendar_create_event", "Create \"Lunch\"", ActionRecord.Status.DONE, wrote = true)
        val memory = setOf(ActionClaims.Claim.MEMORY)

        assertEquals(memory, ActionClaims.unbacked("I've noted that you buy oat milk every week.", listOf(declined)))
        assertEquals(emptySet<ActionClaims.Claim>(), ActionClaims.unbacked("I've noted that you buy oat milk every week.", listOf(remembered)))
        assertEquals("a calendar write doesn't back memory", memory, ActionClaims.unbacked("I've booked lunch and noted that Maya is vegetarian.", listOf(scheduled)))
        val both = ActionClaims.unbacked("I've booked lunch and noted that Maya is vegetarian.", emptyList())
        assertEquals(setOf(ActionClaims.Claim.ACTION, ActionClaims.Claim.MEMORY), both)
        assertEquals("Note from Anu: nothing was actually created or sent, and nothing was saved to memory.", ActionClaims.warning(both))
        assertEquals("${ActionClaims.NOTHING_DONE_NOTE} ${ActionClaims.NOTHING_REMEMBERED_NOTE}", ActionClaims.note(both, canRemember = false))
        assertEquals("the tool is named only when it's offered", "${ActionClaims.NOTHING_REMEMBERED_NOTE} ${ActionClaims.REMEMBER_HINT}", ActionClaims.note(memory, canRemember = true))
    }

    private val base = ScriptedBrain(BrainId.BASE)
    private val pro = ScriptedBrain(BrainId.PRO)
    private val create = FakeCreateEvent()
    private val broker = ToolBroker(listOf(create), RecordingGate())
    private val route = RouteRequest("Schedule it", Skill.SCHEDULING, setOf(DataClass.PERSONAL), promptTokens = 100)
    private val history = listOf(ChatMessage.User("Schedule a review with Maya"))

    private suspend fun run(tiers: Set<Tier>) = Agent(Router(), BrainProvider { mapOf(Tier.BASE to base, Tier.PRO to pro)[it] }, broker)
        .run(route, DeviceState(online = true, availableTiers = tiers), "You are Anu.", history, listOf(create.name), "t1").toList()

    @Test
    fun `a false claim is repaired with a note that nothing was done`() = runTest {
        pro.enqueue(Step.Reply("I've scheduled it and sent the invite."), Step.Reply("Shall I add it for Monday at 10:00?"))
        val events = run(setOf(Tier.PRO))
        assertEquals(ActionClaims.NOTHING_DONE_NOTE, pro.requests[1].note)
        assertEquals("the system prompt stays the same", pro.requests[0].system, pro.requests[1].system)
        val done = events.last() as AgentEvent.Completed
        assertEquals("Shall I add it for Monday at 10:00?", done.text)
        assertFalse(done.unverifiedClaim)
    }

    @Test
    fun `a claim that survives repair and escalation is shown with a warning`() = runTest {
        pro.enqueue(Step.Reply("I've scheduled it."), Step.Reply("I have scheduled it, really."))
        val events = run(setOf(Tier.PRO))
        val done = events.last() as AgentEvent.Completed
        assertTrue(done.unverifiedClaim)
        assertTrue(done.text.endsWith(ActionClaims.NOT_DONE_WARNING))
        assertEquals(done.text, (done.added.last() as ChatMessage.Model).text) // the transcript keeps the truth too
    }

    @Test
    fun `a claim backed by a real write is accepted, and the actions are reported`() = runTest {
        val call = ToolCall("calendar_create_event", buildJsonObject { put("title", "Review") })
        pro.enqueue(Step.Reply(listOf(""), toolCalls = listOf(call)), Step.Reply("I've added it to your calendar."))
        val done = run(setOf(Tier.PRO)).last() as AgentEvent.Completed
        assertFalse(done.unverifiedClaim)
        assertEquals(listOf(ActionRecord("calendar_create_event", "Create \"Review\"", ActionRecord.Status.DONE, wrote = true)), done.actions)
    }

    @Test
    fun `leaked tool-call markup is repaired instead of shown`() = runTest {
        val leaked = "<|tool_call>call:calendar_create_event{title:<|\"|>Review<|\"|>}<tool_call|>"
        assertTrue(ActionClaims.hasControlMarkup(leaked))
        assertFalse(ActionClaims.hasControlMarkup("Use calendar_create_event to add it."))
        pro.enqueue(Step.Reply(leaked), Step.Reply("Shall I add the review on Saturday at 1:00 PM?"))
        val done = run(setOf(Tier.PRO)).last() as AgentEvent.Completed
        assertEquals("Shall I add the review on Saturday at 1:00 PM?", done.text)
    }

    /** A chat turn like the app's: the newest message goes with its send time and a fact from memory (ADR-033, ADR-054). */
    private suspend fun chat(vararg replies: String): List<AgentEvent> {
        replies.forEach { pro.enqueue(Step.Reply(it)) }
        return Agent(Router(), BrainProvider { mapOf(Tier.PRO to pro)[it] }, broker)
            .run(
                route, DeviceState(online = true, availableTiers = setOf(Tier.PRO)), "You are Anu.", history, emptyList(), "t2",
                sentAt = "Sun 2026-09-27 01:32", relevant = factsBlock(listOf("diet: vegetarian")),
            ).toList()
    }

    @Test
    fun `an answer that quotes the message's time or facts is written again`() = runTest {
        val echoed = "I've noted that you are vegetarian. Also, you mentioned: \"[Sun 2026-09-27 01:32] Plan a dinner for tonight.\""
        val events = chat(echoed, "How about a mushroom risotto tonight?")
        assertTrue(events.any { it is AgentEvent.Discarded })
        assertEquals("How about a mushroom risotto tonight?", (events.last() as AgentEvent.Completed).text)
        assertEquals("the retry is greedy", Sampling.Precise, pro.requests[1].sampling)

        val quotedFacts = chat("$FACTS_HEADER diet: vegetarian. Try a risotto.", "Try a risotto.")
        assertEquals("Try a risotto.", (quotedFacts.last() as AgentEvent.Completed).text)
    }

    @Test
    fun `if the retry quotes it too, the framing is cut rather than sent to the cloud`() = runTest {
        val events = chat("[Sun 2026-09-27 01:32] Plan a dinner: try a risotto.", "[Sun 2026-09-27 01:32] Plan a dinner: try a risotto.")
        assertTrue(events.none { it is AgentEvent.Escalated || it is AgentEvent.Failed })
        val done = events.last() as AgentEvent.Completed
        assertEquals("Plan a dinner: try a risotto.", done.text)
        assertEquals(done.text, (done.added.last() as ChatMessage.Model).text)
    }

    @Test
    fun `a short follow-up keeps the previous skill and its tools`() {
        val scheduling = io.github.sandeepvijayarao09.anu.core.router.SkillDetector
        assertEquals(Skill.SCHEDULING, scheduling.detect("Yes please", previous = Skill.SCHEDULING))
        assertEquals(Skill.SCHEDULING, scheduling.detect("Make it 3pm instead", previous = Skill.SCHEDULING))
        assertEquals(Skill.CHAT, scheduling.detect("Yes please", previous = Skill.CHAT))
        assertEquals(Skill.CHAT, scheduling.detect("Yes please"))
    }

    @Test
    fun `the date table gives weekday names for the coming week`() {
        assertEquals(
            "today Fri 2026-09-25; tomorrow Sat 2026-09-26; Sun 2026-09-27; Mon 2026-09-28; Tue 2026-09-29; " +
                "Wed 2026-09-30; Thu 2026-10-01; Fri 2026-10-02",
            ChatSession.dateTable(LocalDate.parse("2026-09-25")),
        )
    }

    @Test
    fun `a reminder or alarm said to be set, or a list said to be updated, is a claim`() {
        listOf(
            "I've set a reminder for Thursday morning at 9:00 AM to call Maya.",
            "I have now set an alarm for 6:30.",
            "Your list has been updated with: oat milk.",
            "The reminder has been set for Friday.",
        ).forEach { assertTrue(it, ActionClaims.claimsAction(it)) }
        listOf(
            "Shall I set an alarm for 6:30?",
            "I can set a reminder for Thursday morning if you like.",
            "Would you like me to set it for 9:00 AM?",
            "For your list: oat milk.",
        ).forEach { assertFalse(it, ActionClaims.claimsAction(it)) }
    }
}
