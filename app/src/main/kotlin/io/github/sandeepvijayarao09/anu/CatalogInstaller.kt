package io.github.sandeepvijayarao09.anu

import io.github.sandeepvijayarao09.anu.core.model.InstallOutcome
import io.github.sandeepvijayarao09.anu.core.model.ModelInstaller
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** A file in one of Anu's model catalogs, with the name it's installed under and how the user knows it. */
data class KnownFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
    val what: String,
    /** The name of another file this one works only with, such as EmbeddingGemma's tokenizer. */
    val pairedWith: String? = null,
)

/**
 * Installs a model file the user already has, such as one downloaded in the phone's browser (ADR-067). A file is
 * kept only if its size and SHA-256 match a file this phone uses, whatever it's called, so a renamed download still
 * installs and a changed or unknown file never does (§12.3). It's hashed while it's copied, so it's read once, and
 * a file whose reported size matches nothing is refused before it's read.
 */
class CatalogInstaller(
    private val dir: File,
    /** The files this phone uses: each chat model's chosen build, and search by meaning's two files. */
    private val wanted: () -> List<KnownFile>,
    /** Every file in the catalogs, to name a file this phone doesn't use. */
    private val known: List<KnownFile>,
    private val onInstalled: (KnownFile) -> Unit = {},
    private val freeSpace: (File) -> Long = File::getUsableSpace,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ModelInstaller {

    override suspend fun install(size: Long?, open: () -> InputStream, onProgress: (done: Long, total: Long) -> Unit): InstallOutcome =
        withContext(io) {
            val use = wanted()
            if (size != null) {
                val match = use.firstOrNull { it.bytes == size } ?: return@withContext InstallOutcome.Refused(notUsed(size))
                if (File(dir, match.name).length() == match.bytes) return@withContext InstallOutcome.AlreadyThere(match.what)
            }
            val expected = size ?: use.maxOf { it.bytes }
            dir.mkdirs()
            if (freeSpace(dir) < expected + SPARE_BYTES) {
                return@withContext InstallOutcome.Refused("There isn't enough free space. This needs ${gigabytes(expected + SPARE_BYTES)} free.")
            }
            val part = File(dir, PART)
            try {
                val (copied, hash) = copy(open, part, limit = use.maxOf { it.bytes }) { done -> onProgress(done, expected) }
                    ?: return@withContext InstallOutcome.Refused(UNKNOWN)
                val match = use.firstOrNull { it.bytes == copied && it.sha256 == hash }
                    ?: return@withContext InstallOutcome.Refused(use.firstOrNull { it.bytes == copied }?.let(::changed) ?: notUsed(copied))
                val target = File(dir, match.name)
                if (target.length() == match.bytes) return@withContext InstallOutcome.AlreadyThere(match.what)
                if (!part.renameTo(target)) return@withContext InstallOutcome.Refused("The file couldn't be moved into place. Try again.")
                onInstalled(match)
                InstallOutcome.Done(match.what, missingPartner(match))
            } finally {
                part.delete()
            }
        }

    /** Copies into [part] while hashing, or returns null once the file grows past [limit], the largest file it could be. */
    private suspend fun copy(open: () -> InputStream, part: File, limit: Long, onProgress: (Long) -> Unit): Pair<Long, String>? =
        withContext(io) {
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            open().use { input ->
                FileOutputStream(part).use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        done += read
                        if (done > limit) return@withContext null
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                        onProgress(done)
                    }
                }
            }
            done to digest.digest().joinToString("") { "%02x".format(it) }
        }

    /** The file [file] works only with, if it isn't installed yet. */
    private fun missingPartner(file: KnownFile): String? {
        val other = known.firstOrNull { it.name == file.pairedWith } ?: return null
        return if (File(dir, other.name).length() == other.bytes) null else "${other.what} (${other.name})"
    }

    private fun notUsed(size: Long): String =
        known.firstOrNull { it.bytes == size }?.let { "That's ${it.what}, which this phone doesn't use." } ?: UNKNOWN

    private fun changed(file: KnownFile) =
        "That file isn't ${file.what} byte for byte, so it wasn't installed. It may be incomplete or changed."

    private fun gigabytes(bytes: Long) = String.format(Locale.getDefault(), "%.1f GB", bytes / 1e9)

    private companion object {
        const val PART = "install.part"
        const val UNKNOWN = "That isn't a file Anu knows. Anu installs only the model files it lists, checked byte for byte."

        /** Room to spare beyond the file itself. */
        const val SPARE_BYTES = 200_000_000L
    }
}
