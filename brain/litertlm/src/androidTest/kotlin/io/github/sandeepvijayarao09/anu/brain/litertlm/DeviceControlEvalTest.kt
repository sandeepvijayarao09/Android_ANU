package io.github.sandeepvijayarao09.anu.brain.litertlm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sandeepvijayarao09.anu.connector.contacts.AddContactTool
import io.github.sandeepvijayarao09.anu.connector.contacts.Contact
import io.github.sandeepvijayarao09.anu.connector.contacts.ContactSource
import io.github.sandeepvijayarao09.anu.connector.contacts.ContactWriter
import io.github.sandeepvijayarao09.anu.connector.contacts.FindContactTool
import io.github.sandeepvijayarao09.anu.connector.notes.AppendNoteTool
import io.github.sandeepvijayarao09.anu.connector.notes.CreateNoteTool
import io.github.sandeepvijayarao09.anu.connector.notes.Note
import io.github.sandeepvijayarao09.anu.connector.notes.NoteStore
import io.github.sandeepvijayarao09.anu.connector.notes.SearchNotesTool
import io.github.sandeepvijayarao09.anu.connector.phone.AlarmInput
import io.github.sandeepvijayarao09.anu.connector.phone.Done
import io.github.sandeepvijayarao09.anu.connector.phone.DoNotDisturbInput
import io.github.sandeepvijayarao09.anu.connector.phone.DoNotDisturbTool
import io.github.sandeepvijayarao09.anu.connector.phone.FlashlightInput
import io.github.sandeepvijayarao09.anu.connector.phone.FlashlightTool
import io.github.sandeepvijayarao09.anu.connector.phone.OpenSettingsTool
import io.github.sandeepvijayarao09.anu.connector.phone.SetAlarmTool
import io.github.sandeepvijayarao09.anu.connector.phone.SetTimerTool
import io.github.sandeepvijayarao09.anu.connector.phone.SettingsInput
import io.github.sandeepvijayarao09.anu.connector.phone.TimerInput
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
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Skill
import io.github.sandeepvijayarao09.anu.core.router.Tier
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spike S12 (ADR-093): can the model under test, E2B or E4B, run the device skill? Eight made-up requests, each of one to
 * three device actions, or none, go through a real chat turn with the device skill's tools, as the app offers them.
 * Every write lands in a fake, so nothing reaches the clock, the torch, Do Not Disturb, the address book or Anu's
 * notes; approvals, if any are asked, are granted. Each request runs [RUNS] times, since tool turns sample at 0.3, and
 * each run's skill, calls, answer, time and result go to AnuS1. Pick the model with `modelPath`:
 *   -Pandroid.testInstrumentationRunnerArguments.modelPath=/data/local/tmp/anu/models/gemma-4-E2B-it.litertlm
 */
@RunWith(AndroidJUnit4::class)
class DeviceControlEvalTest {

    /** What the run did: every call that ran, by tool, with its input. */
    private class Ran(val calls: List<Pair<String, Any>>) {
        inline fun <reified T> all(tool: String): List<T> = calls.filter { it.first == tool }.map { it.second }.filterIsInstance<T>()
        fun tools() = calls.map { it.first }
    }

    /** A made-up request, and what a right turn does: [check] gives null when it passes, else why not. */
    private class Case(val key: String, val text: String, val check: (Ran, String) -> String?)

    private val cases = listOf(
        Case("alarm_and_note", "Set an alarm for 6:45 and add eggs to my groceries note") check@{ ran, _ ->
            val alarm = ran.all<AlarmInput>(ALARM).singleOrNull() ?: return@check "wanted one alarm, got ${ran.tools()}"
            val added = ran.all<Pair<String, String>>(APPEND).singleOrNull() ?: return@check "wanted one note added to, got ${ran.tools()}"
            when {
                alarm.hour != 6 || alarm.minute != 45 -> "the alarm is ${alarm.hour}:${alarm.minute}"
                !added.first.equals("Groceries", ignoreCase = true) -> "added to \"${added.first}\""
                "egg" !in added.second.lowercase() -> "added \"${added.second}\""
                ran.calls.size != 2 -> "did more: ${ran.tools()}"
                else -> null
            }
        },
        Case("contact_and_timer", "Save Raj Patel's number, 555 0100, and set a timer for 10 minutes") check@{ ran, _ ->
            val saved = ran.all<Triple<String, String?, String?>>(ADD_CONTACT).singleOrNull() ?: return@check "wanted one contact saved, got ${ran.tools()}"
            val timer = ran.all<TimerInput>(TIMER).singleOrNull() ?: return@check "wanted one timer, got ${ran.tools()}"
            when {
                !saved.first.equals("Raj Patel", ignoreCase = true) -> "saved \"${saved.first}\""
                saved.second?.filter(Char::isDigit) != "5550100" -> "saved the number \"${saved.second}\""
                timer.seconds != 600 -> "the timer is ${timer.seconds} s"
                ran.calls.size != 2 -> "did more: ${ran.tools()}"
                else -> null
            }
        },
        Case("new_note", "Write a note called Packing: passport, charger, sunscreen") check@{ ran, _ ->
            val note = ran.all<Pair<String, String>>(CREATE_NOTE).singleOrNull() ?: return@check "wanted one note, got ${ran.tools()}"
            when {
                !note.first.equals("Packing", ignoreCase = true) -> "titled \"${note.first}\""
                listOf("passport", "charger", "sunscreen").any { it !in note.second.lowercase() } -> "wrote \"${note.second}\""
                ran.calls.size != 1 -> "did more: ${ran.tools()}"
                else -> null
            }
        },
        Case("read_note", "What's on my groceries note?") { ran, answer ->
            when {
                ran.tools().any { it in WRITES } -> "wrote something: ${ran.tools()}"
                "oat milk" !in answer.lowercase() -> "the answer doesn't say oat milk"
                else -> null
            }
        },
        Case("dnd_and_torch", "Turn on Do Not Disturb and the flashlight") check@{ ran, _ ->
            val dnd = ran.all<DoNotDisturbInput>(DND).singleOrNull() ?: return@check "wanted Do Not Disturb, got ${ran.tools()}"
            val torch = ran.all<FlashlightInput>(TORCH).singleOrNull() ?: return@check "wanted the flashlight, got ${ran.tools()}"
            when {
                !dnd.on || !torch.on -> "turned something off"
                ran.calls.size != 2 -> "did more: ${ran.tools()}"
                else -> null
            }
        },
        Case("email_to_contact", "Add maya.work@example.com to Maya Chen in my contacts") check@{ ran, _ ->
            val saved = ran.all<Triple<String, String?, String?>>(ADD_CONTACT).singleOrNull() ?: return@check "wanted one contact saved, got ${ran.tools()}"
            when {
                !saved.first.equals("Maya Chen", ignoreCase = true) -> "saved to \"${saved.first}\""
                saved.third != "maya.work@example.com" -> "saved the email \"${saved.third}\""
                saved.second != null -> "added a phone number \"${saved.second}\""
                else -> null
            }
        },
        Case("torch_and_settings", "Turn off the flashlight, then open the battery settings") check@{ ran, _ ->
            val torch = ran.all<FlashlightInput>(TORCH).singleOrNull() ?: return@check "wanted the flashlight, got ${ran.tools()}"
            val page = ran.all<SettingsInput>(SETTINGS).singleOrNull() ?: return@check "wanted a settings page, got ${ran.tools()}"
            when {
                torch.on -> "turned the flashlight on"
                page.page != "battery" -> "opened \"${page.page}\""
                ran.calls.size != 2 -> "did more: ${ran.tools()}"
                else -> null
            }
        },
        Case("missing_note", "Do I have a note about the gate code?") { ran, answer ->
            when {
                ran.tools().any { it in WRITES } -> "wrote something: ${ran.tools()}"
                Regex("\\b(yes|here it is|the code is)\\b", RegexOption.IGNORE_CASE).containsMatchIn(answer) -> "said there is one"
                else -> null
            }
        },
    )

    /** Grants every approval asked for, as a user would. Device writes don't ask while nothing taints the turn. */
    private object Approving : ApprovalGate {
        override suspend fun request(request: ApprovalRequest) = ApprovalDecision.Approved
    }

    /** The calls that really ran, in order, with the inputs the fakes received. */
    private class Log {
        val calls = mutableListOf<Pair<String, Any>>()
    }

    /** A real tool as the model sees it, whose action is only recorded. */
    private class Quiet<I : Any>(private val real: Tool<I, Done>, private val log: Log) : Tool<I, Done> by real {
        override suspend fun invoke(input: I, context: ToolContext): Done {
            log.calls += real.name to input
            return Done(real.describe(input) + ": done.")
        }
    }

    /** One note already kept, "Groceries", in memory. */
    private class FakeNotes(private val log: Log) : NoteStore {
        private val notes = mutableListOf(Note(1, "Groceries", "oat milk\nbread", 1))

        override suspend fun create(title: String, text: String, atMillis: Long) =
            Note(notes.size + 1L, title, text, atMillis).also { notes += it; log.calls += CREATE_NOTE to (title to text) }

        override suspend fun search(query: String, limit: Int) = notes.filter { note ->
            query.lowercase().split(" ").filter(String::isNotBlank).all { "${note.title} ${note.text}".lowercase().contains(it) }
        }.sortedByDescending { it.updatedAtMillis }.take(limit)

        override suspend fun append(id: Long, text: String, atMillis: Long): Note {
            val index = notes.indexOfFirst { it.id == id }
            val note = notes[index].let { it.copy(text = it.text + "\n" + text, updatedAtMillis = atMillis) }
            notes[index] = note
            log.calls += APPEND to (note.title to text)
            return note
        }
    }

    /** An address book that knows only Maya Chen (made up), and records what's saved. */
    private class FakeContacts(private val log: Log) : ContactSource, ContactWriter {
        private val maya = Contact("Maya Chen", listOf("maya.chen@example.com"), listOf("555-0142"))

        override suspend fun find(name: String, limit: Int) = listOf(maya).filter { contact ->
            name.lowercase().split(" ").filter(String::isNotBlank).all { word -> contact.name.lowercase().split(" ").any { it.startsWith(word) } }
        }

        override suspend fun names() = listOf(maya.name)

        override suspend fun save(name: String, phone: String?, email: String?): ContactWriter.Saved {
            log.calls += ADD_CONTACT to Triple(name, phone, email)
            return ContactWriter.Saved(if (name.equals(maya.name, ignoreCase = true)) maya.name else name, !name.equals(maya.name, ignoreCase = true))
        }
    }

    @Test
    fun theDeviceSkillDoesExactlyWhatWasAsked() = runBlocking {
        S1.assumeModelPresent()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = EngineConfig(modelPath = S1.modelPath, backend = S1.backend(), maxNumTokens = 4096, cacheDir = S1.cacheDir())
        val capabilities = BrainCapabilities(location = BrainLocation.ON_DEVICE, maxContextTokens = 4096, toolCalling = true, structuredOutput = true)
        EngineHolder(config, S1.options()).use { engine ->
            val brain = LiteRtLmBrain(BrainId.BASE, capabilities, engine)
            var passed = 0
            var seconds = 0.0
            for (case in cases) {
                repeat(RUNS) { run ->
                    val log = Log()
                    val notes = FakeNotes(log)
                    val contacts = FakeContacts(log)
                    // The device skill's tools, as the app offers them (AppGraph, ADR-093).
                    val tools: List<Tool<*, *>> = listOf(
                        Quiet(SetTimerTool(context), log), Quiet(SetAlarmTool(context), log), Quiet(FlashlightTool(context), log),
                        Quiet(DoNotDisturbTool(context), log), Quiet(OpenSettingsTool(context), log),
                        CreateNoteTool(notes), SearchNotesTool(notes), AppendNoteTool(notes), FindContactTool(contacts), AddContactTool(contacts),
                    )
                    var skill: Skill? = null
                    val chat = ChatSession(
                        Agent(Router(), BrainProvider { tier -> if (tier == Tier.BASE || tier == Tier.PRO) brain else null }, ToolBroker(tools, Approving)),
                        toolsFor = { found -> skill = found; if (found == Skill.PHONE) tools.map { it.name } else emptyList() },
                    )
                    brain.reset()
                    val started = System.nanoTime()
                    val events = chat.send(case.text, DeviceState(online = false, availableTiers = setOf(Tier.BASE, Tier.PRO))).toList()
                    val took = (System.nanoTime() - started) / 1e9
                    seconds += took
                    val answer = (events.lastOrNull() as? AgentEvent.Completed)?.text ?: events.lastOrNull().toString()
                    val tried = events.filterIsInstance<AgentEvent.ToolFinished>().map { "${it.call.name} ${it.call.arguments} -> ${it.outcome::class.simpleName}" }
                    val escalated = events.filterIsInstance<AgentEvent.Escalated>().joinToString { it.reason }
                    val failure = if (events.lastOrNull() !is AgentEvent.Completed) "the turn didn't complete" else case.check(Ran(log.calls), answer)
                    val key = "device_eval_${case.key}_$run"
                    S1.report("${key}_skill", skill ?: "none")
                    S1.report("${key}_calls", tried.joinToString(" | ").ifEmpty { "none" })
                    if (escalated.isNotEmpty()) S1.report("${key}_escalated", escalated)
                    S1.report("${key}_answer", answer.take(300).replace("\n", " / "))
                    S1.report("${key}_seconds", "%.1f".format(took))
                    S1.report("${key}_result", failure ?: "pass")
                    if (failure == null) passed++
                }
            }
            val total = cases.size * RUNS
            S1.report("device_eval_passed", "$passed of $total")
            S1.report("device_eval_mean_seconds", "%.1f".format(seconds / total))
            // A spike: it measures, and fails only when nothing works. ADR-093 sets the floor from the first runs.
            assertTrue("$passed of $total runs did exactly what was asked", passed > 0)
        }
    }

    private companion object {
        const val RUNS = 3
        const val ALARM = "phone_set_alarm"
        const val TIMER = "phone_set_timer"
        const val TORCH = "phone_flashlight"
        const val DND = "phone_do_not_disturb"
        const val SETTINGS = "phone_open_settings"
        const val CREATE_NOTE = "notes_create"
        const val APPEND = "notes_append"
        const val ADD_CONTACT = "contacts_add"
        val WRITES = setOf(ALARM, TIMER, TORCH, DND, CREATE_NOTE, APPEND, ADD_CONTACT)
    }
}
