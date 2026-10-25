package io.github.sandeepvijayarao09.anu.core.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
