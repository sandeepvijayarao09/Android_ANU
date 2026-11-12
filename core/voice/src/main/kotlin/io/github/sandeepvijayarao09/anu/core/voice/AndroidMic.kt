package io.github.sandeepvijayarao09.anu.core.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.max

/**
 * The microphone for "Hey Anu" and teaching it the user's voice (ADR-121): [AudioRecord] at 16 kHz, mono 16-bit,
 * from the voice-recognition source, as for voice notes. Chunks go straight to the caller; nothing is buffered here.
 */
class AndroidMic : MicInput {
    // RECORD_AUDIO is checked before "Hey Anu" starts or a sample is recorded; without it, AudioRecord fails to initialize.
    @Suppress("MissingPermission")
    override fun chunks(size: Int): Flow<ShortArray> = flow {
        val rate = SpeechFeatures.RATE
        val bufferSize = max(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), size * 2 * 4)
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw MicUnavailable(MIC_UNAVAILABLE)
        }
        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw MicUnavailable(MIC_UNAVAILABLE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val chunk = ShortArray(size)
                var filled = 0
                while (filled < size) {
                    val read = recorder.read(chunk, filled, size - filled)
                    if (read < 0) throw MicUnavailable(MIC_UNAVAILABLE)
                    filled += read
                }
                emit(chunk)
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val MIC_UNAVAILABLE = "The microphone is in use by another app."
    }
}

/**
 * Talking over Anu (ADR-129): while it speaks in live talk, the voice-communication microphone, with the phone's
 * echo canceller and noise suppressor, feeds [BargeInDetector] 20 ms at a time. What it hears is only measured for
 * loudness, then dropped. A phone with no echo canceller gets no barge-in, since its own voice would set it off; a tap
 * still stops the speech there.
 */
class AndroidBargeIn(
    /** Debug builds: the echo level, the bar and when it fired, never audio. */
    private val log: (String) -> Unit = {},
) : BargeIn {
    @Suppress("MissingPermission") // live talk runs only with RECORD_AUDIO
    override fun watch(): Flow<Unit> = flow {
        if (!AcousticEchoCanceler.isAvailable()) {
            log("Barge-in: off, no echo canceller")
            return@flow
        }
        val size = BargeInDetector.CHUNK
        val rate = SpeechFeatures.RATE
        val bufferSize = max(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), size * 2 * 8)
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@flow
        }
        val echo = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
        val noise = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true } else null
        try {
            if (echo?.enabled != true) {
                log("Barge-in: off, the echo canceller didn't start")
                return@flow
            }
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@flow
            val detector = BargeInDetector()
            val chunk = ShortArray(size)
            var chunks = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                var filled = 0
                while (filled < size) {
                    val read = recorder.read(chunk, filled, size - filled)
                    if (read < 0) return@flow
                    filled += read
                }
                chunks++
                val wasCalibrated = detector.threshold != null
                if (detector.push(level(chunk))) {
                    log("Barge-in: the user spoke over Anu after ${chunks * 20} ms, echo ${"%.2f".format(detector.echo)}, bar ${"%.2f".format(detector.threshold)}")
                    emit(Unit)
                    return@flow
                }
                if (!wasCalibrated && detector.threshold != null) {
                    log("Barge-in: Anu's own voice at ${"%.2f".format(detector.echo)} after echo cancelling, bar ${"%.2f".format(detector.threshold)}")
                }
            }
        } finally {
            runCatching { recorder.stop() }
            echo?.release()
            noise?.release()
            recorder.release()
        }
    }.flowOn(Dispatchers.IO)
}
