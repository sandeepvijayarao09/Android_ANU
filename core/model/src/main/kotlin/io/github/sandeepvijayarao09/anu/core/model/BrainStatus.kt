package io.github.sandeepvijayarao09.anu.core.model

/**
 * What the on-device models are doing, so a wait before the first word can say why (design review D10, ADR-074).
 * Idle once a model is ready and reading only the new message.
 */
sealed interface BrainStatus {
    data object Idle : BrainStatus

    /** Loading [id] into memory. */
    data class Loading(val id: BrainId) : BrainStatus

    /** Loading [id] on the GPU for the first time, which prepares its weights for this phone. */
    data class SettingUpGpu(val id: BrainId) : BrainStatus

    /** Setting another model aside to load [id]. */
    data class Switching(val id: BrainId) : BrainStatus

    /** Reading the whole chat again, after it was set aside or the model was reloaded. */
    data class Reading(val id: BrainId) : BrainStatus
}
