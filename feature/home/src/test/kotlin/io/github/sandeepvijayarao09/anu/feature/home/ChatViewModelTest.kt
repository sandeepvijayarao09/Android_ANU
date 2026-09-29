package io.github.sandeepvijayarao09.anu.feature.home

import io.github.sandeepvijayarao09.anu.core.agent.BrainProvider
import io.github.sandeepvijayarao09.anu.core.agent.Agent
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalCenter
import io.github.sandeepvijayarao09.anu.core.agent.ChatSession
import io.github.sandeepvijayarao09.anu.core.agent.InMemoryTranscriptStore
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain.Step
import io.github.sandeepvijayarao09.anu.core.model.Recording
import io.github.sandeepvijayarao09.anu.core.model.Transcriber
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import io.github.sandeepvijayarao09.anu.core.model.VoiceNote
import io.github.sandeepvijayarao09.anu.core.voice.RecordingEvent
import io.github.sandeepvijayarao09.anu.core.voice.VoiceRecorder
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Tier
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val base = ScriptedBrain(BrainId.BASE)
    private val pro = ScriptedBrain(BrainId.PRO)
    private val cloud = ScriptedBrain(BrainId.CLOUD_FLASH)
    private var device = DeviceState(online = true, availableTiers = setOf(Tier.BASE, Tier.PRO))
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val brains = mapOf(Tier.BASE to base, Tier.PRO to pro, Tier.CLOUD to cloud)
        viewModel = ChatViewModel(ChatSession(Agent(Router(), BrainProvider { brains[it] }, ToolBroker(emptyList(), ApprovalCenter()))), device = { device })
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val bubbles get() = viewModel.state.value.bubbles

    @Test
    fun `a long chat says that older messages are left out`() {
        val brains = mapOf(Tier.BASE to base)
        val session = ChatSession(
            Agent(Router(), BrainProvider { brains[it] }, ToolBroker(emptyList(), ApprovalCenter())),
            window = io.github.sandeepvijayarao09.anu.core.agent.ContextWindow(budget = 600),
        )
        val chat = ChatViewModel(session, device = { device })
        repeat(4) { base.enqueue(Step.Reply("r".repeat(350))) }
        chat.send("First " + "x".repeat(340))
        assertFalse(chat.state.value.trimmed)
        repeat(3) { chat.send("More " + "x".repeat(340)) }
        assertTrue(chat.state.value.trimmed)
    }

    @Test
    fun `a saved chat comes back with each answer's model, receipts and details, and without text written before tools`() {
        val history = listOf(
            ChatMessage.User("Add lunch tomorrow"),
            ChatMessage.Model("Let me add that.", listOf(ToolCall("calendar_create_event", buildJsonObject { put("title", "Lunch") }))),
            ChatMessage.Tool(listOf(io.github.sandeepvijayarao09.anu.core.model.ToolResult("calendar_create_event", buildJsonObject { put("id", "e1") }))),
            ChatMessage.Model("Added lunch."),
            ChatMessage.User("Thanks"),
            ChatMessage.Model("Anytime."),
        )
        val details = io.github.sandeepvijayarao09.anu.core.agent.AnswerDetails(
            tier = Tier.PRO,
            backend = "GPU",
            actions = listOf(io.github.sandeepvijayarao09.anu.core.agent.ActionRecord("calendar_create_event", "Create \"Lunch\"", io.github.sandeepvijayarao09.anu.core.agent.ActionRecord.Status.DONE, wrote = true)),
        )
        val answers = io.github.sandeepvijayarao09.anu.core.agent.InMemoryAnswerStore()
        kotlinx.coroutines.runBlocking { answers.save(0, details) }
        val brains = mapOf(Tier.BASE to base)
        val chat = ChatViewModel(
            ChatSession(Agent(Router(), BrainProvider { brains[it] }, ToolBroker(emptyList(), ApprovalCenter())), InMemoryTranscriptStore(history), answers = answers),
            device = { device },
        )
        val restored = chat.state.value.bubbles
        assertEquals(listOf("Add lunch tomorrow", "Added lunch.", "Thanks", "Anytime."), restored.map { it.text })
        assertEquals(Tier.PRO, restored[1].tier)
        assertEquals(details, restored[1].details)
        assertEquals(listOf("Create \"Lunch\""), restored[1].actions.map { it.summary })
        assertNull("saved before details were kept", restored[3].details)
    }

    @Test
    fun `a completed answer carries its details`() {
        base.enqueue(Step.Reply("Hello!"))
        viewModel.send("Hi")
        assertEquals(Tier.BASE, bubbles.last().details?.tier)
    }

    @Test
    fun `a wait before the first word names its cause, and clears when the model is ready`() {
        val status = kotlinx.coroutines.flow.MutableStateFlow<io.github.sandeepvijayarao09.anu.core.model.BrainStatus>(io.github.sandeepvijayarao09.anu.core.model.BrainStatus.Idle)
        var warmed = 0
        val brains = mapOf(Tier.BASE to base)
        val chat = ChatViewModel(
            ChatSession(Agent(Router(), BrainProvider { brains[it] }, ToolBroker(emptyList(), ApprovalCenter()))),
            device = { device }, brainStatus = status, warm = { warmed++ }, waitNoteDelayMs = 0,
        )
        chat.typingStarted()
        assertEquals(1, warmed)
        status.value = io.github.sandeepvijayarao09.anu.core.model.BrainStatus.Loading(io.github.sandeepvijayarao09.anu.core.model.BrainId.PRO)
        assertTrue("no answer to wait on yet", chat.state.value.bubbles.isEmpty())
        base.enqueue(Step.Hang(emptyList()))
        chat.send("Hi")
        assertEquals("Getting Gemma 4 E4B ready…", chat.state.value.bubbles.last().note)
        status.value = io.github.sandeepvijayarao09.anu.core.model.BrainStatus.Reading(io.github.sandeepvijayarao09.anu.core.model.BrainId.PRO)
        assertEquals("Catching up on this chat…", chat.state.value.bubbles.last().note)
        status.value = io.github.sandeepvijayarao09.anu.core.model.BrainStatus.Idle
        assertNull(chat.state.value.bubbles.last().note)
        chat.stop()
    }

    @Test
    fun `each wait has its own words`() {
        assertEquals("Setting up the GPU. First time only, about a minute.", waitCause(io.github.sandeepvijayarao09.anu.core.model.BrainStatus.SettingUpGpu(io.github.sandeepvijayarao09.anu.core.model.BrainId.PRO)))
        assertEquals("Switching to Gemma 4 E2B…", waitCause(io.github.sandeepvijayarao09.anu.core.model.BrainStatus.Switching(io.github.sandeepvijayarao09.anu.core.model.BrainId.BASE)))
        assertNull(waitCause(io.github.sandeepvijayarao09.anu.core.model.BrainStatus.Idle))
    }

    @Test
    fun `an attached photo goes with the next message, alone or with words, and can be taken back`() {
        viewModel.attach(byteArrayOf(1, 2, 3))
        viewModel.removeAttachment()
        assertNull(viewModel.state.value.attachment)

        viewModel.attach(byteArrayOf(1, 2, 3))
        base.enqueue(Step.Reply("A red circle."))
        viewModel.send("")
        val asked = bubbles.first()
        assertEquals("", asked.text)
        assertEquals(listOf<Byte>(1, 2, 3), asked.photos.single().bytes!!.toList())
        val seen = base.requests.single().transcript.last() as ChatMessage.User
        assertEquals(listOf<Byte>(1, 2, 3), seen.photos.single().bytes!!.toList())
        assertNull("the composer is clear again", viewModel.state.value.attachment)
    }

    @Test
    fun `reasoning shows as Thinking, then sits apart from the answer`() {
        base.enqueue(Step.Reply(listOf("12:35"), thoughts = listOf("First the train, ", "then the walk.")))
        viewModel.send("When does Maya arrive?")
        val answer = bubbles.last()
        assertEquals("12:35", answer.text)
        assertEquals("First the train, then the walk.", answer.thought)
        assertNull(answer.note)
    }

    @Test
    fun `a message streams into an answer labelled with its tier`() {
        base.enqueue(Step.Reply(listOf("Hel", "lo!")))
        viewModel.send("Hi")
        assertEquals(listOf("Hi", "Hello!"), bubbles.map { it.text })
        assertEquals(Tier.BASE, bubbles.last().tier)
        assertFalse(viewModel.state.value.busy)
        assertFalse(bubbles.last().streaming)
    }

    @Test
    fun `cloud use waits for consent, and approval sends it`() {
        device = DeviceState(online = true, availableTiers = setOf(Tier.CLOUD))
        viewModel.send("Plan my week")
        assertEquals("Plan my week", viewModel.state.value.consentFor)
        assertEquals(listOf("Plan my week"), bubbles.map { it.text }) // no empty answer bubble left behind
        assertTrue(cloud.requests.isEmpty())

        cloud.enqueue(Step.Reply("Here's a plan."))
        viewModel.approveCloud()
        assertNull(viewModel.state.value.consentFor)
        assertEquals(Tier.CLOUD, bubbles.last().tier)
        assertEquals("Here's a plan.", bubbles.last().text)
    }

    private fun withFacts(): ChatViewModel {
        val brains = mapOf(Tier.BASE to base, Tier.PRO to pro, Tier.CLOUD to cloud)
        val session = ChatSession(
            Agent(Router(), BrainProvider { brains[it] }, ToolBroker(emptyList(), ApprovalCenter())),
            relevant = { _, _ -> listOf(io.github.sandeepvijayarao09.anu.core.model.RelevantFact("manager: Maya Chen", sensitive = false)) },
        )
        return ChatViewModel(session, device = { device })
    }

    @Test
    fun `the consent sheet lists the facts, and approving shares exactly those`() {
        device = DeviceState(online = true, availableTiers = setOf(Tier.CLOUD))
        val chat = withFacts()
        chat.send("Lunch ideas for my manager")
        assertEquals(listOf("manager: Maya Chen"), chat.state.value.consentFacts)
        cloud.enqueue(Step.Reply("Try the new Thai place."))
        chat.approveCloud(shareFacts = true)
        assertEquals(listOf("manager: Maya Chen"), cloud.requests.last().sharedFacts)
        assertEquals(emptyList<String>(), chat.state.value.consentFacts)
    }

    @Test
    fun `keeping the facts on the phone sends none of them`() {
        device = DeviceState(online = true, availableTiers = setOf(Tier.CLOUD))
        val chat = withFacts()
        chat.send("Lunch ideas for my manager")
        cloud.enqueue(Step.Reply("Try the new Thai place."))
        chat.approveCloud(shareFacts = false)
        assertEquals(emptyList<String>(), cloud.requests.last().sharedFacts)
    }

    @Test
    fun `declining consent sends nothing`() {
        device = DeviceState(online = true, availableTiers = setOf(Tier.CLOUD))
        viewModel.send("Plan my week")
        viewModel.declineCloud()
        assertTrue(cloud.requests.isEmpty())
        assertEquals("Kept on this phone. Nothing was sent.", bubbles.last().text)
        assertEquals("declining isn't a failure", 0, viewModel.state.value.failures)
    }

    @Test
    fun `on a warm phone the lighter answer offers the phone and Gemini, and says why`() {
        device = DeviceState(online = true, availableTiers = setOf(Tier.BASE, Tier.PRO, Tier.CLOUD), guard = io.github.sandeepvijayarao09.anu.core.router.Guard.HEAT)
        val chat = ChatViewModel(ChatSession(Agent(Router(), BrainProvider { mapOf(Tier.BASE to base, Tier.PRO to pro, Tier.CLOUD to cloud)[it] }, ToolBroker(emptyList(), ApprovalCenter()))), device = { device })
        base.enqueue(Step.Reply("A lighter plan."))
        chat.send("Find a time next week for me, Maya and Raj, then email them an invite")
        val answer = chat.state.value.bubbles.last()
        assertEquals(Tier.BASE, answer.tier)
        assertEquals(listOf(RetryChoice.ON_PHONE, RetryChoice.GEMINI), retryChoices(answer, chat.state.value.cloudAllowed))
        assertEquals("Lighter model while the phone is warm", guardLine(answer))
    }

    @Test
    fun `asking Gemini and then keeping it on the phone leaves the answer and its choices`() {
        device = DeviceState(online = true, availableTiers = setOf(Tier.BASE, Tier.PRO, Tier.CLOUD), guard = io.github.sandeepvijayarao09.anu.core.router.Guard.BATTERY)
        val chat = ChatViewModel(ChatSession(Agent(Router(), BrainProvider { mapOf(Tier.BASE to base, Tier.PRO to pro, Tier.CLOUD to cloud)[it] }, ToolBroker(emptyList(), ApprovalCenter()))), device = { device })
        base.enqueue(Step.Reply("A lighter plan."))
        chat.send("Find a time next week for me, Maya and Raj, then email them an invite")
        chat.askGemini()
        assertEquals("Find a time next week for me, Maya and Raj, then email them an invite", chat.state.value.consentFor)
        chat.declineCloud()
        assertEquals(listOf("Find a time next week for me, Maya and Raj, then email them an invite", "A lighter plan."), chat.state.value.bubbles.map { it.text })
        assertTrue(cloud.requests.isEmpty())

        chat.askGemini()
        cloud.enqueue(Step.Reply("Gemini's plan."))
        chat.approveCloud(shareFacts = false)
        assertEquals(listOf("Find a time next week for me, Maya and Raj, then email them an invite", "Gemini's plan."), chat.state.value.bubbles.map { it.text })
        assertEquals(Tier.CLOUD, chat.state.value.bubbles.last().tier)
    }

    @Test
    fun `retrying a turn that was never saved keeps the saved chat before it`() {
        val brains = mapOf(Tier.BASE to base, Tier.PRO to pro)
        val session = ChatSession(Agent(Router(), BrainProvider { brains[it] }, ToolBroker(emptyList(), ApprovalCenter())))
        val chat = ChatViewModel(session, device = { device })
        base.enqueue(Step.Reply("First answer."))
        chat.send("First question")
        base.enqueue(Step.Fail(IllegalStateException("out of memory")))
        pro.enqueue(Step.Fail(IllegalStateException("out of memory")))
        chat.send("Second question")
        assertTrue(chat.state.value.bubbles.last().failed)
        pro.enqueue(Step.Reply("Second answer."))
        chat.retryWithPro()
        val history = kotlinx.coroutines.runBlocking { session.history() }
        assertEquals(
            listOf(ChatMessage.User("First question"), ChatMessage.Model("First answer."), ChatMessage.User("Second question"), ChatMessage.Model("Second answer.")),
            history,
        )
        assertEquals(listOf("First question", "First answer.", "Second question", "Second answer."), chat.state.value.bubbles.map { it.text })
    }

    @Test
    fun `retry with Pro replaces the last answer`() {
        base.enqueue(Step.Reply("short"))
        pro.enqueue(Step.Reply("thorough"))
        viewModel.send("Tell me about tides")
        viewModel.retryWithPro()
        assertEquals(listOf("Tell me about tides", "thorough"), bubbles.map { it.text })
        assertEquals(Tier.PRO, bubbles.last().tier)
    }

    @Test
    fun `escalation shows its progress and the final tier`() {
        base.enqueue(Step.Fail(IllegalStateException("out of memory")))
        pro.enqueue(Step.Reply("Answer from Pro"))
        viewModel.send("Hi")
        assertEquals("Answer from Pro", bubbles.last().text)
        assertEquals(Tier.PRO, bubbles.last().tier)
        assertNull(bubbles.last().note)
        assertEquals("the turn itself succeeded", 0, viewModel.state.value.failures)
    }

    @Test
    fun `with no model ready the user is told where to fix it`() {
        device = DeviceState(online = true, availableTiers = emptySet())
        viewModel.send("Hi")
        assertTrue(bubbles.last().failed)
        assertTrue(bubbles.last().text.contains("Settings"))
        assertEquals(1, viewModel.state.value.failures)
    }

    @Test
    fun `the saved conversation is shown when the screen opens`() {
        val store = InMemoryTranscriptStore(listOf(ChatMessage.User("Remember milk"), ChatMessage.Model("Noted.")))
        val brains = mapOf(Tier.BASE to base)
        val reopened = ChatViewModel(
            ChatSession(Agent(Router(), BrainProvider { brains[it] }, ToolBroker(emptyList(), ApprovalCenter())), store),
            device = { device },
        )
        assertEquals(listOf("Remember milk", "Noted."), reopened.state.value.bubbles.map { it.text })
        assertFalse(reopened.state.value.busy)
    }

    @Serializable
    data class Invite(val title: String, val attendees: List<String>)

    @Serializable
    data class Created(val id: String)

    private class InviteTool : Tool<Invite, Created> {
        var created = 0
        override val name = "calendar_create_event"
        override val description = "Create an event"
        override val input = Invite.serializer()
        override val output = Created.serializer()
        override val dataClasses = setOf(DataClass.PERSONAL)
        override val untrustedContent: UntrustedContent? = null
        override fun risk(input: Invite) = RiskTier.WRITE_EXTERNAL
        override fun describe(input: Invite) = "Create \"${input.title}\" and invite ${input.attendees.joinToString()}"
        override suspend fun invoke(input: Invite, context: ToolContext): Created = Created("e${++created}")
    }

    @Test
    fun `an invite waits for approval, shows the exact payload, and runs once approved`() {
        val tool = InviteTool()
        val center = ApprovalCenter()
        val brains = mapOf(Tier.BASE to base, Tier.PRO to pro)
        val scheduling = ChatViewModel(
            ChatSession(Agent(Router(), BrainProvider { brains[it] }, ToolBroker(listOf(tool), center)), toolsFor = { listOf(tool.name) }),
            device = { device },
            approvals = center,
        )
        val call = ToolCall("calendar_create_event", buildJsonObject {
            put("title", "Design review")
            putJsonArray("attendees") { add(JsonPrimitive("maya@example.com")) }
        })
        pro.enqueue(Step.Reply(listOf(""), toolCalls = listOf(call)), Step.Reply("Invite sent."))
        scheduling.send("Schedule a design review with Maya")

        val approval = scheduling.state.value.approval!!
        assertEquals("Create \"Design review\" and invite maya@example.com", approval.summary)
        assertEquals(listOf("title: Design review", "attendees: maya@example.com"), payloadLines(approval.payload))
        assertEquals(listOf("title: x"), payloadLines(buildJsonObject { put("title", "x"); put("location", kotlinx.serialization.json.JsonNull) }))
        val order = kotlinx.serialization.json.Json.parseToJsonElement("""{"store":"shoes.example.com","items":[{"item_id":"shoe_10","quantity":1},{"item_id":"sock_m","quantity":2}]}""")
        assertEquals(
            listOf("store: shoes.example.com", "items: item_id: shoe_10, quantity: 1; item_id: sock_m, quantity: 2"),
            payloadLines(order as kotlinx.serialization.json.JsonObject),
        )
        assertEquals(0, tool.created)

        scheduling.approve(approval.id)
        assertEquals(1, tool.created)
        assertNull(scheduling.state.value.approval)
        assertEquals("Invite sent.", scheduling.state.value.bubbles.last().text)
    }

    @Test
    fun `stop cancels the running answer`() {
        base.enqueue(Step.Hang(listOf("Once upon")))
        viewModel.send("Tell me a story")
        assertTrue(viewModel.state.value.busy)
        viewModel.stop()
        assertEquals("Stopped", bubbles.last().note)
        assertFalse(viewModel.state.value.busy)
        assertEquals(1, base.cancellations)
    }

    /** A recorder that records [seconds] of made-up audio, then finishes, cut short when [interrupted]; or fails with [error]. */
    private class FakeRecorder(private val seconds: Int = 4, private val interrupted: Boolean = false, private val error: String? = null) : VoiceRecorder {
        var finishes = 0
        private val stop = kotlinx.coroutines.CompletableDeferred<Unit>()

        override fun record(maxSeconds: Int): kotlinx.coroutines.flow.Flow<RecordingEvent> = kotlinx.coroutines.flow.flow {
            if (error != null) {
                emit(RecordingEvent.Error(error))
                return@flow
            }
            emit(RecordingEvent.Level(0.5f, seconds))
            if (!interrupted) stop.await()
            emit(RecordingEvent.Finished(Recording(byteArrayOf(1, 2), seconds, interrupted = interrupted)))
        }

        override fun finish() {
            finishes++
            stop.complete(Unit)
        }
    }

    private class FakeTranscriber(private val words: List<String>, private val error: Exception? = null) : Transcriber {
        override fun available() = true

        override fun transcribe(recording: Recording): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
            error?.let { throw it }
            words.forEach { emit(it) }
        }
    }

    private fun voiceChat(recorder: VoiceRecorder, transcriber: Transcriber, hears: Boolean = true): ChatViewModel {
        val brains = mapOf(Tier.BASE to base, Tier.PRO to pro)
        val session = ChatSession(Agent(Router(), BrainProvider { brains[it] }, ToolBroker(emptyList(), ApprovalCenter())), transcriber = transcriber)
        return ChatViewModel(session, device = { device }, recorder = recorder, hears = { hears })
    }

    @Test
    fun `a voice note records with a level and a time, Stop keeps it for the message, and its words show in your turn`() {
        val recorder = FakeRecorder(seconds = 4)
        val chat = voiceChat(recorder, FakeTranscriber(listOf("Call Maya", " on Thursday.")))
        chat.startRecording()
        assertEquals(4, chat.state.value.recordingSeconds)
        assertEquals(VoicePhase.LISTENING, chat.state.value.voice)
        chat.finishRecording()
        assertNull(chat.state.value.recordingSeconds)
        assertEquals(4, chat.state.value.recording?.seconds)

        pro.enqueue(Step.Reply("I'll add that."))
        chat.send("")
        val state = chat.state.value
        assertNull("the recording left the composer", state.recording)
        val clip = state.bubbles.first { it.fromUser }.voice!!
        assertEquals("Call Maya on Thursday.", clip.words)
        assertFalse(clip.writing)
        assertNull("the recording is let go once written down", clip.recording)
        assertEquals("I'll add that.", state.bubbles.last().text)
        assertEquals("Writing it down", state.bubbles.last().details?.let(::speedRows)?.first()?.first)
    }

    @Test
    fun `cancelling throws the recording away, and without a model that can hear none starts`() {
        val chat = voiceChat(FakeRecorder(), FakeTranscriber(emptyList()))
        chat.startRecording()
        chat.cancelRecording()
        assertNull(chat.state.value.recordingSeconds)
        assertNull(chat.state.value.recording)

        val deaf = voiceChat(FakeRecorder(), FakeTranscriber(emptyList()), hears = false)
        deaf.startRecording()
        assertNull(deaf.state.value.recordingSeconds)
        assertEquals(ChatSession.CANT_HEAR, deaf.state.value.attachmentNote)
    }

    @Test
    fun `a recording cut short is kept and says so, and a busy microphone is reported`() {
        val cut = voiceChat(FakeRecorder(seconds = 7, interrupted = true), FakeTranscriber(emptyList()))
        cut.startRecording()
        assertEquals(7, cut.state.value.recording?.seconds)
        assertEquals("The recording stopped early. What was recorded is kept.", cut.state.value.attachmentNote)

        val busy = voiceChat(FakeRecorder(error = "The microphone is in use by another app."), FakeTranscriber(emptyList()))
        busy.startRecording()
        assertNull(busy.state.value.recording)
        assertEquals("The microphone is in use by another app.", busy.state.value.attachmentNote)
    }

    @Test
    fun `a recording that can't be written down goes back to the composer, and one with no words doesn't`() {
        val broken = voiceChat(FakeRecorder(), FakeTranscriber(emptyList(), IllegalStateException("decoder")))
        broken.attachRecording(Recording(byteArrayOf(1), seconds = 12, shared = true))
        broken.send("")
        assertEquals(ChatSession.COULDNT_HEAR, broken.state.value.bubbles.last().text)
        assertEquals("the same recording can be sent again", 12, broken.state.value.recording?.seconds)

        val silent = voiceChat(FakeRecorder(), FakeTranscriber(listOf(" ")))
        silent.attachRecording(Recording(byteArrayOf(1), seconds = 3))
        silent.send("")
        assertEquals(ChatSession.NOTHING_SAID, silent.state.value.bubbles.last().text)
        assertNull(silent.state.value.recording)
        assertEquals("the turn shows it wasn't written down", VoiceClip(seconds = 3), silent.state.value.bubbles.first().voice)
    }

    @Test
    fun `a saved voice note comes back with its words, and Retry sends them without the recording`() {
        val saved = listOf(
            ChatMessage.User("", voiceNote = VoiceNote("Buy oat milk.", seconds = 3)),
            ChatMessage.Model("Noted: oat milk."),
        )
        val brains = mapOf(Tier.BASE to base, Tier.PRO to pro)
        val session = ChatSession(Agent(Router(), BrainProvider { brains[it] }, ToolBroker(emptyList(), ApprovalCenter())), InMemoryTranscriptStore(saved))
        val chat = ChatViewModel(session, device = { device })
        assertEquals(VoiceClip(seconds = 3, words = "Buy oat milk."), chat.state.value.bubbles.first().voice)
        pro.enqueue(Step.Reply("Added oat milk to your list."))
        chat.retryWithPro()
        val sent = pro.requests.single().transcript.first() as ChatMessage.User
        assertEquals(VoiceNote("Buy oat milk.", seconds = 3), sent.voiceNote)
    }

    @Test
    fun `a voice note's label says what it is, how long, and whether it stopped early`() {
        assertEquals("Voice note · 0:17", voiceLabel(VoiceClip(17)))
        assertEquals("Shared recording · 1:02 · Stopped early", voiceLabel(VoiceClip(62, shared = true, interrupted = true)))
    }

    private class FakeScreens(private val look: ScreenLook) : ScreenSource {
        var asked = 0

        override fun offered() = true

        override suspend fun screen(): ScreenLook {
            asked++
            return look
        }
    }

    private fun cardChat(screens: ScreenSource): ChatViewModel {
        val brains = mapOf(Tier.BASE to base, Tier.PRO to pro)
        val session = ChatSession(Agent(Router(), BrainProvider { brains[it] }, ToolBroker(emptyList(), ApprovalCenter())))
        return ChatViewModel(session, device = { device }, screens = screens)
    }

    @Test
    fun `a pointing question from the card reads the screen, and other questions don't`() {
        val screens = FakeScreens(ScreenLook.Ready(io.github.sandeepvijayarao09.anu.core.model.Screen("Messages", 5, byteArrayOf(1))))
        val card = cardChat(screens)
        assertTrue(card.state.value.screenOffered)
        base.enqueue(Step.Reply("It's 12:30."))
        card.send("What time is it in Tokyo?")
        assertEquals(0, screens.asked)

        pro.enqueue(Step.Reply("Maya invites you to lunch."))
        base.enqueue(Step.Reply("Maya invites you to lunch."))
        card.send("What's this?")
        assertEquals(1, screens.asked)
        assertEquals("Messages", card.state.value.bubbles.last { it.fromUser }.screenApp)
        assertNull("the reading line goes once the answer is done", card.state.value.readingApp)
    }

    @Test
    fun `Use this screen sends the screen with any question, once`() {
        val screens = FakeScreens(ScreenLook.Ready(io.github.sandeepvijayarao09.anu.core.model.Screen("Chrome", 5, byteArrayOf(1))))
        val card = cardChat(screens)
        card.toggleUseScreen()
        assertTrue(card.state.value.useScreen)
        base.enqueue(Step.Reply("A recipe for soup."))
        pro.enqueue(Step.Reply("A recipe for soup."))
        card.send("Is it vegetarian?")
        assertEquals(1, screens.asked)
        assertFalse(card.state.value.useScreen)
    }

    @Test
    fun `a screen that can't be read is said so, and nothing is asked without it`() {
        val card = cardChat(FakeScreens(ScreenLook.Unavailable("Anu can't see this screen.")))
        card.send("What's this?")
        val answer = card.state.value.bubbles.last()
        assertEquals("Anu can't see this screen.", answer.text)
        assertTrue(answer.failed)
        assertTrue("no model was asked", base.requests.isEmpty() && pro.requests.isEmpty())
    }
}
