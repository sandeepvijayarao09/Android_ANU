package io.github.sandeepvijayarao09.anu.feature.home

import io.github.sandeepvijayarao09.anu.core.agent.Agent
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalCenter
import io.github.sandeepvijayarao09.anu.core.agent.BrainProvider
import io.github.sandeepvijayarao09.anu.core.agent.ChatSession
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain.Step
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Tier
import io.github.sandeepvijayarao09.anu.core.voice.SpeechEvent
import io.github.sandeepvijayarao09.anu.core.voice.SpeechInput
import io.github.sandeepvijayarao09.anu.core.voice.SpeechOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceChatViewModelTest {

    private class FakeSpeechIn(var events: List<SpeechEvent>, override var available: Boolean = true) : SpeechInput {
        var listens = 0
        override fun listen(): Flow<SpeechEvent> = flow {
            listens++
            events.forEach { emit(it) }
        }
    }

    private class FakeSpeechOut : SpeechOutput {
        val spoken = mutableListOf<String>()
        var stops = 0
        override val speaking = MutableStateFlow(false)
        override fun speak(sentence: String) {
            spoken += sentence
            speaking.value = true
        }

        override fun stop() {
            stops++
            speaking.value = false
        }
    }

    private val base = ScriptedBrain(BrainId.BASE)
    private val device = DeviceState(online = true, availableTiers = setOf(Tier.BASE))
    private val ears = FakeSpeechIn(emptyList())
    private val mouth = FakeSpeechOut()
    private lateinit var chat: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val session = ChatSession(Agent(Router(), BrainProvider { if (it == Tier.BASE) base else null }, ToolBroker(emptyList(), ApprovalCenter())))
        chat = ChatViewModel(session, device = { device }, speechIn = ears, speechOut = mouth)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val state get() = chat.state.value

    @Test
    fun `a spoken request is sent, and its answer is read aloud sentence by sentence`() {
        ears.events = listOf(SpeechEvent.Listening, SpeechEvent.Level(0.6f), SpeechEvent.Partial("What's the"), SpeechEvent.Final("What's the weather like?"))
        base.enqueue(Step.Reply(listOf("It's **sunny** ", "today. Enjoy", " your day!")))
        chat.toggleListening()
        assertEquals(listOf("What's the weather like?", "It's **sunny** today. Enjoy your day!"), state.bubbles.map { it.text })
        assertEquals(listOf("It's sunny today.", "Enjoy your day!"), mouth.spoken)
        assertEquals("What's the weather like?", state.heard)
        assertFalse(state.listening)
        assertEquals(VoicePhase.SPEAKING, state.voice)
    }

    @Test
    fun `typed turns stay silent, and mute keeps spoken ones silent too`() {
        base.enqueue(Step.Reply("Hello there."), Step.Reply("Still here."))
        chat.send("Hi")
        assertTrue(mouth.spoken.isEmpty())
        chat.toggleMute()
        ears.events = listOf(SpeechEvent.Final("Are you there?"))
        chat.toggleListening()
        assertTrue(mouth.spoken.isEmpty())
        assertTrue(state.muted)
    }

    @Test
    fun `tapping the orb while Anu talks stops the speech`() {
        mouth.speaking.value = true
        ears.events = listOf(SpeechEvent.Error("Didn't catch that.", heardNothing = true))
        chat.toggleListening()
        assertEquals(1, mouth.stops)
    }

    @Test
    fun `a quiet microphone says so, and nothing is sent`() {
        ears.events = listOf(SpeechEvent.Listening, SpeechEvent.Error("Didn't catch that.", heardNothing = true))
        chat.toggleListening()
        assertEquals("Didn't catch that.", state.voiceProblem)
        assertTrue(state.bubbles.isEmpty())
        assertEquals(VoicePhase.IDLE, state.voice)
    }

    @Test
    fun `without on-device recognition Anu doesn't listen, and says why`() {
        ears.available = false
        chat.toggleListening()
        assertEquals(0, ears.listens)
        assertFalse(state.voiceAvailable)
        assertEquals("This phone has no on-device speech recognition.", state.voiceProblem)
    }

    @Test
    fun `a request can be queued to send once the conversation has loaded`() {
        base.enqueue(Step.Reply("Your day is clear."))
        chat.sendWhenReady("What's on today?")
        assertEquals(listOf("What's on today?", "Your day is clear."), state.bubbles.map { it.text })
        assertEquals(listOf("Your day is clear."), mouth.spoken)
    }
}
