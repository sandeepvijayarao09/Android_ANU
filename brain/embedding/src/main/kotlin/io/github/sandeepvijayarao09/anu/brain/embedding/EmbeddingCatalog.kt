package io.github.sandeepvijayarao09.anu.brain.embedding

/**
 * The search-by-meaning model (ADR-055): EmbeddingGemma 300M, mixed precision, 256 tokens, for the CPU. Messages
 * and facts are short, and a query always fills its token window, so 256 tokens halve the work of 512 (ADR-062).
 * Its Hugging Face repo is gated, so the user downloads it after accepting the Gemma terms, then installs it in
 * Settings → Models → Choose a file (ADR-067), or from a computer with scripts/dev-install-model.sh. Both check
 * these hashes first. Each file's name, size and hash stay on one line, which is how the script finds them.
 */
object EmbeddingCatalog {
    data class Artifact(val name: String, val bytes: Long, val sha256: String)

    val MODEL = Artifact("embeddinggemma-300M_seq256_mixed-precision.tflite", 179_131_736L, "37115ef7bff76cd37dd86abe503ff511b1032bf85fc624a85c49c84899e92bc5")
    val TOKENIZER = Artifact("sentencepiece.model", 4_683_319L, "d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7")

    /**
     * EmbeddingGemma 2's text and vision build (ADR-111): words and pictures in one space, for live camera search.
     * Apache 2.0 and ungated on Hugging Face (litert-community/embeddinggemma-2-text-vision-440m-litert-lm). The
     * first runs on the CPU or the GPU; the second is the Tensor G5's TPU build.
     */
    val VISION = Artifact("embeddinggemma-2-text-vision-440m.litertlm", 387_710_976L, "92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0")
    val VISION_TENSOR_G5 = Artifact("embeddinggemma-2-text-vision-440m_Google_Tensor_G5.litertlm", 559_418_036L, "96cdbae7b3dd6fa0f829fef2d0dd5bafc0179890d9bafbfe972db852e818d758")

    /** Names EmbeddingGemma 2's vectors, cut to [VISION_DIMENSIONS] values. */
    const val VISION_DIMENSIONS = 256
    const val VISION_MODEL_ID = "embeddinggemma-2-text-vision-440m@$VISION_DIMENSIONS"

    /** Where the model card and licence are. */
    const val SOURCE = "huggingface.co/litert-community/embeddinggemma-300m"

    /** Vectors are cut to this many values (Matryoshka) and kept at unit length. */
    const val DIMENSIONS = 256

    /** Names the vectors, so vectors from another model or size are never compared with these. */
    const val MODEL_ID = "embeddinggemma-300m-seq256@$DIMENSIONS"
}
