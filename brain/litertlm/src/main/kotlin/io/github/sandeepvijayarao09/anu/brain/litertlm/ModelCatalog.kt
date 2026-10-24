package io.github.sandeepvijayarao09.anu.brain.litertlm

import io.github.sandeepvijayarao09.anu.core.model.BrainId

/** Which build of a model file to use. Each backend has its own file (ADR-019). */
enum class ModelVariant { CPU, GPU, NPU_TENSOR_G5, NPU_TENSOR_G6, NPU_SNAPDRAGON_SM8750 }

data class ModelArtifact(
    val brain: BrainId,
    val variant: ModelVariant,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    /**
     * The weight cache the CPU backend writes the first time the model runs (ADR-020), measured in S1: the same
     * on the Pixel 10 Pro and the emulator. 0 where it hasn't been measured.
     */
    val cacheBytes: Long = 0,
)

/**
 * The model files Anu may download (ARCHITECTURE §5). URLs are pinned to a Hugging Face commit, and
 * every file is checked against its SHA-256 before use (threat model: tampered model file, §12.3).
 * Hashes and sizes are copied from the Hugging Face API. The E4B CPU hash was also computed locally
 * on 2026-09-25.
 */
object ModelCatalog {
    private const val E2B = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/"
    private const val E4B = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/2eee7ac325f20eb8c9ac1d0e972f7c84663062da/"

    val all: List<ModelArtifact> = listOf(
        ModelArtifact(BrainId.BASE, ModelVariant.CPU, "gemma-4-E2B-it.litertlm", E2B + "gemma-4-E2B-it.litertlm", 2_588_147_712, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", cacheBytes = 788_412_736),
        ModelArtifact(BrainId.BASE, ModelVariant.GPU, "gemma-4-E2B-it-gpu.litertlm", E2B + "gemma-4-E2B-it-gpu.litertlm", 2_008_432_640, "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5"),
        ModelArtifact(BrainId.BASE, ModelVariant.NPU_TENSOR_G5, "gemma-4-E2B-it_Google_Tensor_G5.litertlm", E2B + "gemma-4-E2B-it_Google_Tensor_G5.litertlm", 3_113_545_589, "af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc"),
        ModelArtifact(BrainId.BASE, ModelVariant.NPU_TENSOR_G6, "gemma-4-E2B-it_Google_Tensor_G6.litertlm", E2B + "gemma-4-E2B-it_Google_Tensor_G6.litertlm", 3_313_938_293, "f86c7c19c736e9307267946edef58b0a45494a9127036d54d90dd6b7617c95b7"),
        ModelArtifact(BrainId.BASE, ModelVariant.NPU_SNAPDRAGON_SM8750, "gemma-4-E2B-it_qualcomm_sm8750.litertlm", E2B + "gemma-4-E2B-it_qualcomm_sm8750.litertlm", 3_016_294_400, "41dd675fbe735b6029012b5576a5716bac614fd8156de0128db4c9dff3cebd4e"),
        ModelArtifact(BrainId.PRO, ModelVariant.CPU, "gemma-4-E4B-it.litertlm", E4B + "gemma-4-E4B-it.litertlm", 3_659_530_240, "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0", cacheBytes = 2_210_334_416),
        ModelArtifact(BrainId.PRO, ModelVariant.GPU, "gemma-4-E4B-it-gpu.litertlm", E4B + "gemma-4-E4B-it-gpu.litertlm", 2_969_059_328, "4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff"),
    )

    /**
     * Chips whose GPU builds have passed spike S1, with correct answers at a measured speed. Only these use
     * the GPU (ADR-059). None has yet: on the Pixel 10 Pro (Tensor G5), E4B's GPU build wrote garbage.
     */
    val GPU_VERIFIED: Set<String> = emptySet()

    /**
     * TPU builds that have passed S1's contract on their chip (ADR-102, ADR-103). Base runs on one when its file is
     * installed. Memory extraction stays on Pro, on the CPU, because the TPU missed a fact the CPU finds.
     */
    val TPU_VERIFIED: Set<ModelVariant> = setOf(ModelVariant.NPU_TENSOR_G5)

    /**
     * Chips where Pro's usual file passed S1's contract on the GPU, and stayed loaded beside Base on the TPU with both
     * answering correctly (ADR-106). Pro's chat runs there on the GPU. Its extraction missed a fact the CPU finds, so
     * memory keeps the CPU.
     */
    val PRO_GPU_CHIPS: Set<ModelVariant> = setOf(ModelVariant.NPU_TENSOR_G5)

    /** Whether Pro's chat may run on the GPU on the chip [socModel] names (ADR-106). */
    fun proOnGpu(socModel: String): Boolean = npuFor(socModel) in PRO_GPU_CHIPS

    /** [brain]'s TPU build for the chip [socModel] names, if that chip's TPU is verified. */
    fun tpuFor(brain: BrainId, socModel: String, verified: Set<ModelVariant> = TPU_VERIFIED): ModelArtifact? {
        val variant = npuFor(socModel)?.takeIf { it in verified } ?: return null
        return all.firstOrNull { it.brain == brain && it.variant == variant }
    }

    /**
     * The memory a phone should have for each model, in GB, as Google's AI Edge Gallery asks (`minDeviceMemoryInGb`
     * in its 1.0.19 model list). Below it a model still runs, but Android closes other apps to make room, and it may
     * be slow (ADR-066).
     */
    fun minMemoryGb(brain: BrainId): Int = when (brain) {
        BrainId.BASE -> 8
        BrainId.PRO -> 12
        else -> 0
    }

    /** The build to prefer on a device with [socModel]: the GPU only on a [verified] chip, never on an emulator. */
    fun preferredVariant(socModel: String, emulator: Boolean, verified: Set<String> = GPU_VERIFIED): ModelVariant =
        if (!emulator && socModel in verified) ModelVariant.GPU else ModelVariant.CPU

    /**
     * The file for [brain] on this device.
     * - [socModel] is `Build.SOC_MODEL`.
     * - NPU builds are used only when [allowNpu] is set: the chip names reported on real devices are still
     *   unverified (spike S1).
     * - Otherwise [preferred] is used: the CPU build, unless the chip's GPU is verified ([preferredVariant]).
     */
    fun choose(brain: BrainId, socModel: String, preferred: ModelVariant = ModelVariant.CPU, allowNpu: Boolean = false): ModelArtifact {
        val candidates = all.filter { it.brain == brain }
        require(candidates.isNotEmpty()) { "No on-device model for $brain" }
        if (allowNpu) npuFor(socModel)?.let { npu -> candidates.firstOrNull { it.variant == npu }?.let { return it } }
        return candidates.firstOrNull { it.variant == preferred } ?: candidates.first { it.variant == ModelVariant.CPU }
    }

    private fun npuFor(socModel: String): ModelVariant? {
        val soc = socModel.lowercase().replace(" ", "")
        return when {
            "tensorg5" in soc -> ModelVariant.NPU_TENSOR_G5
            "tensorg6" in soc -> ModelVariant.NPU_TENSOR_G6
            "sm8750" in soc -> ModelVariant.NPU_SNAPDRAGON_SM8750
            else -> null
        }
    }
}
