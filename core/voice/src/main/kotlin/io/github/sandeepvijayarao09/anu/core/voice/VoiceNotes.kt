package io.github.sandeepvijayarao09.anu.core.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.github.sandeepvijayarao09.anu.core.model.Recording
import io.github.sandeepvijayarao09.anu.core.model.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Records voice notes with [AudioRecord] (G3, ADR-078): mono 16-bit PCM at 16 kHz, the rate speech models expect,
 * in memory only, never in a file. The voice-recognition source leaves out the processing meant for calls. When
 * Android silences Anu's microphone, because a call started or the user left the app, the recording ends there and
 * is marked as cut short.
 */
class AndroidVoiceRecorder(private val context: Context) : VoiceRecorder {

    @Volatile private var finishing = false

    // RECORD_AUDIO is checked by the screen before it records; without it, AudioRecord fails to initialize.
    @Suppress("MissingPermission")
    override fun record(maxSeconds: Int): Flow<RecordingEvent> = flow {
        finishing = false
        val rate = Wav.SPEECH_RATE
        val chunk = rate / 10 * 2
        val size = max(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), chunk * 4)
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            emit(RecordingEvent.Error(MIC_UNAVAILABLE))
            return@flow
        }
        val audio = context.getSystemService(AudioManager::class.java)
        val silenced = AtomicBoolean(false)
        val watch = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                if (configs.any { it.clientAudioSessionId == recorder.audioSessionId && it.isClientSilenced }) silenced.set(true)
            }
        }
        audio.registerAudioRecordingCallback(watch, Handler(Looper.getMainLooper()))
        val pcm = ByteArrayOutputStream()
        val buffer = ByteArray(chunk)
        val limit = maxSeconds * rate * 2
        var interrupted = false
        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                emit(RecordingEvent.Error(MIC_UNAVAILABLE))
                return@flow
            }
            while (!finishing && pcm.size() < limit) {
                currentCoroutineContext().ensureActive()
                val read = recorder.read(buffer, 0, minOf(buffer.size, limit - pcm.size()))
                if (read < 0) {
                    interrupted = true
                    break
                }
                pcm.write(buffer, 0, read)
                emit(RecordingEvent.Level(level(buffer, read), pcm.size() / 2 / rate))
                if (silenced.get()) {
                    interrupted = true
                    break
                }
            }
        } finally {
            audio.unregisterAudioRecordingCallback(watch)
            runCatching { recorder.stop() }
            recorder.release()
        }
        val bytes = pcm.toByteArray()
        emit(RecordingEvent.Finished(Recording(Wav.of(bytes, rate), Wav.seconds(bytes.size, rate), interrupted = interrupted)))
    }.flowOn(Dispatchers.IO)

    override fun finish() {
        finishing = true
    }

    private companion object {
        const val MIC_UNAVAILABLE = "The microphone is in use by another app."
    }
}

/** How loud [read] bytes of 16-bit audio are, from 0 (-60 dBFS or quieter) to 1 (full scale), for the sphere. */
internal fun level(buffer: ByteArray, read: Int): Float {
    val samples = ByteBuffer.wrap(buffer, 0, read - read % 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    if (!samples.hasRemaining()) return 0f
    var sum = 0.0
    val count = samples.remaining()
    while (samples.hasRemaining()) {
        val value = samples.get().toDouble()
        sum += value * value
    }
    val rms = sqrt(sum / count)
    if (rms < 1) return 0f
    return ((20 * log10(rms / 32_768) + 60) / 60).toFloat().coerceIn(0f, 1f)
}

/** A shared recording, read (ADR-078), or why it can't be. */
sealed interface SharedRecording {
    data class Ready(val recording: Recording) : SharedRecording

    /** Longer than a voice note can be. [seconds] is how long it is. */
    data class TooLong(val seconds: Int) : SharedRecording

    /** Not audio Android can decode, or it couldn't be opened. */
    data object Unreadable : SharedRecording
}

/**
 * Reads a recording shared from another app as mono 16-bit WAV (ADR-078): Android's own decoders turn AAC, Opus, MP3
 * and the rest into PCM, because the model's decoder takes only WAV (S6). Its channels are mixed down; its rate stays,
 * since the model resamples. One longer than [maxSeconds] is refused before it's decoded. Call it off the main thread.
 */
fun readSharedRecording(context: Context, uri: Uri, maxSeconds: Int): SharedRecording {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    return try {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: return SharedRecording.Unreadable
        val format = extractor.getTrackFormat(track)
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            val seconds = ((format.getLong(MediaFormat.KEY_DURATION) + 500_000) / 1_000_000).toInt()
            if (seconds > maxSeconds) return SharedRecording.TooLong(seconds)
        }
        extractor.selectTrack(track)
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec = decoder
        decoder.configure(format, null, null, 0)
        decoder.start()
        val mono = ByteArrayOutputStream()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var float = false
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val input = decoder.getInputBuffer(index)!!
                    val size = extractor.readSampleData(input, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val out = decoder.outputFormat
                    rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = out.containsKey(MediaFormat.KEY_PCM_ENCODING) && out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }
                index >= 0 -> {
                    val output = decoder.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                    output.position(info.offset)
                    output.limit(info.offset + info.size)
                    mixDown(output, channels, float, mono)
                    decoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    // A file whose length wasn't in its header is still held to the limit.
                    if (mono.size() / 2 > (maxSeconds + 1) * rate) return SharedRecording.TooLong(mono.size() / 2 / rate)
                }
            }
        }
        val bytes = mono.toByteArray()
        if (bytes.isEmpty()) SharedRecording.Unreadable else SharedRecording.Ready(Recording(Wav.of(bytes, rate), Wav.seconds(bytes.size, rate), shared = true))
    } catch (_: Exception) {
        SharedRecording.Unreadable
    } finally {
        runCatching { codec?.stop() }
        codec?.release()
        extractor.release()
    }
}

/** Appends [pcm]'s frames to [out] as mono 16-bit little-endian, averaging the channels. */
private fun mixDown(pcm: ByteBuffer, channels: Int, float: Boolean, out: ByteArrayOutputStream) {
    val width = if (float) 4 else 2
    val frame = ByteArray(2)
    while (pcm.remaining() >= channels * width) {
        var sum = 0.0
        repeat(channels) { sum += if (float) pcm.float * 32_767.0 else pcm.short.toDouble() }
        val sample = (sum / channels).toInt().coerceIn(-32_768, 32_767)
        frame[0] = (sample and 0xFF).toByte()
        frame[1] = (sample shr 8 and 0xFF).toByte()
        out.write(frame, 0, 2)
    }
}

private const val TIMEOUT_US = 10_000L
