package io.github.sandeepvijayarao09.anu.brain.litertlm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

class ModelDownloadException(message: String, val retryable: Boolean) : IOException(message)

/**
 * Downloads one model file into a `.part` file. It resumes with an HTTP Range request and checks the
 * pinned size and SHA-256 before the file is renamed into place. A file that fails the check is deleted,
 * and is never loaded (§5, §12.3).
 */
class ResumableDownload(private val http: OkHttpClient, private val io: CoroutineDispatcher = Dispatchers.IO) {

    suspend fun run(artifact: ModelArtifact, target: File, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): File =
        withContext(io) {
            if (target.length() == artifact.sizeBytes && sha256(target) == artifact.sha256) return@withContext target
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            if (part.length() > artifact.sizeBytes) part.delete()
            val offset = part.length()

            if (offset < artifact.sizeBytes) {
                val request = Request.Builder().url(artifact.url)
                    .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                    .build()
                http.newCall(request).execute().use { response ->
                    val append = when (response.code) {
                        206 -> true
                        200 -> false // the server ignored the range, so start over
                        in 500..599, 408, 429 -> throw ModelDownloadException("server said ${response.code}", retryable = true)
                        else -> throw ModelDownloadException("server said ${response.code}", retryable = false)
                    }
                    var done = if (append) offset else 0L
                    FileOutputStream(part, append).use { out ->
                        val source = response.body.source()
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            ensureActive() // WorkManager stops us; the .part file stays for the next resume
                            val read = source.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            done += read
                            onProgress(done, artifact.sizeBytes)
                        }
                    }
                }
            }

            if (part.length() != artifact.sizeBytes) {
                throw ModelDownloadException("got ${part.length()} of ${artifact.sizeBytes} bytes", retryable = true)
            }
            if (sha256(part) != artifact.sha256) {
                part.delete()
                throw ModelDownloadException("checksum mismatch for ${artifact.fileName}; the file was deleted", retryable = false)
            }
            if (!part.renameTo(target)) throw ModelDownloadException("could not move ${artifact.fileName} into place", retryable = true)
            target
        }

    companion object {
        fun sha256(file: File): String? {
            if (!file.exists()) return null
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
