package io.github.sandeepvijayarao09.anu.brain.embedding

/**
 * The search-by-meaning model (ADR-055): EmbeddingGemma 300M, mixed precision, 256 tokens, for the CPU. Messages
 * and facts are short, and a query always fills its token window, so 256 tokens halve the work of 512 (ADR-062).
 * Its Hugging Face repo is gated, so the user downloads it after accepting the Gemma terms, and it is
 * installed from their computer: scripts/dev-install-model.sh checks these hashes first. Each file's name,
 * size and hash stay on one line, which is how the script finds them.
 */
object EmbeddingCatalog {
    data class Artifact(val name: String, val bytes: Long, val sha256: String)

    val MODEL = Artifact("embeddinggemma-300M_seq256_mixed-precision.tflite", 179_131_736L, "37115ef7bff76cd37dd86abe503ff511b1032bf85fc624a85c49c84899e92bc5")
    val TOKENIZER = Artifact("sentencepiece.model", 4_683_319L, "d6daa52d93d7aad10e8388bd526c4e501d914b47177398d1d9621f1fe48438c7")

    /** Where the model card and licence are. */
    const val SOURCE = "huggingface.co/litert-community/embeddinggemma-300m"

    /** Vectors are cut to this many values (Matryoshka) and kept at unit length. */
    const val DIMENSIONS = 256

    /** Names the vectors, so vectors from another model or size are never compared with these. */
    const val MODEL_ID = "embeddinggemma-300m-seq256@$DIMENSIONS"
}
