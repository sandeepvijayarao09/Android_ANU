package io.github.sandeepvijayarao09.anu.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sandeepvijayarao09.anu.core.agent.ActionRecord
import io.github.sandeepvijayarao09.anu.core.agent.AgentEvent
import io.github.sandeepvijayarao09.anu.core.agent.AnswerDetails
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalCenter
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalDecision
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalRequest
import io.github.sandeepvijayarao09.anu.core.agent.ToolOutcome
import io.github.sandeepvijayarao09.anu.core.agent.ChatSession
import io.github.sandeepvijayarao09.anu.core.agent.PointingWords
import io.github.sandeepvijayarao09.anu.core.model.ReplyWords
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainStatus
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.Document
import io.github.sandeepvijayarao09.anu.core.model.Pdf
import io.github.sandeepvijayarao09.anu.core.model.Photo
import io.github.sandeepvijayarao09.anu.core.model.Recording
import io.github.sandeepvijayarao09.anu.core.model.VoiceNote
import io.github.sandeepvijayarao09.anu.core.router.DeviceState
import io.github.sandeepvijayarao09.anu.core.router.Guard
import io.github.sandeepvijayarao09.anu.core.router.Tier
import io.github.sandeepvijayarao09.anu.core.voice.RecordingEvent
import io.github.sandeepvijayarao09.anu.core.voice.Sentences
import io.github.sandeepvijayarao09.anu.core.voice.SpeechEvent
import io.github.sandeepvijayarao09.anu.core.voice.SpeechInput
import io.github.sandeepvijayarao09.anu.core.voice.SpeechOutput
import io.github.sandeepvijayarao09.anu.core.voice.VoiceRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject

/** A reply drafted about the screen (EXP-2, ADR-092): for [chat] when Anu knows it, and sendable while [canSend]. */
data class ReplyDraft(val chat: ScreenChat?, val canSend: Boolean = false)

data class Bubble(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    /** Photos the user attached (G2): with their bytes while this screen has them, else by id. */
    val photos: List<Photo> = emptyList(),
    val tier: Tier? = null,
    /** Status shown under the text, for example "Trying Pro…". */
    val note: String? = null,
    val streaming: Boolean = false,
    val failed: Boolean = false,
    /** A note from Anu instead of an answer, such as "Nothing was said in this recording.": shown quietly, not as a failure (ADR-090). */
    val quiet: Boolean = false,
    /** An answer that is a reply to the chat on the screen, to copy or send (EXP-2, ADR-092). */
    val draft: ReplyDraft? = null,
    /** What really happened, from the Tool Broker's record, never from the model's words (ADR-027). */
    val actions: List<Receipt> = emptyList(),
    /** Links a tool offered, such as a store's checkout page. Shown as buttons; only the user opens them (ADR-047). */
    val links: List<LinkAction> = emptyList(),
    /** What "About this answer" shows, for a completed answer (ADR-071). */
    val details: AnswerDetails? = null,
    /** The phone's guard when this turn was routed, and whether it changed the answer (ADR-072). */
    val guard: Guard? = null,
    val guarded: Boolean = false,
    /** In the saved chat: a completed or restored answer. A failed, stopped or declined turn isn't saved. */
    val saved: Boolean = false,
    /** [note] names what the model is doing before the first word (design review D10). */
    val waiting: Boolean = false,
    /** The model's reasoning before its answer, while this screen has it (G4, ADR-077). Never saved in the chat. */
    val thought: String = "",
    /** A voice note in the user's turn (G3, EXP-3, ADR-078). */
    val voice: VoiceClip? = null,
    /** An answer waiting while its turn's voice note is written down. */
    val hearing: Boolean = false,
    /** The app whose screen the user asked about in this turn (EXP-2, ADR-083). Its picture is never kept here. */
    val screenApp: String? = null,
    /** A PDF in the user's turn (ADR-095). */
    val document: DocumentClip? = null,
    /** An answer waiting while its turn's PDF is read: what the line says, such as "Reading Lease.pdf… page 7 of 20". */
    val reading: String? = null,
)

/**
 * A PDF as the thread shows it (ADR-095): its name and size, and once it's read, the [document] the turn kept, for a
 * retry to send as it is. The PDF itself is never kept.
 */
data class DocumentClip(val name: String, val pages: Int, val document: Document? = null)

/**
 * A voice note as the thread shows it (ADR-078): how long it is, and its [words], which grow while [writing]. The
 * [recording] stays on this screen only until its words are written down, so it can go back to the composer if that
 * fails or is stopped. It's never saved.
 */
data class VoiceClip(
    val seconds: Int,
    val shared: Boolean = false,
    val interrupted: Boolean = false,
    val words: String = "",
    val writing: Boolean = false,
    val recording: Recording? = null,
) {
    /** The words as a voice note, once written down, for a retry to send as they are. */
    val note: VoiceNote? get() = if (writing || words.isEmpty()) null else VoiceNote(words, seconds, shared, interrupted)
}

data class LinkAction(val label: String, val url: String)

/**
 * One action's receipt, written from the Tool Broker's record (ADR-027): the tool's own summary and what
 * happened, never the model's words. The screen shows it with the tool's icon and where it happened.
 */
data class Receipt(val tool: String, val summary: String, val status: Status) {
    enum class Status { DONE, NOT_DONE, FAILED }

    /** Where it happened: tools that talk to the outside leave the phone (Gmail, replies, services, stores). */
    val leftPhone: Boolean
        get() = tool.startsWith("email_") || tool.startsWith("mcp_") || tool.startsWith("shop_") || tool == "messages_reply"

    /** The kind of thing it touched, for its icon and its "Calendar · on device" line. */
    val family: String
        get() = when {
            tool.startsWith("calendar_") -> "Calendar"
            tool.startsWith("contacts_") -> "Contacts"
            tool.startsWith("email_") -> "Gmail"
            tool.startsWith("messages_") -> "Messages"
            tool.startsWith("sms_") -> "Text messages"
            tool.startsWith("memory_") -> "Memory"
            tool.startsWith("shop_") -> "Store"
            tool.startsWith("mcp_") -> "Service"
            tool.startsWith("phone_") -> "Phone"
            tool.startsWith("notes_") -> "Notes"
            else -> "Anu"
        }
}

data class ChatUiState(
    val bubbles: List<Bubble> = emptyList(),
    val busy: Boolean = false,
    /** Set when a message needs cloud consent (§12.2). Holds the text waiting to be sent. */
    val consentFor: String? = null,
    /** "About this answer", while its sheet is open (ADR-071). */
    val about: AboutAnswer? = null,
    /** The facts from memory the cloud would also see, listed on the consent sheet (ADR-054). */
    val consentFacts: List<String> = emptyList(),
    /** Whether Gemini could be asked right now, for the choices under a guarded answer (design review D21). */
    val cloudAllowed: Boolean = false,
    /** Photos picked or taken for the next message, as JPEGs in the order added, at most [Photo.MAX_IN_VIEW] (ADR-094). */
    val attachments: List<ByteArray> = emptyList(),
    /** A voice note recorded or shared for the next message, not yet written down (G3, ADR-078). */
    val recording: Recording? = null,
    /** A PDF picked or shared for the next message, read on the phone but not yet for a question (ADR-095). */
    val pdf: Pdf? = null,
    /** How long the voice note being recorded has run, or null when none is. */
    val recordingSeconds: Int? = null,
    /** Why a photo or a recording couldn't be used, or what happened to it, such as "This photo can't be read." */
    val attachmentNote: String? = null,
    /** The assistant card was handed the screen below it, so it can offer "Use this screen" (EXP-2, ADR-083). */
    val screenOffered: Boolean = false,
    /** The user tapped "Use this screen" for the next question. */
    val useScreen: Boolean = false,
    /** Why the card wasn't handed the screen when the user asked for it, from the Quick Settings tile (ADR-108). */
    val screenNote: String? = null,
    /** The app whose screen is being read for the running answer, for the card's "Reading <app>" line. */
    val readingApp: String? = null,
    /** The oldest action waiting for the user's approval (I2). */
    val approval: ApprovalRequest? = null,
    /** True once the chat is longer than the model's view, so older messages are left out (ADR-040). */
    val trimmed: Boolean = false,
    /** Voice (ADR-043): whether Anu is listening, and what it has heard so far. */
    val listening: Boolean = false,
    val heard: String = "",
    /** How loud the user is, from 0 to 1, for the orb. */
    val level: Float = 0f,
    val speaking: Boolean = false,
    val muted: Boolean = false,
    /**
     * Live talk (ADR-101): after each spoken answer Anu listens again by itself, until the user ends it, leaves Anu
     * where it can't go on behind other apps (ADR-108), or [LIVE_MISSES] listens in a row hear nothing.
     */
    val live: Boolean = false,
    /** Why voice didn't work this time, such as "Didn't catch that." */
    val voiceProblem: String? = null,
    /** The on-device recognizer needs this language's model; the user can choose to get it. */
    val offerLanguagePack: Boolean = false,
    /** False when the phone has no on-device speech recognition. */
    val voiceAvailable: Boolean = false,
    /**
     * Turns that failed since this screen opened: each one briefly greys the sphere (DESIGN.md, Error).
     * Declining the cloud isn't a failure, and neither is a tier that fails before a stronger one answers.
     */
    val failures: Int = 0,
) {
    /** What the orb shows. */
    val voice: VoicePhase
        get() = when {
            listening || recordingSeconds != null -> VoicePhase.LISTENING
            speaking -> VoicePhase.SPEAKING
            busy -> VoicePhase.THINKING
            else -> VoicePhase.IDLE
        }
}

enum class VoicePhase { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * The conversation's state, typed or spoken. It turns cascade events into bubbles and owns the one
 * running request. With voice (ADR-043), a spoken turn's answer is read aloud sentence by sentence
 * as it streams.
 */
class ChatViewModel(
    private val session: ChatSession,
    private val device: () -> DeviceState,
    private val approvals: ApprovalCenter = ApprovalCenter(),
    private val speechIn: SpeechInput? = null,
    private val speechOut: SpeechOutput? = null,
    /** The text of memory's current facts, by id, for "About this answer". Deleted or changed ones are missing (ADR-071). */
    private val facts: suspend (ids: List<Long>) -> Map<Long, String> = { emptyMap() },
    /** What the on-device models are doing, so a wait can say why (design review D10, ADR-074). */
    private val brainStatus: StateFlow<BrainStatus>? = null,
    /** Warm start (EXP-1): loads the likely model when the user starts typing. */
    private val warm: () -> Unit = {},
    /** How long a wait lasts before its cause is named. */
    private val waitNoteDelayMs: Long = 1_000,
    /** Records voice notes (G3, EXP-3). Null where there's no microphone to use. */
    private val recorder: VoiceRecorder? = null,
    /** Whether an installed model can write a voice note down (ADR-078). */
    private val hears: () -> Boolean = { false },
    /** The screen under the assistant card (EXP-2, ADR-083). Only the card has one. */
    private val screens: ScreenSource? = null,
    /** Whether a chat can still be answered from Anu, for a reply drafted on the card (ADR-092). */
    private val replies: ReplyTargets? = null,
    /** Whether live talk can go on behind other apps (ADR-108). Without it, leaving Anu ends live talk. */
    private val behind: LiveBehind? = null,
) : ViewModel() {

    private val mutable = MutableStateFlow(
        ChatUiState(
            voiceAvailable = speechIn?.available == true, cloudAllowed = device().cloudAllowed, screenOffered = screens?.offered() == true,
            screenNote = screens?.note(),
        ),
    )
    val state: StateFlow<ChatUiState> = mutable.asStateFlow()

    private var running: Job? = null
    private var listening: Job? = null
    private var recording: Job? = null
    private var nextId = 0L

    /** The answer a consent sheet would replace, when "Ask Gemini" opened it. Nothing is taken back unless approved. */
    private var consentReplaces: Bubble? = null
    private val loaded = CompletableDeferred<Unit>()

    /** Whether the running turn is read aloud, and how much of its answer has been. */
    private var speakTurn = false
    private var spokenUpTo = 0

    /** Listens in a row that heard nothing during live talk. */
    private var liveMisses = 0

    /**
     * Counts finished listens, so live talk listens again even after one that ended at once, such as a busy recognizer's
     * error: then "listening" turns on and off with no pause, and the state alone shows no change.
     */
    private val listensEnded = MutableStateFlow(0)

    init {
        speechOut?.let { out -> viewModelScope.launch { out.speaking.collect { speaking -> mutable.update { it.copy(speaking = speaking) } } } }
        viewModelScope.launch { approvals.requests.collect { pending -> mutable.update { it.copy(approval = pending.firstOrNull()) } } }
        // Live talk (ADR-101): whenever Anu is free again (not thinking, not speaking, nothing to approve), it listens.
        viewModelScope.launch {
            combine(mutable, listensEnded) { it, ended ->
                val free = it.live && !it.busy && !it.speaking && !it.listening && it.approval == null && it.consentFor == null && it.recordingSeconds == null
                if (free) ended else -1
            }
                .distinctUntilChanged()
                .collectLatest { free ->
                    if (free < 0) return@collectLatest
                    delay(LIVE_PAUSE_MS)
                    listen(live = true)
                }
        }
        viewModelScope.launch { session.context.collect { view -> mutable.update { it.copy(trimmed = view.trimmed) } } }
        // After a second with no word, the answer names what the model is doing (design review D10).
        brainStatus?.let { status ->
            viewModelScope.launch {
                combine(status, mutable.map { it.busy }.distinctUntilChanged()) { now, busy -> now to busy }.collectLatest { (now, busy) ->
                    val cause = waitCause(now)
                    if (!busy || cause == null) {
                        clearWaitCause()
                    } else {
                        delay(waitNoteDelayMs)
                        showWaitCause(cause)
                    }
                }
            }
        }
        // Restore the saved conversation (§14). Sending waits until it has loaded.
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val restored = saved()
                mutable.update { it.copy(bubbles = restored + it.bubbles) }
            } finally {
                mutable.update { it.copy(busy = false) }
                loaded.complete(Unit)
            }
        }
    }

    /** The user started typing: the model can load while they write (EXP-1). */
    fun typingStarted() = warm()

    /**
     * Photos for the next message (G2, ADR-094), added after any already there. Adding one also warms the model, which
     * reads them on the phone. Past [Photo.MAX_IN_VIEW] the rest are left out, and the composer says so.
     */
    fun attach(vararg jpegs: ByteArray) {
        if (jpegs.isEmpty()) return
        mutable.update { state ->
            val room = Photo.MAX_IN_VIEW - state.attachments.size
            state.copy(
                attachments = state.attachments + jpegs.take(room.coerceAtLeast(0)),
                attachmentNote = if (jpegs.size > room) FULL else null,
            )
        }
        warm()
    }

    /**
     * A frame from live camera (ADR-111), with what its detector found in it ("cup 91%, donut 90%"), which the model
     * reads beside the picture.
     */
    fun attachFrame(jpeg: ByteArray, found: String) {
        foundIn[jpeg] = found
        attach(jpeg)
    }

    /** What live camera's detector found in the composer's frames, by the frame itself (each array is one photo). */
    private val foundIn = java.util.IdentityHashMap<ByteArray, String>()

    /** Takes back the photo at [index] in the composer. */
    fun removeAttachment(index: Int) = mutable.update { state ->
        state.copy(attachments = state.attachments.filterIndexed { i, _ -> i != index }, attachmentNote = state.attachmentNote.takeIf { it != FULL })
    }

    /** How many more photos the next message can take: a scanned PDF's pages count too (ADR-095). */
    val photosLeft: Int get() = mutable.value.let { Photo.MAX_IN_VIEW - it.attachments.size - (it.pdf?.pictures?.size ?: 0) }

    /**
     * A PDF for the next message (ADR-095), in place of any before it. A scan's pages are pictures, so only as many
     * as there's room for go, and the composer says when that's none. Adding one also warms the model.
     */
    fun attachPdf(pdf: Pdf) {
        mutable.update { state ->
            val room = Photo.MAX_IN_VIEW - state.attachments.size
            when {
                pdf.hasText -> state.copy(pdf = pdf, attachmentNote = null)
                room <= 0 -> state.copy(attachmentNote = SCAN_NO_ROOM)
                else -> state.copy(pdf = Pdf(pdf.name, pdf.pages, pdf.texts, pdf.pictures.take(room)), attachmentNote = null)
            }
        }
        warm()
    }

    fun removePdf() = mutable.update { it.copy(pdf = null) }

    /** A picked or shared PDF that can't be used, and why. */
    fun pdfUnusable(note: String) = mutable.update { it.copy(attachmentNote = note) }

    /** The system camera couldn't be opened. */
    fun cameraUnavailable() = mutable.update { it.copy(attachmentNote = "No camera app on this phone can take a photo for Anu.") }

    /** "Use this screen": the next question goes with the screen under the card, whatever its words (design review D13). */
    fun toggleUseScreen() = mutable.update { it.copy(useScreen = !it.useScreen) }

    /** The Quick Settings tile asked about the screen (ADR-108): the next question uses it, when the card was handed one. */
    fun useScreen() = mutable.update { it.copy(useScreen = it.screenOffered) }

    /** Whether a voice note could be written down now, for the "+" sheet (ADR-078). */
    fun canHear(): Boolean = hears()

    /**
     * Starts a voice note (G3, EXP-3): the sphere and the level bars follow the voice, and Stop keeps it for the next
     * message, where words can go with it. It stops by itself at [VOICE_NOTE_SECONDS].
     */
    fun startRecording() {
        val input = recorder ?: return
        val state = mutable.value
        if (state.busy || state.listening || recording?.isActive == true) return
        if (!hears()) {
            mutable.update { it.copy(attachmentNote = ChatSession.CANT_HEAR) }
            return
        }
        speechOut?.stop()
        warm()
        recording = viewModelScope.launch {
            mutable.update { it.copy(recordingSeconds = 0, level = 0f, attachmentNote = null) }
            try {
                input.record(VOICE_NOTE_SECONDS).collect { event ->
                    when (event) {
                        is RecordingEvent.Level -> mutable.update { it.copy(level = event.value, recordingSeconds = event.seconds) }
                        is RecordingEvent.Finished -> recorded(event.recording)
                        is RecordingEvent.Error -> mutable.update { it.copy(attachmentNote = event.reason) }
                    }
                }
            } finally {
                mutable.update { it.copy(recordingSeconds = null, level = 0f) }
            }
        }
    }

    /** Stop: keeps the voice note for the next message. */
    fun finishRecording() {
        recorder?.finish()
    }

    /** Cancel: throws the voice note away. */
    fun cancelRecording() {
        recording?.cancel()
    }

    /**
     * Anu left the screen: a voice note ends there, kept and marked as stopped early. Android keeps the microphone
     * open in the background for an app with a foreground service, such as "Hey Anu"'s, so Anu doesn't wait for it to.
     * Live talk goes on behind the other app only where [behind] shows a control the user can see and stop (ADR-108);
     * otherwise it ends here, and says why when it could have gone on.
     */
    fun leftAnu() {
        if (recording?.isActive == true) recorder?.cutShort()
        if (!mutable.value.live) return
        val blocked = behind?.let { it.blocked() ?: return }
        endLive()
        blocked?.let { why -> mutable.update { it.copy(voiceProblem = why) } }
    }

    /** Ends live talk: stops listening and speaking, and doesn't listen again. */
    fun endLive() {
        mutable.update { it.copy(live = false) }
        listening?.cancel()
        speechOut?.stop()
    }

    private fun recorded(voice: Recording) {
        val note = when {
            voice.seconds == 0 -> "Nothing was recorded."
            voice.interrupted -> "The recording stopped early. What was recorded is kept."
            voice.seconds >= VOICE_NOTE_SECONDS -> "Stopped at 5 minutes, the longest a voice note can be."
            else -> null
        }
        mutable.update { it.copy(recording = voice.takeIf { voice.seconds > 0 }, attachmentNote = note) }
    }

    /** A recording shared from another app, for the next message (ADR-078). It's someone else's, so only summarized unless asked. */
    fun attachRecording(voice: Recording) {
        mutable.update { it.copy(recording = voice, attachmentNote = null) }
        warm()
    }

    fun removeRecording() = mutable.update { it.copy(recording = null) }

    /** A shared recording that can't be used, and why. */
    fun recordingUnusable(note: String) = mutable.update { it.copy(attachmentNote = note) }

    fun photoUnreadable() = mutable.update { it.copy(attachmentNote = "This photo can't be read. Try another.") }

    fun dismissAttachmentNote() = mutable.update { it.copy(attachmentNote = null) }

    /** A kept photo's bytes, for showing it, or null once it's no longer kept (ADR-075). */
    suspend fun photo(id: Long): ByteArray? = session.photo(id)

    private fun showWaitCause(cause: String) = mutable.update { state ->
        state.copy(bubbles = state.bubbles.map { if (!it.fromUser && it.streaming && it.text.isEmpty()) it.copy(note = cause, waiting = true) else it })
    }

    private fun clearWaitCause() = mutable.update { state ->
        state.copy(bubbles = state.bubbles.map { if (it.waiting) it.copy(note = restingNote(it), waiting = false) else it })
    }

    /**
     * The note an answer keeps once a wait's cause is gone: writing down its voice note (ADR-078), or a guarded Pro
     * answer reading slowly (design review D22).
     */
    private fun restingNote(bubble: Bubble): String? = when {
        bubble.hearing -> WRITING_DOWN
        bubble.reading != null -> bubble.reading
        bubble.streaming && bubble.guarded && bubble.tier == Tier.PRO && bubble.guard == Guard.HEAT -> SLOW_WHILE_WARM
        else -> null
    }

    /** Sends [text]. A [spoken] turn's answer is read aloud, unless muted. */
    fun send(text: String, spoken: Boolean = false) {
        val trimmed = text.trim()
        val state = mutable.value
        val picked = state.attachments
        val voice = state.recording
        val pdf = state.pdf
        if ((trimmed.isEmpty() && picked.isEmpty() && voice == null && pdf == null) || state.busy || state.recordingSeconds != null) return
        speakTurn = spoken && !state.muted && speechOut != null
        spokenUpTo = 0
        val photos = picked.map { Photo(0, it, foundIn[it]) }
        foundIn.clear()
        val asked = nextId++
        val clip = voice?.let { VoiceClip(it.seconds, it.shared, it.interrupted, writing = true, recording = it) }
        // A question from the card about the screen below it: pointing words, or "Use this screen" (EXP-2, D13).
        val aboutScreen = screens != null && state.screenOffered && (state.useScreen || PointingWords.asks(trimmed))
        // A scan's pages show with the user's photos, as the model sees them (ADR-095).
        val shown = photos + pdf?.takeUnless { it.hasText }?.pictures.orEmpty().map { Photo(0, it) }
        add(Bubble(asked, fromUser = true, text = trimmed, photos = shown, voice = clip, document = pdf?.let { DocumentClip(it.name, it.pages) }))
        mutable.update { it.copy(attachments = emptyList(), recording = null, pdf = null, attachmentNote = null, useScreen = false) }
        run(trimmed, photos = photos, recording = voice, pdf = pdf, asked = asked, aboutScreen = aboutScreen, replying = aboutScreen && ReplyWords.asks(trimmed))
    }

    /**
     * Send… under a reply drafted on the card (EXP-2, ADR-092): the reply goes through the broker, whose approval sheet
     * shows it and names the chat before anything is sent. A chat that can't be answered anymore loses Send…; Copy stays.
     */
    fun sendReply(id: Long) {
        val state = mutable.value
        if (state.busy) return
        val draft = state.bubbles.firstOrNull { it.id == id } ?: return
        val chat = draft.draft?.chat ?: return
        if (replies?.canReply(chat.key) != true) {
            edit(id) { it.copy(draft = it.draft?.copy(canSend = false)) }
            return
        }
        val text = "Send this reply to ${chat.title}"
        val asked = nextId++
        add(Bubble(asked, fromUser = true, text = text))
        run(text, asked = asked, sending = ReplySend(chat.key, chat.title, draft.text))
    }

    /** Starts listening once the saved conversation has loaded, for the assistant overlay (ADR-044). */
    fun listenWhenReady() {
        viewModelScope.launch {
            loaded.await()
            if (!mutable.value.listening) listen(live = false)
        }
    }

    /**
     * Shows turns made on another screen, such as the assistant overlay, which shares this conversation.
     * Only while nothing is running here.
     */
    fun reloadIfIdle() {
        if (!loaded.isCompleted || mutable.value.busy || mutable.value.listening) return
        viewModelScope.launch {
            val saved = saved()
            val shown = mutable.value.bubbles.filterNot { it.failed && it.tier == null }.map { it.text to it.fromUser }
            if (saved.map { it.text to it.fromUser } == shown || mutable.value.busy) return@launch
            mutable.update { state -> state.copy(bubbles = saved) }
        }
    }

    /**
     * The saved conversation as bubbles. Each answer is the exchange's final reply, as on screen while it ran:
     * text written before calling tools isn't shown. It carries its model, its receipts and its details (ADR-071).
     */
    private suspend fun saved(): List<Bubble> {
        val answers = session.answerDetails()
        val out = mutableListOf<Bubble>()
        var asked = -1
        session.history().forEachIndexed { i, message ->
            when (message) {
                is ChatMessage.User -> {
                    asked = i
                    val voice = message.voiceNote?.let { VoiceClip(it.seconds, it.shared, it.interrupted, words = it.words) }
                    val document = message.document?.let { DocumentClip(it.name, it.pages, it) }
                    out += Bubble(nextId++, fromUser = true, text = message.text, photos = message.photos, saved = true, voice = voice, screenApp = message.screen?.app, document = document)
                }
                is ChatMessage.Model -> if (message.toolCalls.isEmpty() && message.text.isNotEmpty()) {
                    val details = answers[asked]
                    out += Bubble(
                        nextId++, fromUser = false, text = message.text, tier = details?.tier,
                        actions = details?.actions.orEmpty().map(::receipt), details = details, saved = true,
                    )
                }
                is ChatMessage.Tool -> Unit
            }
        }
        return out
    }

    /** Sends [text] once the saved conversation has loaded, for example what followed "Hey Anu". */
    fun sendWhenReady(text: String, spoken: Boolean = true) {
        viewModelScope.launch {
            loaded.await()
            send(text, spoken)
        }
    }

    /**
     * The orb and the Talk button: starts live talk, or ends it while listening, or stops Anu talking. While a turn runs,
     * a tap only stops the speech, because one request runs at a time; live talk then listens once the turn is done.
     */
    fun toggleListening() {
        if (mutable.value.listening) {
            endLive()
            return
        }
        listen(live = true)
    }

    /**
     * Listens once and sends what was heard, read aloud when it's answered. With [live], Anu listens again after each
     * answer (ADR-101); a listen that hears nothing counts, and [LIVE_MISSES] in a row end live talk.
     */
    private fun listen(live: Boolean) {
        val input = speechIn ?: return
        if (mutable.value.listening) return
        speechOut?.stop()
        if (mutable.value.busy) {
            if (live) mutable.update { it.copy(live = true) }
            return
        }
        if (!input.available) {
            mutable.update { it.copy(live = false, voiceAvailable = false, voiceProblem = "This phone has no on-device speech recognition.") }
            return
        }
        if (live && !mutable.value.live) liveMisses = 0
        listening = viewModelScope.launch {
            mutable.update { it.copy(listening = true, live = live, heard = "", level = 0f, voiceProblem = null, offerLanguagePack = false, voiceAvailable = true) }
            var heard = false
            try {
                input.listen().collect { event ->
                    when (event) {
                        SpeechEvent.Listening -> Unit
                        is SpeechEvent.Level -> mutable.update { it.copy(level = event.value) }
                        is SpeechEvent.Partial -> mutable.update { it.copy(heard = event.text) }
                        is SpeechEvent.Final -> {
                            heard = event.text.isNotBlank()
                            mutable.update { it.copy(heard = event.text, listening = false, level = 0f) }
                            send(event.text, spoken = true)
                        }
                        is SpeechEvent.Error -> mutable.update {
                            // A missing language pack won't fix itself by listening again.
                            it.copy(voiceProblem = event.reason, offerLanguagePack = event.needsLanguagePack, live = it.live && !event.needsLanguagePack)
                        }
                    }
                }
            } finally {
                liveMisses = if (heard) 0 else liveMisses + 1
                mutable.update {
                    val ended = it.live && liveMisses >= LIVE_MISSES
                    it.copy(listening = false, level = 0f, live = it.live && !ended, voiceProblem = if (ended) LIVE_ENDED else it.voiceProblem)
                }
                listensEnded.update { it + 1 }
            }
        }
    }

    /** The user chose to get the on-device speech model for their language. */
    fun downloadLanguagePack() {
        speechIn?.downloadLanguagePack()
        mutable.update { it.copy(offerLanguagePack = false, voiceProblem = "Getting on-device speech. Try again in a minute or two.") }
    }

    /** Stops or allows reading answers aloud. */
    fun toggleMute() {
        val muted = !mutable.value.muted
        if (muted) {
            speakTurn = false
            speechOut?.stop()
        }
        mutable.update { it.copy(muted = muted) }
    }

    /**
     * Answers the last message again on this phone, with Gemma 4 E4B (§6). While the phone is guarded, Pro gives a
     * shorter answer (ADR-072). The last answer stays until the new one is complete.
     */
    fun retryWithPro() = retry(Tier.PRO)

    /** Asks Gemini to answer the last message again, through the consent sheet (design review D21). */
    fun askGemini() = retry(Tier.CLOUD)

    private fun retry(tier: Tier) {
        val state = mutable.value
        if (state.busy) return
        val answer = state.bubbles.lastOrNull()?.takeIf { !it.fromUser } ?: return
        val question = state.bubbles.lastOrNull { it.fromUser } ?: return
        run(question.text, pinned = tier, replaces = answer, photos = question.photos, voiceNote = question.voice?.note, document = question.document?.document, asked = question.id)
    }

    /**
     * The user approved the cloud for this message. With [shareFacts], the facts listed on the sheet go
     * too, exactly those (ADR-054); without it, none do.
     */
    fun approveCloud(shareFacts: Boolean = true) {
        val state = mutable.value
        val text = state.consentFor ?: return
        val replaces = consentReplaces
        consentReplaces = null
        mutable.update { it.copy(consentFor = null, consentFacts = emptyList()) }
        run(
            text, pinned = if (replaces != null) Tier.CLOUD else null, cloudConsented = true,
            sharedFacts = if (shareFacts) state.consentFacts else emptyList(), replaces = replaces,
            photos = state.bubbles.lastOrNull { it.fromUser }?.photos.orEmpty(),
        )
    }

    /** Keeps the message on the phone. After "Ask Gemini", the answer it would have replaced simply stays (D21). */
    fun declineCloud() {
        val retrying = consentReplaces != null
        consentReplaces = null
        mutable.update { it.copy(consentFor = null, consentFacts = emptyList()) }
        if (!retrying) add(Bubble(nextId++, fromUser = false, text = "Kept on this phone. Nothing was sent.", failed = true))
    }

    /** Opens "About this answer" for a completed answer, with its facts looked up in memory now (ADR-071). */
    fun openAbout(bubbleId: Long) {
        val details = mutable.value.bubbles.firstOrNull { it.id == bubbleId }?.details ?: return
        viewModelScope.launch {
            val known = try {
                facts(details.factIds)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
            mutable.update { it.copy(about = AboutAnswer(details, details.factIds.map { id -> id to known[id] })) }
        }
    }

    fun closeAbout() = mutable.update { it.copy(about = null) }

    fun approve(id: String) = approvals.decide(id, ApprovalDecision.Approved)

    fun deny(id: String) = approvals.decide(id, ApprovalDecision.Denied("they tapped Don't do it"))

    /** The user changed what the tool allows. This approves nothing: the broker checks it and asks again (§7.2). */
    fun revise(id: String, payload: JsonObject) = approvals.decide(id, ApprovalDecision.Revised(payload))

    fun stop() {
        running?.cancel()
    }

    /**
     * Runs [text]. With [replaces], it answers again: that answer goes once the new one is complete, and when it was
     * saved, the saved chat drops its exchange only then.
     */
    private fun run(
        text: String,
        pinned: Tier? = null,
        cloudConsented: Boolean = false,
        sharedFacts: List<String> = emptyList(),
        replaces: Bubble? = null,
        photos: List<Photo> = emptyList(),
        recording: Recording? = null,
        voiceNote: VoiceNote? = null,
        pdf: Pdf? = null,
        document: Document? = null,
        asked: Long? = null,
        aboutScreen: Boolean = false,
        /** The question asks for a reply to the chat on the screen, so the answer becomes a draft (ADR-092). */
        replying: Boolean = false,
        /** Sends a drafted reply instead of asking the model (ADR-092). */
        sending: ReplySend? = null,
    ) {
        val id = nextId++
        add(Bubble(id, fromUser = false, text = "", streaming = true))
        mutable.update { it.copy(busy = true) }
        running = viewModelScope.launch {
            try {
                // The screen is read only now, when the user has asked about it; one that can't be read is said so,
                // rather than answered without it (EXP-2, ADR-083).
                var chat: ScreenChat? = null
                val screen = if (aboutScreen) {
                    when (val look = screens?.screen()) {
                        is ScreenLook.Ready -> look.screen.also { chat = look.chat }
                        is ScreenLook.Unavailable -> {
                            edit(id) { it.copy(text = look.reason, failed = true) }
                            return@launch
                        }
                        null -> null
                    }
                } else {
                    null
                }
                screen?.let { shown ->
                    asked?.let { question -> edit(question) { it.copy(screenApp = shown.app) } }
                    mutable.update { it.copy(readingApp = shown.app) }
                }
                val events = sending?.let { session.sendReply(it.chatKey, it.chatTitle, it.text) } ?: session.send(
                    text, device(), pinned, cloudConsented, sharedFacts, replacingLast = replaces?.saved == true, photos = photos,
                    recording = recording, voiceNote = voiceNote, screen = screen, pdf = pdf, document = document,
                )
                events.collect { reduce(id, text, it, replaces, asked) }
                // The answer to "reply to this" is the reply: a draft to copy, or to send while its chat can be answered.
                if (replying) {
                    val sendable = chat?.let { replies?.canReply(it.key) } == true
                    edit(id) { done ->
                        val reply = !done.failed && !done.quiet && done.text.isNotBlank() && !ReplyWords.nothingToReply(done.text)
                        if (reply) done.copy(draft = ReplyDraft(chat, sendable)) else done
                    }
                }
            } catch (e: CancellationException) {
                edit(id) { it.copy(note = "Stopped", hearing = false) }
                giveBackRecording(asked)
                throw e
            } catch (e: Exception) {
                edit(id) { it.copy(text = "Something went wrong: ${e.message}", failed = true) }
                mutable.update { it.copy(failures = it.failures + 1) }
            } finally {
                edit(id) { it.copy(streaming = false) }
                mutable.update { it.copy(busy = false, cloudAllowed = device().cloudAllowed, readingApp = null) }
            }
        }
    }

    /**
     * A voice note whose words weren't all written down goes back to the composer, so the recording isn't lost: the
     * user can send it again (ADR-078). Its turn keeps no partial words.
     */
    private fun giveBackRecording(asked: Long?) {
        val clip = mutable.value.bubbles.firstOrNull { it.id == asked }?.voice ?: return
        val recording = clip.recording ?: return
        notWrittenDown(asked)
        mutable.update { if (it.recording == null) it.copy(recording = recording) else it }
    }

    private fun notWrittenDown(asked: Long?) {
        asked?.let { question -> edit(question) { it.copy(voice = it.voice?.copy(words = "", writing = false, recording = null)) } }
    }

    private fun reduce(id: Long, text: String, event: AgentEvent, replaces: Bubble? = null, asked: Long? = null) {
        when (event) {
            is AgentEvent.Transcribing -> {
                asked?.let { question -> edit(question) { it.copy(voice = it.voice?.copy(words = event.words, writing = true)) } }
                edit(id) { it.copy(hearing = true, note = if (it.waiting) it.note else WRITING_DOWN) }
            }
            is AgentEvent.Transcribed -> {
                asked?.let { question -> edit(question) { it.copy(voice = it.voice?.copy(words = event.note.words, writing = false, recording = null)) } }
                edit(id) { it.copy(hearing = false, note = if (it.waiting) it.note else null) }
            }
            is AgentEvent.ReadingDocument -> {
                val line = "Reading ${event.name}… page ${event.page} of ${event.of}"
                edit(id) { it.copy(reading = line, note = if (it.waiting) it.note else line) }
            }
            is AgentEvent.DocumentRead -> {
                asked?.let { question -> edit(question) { it.copy(document = it.document?.copy(document = event.document)) } }
                edit(id) { it.copy(reading = null, note = if (it.waiting || it.reading == null) it.note else null) }
            }
            is AgentEvent.Routed -> edit(id) {
                val decision = event.decision
                // Pro while warm writes slowly, so the wait says why (design review D22).
                val slow = decision.guarded && decision.tier == Tier.PRO && decision.guard == Guard.HEAT
                it.copy(tier = decision.tier, guard = decision.guard, guarded = decision.guarded, note = if (slow) SLOW_WHILE_WARM else it.note)
            }
            is AgentEvent.TextDelta -> {
                edit(id) {
                    it.copy(text = it.text + event.text, note = it.note.takeUnless { note -> note == SLOW_WHILE_WARM || note == THINKING || it.waiting }, waiting = false)
                }
                speakProgress(id, final = false)
            }
            is AgentEvent.Thinking -> edit(id) { it.copy(thought = it.thought + event.text, note = THINKING, waiting = false) }
            is AgentEvent.Discarded -> {
                edit(id) { it.copy(text = "", thought = "", note = "That answer didn't pass its checks. Trying again…") }
                if (speakTurn) speechOut?.stop()
                spokenUpTo = 0
            }
            is AgentEvent.Escalated -> edit(id) { it.copy(tier = event.to, note = "Trying ${event.to.model}…") }
            is AgentEvent.ConsentNeeded -> {
                consentReplaces = replaces
                mutable.update { state -> state.copy(bubbles = state.bubbles.filterNot { it.id == id }, consentFor = text, consentFacts = event.facts) }
            }
            is AgentEvent.ToolStarted -> {
                edit(id) { it.copy(text = "", note = activity(event.call.name)) }
                spokenUpTo = 0
            }
            is AgentEvent.ToolFinished -> {
                receipt(event.call.name, event.outcome)?.let { line -> edit(id) { it.copy(actions = it.actions + line) } }
                linkFrom(event.outcome)?.let { link -> edit(id) { it.copy(links = it.links + link) } }
            }
            // Only the final answer is kept. Text the model wrote before calling tools is scaffolding.
            is AgentEvent.Completed -> {
                edit(id) {
                    it.copy(
                        text = event.text, tier = event.tier, note = null, actions = event.actions.map(::receipt), failed = event.unverifiedClaim,
                        details = event.details, saved = true,
                    )
                }
                // A retry's new answer replaces the one it was asked for.
                if (replaces != null) mutable.update { state -> state.copy(bubbles = state.bubbles.filterNot { it.id == replaces.id }) }
                speakProgress(id, final = true)
            }
            is AgentEvent.Failed -> {
                edit(id) { it.copy(text = friendly(event.reasons), failed = true, note = null, hearing = false) }
                giveBackRecording(asked)
                mutable.update { it.copy(failures = it.failures + 1) }
                spokenUpTo = 0
                speakProgress(id, final = true)
            }
            // Nothing to answer isn't a failure: a quiet note, and the sphere doesn't grey (ADR-090).
            is AgentEvent.NothingToAnswer -> {
                edit(id) { it.copy(text = event.note, quiet = true, note = null, hearing = false) }
                notWrittenDown(asked)
                spokenUpTo = 0
                speakProgress(id, final = true)
            }
        }
    }

    /** Reads the answer's newly complete sentences aloud, on a spoken turn. */
    private fun speakProgress(id: Long, final: Boolean) {
        if (!speakTurn) return
        val out = speechOut ?: return
        val text = mutable.value.bubbles.firstOrNull { it.id == id }?.text ?: return
        val (sentences, next) = Sentences.next(text, spokenUpTo, final)
        spokenUpTo = next
        sentences.forEach(out::speak)
    }

    private fun receipt(tool: String, outcome: ToolOutcome): Receipt? = when (outcome) {
        is ToolOutcome.Ok -> Receipt(tool, outcome.summary, Receipt.Status.DONE)
        is ToolOutcome.Rejected -> Receipt(tool, outcome.summary, Receipt.Status.NOT_DONE)
        is ToolOutcome.Failed -> Receipt(tool, outcome.summary, Receipt.Status.FAILED)
        is ToolOutcome.Invalid -> null
    }

    /** A link a tool's result offers as `open_url` and `open_label`. Only web links, never other schemes. */
    private fun linkFrom(outcome: ToolOutcome): LinkAction? {
        val result = (outcome as? ToolOutcome.Ok)?.result as? JsonObject ?: return null
        val url = (result["open_url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        val label = (result["open_label"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "Open link"
        return LinkAction(label.take(60), url)
    }

    private fun receipt(action: ActionRecord): Receipt = Receipt(
        action.tool,
        action.summary,
        when (action.status) {
            ActionRecord.Status.DONE -> Receipt.Status.DONE
            ActionRecord.Status.NOT_DONE -> Receipt.Status.NOT_DONE
            ActionRecord.Status.FAILED -> Receipt.Status.FAILED
        },
    )

    /** What the user sees while a tool runs. */
    private fun activity(tool: String): String = when {
        tool.startsWith("calendar_create") -> "Preparing the event…"
        tool.startsWith("calendar_") -> "Checking your calendar…"
        tool == "contacts_add" -> "Saving the contact…"
        tool.startsWith("contacts_") -> "Looking up contacts…"
        tool == "notes_search" -> "Checking your notes…"
        tool.startsWith("notes_") -> "Saving the note…"
        tool == "messages_reply" -> "Preparing the reply…"
        tool.startsWith("messages_") || tool.startsWith("sms_") -> "Checking your messages…"
        tool == "email_send" || tool == "email_create_draft" -> "Preparing the email…"
        tool == "memory_remember" -> "Remembering…"
        tool == "memory_forget" -> "Finding what to forget…"
        tool == "memory_search" -> "Checking what I remember…"
        tool.startsWith("email_") -> "Checking your email…"
        tool == "shop_search" -> "Searching your stores…"
        tool == "shop_checkout" -> "Preparing the checkout…"
        tool.startsWith("mcp_") -> "Asking your service…"
        tool.startsWith("phone_") -> "Doing it…"
        else -> "Working…"
    }

    private fun add(bubble: Bubble) = mutable.update { it.copy(bubbles = it.bubbles + bubble) }

    private fun edit(id: Long, change: (Bubble) -> Bubble) =
        mutable.update { state -> state.copy(bubbles = state.bubbles.map { if (it.id == id) change(it) else it }) }

    private fun friendly(reasons: List<String>): String = when {
        reasons.any { it.startsWith("a photo is in view") } -> "Photos stay on this phone, so Gemini can't answer this."
        reasons.any { it.startsWith("a voice note is in view") } -> "Voice notes stay on this phone, so Gemini can't answer this."
        reasons.any { it.startsWith("a PDF is in view") } -> "PDFs stay on this phone, so Gemini can't answer this."
        reasons.singleOrNull() in HEARING_REASONS -> reasons.single()
        reasons.any { it == "no model tier is available" } ->
            "No model is ready yet. Download one in Settings, or add a Gemini key there."
        else -> "I couldn't answer that: ${reasons.lastOrNull() ?: "unknown error"}"
    }
}

/** What a wait before the first word is, in words (design review D10). Null when there's nothing to wait for. */
internal fun waitCause(status: BrainStatus): String? = when (status) {
    BrainStatus.Idle -> null
    is BrainStatus.Loading -> "Getting ${modelOf(status.id)} ready…"
    is BrainStatus.SettingUpGpu -> "Setting up the GPU. First time only, about a minute."
    is BrainStatus.Switching -> "Switching to ${modelOf(status.id)}…"
    is BrainStatus.Reading -> "Catching up on this chat…"
}

private fun modelOf(id: BrainId): String = if (id == BrainId.BASE) Tier.BASE.model else Tier.PRO.model

/** The line while the model reasons, before its answer begins (G4). */
internal const val THINKING = "Thinking…"

/** The line while a voice note is written down, before the model begins (G3, ADR-078). */
internal const val WRITING_DOWN = "Writing down your voice note…"

/** Why a scanned PDF wasn't added: its pages are pictures, and the message already has as many as Anu looks at. */
internal const val SCAN_NO_ROOM = "This PDF has no text Anu can read, so its pages go as pictures. Remove a photo to add it."

/** Why the composer took no more photos: one message shows the model at most so many (ADR-094). */
internal const val FULL = "Anu can look at up to ${Photo.MAX_IN_VIEW} photos at once."

/** The longest voice note Anu records or takes from another app: about 7,500 tokens of audio, in 30-second pieces (ADR-078). */
const val VOICE_NOTE_SECONDS = 300

/** Why a voice note ended its turn, shown as they are. */
private val HEARING_REASONS = setOf(ChatSession.NOTHING_SAID, ChatSession.CANT_HEAR, ChatSession.COULDNT_HEAR)

/** The pause after Anu finishes before it listens again in live talk, so it doesn't hear the end of its own voice (ADR-101). */
internal const val LIVE_PAUSE_MS = 400L

/**
 * Listens in a row that hear nothing before live talk ends (ADR-101): each gives up after several seconds of quiet, so
 * this is 20 to 30 s. Two ended it on the Pixel while the user was still thinking.
 */
internal const val LIVE_MISSES = 4

/** What the Talk page says when live talk ends by itself. */
internal const val LIVE_ENDED = "Live talk ended. Tap the mic to talk again."

/** The wait line while Pro answers on a warm phone (design review D22). */
internal const val SLOW_WHILE_WARM = "The phone is warm, so this may take longer."

/** How the UI names each tier, so users always know where an answer came from (§6, DESIGN.md). */
val Tier.label: String
    get() = when (this) {
        Tier.BASE -> "On device · Gemma 4 E2B"
        Tier.PRO -> "On device · Gemma 4 E4B"
        Tier.CLOUD -> "Cloud · Gemini"
    }

/** A tier's model, for notes such as "Trying Gemma 4 E4B…". */
val Tier.model: String
    get() = when (this) {
        Tier.BASE -> "Gemma 4 E2B"
        Tier.PRO -> "Gemma 4 E4B"
        Tier.CLOUD -> "Gemini"
    }

/** A drafted reply on its way through the broker (ADR-092). */
internal data class ReplySend(val chatKey: String, val chatTitle: String, val text: String)
