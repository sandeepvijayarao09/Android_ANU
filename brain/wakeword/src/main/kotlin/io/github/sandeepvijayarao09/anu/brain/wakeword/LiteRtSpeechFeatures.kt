package io.github.sandeepvijayarao09.anu.brain.wakeword

import android.content.res.AssetManager
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import io.github.sandeepvijayarao09.anu.core.voice.SpeechFeatures

/**
 * openWakeWord's mel spectrogram and speech-embedding models on LiteRT's CompiledModel (ADR-121), from this module's
 * assets (1.1 MB and 1.3 MB, 32-bit). They run on the CPU on one thread: together they take a few milliseconds per
 * 80 ms of audio, and the GPU or NPU would cost more to wake than they save. Audio and embeddings stay in memory.
 */
class LiteRtSpeechFeatures private constructor(
    private val mel: CompiledModel,
    private val embedding: CompiledModel,
) : SpeechFeatures {
    private val melIn: List<TensorBuffer> = mel.createInputBuffers()
    private val melOut: List<TensorBuffer> = mel.createOutputBuffers()
    private val embeddingIn: List<TensorBuffer> = embedding.createInputBuffers()
    private val embeddingOut: List<TensorBuffer> = embedding.createOutputBuffers()

    override fun mel(samples: FloatArray): FloatArray {
        melIn[0].writeFloat(samples)
        mel.run(melIn, melOut)
        return melOut[0].readFloat()
    }

    override fun embed(mel: FloatArray): FloatArray {
        embeddingIn[0].writeFloat(mel)
        embedding.run(embeddingIn, embeddingOut)
        return embeddingOut[0].readFloat()
    }

    override fun close() {
        (melIn + melOut + embeddingIn + embeddingOut).forEach { it.close() }
        mel.close()
        embedding.close()
    }

    companion object {
        const val MEL_ASSET = "wake_melspectrogram.tflite"
        const val EMBEDDING_ASSET = "wake_embedding.tflite"

        /** Throws when LiteRT can't load them, such as on an ABI it has no library for. */
        fun open(assets: AssetManager): LiteRtSpeechFeatures {
            val mel = CompiledModel.create(assets, MEL_ASSET, cpu())
            val embedding = try {
                CompiledModel.create(assets, EMBEDDING_ASSET, cpu())
            } catch (e: Exception) {
                mel.close()
                throw e
            }
            return LiteRtSpeechFeatures(mel, embedding)
        }

        private fun cpu() = CompiledModel.Options(Accelerator.CPU).apply { cpuOptions = CompiledModel.CpuOptions(1, null, null) }
    }
}
