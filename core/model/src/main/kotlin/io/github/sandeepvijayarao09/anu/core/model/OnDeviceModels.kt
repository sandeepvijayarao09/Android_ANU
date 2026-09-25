package io.github.sandeepvijayarao09.anu.core.model

import kotlinx.coroutines.flow.Flow

/** Install state of an on-device model file. */
sealed interface ModelState {
    data object NotInstalled : ModelState

    data class Downloading(val done: Long, val total: Long) : ModelState

    data object Ready : ModelState

    data class Failed(val reason: String) : ModelState
}

data class ModelInfo(val id: BrainId, val fileName: String, val sizeBytes: Long, val variant: String)

/**
 * The on-device models as screens see them (§4.2: features depend on contracts, not on
 * `brain:litertlm`). Implemented by `ModelManager`.
 */
interface OnDeviceModels {
    fun info(id: BrainId): ModelInfo

    fun isInstalled(id: BrainId): Boolean

    fun state(id: BrainId): Flow<ModelState>

    fun download(id: BrainId)

    fun delete(id: BrainId)
}
