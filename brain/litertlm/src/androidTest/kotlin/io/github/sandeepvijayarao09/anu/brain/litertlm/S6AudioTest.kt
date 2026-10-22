package io.github.sandeepvijayarao09.anu.brain.litertlm

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Spike S6 (§16): a recording in, words out, with Gemma 4 on the device. It is the basis for voice notes and for
 * asking about a recording (G3). The test plays the model a made-up voice note, a 16 kHz mono WAV made with the
 * Mac's `say`, and asks for its words, then for what to do about it. It reports the load time, the tokens the
 * recording takes, the time to the first token, the speed and the peak memory. Push the note first:
 *   adb push voice-note.wav /data/local/tmp/anu/audio/
 * The audio encoder runs on the CPU unless `audioBackend=GPU`.
 */
@OptIn(ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class S6AudioTest {

    private val notePath: String
        get() = InstrumentationRegistry.getArguments().getString("audioPath") ?: "/data/local/tmp/anu/audio/voice-note.wav"

    @Test
    fun transcribesAVoiceNote() {
        S1.assumeModelPresent()
        assumeTrue("No recording at $notePath; skipping", File(notePath).canRead())
        val audioName = InstrumentationRegistry.getArguments().getString("audioBackend") ?: "CPU"
        val audio = if (audioName == "GPU") Backend.GPU() else Backend.CPU()
        S1.report("audio_backend", audioName)
        ModelInfo.from(S1.modelPath).use { S1.report("hears", it.inputModalities().audio) }
        val note = File(notePath).readBytes()
        S1.report("note_bytes", note.size)
        S1.report("note_seconds", "%.1f".format((note.size - 44) / 32_000.0))
        ExperimentalFlags.enableBenchmark = true
        try {
            val greedy = ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0))
            val transcribe = "Write down exactly what is said in this recording. Reply with the words only."
            var started = SystemClock.elapsedRealtime()
            Engine(config(audio)).use { engine ->
                engine.initialize()
                S1.report("load_ms", SystemClock.elapsedRealtime() - started)

                // The same ask with no recording, so the recording's own tokens are the difference.
                val baseline = engine.createConversation(greedy).use { conversation ->
                    conversation.sendMessage(Contents.of(Content.Text(transcribe))).text()
                    conversation.getBenchmarkInfo().lastPrefillTokenCount
                }
                S1.report("text_only_prefill_tokens", baseline)

                engine.createConversation(greedy).use { conversation ->
                    val words = conversation.sendMessage(Contents.of(Content.AudioBytes(note), Content.Text(transcribe))).text()
                    val info = conversation.getBenchmarkInfo()
                    report("transcribe", info, words)
                    S1.report("note_tokens", info.lastPrefillTokenCount - baseline)
                    val heard = words.lowercase()
                    assertTrue("hears the name: $words", "maya" in heard)
                    assertTrue("hears the day: $words", "thursday" in heard)
                    assertTrue("hears the item: $words", "oat milk" in heard)
                }

                // The same note, asked about rather than written down, as a voice note's summary would be.
                engine.createConversation(greedy).use { conversation ->
                    val ask = Content.Text("This is a voice note to myself. List the things I need to do, one per line.")
                    val todo = conversation.sendMessage(Contents.of(Content.AudioBytes(note), ask)).text()
                    report("understand", conversation.getBenchmarkInfo(), todo)
                    val heard = todo.lowercase()
                    assertTrue("finds the call: $todo", "maya" in heard)
                    assertTrue("finds the groceries: $todo", "oat milk" in heard)
                }

                // A longer note, if pushed, shows how the tokens grow with length.
                val long = File(notePath.replace(".wav", "-long.wav"))
                if (long.canRead()) {
                    val bytes = long.readBytes()
                    S1.report("long_seconds", "%.1f".format((bytes.size - 44) / 32_000.0))
                    engine.createConversation(greedy).use { conversation ->
                        val words = conversation.sendMessage(Contents.of(Content.AudioBytes(bytes), Content.Text(transcribe))).text()
                        val info = conversation.getBenchmarkInfo()
                        report("long", info, words)
                        S1.report("long_tokens", info.lastPrefillTokenCount - baseline)
                    }
                }
            }

            // A second load with the same cache: does the first recording's wait come back every time the model loads?
            started = SystemClock.elapsedRealtime()
            Engine(config(audio)).use { engine ->
                engine.initialize()
                S1.report("reload_ms", SystemClock.elapsedRealtime() - started)
                engine.createConversation(greedy).use { conversation ->
                    val words = conversation.sendMessage(Contents.of(Content.AudioBytes(note), Content.Text(transcribe))).text()
                    report("after_reload", conversation.getBenchmarkInfo(), words)
                }
            }
        } finally {
            ExperimentalFlags.enableBenchmark = false
        }
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") }?.let { S1.report("peak_rss", it.substringAfter(':').trim()) }
    }

    /**
     * Which recordings the runtime takes as they are, so Anu knows what it must convert first: the same note as a
     * 44.1 kHz stereo WAV, a 48 kHz WAV and an AAC M4A, as phones record them. Reports each; asserts nothing. Also
     * reports the memory held after loading with and without audio, to show when the audio encoder is loaded.
     */
    @Test
    fun formatsAndMemory() {
        S1.assumeModelPresent()
        assumeTrue("No recording at $notePath; skipping", File(notePath).canRead())
        val greedy = ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0))
        val transcribe = "Write down exactly what is said in this recording. Reply with the words only."
        Engine(config(null)).use { engine ->
            engine.initialize()
            S1.report("rss_loaded_without_audio", rss())
        }
        Engine(config(Backend.CPU())).use { engine ->
            engine.initialize()
            S1.report("rss_loaded_with_audio", rss())
            for (name in listOf("voice-note.wav", "voice-note-44k.wav", "voice-note-48k.wav", "voice-note.m4a")) {
                val file = File(File(notePath).parentFile, name)
                if (!file.canRead()) continue
                val result = runCatching {
                    engine.createConversation(greedy).use { conversation ->
                        conversation.sendMessage(Contents.of(Content.AudioBytes(file.readBytes()), Content.Text(transcribe))).text()
                    }
                }
                S1.report("format_$name", result.fold({ "ok: " + it.take(120).replace("\n", " ") }, { "failed: ${it.javaClass.simpleName}: ${it.message?.take(160)}" }))
                if (name == "voice-note.wav") S1.report("rss_after_first_recording", rss())
            }
        }
    }

    /**
     * Whether a recording can be written down beside the chat's open conversation, so a voice note doesn't make the
     * chat read everything again: a chat conversation stays open while a second one hears the note, then the chat
     * continues. Reports what happened; asserts nothing.
     */
    @Test
    fun twoConversationsAtOnce() {
        S1.assumeModelPresent()
        assumeTrue("No recording at $notePath; skipping", File(notePath).canRead())
        val greedy = ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0))
        Engine(config(Backend.CPU())).use { engine ->
            engine.initialize()
            engine.createConversation(greedy).use { chat ->
                val first = chat.sendMessage(Contents.of(Content.Text("My sister is called Priya. Reply with OK."))).text()
                S1.report("chat_first", first.take(60))
                val second = runCatching {
                    engine.createConversation(greedy).use { note ->
                        note.sendMessage(Contents.of(Content.AudioBytes(File(notePath).readBytes()), Content.Text("Write down exactly what is said in this recording. Reply with the words only."))).text()
                    }
                }
                S1.report("second_conversation", second.fold({ "ok: " + it.take(100) }, { "failed: ${it.javaClass.simpleName}: ${it.message?.take(200)}" }))
                val after = runCatching { chat.sendMessage(Contents.of(Content.Text("What is my sister called? One word."))).text() }
                S1.report("chat_after", after.fold({ "ok: " + it.take(60) }, { "failed: ${it.javaClass.simpleName}: ${it.message?.take(200)}" }))
            }
        }
    }

    /**
     * What the transcription prompt gives for silence, for noise and for speech, so "nothing was said" is spotted by
     * the model's own marker (ADR-078). Reports each; asserts nothing.
     */
    @Test
    fun silenceAndNoise() {
        S1.assumeModelPresent()
        val greedy = ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0))
        val prompt = "Write down exactly what is said in this recording, word for word, in the language it is spoken in. " +
            "Reply with the words only. If no one speaks, reply with only: [no speech]"
        val random = java.util.Random(7)
        val silence = io.github.sandeepvijayarao09.anu.core.model.Wav.of(ByteArray(3 * 32_000))
        val noise = io.github.sandeepvijayarao09.anu.core.model.Wav.of(
            java.nio.ByteBuffer.allocate(3 * 32_000).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                repeat(3 * 16_000) { putShort((random.nextGaussian() * 1_500).toInt().coerceIn(-32_768, 32_767).toShort()) }
            }.array(),
        )
        val samples = listOfNotNull("silence" to silence, "noise" to noise, File(notePath).takeIf { it.canRead() }?.let { "speech" to it.readBytes() })
        Engine(config(Backend.CPU())).use { engine ->
            engine.initialize()
            for ((name, wav) in samples) {
                engine.createConversation(greedy).use { conversation ->
                    val said = conversation.sendMessage(Contents.of(Content.AudioBytes(wav), Content.Text(prompt))).text()
                    S1.report("prompt_$name", said.take(160).replace("\n", " / "))
                }
            }
        }
    }

    private fun rss(): String =
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }?.substringAfter(':')?.trim() ?: "unknown"

    private fun config(audio: Backend?) = EngineConfig(
        modelPath = S1.modelPath,
        backend = S1.backend(),
        audioBackend = audio,
        maxNumTokens = 4096,
        cacheDir = S1.cacheDir(),
    )

    private fun report(step: String, info: BenchmarkInfo, answer: String) {
        S1.report("${step}_ttft_ms", (info.timeToFirstTokenInSecond * 1000).toInt())
        S1.report("${step}_prefill_tokens", info.lastPrefillTokenCount)
        S1.report("${step}_prefill_tok_s", "%.1f".format(info.lastPrefillTokensPerSecond))
        S1.report("${step}_decode_tokens", info.lastDecodeTokenCount)
        S1.report("${step}_decode_tok_s", "%.1f".format(info.lastDecodeTokensPerSecond))
        S1.report("${step}_answer", answer.take(240).replace("\n", " / "))
    }
}
