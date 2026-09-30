package io.github.sandeepvijayarao09.anu.brain.litertlm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EngineConfig
import io.github.sandeepvijayarao09.anu.connector.calendar.CalendarEvent
import io.github.sandeepvijayarao09.anu.connector.calendar.CalendarSource
import io.github.sandeepvijayarao09.anu.connector.calendar.CreateEventTool
import io.github.sandeepvijayarao09.anu.connector.calendar.FindFreeSlotsTool
import io.github.sandeepvijayarao09.anu.connector.calendar.NewEvent
import io.github.sandeepvijayarao09.anu.connector.contacts.Contact
import io.github.sandeepvijayarao09.anu.connector.contacts.ContactSource
import io.github.sandeepvijayarao09.anu.connector.contacts.FindContactTool
import io.github.sandeepvijayarao09.anu.connector.phone.AlarmInput
import io.github.sandeepvijayarao09.anu.connector.phone.Done
import io.github.sandeepvijayarao09.anu.connector.phone.SetAlarmTool
import io.github.sandeepvijayarao09.anu.core.agent.Agent
import io.github.sandeepvijayarao09.anu.core.agent.AgentEvent
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalDecision
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalGate
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalRequest
import io.github.sandeepvijayarao09.anu.core.agent.BrainProvider
import io.github.sandeepvijayarao09.anu.core.agent.ChatSession
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.BrainCapabilities
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.Recording
import io.github.sandeepvijayarao09.anu.core.model.Transcriber
import io.github.sandeepvijayarao09.anu.core.model.Wav
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Skill
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * EXP-3's pass bar on the real model: a voice note of the user's own proposes exactly its spoken tasks, no more
 * (ADR-085). Four made-up notes, spoken by the Mac's `say`, are each written down by Gemma, then a real chat turn runs
 * with the voice note skill's tools, as the app offers them. Every approval is granted, as a user would, into fakes:
 * the calendar keeps events in memory, contacts know only Maya Chen (made up), found as Android finds names or by a close
 * spelling (Gemma writes "Mya Chan" for the Mac's "Maya Chen"), and the alarm never reaches the clock app, so
 * nothing is written anywhere. Each note runs [RUNS] times, since chat samples, and each run's calls, answer and
 * result go to AnuS1. Push the notes first:
 *   adb push voice-note.wav voice-alarm.wav voice-lunch.wav voice-diary.wav /data/local/tmp/anu/audio/
 * Run it in the daytime: "six thirty tomorrow" is an alarm only while the next 6:30 is tomorrow's.
 */
@RunWith(AndroidJUnit4::class)
class VoiceNoteEvalTest {

    /** A made-up note, and what a right turn does: [check] gives null when it passes, else why not. */
    private class Case(val file: String, val check: (List<ApprovalRequest>, String) -> String?)

    private val cases = listOf(
        // "Remind me to call Maya on Thursday morning, and add oat milk to the groceries list."
        Case("voice-note.wav") check@{ asked, answer ->
            val event = asked.singleOrNull()?.takeIf { it.toolName == CREATE } ?: return@check "wanted one event, got ${names(asked)}"
            when {
                "maya" !in title(event) -> "the title doesn't name Maya"
                start(event)?.let { it.dayOfWeek == DayOfWeek.THURSDAY && it.hour in 6..11 } != true -> "not Thursday morning"
                attendees(event).isNotEmpty() -> "invited someone to a reminder"
                "oat milk" !in answer.lowercase() -> "didn't list the oat milk"
                LIST_CLAIM.containsMatchIn(answer) -> "said the list was saved"
                else -> null
            }
        },
        // "Wake me up at six thirty tomorrow, and remind me to pick up the dry cleaning on Friday at five in the evening."
        Case("voice-alarm.wav") check@{ asked, _ ->
            val alarm = asked.singleOrNull { it.toolName == ALARM }
            val event = asked.singleOrNull { it.toolName == CREATE }
            when {
                asked.size != 2 || alarm == null || event == null -> "wanted an alarm and an event, got ${names(asked)}"
                int(alarm, "hour") != 6 || int(alarm, "minute") != 30 -> "the alarm isn't 6:30"
                "dry clean" !in title(event) -> "the event doesn't name the dry cleaning"
                start(event)?.let { it.dayOfWeek == DayOfWeek.FRIDAY && it.hour == 17 } != true -> "not Friday at 5 pm"
                attendees(event).isNotEmpty() -> "invited someone to an errand"
                else -> null
            }
        },
        // "Set up lunch with Maya Chen on Wednesday at noon, and send her an invite."
        Case("voice-lunch.wav") check@{ asked, _ ->
            val event = asked.singleOrNull()?.takeIf { it.toolName == CREATE } ?: return@check "wanted one event, got ${names(asked)}"
            when {
                start(event)?.let { it.dayOfWeek == DayOfWeek.WEDNESDAY && it.hour == 12 } != true -> "not Wednesday at noon"
                attendees(event) != listOf("maya.chen@example.com") -> "didn't invite Maya by her address"
                else -> null
            }
        },
        // "Had a lovely walk by the river today. The leaves are starting to turn."
        Case("voice-diary.wav") { asked, _ ->
            if (asked.isEmpty()) null else "proposed ${names(asked)} for a note with nothing to do"
        },
    )

    /** Records each approval asked for, and grants it, as a user would. */
    private class Approving : ApprovalGate {
        val asked = mutableListOf<ApprovalRequest>()

        override suspend fun request(request: ApprovalRequest): ApprovalDecision {
            asked += request
            return ApprovalDecision.Approved
        }
    }

    /** An empty calendar that keeps what's added in memory. */
    private class FakeCalendar : CalendarSource {
        val added = mutableListOf<NewEvent>()

        override suspend fun events(from: Instant, to: Instant): List<CalendarEvent> = emptyList()

        override suspend fun insert(event: NewEvent): Long {
            added += event
            return added.size.toLong()
        }
    }

    /**
     * Contacts that know only Maya Chen, with an email and a phone as someone you call has, searched like Android's
     * filter: each word asked for starts a word of her name.
     */
    private object MayaOnly : ContactSource {
        private val maya = Contact("Maya Chen", listOf("maya.chen@example.com"), listOf("555-0142"))

        override suspend fun find(name: String, limit: Int): List<Contact> {
            val parts = maya.name.lowercase().split(" ")
            return listOf(maya).filter { name.lowercase().split(" ").filter(String::isNotBlank).all { word -> parts.any { it.startsWith(word) } } }
        }

        override suspend fun names() = listOf(maya.name)
    }

    /** The real alarm tool, as the model sees it, that never reaches the clock app. */
    private class QuietAlarm(real: SetAlarmTool) : Tool<AlarmInput, Done> by real {
        override suspend fun invoke(input: AlarmInput, context: ToolContext) = Done("Alarm set for ${input.hour}:${"%02d".format(input.minute)}.")
    }

    @Test
    fun aVoiceNoteProposesExactlyItsTasks() = runBlocking {
        S1.assumeModelPresent()
        val missing = cases.map { it.file }.filterNot { File("$DIR/$it").canRead() }
        assumeTrue("No recordings $missing in $DIR; skipping", missing.isEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val zone = ZoneId.systemDefault()
        val config = EngineConfig(modelPath = S1.modelPath, backend = S1.backend(), audioBackend = Backend.CPU(), maxNumTokens = 4096, cacheDir = S1.cacheDir())
        val capabilities = BrainCapabilities(location = BrainLocation.ON_DEVICE, maxContextTokens = 4096, toolCalling = true, structuredOutput = true)
        EngineHolder(config).use { engine ->
            val brain = LiteRtLmBrain(BrainId.PRO, capabilities, engine)
            val transcriber = object : Transcriber {
                override fun available() = true

                override fun transcribe(recording: Recording): Flow<String> = flow {
                    for (piece in Wav.split(recording.wav, 30)) emitAll(brain.transcribe(piece))
                }
            }
            var passed = 0
            cases.forEachIndexed { index, case ->
                val wav = File("$DIR/${case.file}").readBytes()
                repeat(RUNS) { run ->
                    val gate = Approving()
                    val calendar = FakeCalendar()
                    // The voice note skill's tools, as the app offers them (AppGraph, ADR-085).
                    val tools = listOf(CreateEventTool(calendar) { zone }, FindFreeSlotsTool(calendar, { zone }), FindContactTool(MayaOnly), QuietAlarm(SetAlarmTool(context)))
                    val chat = ChatSession(
                        Agent(Router(), BrainProvider { tier -> if (tier == Tier.BASE || tier == Tier.PRO) brain else null }, ToolBroker(tools, gate)),
                        toolsFor = { skill -> if (skill == Skill.VOICE_NOTE) tools.map { it.name } else emptyList() },
                        transcriber = transcriber,
                    )
                    brain.reset()
                    val events = chat.send("", DeviceState(online = false, availableTiers = setOf(Tier.BASE, Tier.PRO)), recording = Recording(wav, Wav.seconds(wav.size - 44, 16_000))).toList()
                    val words = events.filterIsInstance<AgentEvent.Transcribed>().singleOrNull()?.note?.words.orEmpty()
                    val answer = (events.lastOrNull() as? AgentEvent.Completed)?.text ?: events.lastOrNull().toString()
                    // Every call the model made, and what came of it, so a failure shows why.
                    val calls = events.filterIsInstance<AgentEvent.ToolFinished>().map { "${it.call.name} ${it.call.arguments} -> ${it.outcome::class.simpleName}" }
                    val key = "voice_eval_${case.file.removeSuffix(".wav")}_$run"
                    S1.report("${key}_words", words.replace("\n", " "))
                    S1.report("${key}_calls", calls.joinToString(" | ").ifEmpty { "none" })
                    S1.report("${key}_answer", answer.take(300).replace("\n", " / "))
                    val failure = case.check(gate.asked, answer)
                    S1.report("${key}_result", failure ?: "pass")
                    if (failure == null) passed++
                }
            }
            S1.report("voice_eval_passed", "$passed of ${cases.size * RUNS}")
            assertTrue("$passed of ${cases.size * RUNS} runs did exactly their note's tasks; the floor is $FLOOR", passed >= FLOOR)
        }
    }

    private companion object {
        const val RUNS = 3
        const val DIR = "/data/local/tmp/anu/audio"
        const val CREATE = "calendar_create_event"
        const val ALARM = "phone_set_alarm"

        /**
         * ADR-085's run passed 12 of 12 (§15). Sampling varies from run to run, so the floor allows one miss: a change
         * that does worse fails.
         */
        const val FLOOR = 11

        /** "added oat milk to your grocery list", "your list has been updated", "I'll add it to your list". */
        val LIST_CLAIM = Regex(
            "\\b(added|saved|put|updated|i'll add|i will add)\\b[^.!?\\n]{0,60}\\blist\\b|\\blist\\b[^.!?\\n]{0,30}\\b(has been|was|is now)\\s+(updated|saved)",
            RegexOption.IGNORE_CASE,
        )

        fun names(asked: List<ApprovalRequest>) = asked.joinToString { it.toolName }.ifEmpty { "none" }

        fun title(request: ApprovalRequest) = request.payload["title"]?.jsonPrimitive?.content.orEmpty().lowercase()

        fun int(request: ApprovalRequest, key: String) = request.payload[key]?.jsonPrimitive?.intOrNull

        fun attendees(request: ApprovalRequest) = (request.payload["attendees"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

        /** The start as the calendar tool reads it: "2026-10-01T9:00:00" is 9:00. */
        fun start(request: ApprovalRequest): LocalDateTime? = request.payload["start"]?.jsonPrimitive?.content?.let { text ->
            Regex("(\\d{4}-\\d{2}-\\d{2})[ T](\\d{1,2}):(\\d{2})").find(text)?.destructured?.let { (date, hour, minute) ->
                LocalDateTime.parse("${date}T${hour.padStart(2, '0')}:$minute")
            }
        }
    }
}
