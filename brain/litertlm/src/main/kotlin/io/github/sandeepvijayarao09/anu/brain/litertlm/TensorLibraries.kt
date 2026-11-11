package io.github.sandeepvijayarao09.anu.brain.litertlm

import android.system.Os
import java.io.File

/**
 * The folder LiteRT looks in for the Pixel's TPU (ADR-102, ADR-128). The `personal` build's native library folder
 * also holds Qualcomm's dispatch library for the Snapdragon (ADR-115), and LiteRT takes the first dispatch library it
 * finds there, Qualcomm's, so on the Pixel the TPU engine failed to load and Base fell back to the CPU. This is a
 * folder with a link to Google Tensor's dispatch library alone. A build without Qualcomm's library uses the library
 * folder as it is.
 */
object TensorLibraries {
    const val TENSOR_DISPATCH = "libLiteRtDispatch_GoogleTensor.so"
    private const val QUALCOMM_DISPATCH = "libLiteRtDispatch_Qualcomm.so"

    /** The folder to give `Backend.NPU` for the TPU: [nativeLibraryDir], or links made in [into] when it holds both. */
    fun folder(nativeLibraryDir: String, into: File): String {
        val source = File(nativeLibraryDir)
        if (!File(source, QUALCOMM_DISPATCH).exists() || !File(source, TENSOR_DISPATCH).exists()) return nativeLibraryDir
        into.mkdirs()
        into.listFiles()?.forEach { it.delete() }
        Os.symlink(File(source, TENSOR_DISPATCH).path, File(into, TENSOR_DISPATCH).path)
        return into.path
    }
}
