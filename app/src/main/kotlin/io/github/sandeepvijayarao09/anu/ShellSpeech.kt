package io.github.sandeepvijayarao09.anu

import io.github.sandeepvijayarao09.anu.core.voice.SpeechEvent
import io.github.sandeepvijayarao09.anu.core.voice.SpeechInput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug builds only: speech typed from adb, for the emulator, whose recognizer has no English pack. Once `DebugVoice`
 * turns it on, each listen waits up to [WAIT_MS] for words sent the same way and hears them, or hears nothing, as a
 * quiet listen does, so live talk can run on the emulator (ADR-101, ADR-108). Otherwise it's the phone's recognizer.
 */
internal class ShellSpeech(private val phone: SpeechInput) : SpeechInput {
    override val available: Boolean get() = on || phone.available

    override fun listen(): Flow<SpeechEvent> = if (!on) phone.listen() else flow {
        emit(SpeechEvent.Listening)
        val words = withTimeoutOrNull(WAIT_MS) { heard.receive() }
        emit(if (words == null) SpeechEvent.Error("Didn't catch that.", heardNothing = true) else SpeechEvent.Final(words))
    }

    override fun downloadLanguagePack() = phone.downloadLanguagePack()

    companion object {
        @Volatile var on = false
        val heard = Channel<String>(Channel.CONFLATED)
        private const val WAIT_MS = 8_000L
    }
}
