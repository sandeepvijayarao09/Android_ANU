package io.github.sandeepvijayarao09.anu.core.model

import kotlinx.coroutines.flow.Flow
import java.io.InputStream

/** Install state of an on-device model file. */
sealed interface ModelState {
    data object NotInstalled : ModelState

    /** [bytesPerSecond] is this download's average since it started or resumed, 0 until it's known. */
    data class Downloading(val done: Long, val total: Long, val bytesPerSecond: Long = 0) : ModelState

    data object Ready : ModelState

    data class Failed(val reason: String) : ModelState
}

data class ModelInfo(
    val id: BrainId,
    val fileName: String,
    val sizeBytes: Long,
    val variant: String,
    /** The weight cache the runtime writes the first time the model runs, measured in spike S1 (ADR-020, ADR-061). */
    val cacheBytes: Long = 0,
    /** The memory a phone should have for this model, in GB (ADR-066). 0 when there's no guidance. */
    val minMemoryGb: Int = 0,
) {
    /** The space the model needs once it has run: its file and its cache. */
    val spaceNeeded: Long get() = sizeBytes + cacheBytes
}

/**
 * The on-device models as screens see them (§4.2: features depend on contracts, not on
 * `brain:litertlm`). Implemented by `ModelManager`.
 */
interface OnDeviceModels {
    fun info(id: BrainId): ModelInfo

    fun isInstalled(id: BrainId): Boolean

    /** The space the model's files take now: the model, a partial download, and its weight cache. */
    fun spaceUsed(id: BrainId): Long

    fun state(id: BrainId): Flow<ModelState>

    fun download(id: BrainId)

    fun delete(id: BrainId)

    /** The phone's memory as sold, in whole GB, to compare with [ModelInfo.minMemoryGb]. 0 when unknown. */
    val deviceMemoryGb: Int get() = 0
}

/**
 * Installs a model file the user already has on the phone, such as one downloaded in the browser (ADR-067).
 * Only an exact file from Anu's catalogs is kept: its size and SHA-256 must match, whatever its name.
 */
interface ModelInstaller {
    /**
     * Reads the file from [open] and installs it if it is one this phone uses. [size] is what the file's
     * provider reports, if anything, so a file that can't match is refused before it's read.
     */
    suspend fun install(size: Long?, open: () -> InputStream, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): InstallOutcome
}

/** What [ModelInstaller.install] did, in words for the user. */
sealed interface InstallOutcome {
    /**
     * [what] names the file, such as "Gemma 4 E4B" or "EmbeddingGemma's tokenizer". [alsoNeeds] names a file it
     * works only with that isn't installed yet.
     */
    data class Done(val what: String, val alsoNeeds: String? = null) : InstallOutcome

    /** It was already installed; the copy was not kept. */
    data class AlreadyThere(val what: String) : InstallOutcome

    data class Refused(val reason: String) : InstallOutcome
}
