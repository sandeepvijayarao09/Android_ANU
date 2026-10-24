package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.BrainEvent
import io.github.sandeepvijayarao09.anu.core.model.BrainRequest
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.Sampling
import kotlinx.coroutines.flow.Flow

/**
 * Base's own opinion of a request the rules gave it (ADR-106). Beside Pro on the GPU, Base answers one word, BASE or
 * PRO, in a conversation of its own, and PRO sends the turn to Pro. On the router eval, Pro whenever the rules or Base
 * said so got 56 of 60 on the dev split and 37 of 40 held out, against the rules' 52 and 34. True means beyond Base;
 * null means no opinion, so the rules' choice stands.
 */
fun interface BaseOpinion {
    suspend fun beyondBase(request: String): Boolean?

    companion object {
        /** Written from the router eval's dev split rubric (BASE: no tools or one simple read, short answers). */
        const val PROMPT = "Choose which model on this phone should answer the user's request. Reply with one word, BASE or PRO, and nothing else.\n" +
            "BASE is Gemma 4 E2B, fast: greetings and small talk, short factual answers, and one simple action or lookup, " +
            "such as setting a timer, turning on the flashlight, or adding a note.\n" +
            "PRO is Gemma 4 E4B, smarter: anything with several steps or actions, scheduling and other date or time " +
            "arithmetic, reading or summarizing emails or chats, drafting replies or emails, shopping, explanations, " +
            "advice, plans, comparisons, rewriting, and any long or detailed writing."

        /** The one-shot request that asks it: greedy, a few tokens, no tools, no memory. */
        fun request(text: String) = BrainRequest(
            system = PROMPT,
            transcript = listOf(ChatMessage.User(text)),
            sampling = Sampling.Precise,
            maxOutputTokens = 8,
        )

        /** True for PRO, false for BASE, null when the answer names neither. */
        fun parse(answer: String): Boolean? = when (Regex("\\b(BASE|PRO)\\b").find(answer.uppercase())?.value) {
            "PRO" -> true
            "BASE" -> false
            else -> null
        }

        /** An opinion asked of [base], which runs one request in a conversation of its own and streams its answer. */
        fun of(base: (BrainRequest) -> Flow<BrainEvent>) = BaseOpinion { text ->
            val words = StringBuilder()
            base(request(text)).collect { event -> if (event is BrainEvent.TextDelta && !event.thought) words.append(event.text) }
            parse(words.toString())
        }
    }
}
