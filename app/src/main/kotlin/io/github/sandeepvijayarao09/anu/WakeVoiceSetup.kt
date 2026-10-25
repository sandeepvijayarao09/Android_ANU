package io.github.sandeepvijayarao09.anu

import io.github.sandeepvijayarao09.anu.core.vault.KeyPolicy
import io.github.sandeepvijayarao09.anu.core.vault.KeystoreAead
import io.github.sandeepvijayarao09.anu.core.voice.MicInput
import io.github.sandeepvijayarao09.anu.core.voice.MicUnavailable
import io.github.sandeepvijayarao09.anu.core.voice.SpeechFeatures
import io.github.sandeepvijayarao09.anu.core.voice.WakeEnrollment
import io.github.sandeepvijayarao09.anu.core.voice.WakeStream
import io.github.sandeepvijayarao09.anu.core.voice.WakeTemplate
import io.github.sandeepvijayarao09.anu.core.voice.WakeVoice
import io.github.sandeepvijayarao09.anu.core.voice.level
import io.github.sandeepvijayarao09.anu.feature.settings.WakeSampleEvent
import io.github.sandeepvijayarao09.anu.feature.settings.WakeVoiceSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The user's "Hey Anu" samples (ADR-121): embeddings derived from their voice, so they're encrypted with a Keystore key
 * in no-backup storage and never leave the phone. The key works after the first unlock, as the database's does, since
 * "Hey Anu" listens only while the screen is on.
 */
class WakeVoiceStore(private val file: File) {
    private val aead by lazy { KeystoreAead("anu.wake.voice", KeyPolicy.AfterFirstUnlock) }

    fun exists(): Boolean = file.exists()

    /** Null when there's none, or it can't be read, such as after the key was reset. */
    fun load(): WakeVoice? = runCatching { if (file.exists()) WakeVoice.decode(aead.decrypt(file.readBytes(), AD)) else null }.getOrNull()

    fun save(voice: WakeVoice) {
        file.parentFile?.mkdirs()
        val next = File(file.path + ".new")
        next.writeBytes(aead.encrypt(voice.encode(), AD))
        check(next.renameTo(file)) { "Couldn't keep the samples" }
    }

    fun delete() {
        file.delete()
    }

    private companion object {
        val AD = "anu.wake.voice.v1".toByteArray()
    }
}

/**
 * Records the user's "Hey Anu" for [WakeEnrollment] (ADR-121). Each sample records until the user stops talking, or
 * for [MAX_CHUNKS] at most, while its level shows; its audio is checked, turned into a template and dropped. The
 * models load at the first sample, and a lock keeps [close] from freeing them while a sample is being read.
 */
internal class AnuWakeVoiceSetup(
    private val mic: MicInput,
    private val open: () -> SpeechFeatures,
    private val keep: (WakeVoice) -> Unit,
) : WakeVoiceSetup {
    override val samples = WakeEnrollment.SAMPLES

    private val kept = mutableListOf<WakeTemplate>()
    private val lock = Any()
    private var features: SpeechFeatures? = null
    private var closed = false

    override fun record(): Flow<WakeSampleEvent> = flow {
        val stream = withContext(Dispatchers.Default) {
            synchronized(lock) {
                if (closed) return@withContext null
                val loaded = features ?: runCatching(open).getOrNull()?.also { features = it }
                loaded?.let(::WakeStream)
            }
        }
        if (stream == null) {
            emit(WakeSampleEvent.Again("This phone can't run “Hey Anu”'s models."))
            return@flow
        }
        val audio = ArrayList<ShortArray>()
        val embeddings = ArrayList<FloatArray>()
        val levels = ArrayList<Float>()
        try {
            mic.chunks(SpeechFeatures.CHUNK)
                .map { chunk -> Triple(chunk, synchronized(lock) { if (closed) null else stream.push(chunk) }, level(chunk)) }
                .flowOn(Dispatchers.Default)
                .takeWhile { (_, embedding, _) -> embedding != null && audio.size < MAX_CHUNKS && !WakeEnrollment.ended(levels) }
                .collect { (chunk, embedding, loudness) ->
                    audio += chunk
                    embeddings += embedding!!
                    levels += loudness
                    emit(WakeSampleEvent.Level(loudness))
                }
        } catch (e: MicUnavailable) {
            emit(WakeSampleEvent.Again(e.message ?: "The microphone is in use by another app."))
            return@flow
        }
        val pcm = ShortArray(audio.sumOf { it.size }).also { out -> audio.forEachIndexed { i, chunk -> chunk.copyInto(out, i * SpeechFeatures.CHUNK) } }
        when (val sample = WakeEnrollment.sample(pcm, embeddings)) {
            is WakeEnrollment.Sample.Again -> emit(WakeSampleEvent.Again(sample.reason))
            is WakeEnrollment.Sample.Learned -> if (WakeEnrollment.fits(sample.template, kept)) {
                kept += sample.template
                emit(WakeSampleEvent.Kept(kept.size))
            } else {
                emit(WakeSampleEvent.Again("That sounded different from the others. Say “Hey Anu” as you did before."))
            }
        }
    }

    override fun save() {
        check(kept.size >= samples) { "Only ${kept.size} of $samples samples" }
        keep(WakeEnrollment.voice(kept.toList()))
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            features?.close()
            features = null
        }
    }

    private companion object {
        /** 4 s: long enough to start late and say it slowly. */
        const val MAX_CHUNKS = 50
    }
}
