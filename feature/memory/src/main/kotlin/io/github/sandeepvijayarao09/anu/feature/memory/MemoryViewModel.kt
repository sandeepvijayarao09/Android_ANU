package io.github.sandeepvijayarao09.anu.feature.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sandeepvijayarao09.anu.core.memory.LearnResult
import io.github.sandeepvijayarao09.anu.core.memory.MemoryInspector
import io.github.sandeepvijayarao09.anu.core.memory.MemoryState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Memory tab (§8.4): everything Anu has learned, where it came from, and the user's controls over it. */
class MemoryViewModel(private val memory: MemoryInspector) : ViewModel() {

    val state: StateFlow<MemoryState> = memory.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryState())

    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    fun learnNow() = act { mutableMessage.value = describe(memory.learnNow()) }

    /** True while the on-device model reads something the user typed. */
    private val mutableAdding = MutableStateFlow(false)
    val adding: StateFlow<Boolean> = mutableAdding.asStateFlow()

    fun add(text: String) = act {
        mutableAdding.value = true
        try {
            val kept = memory.add(text)
            mutableMessage.value = if (kept == 0) "Anu didn't find anything to remember in that. Try “My sister is Priya”." else "Remembered ${plural(kept, "thing")}."
        } finally {
            mutableAdding.value = false
        }
    }

    fun delete(factId: Long) = act { memory.delete(factId) }

    fun confirm(factId: Long) = act { memory.confirm(factId) }

    fun correct(factId: Long, value: String) = act { memory.correct(factId, value) }

    fun forgetSource(eventId: Long) = act { memory.forgetSource(eventId) }

    fun dismissMessage() {
        mutableMessage.value = null
    }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onFailure { mutableMessage.value = it.message ?: "That didn't work." }
    }

    companion object {
        fun describe(result: LearnResult): String = when {
            result.busy -> "Anu is already learning."
            result.noModel -> "Download a model in Settings first. Anu learns only on this phone."
            result.learned > 0 -> "Learned ${plural(result.learned, "thing")} from ${plural(result.processed, "message")}."
            result.failed > 0 -> "Couldn't read ${plural(result.failed, "message")}. Anu will try again later."
            else -> "Nothing new to learn."
        }

        private fun plural(count: Int, word: String) = if (count == 1) "1 $word" else "$count ${word}s"
    }
}
