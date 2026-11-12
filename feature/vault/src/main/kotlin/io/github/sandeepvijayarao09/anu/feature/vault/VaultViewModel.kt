package io.github.sandeepvijayarao09.anu.feature.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A saved item as the Vault page shows it: everything but its secret (§11). */
data class VaultEntry(
    val id: String,
    /** "API key", "Password", "Token", "App password" or "Secure note". */
    val kind: String,
    val label: String,
    val service: String,
    /** The only sites it may be sent to. Empty: it's never sent. */
    val hosts: List<String>,
    /** The parts of Anu that may use it, in words ("Gemini", "Your Mac"). */
    val usedBy: List<String>,
    /** True when it works whenever the phone is unlocked; false when it needs a recent fingerprint or screen lock. */
    val whileUnlocked: Boolean,
    val savedAtMillis: Long,
    val lastUsedAtMillis: Long?,
)

/** What a reveal gave: the value, or that the vault needs the user to unlock it first. */
sealed interface Revealed {
    class Value(val secret: String) : Revealed {
        override fun toString() = "Value(…)" // never print the secret
    }

    /** The Keystore key needs a fresh fingerprint or screen lock: prompt, then try again. */
    data object Locked : Revealed
}

/** The vault, for this page only (ADR-135). The app backs it with `core:vault`, the only module that decrypts. */
interface VaultShelf {
    suspend fun items(): List<VaultEntry>

    suspend fun delete(id: String)

    /** The secret, for the user's eyes on this page and nowhere else (I1). */
    suspend fun reveal(id: String): Revealed
}

data class VaultUiState(
    val items: List<VaultEntry> = emptyList(),
    val loaded: Boolean = false,
    /** The item whose value is on screen, and the value. Cleared when its sheet closes. */
    val shownId: String? = null,
    val shownValue: String? = null,
    /** A reveal found the vault locked even after the prompt: the page asks again. */
    val unlockAgainFor: String? = null,
    val error: String? = null,
) {
    override fun toString() = "VaultUiState(${items.size} items, shown=${shownId != null})" // never print the secret
}

/**
 * The Vault page (§11, ADR-135): the saved keys, what each may reach, and Show and Delete. Show is reached only after
 * the system's biometric or screen-lock prompt, whatever the item's level; the page asks for it, then calls [reveal].
 * The value stays in this state only while its sheet is open: it's never logged, saved or sent (I1).
 */
class VaultViewModel(private val shelf: VaultShelf) : ViewModel() {
    private val mutable = MutableStateFlow(VaultUiState())
    val state: StateFlow<VaultUiState> = mutable.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val items = attempt { shelf.items() } ?: return@launch
            mutable.update { it.copy(items = items.sortedBy { item -> item.label.lowercase() }, loaded = true) }
        }
    }

    /** After the user passed the prompt: decrypt [id]'s value for the screen. A locked vault asks for the prompt again. */
    fun reveal(id: String) {
        viewModelScope.launch {
            when (val result = attempt { shelf.reveal(id) } ?: return@launch) {
                is Revealed.Value -> mutable.update { it.copy(shownId = id, shownValue = result.secret, unlockAgainFor = null) }
                Revealed.Locked -> mutable.update { it.copy(unlockAgainFor = id) }
            }
        }
    }

    /** The prompt was cancelled or failed: nothing is shown. [why], when the phone can't ask at all, is said on the page. */
    fun unlockDismissed(why: String? = null) = mutable.update { it.copy(unlockAgainFor = null, error = why ?: it.error) }

    /** The sheet closed: the value leaves the screen and this state. */
    fun hide() = mutable.update { it.copy(shownId = null, shownValue = null, unlockAgainFor = null) }

    fun delete(id: String) {
        viewModelScope.launch {
            attempt { shelf.delete(id) } ?: return@launch
            mutable.update { state ->
                state.copy(items = state.items.filterNot { it.id == id }, shownId = null, shownValue = null, error = null)
            }
        }
    }

    fun dismissError() = mutable.update { it.copy(error = null) }

    /** Runs [block], or says why it couldn't, in words without the secret. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        mutable.update { it.copy(error = "Anu couldn't open the vault. Try again.") }
        null
    }
}
