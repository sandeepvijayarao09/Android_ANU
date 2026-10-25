package io.github.sandeepvijayarao09.anu.brain.nano

import android.content.Context
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.proofreading.Proofreader
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import com.google.mlkit.genai.rewriting.Rewriter
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import io.github.sandeepvijayarao09.anu.core.model.SystemWriter
import io.github.sandeepvijayarao09.anu.core.model.SystemWriterStatus
import io.github.sandeepvijayarao09.anu.core.model.SystemWritingTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AI Writing on Gemini Nano through AICore, with ML Kit's GenAI feature APIs (ADR-116): rewriting in four styles,
 * proofreading and summaries, in English. Nano runs in the system's AICore, on the phone.
 * - ML Kit's usage-log uploader is left out of the build, so nothing leaves Anu's process (I3).
 * - Each feature has its own client, made when first used and kept, since AICore shares one base model.
 * - Failures answer null, and AI Writing uses Gemma: AICore refuses an app that isn't on screen (S10's error 30),
 *   rewriting and proofreading take short texts only, and the system may be busy.
 */
class MlKitWriter(private val context: Context, private val scope: CoroutineScope) : SystemWriter {
    override val name: String = "Gemini Nano"

    private val mutable = MutableStateFlow<SystemWriterStatus>(SystemWriterStatus.Unavailable("Not checked yet"))
    override val status: StateFlow<SystemWriterStatus> = mutable.asStateFlow()

    private val lock = Mutex()
    private val rewriters = mutableMapOf<Int, Rewriter>()
    private var proofreader: Proofreader? = null
    private var summarizer: Summarizer? = null

    override suspend fun refresh() {
        if (mutable.value is SystemWriterStatus.Downloading) return
        mutable.value = try {
            when (proofreader().checkFeatureStatus().await()) {
                FeatureStatus.AVAILABLE -> SystemWriterStatus.Ready
                FeatureStatus.DOWNLOADABLE -> SystemWriterStatus.Downloadable
                FeatureStatus.DOWNLOADING -> SystemWriterStatus.Downloading(0, 0)
                else -> SystemWriterStatus.Unavailable("This phone doesn't offer Gemini Nano to Anu.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SystemWriterStatus.Unavailable(reason(e))
        }
    }

    override fun download() {
        if (mutable.value is SystemWriterStatus.Downloading) return
        mutable.value = SystemWriterStatus.Downloading(0, 0)
        scope.launch {
            try {
                // The features share Nano, and each adds its own small part, so each is fetched in turn.
                val features = listOf(proofreader().let { it::downloadFeature }) +
                    STYLES.values.map { style -> rewriter(style).let { it::downloadFeature } } +
                    listOf(summarizer().let { it::downloadFeature })
                for (fetch in features) fetch(progress()).await()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = SystemWriterStatus.Unavailable(reason(e))
            }
        }
    }

    override suspend fun write(task: SystemWritingTask, text: String): String? {
        if (mutable.value != SystemWriterStatus.Ready) return null
        val limit = if (task == SystemWritingTask.SUMMARIZE) SUMMARY_CHARS else SHORT_CHARS
        if (text.isBlank() || text.length > limit) return null
        return try {
            val out = when (task) {
                SystemWritingTask.PROOFREAD -> proofreader().runInference(ProofreadingRequest.builder(text).build()).await().results.firstOrNull()?.text
                SystemWritingTask.SUMMARIZE -> summarizer().runInference(SummarizationRequest.builder(text).build()).await().summary
                else -> rewriter(STYLES.getValue(task)).runInference(RewritingRequest.builder(text).build()).await().results.firstOrNull()?.text
            }
            out?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Kinds and codes only: an exception's message could quote the user's text.
            android.util.Log.d("Anu", "Gemini Nano: $task fell back, ${e.javaClass.simpleName} ${(e as? GenAiException ?: e.cause as? GenAiException)?.errorCode ?: ""}")
            null
        }
    }

    private fun progress() = object : DownloadCallback {
        private var total = 0L

        override fun onDownloadStarted(bytesToDownload: Long) {
            total = bytesToDownload
            mutable.value = SystemWriterStatus.Downloading(0, total)
        }

        override fun onDownloadProgress(totalBytesDownloaded: Long) {
            mutable.value = SystemWriterStatus.Downloading(totalBytesDownloaded, total)
        }

        override fun onDownloadCompleted() = Unit

        override fun onDownloadFailed(e: GenAiException) {
            mutable.value = SystemWriterStatus.Unavailable(reason(e))
        }
    }

    private suspend fun proofreader(): Proofreader = lock.withLock {
        proofreader ?: Proofreading.getClient(
            ProofreaderOptions.builder(context)
                .setInputType(ProofreaderOptions.InputType.KEYBOARD)
                .setLanguage(ProofreaderOptions.Language.ENGLISH)
                .build(),
        ).also { proofreader = it }
    }

    private suspend fun rewriter(style: Int): Rewriter = lock.withLock {
        rewriters.getOrPut(style) {
            Rewriting.getClient(RewriterOptions.builder(context).setOutputType(style).setLanguage(RewriterOptions.Language.ENGLISH).build())
        }
    }

    private suspend fun summarizer(): Summarizer = lock.withLock {
        summarizer ?: Summarization.getClient(
            SummarizerOptions.builder(context)
                .setInputType(SummarizerOptions.InputType.ARTICLE)
                .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
                .setLanguage(SummarizerOptions.Language.ENGLISH)
                .build(),
        ).also { summarizer = it }
    }

    private companion object {
        val STYLES = mapOf(
            SystemWritingTask.REPHRASE to RewriterOptions.OutputType.REPHRASE,
            SystemWritingTask.SHORTEN to RewriterOptions.OutputType.SHORTEN,
            SystemWritingTask.PROFESSIONAL to RewriterOptions.OutputType.PROFESSIONAL,
            SystemWritingTask.FRIENDLY to RewriterOptions.OutputType.FRIENDLY,
        )

        /** Rewriting and proofreading take up to 256 tokens, about 1,000 characters of English. */
        const val SHORT_CHARS = 1_000

        /** Summaries take up to about 4,000 tokens. */
        const val SUMMARY_CHARS = 12_000

        fun reason(e: Exception): String = when ((e as? GenAiException)?.errorCode ?: (e.cause as? GenAiException)?.errorCode) {
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> "Not enough space on the phone for Gemini Nano."
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> "Gemini Nano needs a system update first."
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE -> "This phone's AICore doesn't work with Anu's ML Kit."
            else -> "Gemini Nano isn't available: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
