package io.github.sandeepvijayarao09.anu.core.model

import kotlinx.coroutines.flow.StateFlow

/** The writing tasks the system's own model does (ADR-116): ML Kit's rewriting styles, proofreading and summaries. */
enum class SystemWritingTask { REPHRASE, SHORTEN, PROFESSIONAL, FRIENDLY, PROOFREAD, SUMMARIZE }

/** Whether the system's writing model can be used, as Settings shows it. */
sealed interface SystemWriterStatus {
    /** This phone has no such model, or doesn't offer it to Anu. */
    data class Unavailable(val reason: String) : SystemWriterStatus

    /** The system can download it, on the user's word. */
    data object Downloadable : SystemWriterStatus

    /** [done] of [total] bytes; [total] is 0 until the system says. */
    data class Downloading(val done: Long, val total: Long) : SystemWriterStatus

    data object Ready : SystemWriterStatus
}

/**
 * The phone's own writing model, such as Gemini Nano through Android's AICore (ADR-116). It does single-turn
 * writing on the phone, with no tools and no memory. A null answer means it can't now (not downloaded, busy, Anu not
 * on screen, too long or a language it lacks), and the caller uses Gemma instead.
 */
interface SystemWriter {
    /** For labels, such as "Gemini Nano". */
    val name: String
    val status: StateFlow<SystemWriterStatus>

    /** Asks the system again, for example when Settings opens. */
    suspend fun refresh()

    /** Starts the system's download. Only on the user's tap: the system fetches the model, not Anu. */
    fun download()

    suspend fun write(task: SystemWritingTask, text: String): String?
}
