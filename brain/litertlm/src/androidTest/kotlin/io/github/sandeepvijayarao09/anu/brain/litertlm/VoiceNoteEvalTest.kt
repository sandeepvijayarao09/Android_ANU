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
import io.github.sandeepvijayarao09.anu.connector.phone.SetAlarmTool
import io.github.sandeepvijayarao09.anu.core.agent.Agent
import io.github.sandeepvijayarao09.anu.core.agent.AgentEvent
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalDecision
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalGate
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalRequest
import io.github.sandeepvijayarao09.anu.core.agent.BrainProvider
import io.github.sandeepvijayarao09.anu.core.agent.ChatSession
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
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
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * EXP-3's pass bar on the real model: a made-up voice note of the user's own proposes exactly its spoken tasks, no
 * more. The note ("Remind me to call Maya on Thursday morning, and add oat milk to the groceries list.") is written
 * down by Gemma, then a real chat turn runs with the voice note skill's calendar and alarm tools. Every approval is
 * refused, and the calendar is an empty fake, so nothing is written anywhere. It runs [RUNS] times, since chat
 * samples, and reports each run's proposals and answer to AnuS1. Push the note first:
 *   adb push voice-note.wav /data/local/tmp/anu/audio/
 */
@RunWith(AndroidJUnit4::class)
class VoiceNoteEvalTest {

    private val notePath = "/data/local/tmp/anu/audio/voice-note.wav"

    /** Records each approval asked for, and refuses it. */
    private class Refusing : ApprovalGate {
        val asked = mutableListOf<ApprovalRequest>()

        override suspend fun request(request: ApprovalRequest): ApprovalDecision {
            asked += request
            return ApprovalDecision.Denied("eval: nothing is written")
        }
    }

    /** An empty calendar that must never be written. */
    private object EmptyCalendar : CalendarSource {
        override suspend fun events(from: java.time.Instant, to: java.time.Instant): List<CalendarEvent> = emptyList()

        override suspend fun insert(event: NewEvent): Long = error("the eval never writes")
    }

    @Test
    fun aVoiceNoteProposesExactlyItsTasks() = runBlocking {
        S1.assumeModelPresent()
        assumeTrue("No recording at $notePath; skipping", File(notePath).canRead())
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
            val wav = File(notePath).readBytes()
            var passed = 0
            repeat(RUNS) { run ->
                val gate = Refusing()
                val tools = listOf(CreateEventTool(EmptyCalendar) { zone }, FindFreeSlotsTool(EmptyCalendar, { zone }), SetAlarmTool(context))
                val chat = ChatSession(
                    Agent(Router(), BrainProvider { tier -> if (tier == Tier.BASE || tier == Tier.PRO) brain else null }, ToolBroker(tools, gate)),
                    toolsFor = { skill -> if (skill == Skill.VOICE_NOTE) tools.map { it.name } else emptyList() },
                    transcriber = transcriber,
                )
                brain.reset()
                val events = chat.send("", DeviceState(online = false, availableTiers = setOf(Tier.BASE, Tier.PRO)), recording = Recording(wav, Wav.seconds(wav.size - 44, 16_000))).toList()
                val words = events.filterIsInstance<AgentEvent.Transcribed>().singleOrNull()?.note?.words.orEmpty()
                val answer = (events.lastOrNull() as? AgentEvent.Completed)?.text ?: events.lastOrNull().toString()
                val proposals = gate.asked.map { it.toolName + " " + it.payload }
                S1.report("voice_eval_${run}_words", words.replace("\n", " "))
                S1.report("voice_eval_${run}_proposals", proposals.joinToString(" | ").ifEmpty { "none" })
                S1.report("voice_eval_${run}_answer", answer.take(300).replace("\n", " / "))
                val calls = gate.asked.filter { it.toolName == "calendar_create_event" }
                val call = calls.singleOrNull()
                val start = call?.payload?.get("start")?.jsonPrimitive?.content?.let { runCatching { LocalDateTime.parse(it.take(16)) }.getOrNull() }
                val exact = gate.asked.size == 1 && call != null &&
                    call.payload["title"]?.jsonPrimitive?.content.orEmpty().contains("Maya", ignoreCase = true) &&
                    start?.dayOfWeek == DayOfWeek.THURSDAY && start.hour in 6..11
                val listed = "oat milk" in answer.lowercase()
                S1.report("voice_eval_${run}_pass", exact && listed)
                if (exact && listed) passed++
            }
            S1.report("voice_eval_passed", "$passed of $RUNS")
            // Nothing was written: every approval was refused, and the calendar's insert throws.
            assertTrue("at least one run proposes exactly the call on Thursday morning and lists the oat milk", passed > 0)
        }
    }

    private companion object {
        const val RUNS = 3
    }
}
