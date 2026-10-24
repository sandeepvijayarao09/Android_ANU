package io.github.sandeepvijayarao09.anu.brain.vision

import android.system.Os
import java.io.File

/**
 * The folder LiteRT looks in for the Snapdragon's NPU (ADR-115). The app's native library folder also holds Google
 * Tensor's dispatch library for the Pixel, and LiteRT takes the first dispatch library it finds there, so this is a
 * folder of links to Qualcomm's libraries alone: the dispatch library, the compiler plugin and QNN's. The links are
 * made again on each open, because an app update moves the library folder.
 */
object QualcommLibraries {
    /** Links in [into] to the Qualcomm libraries in [nativeLibraryDir], or null when the app doesn't carry them. */
    fun folder(nativeLibraryDir: String, into: File): String? {
        val source = File(nativeLibraryDir)
        if (!File(source, YoloxDetector.QUALCOMM_DISPATCH).exists()) return null
        into.mkdirs()
        into.listFiles()?.forEach { it.delete() }
        source.listFiles { f -> f.name in NAMED || f.name.startsWith("libQnn") }?.forEach {
            Os.symlink(it.path, File(into, it.name).path)
        }
        return into.path
    }

    private val NAMED = setOf(YoloxDetector.QUALCOMM_DISPATCH, "libLiteRtCompilerPlugin_Qualcomm.so")
}
