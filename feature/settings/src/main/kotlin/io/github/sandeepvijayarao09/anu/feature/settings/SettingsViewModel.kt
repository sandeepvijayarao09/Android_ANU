package io.github.sandeepvijayarao09.anu.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ModelInfo
import io.github.sandeepvijayarao09.anu.core.model.ModelState
import io.github.sandeepvijayarao09.anu.core.model.OnDeviceModels
import io.github.sandeepvijayarao09.anu.core.security.LedgerEntry
import io.github.sandeepvijayarao09.anu.core.security.PrivacyLedger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Gemini key as the settings screen sees it. The key goes straight into the vault and is never shown again (§11). */
interface CloudKeySettings {
    val hasKey: StateFlow<Boolean>

    suspend fun save(apiKey: String)

    suspend fun remove()
}

data class ModelRow(val info: ModelInfo, val state: ModelState)

class SettingsViewModel(
    private val models: OnDeviceModels,
    private val cloudKey: CloudKeySettings,
    ledger: PrivacyLedger,
) : ViewModel() {

    // WorkManager doesn't emit when a file is deleted, so a delete bumps this to re-read the state.
    private val refresh = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val modelRows: StateFlow<List<ModelRow>> = refresh.flatMapLatest {
        combine(models.state(BrainId.BASE), models.state(BrainId.PRO)) { base, pro ->
            listOf(ModelRow(models.info(BrainId.BASE), base), ModelRow(models.info(BrainId.PRO), pro))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasCloudKey: StateFlow<Boolean> = cloudKey.hasKey
    val ledgerEntries: StateFlow<List<LedgerEntry>> = ledger.entries

    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    fun download(id: BrainId) = models.download(id)

    fun delete(id: BrainId) {
        models.delete(id)
        refresh.value++
    }

    fun saveCloudKey(key: String) = viewModelScope.launch {
        mutableMessage.value = runCatching { cloudKey.save(key) }
            .fold({ "Key saved to the vault." }, { "Couldn't save the key: ${it.message}" })
    }

    fun removeCloudKey() = viewModelScope.launch {
        cloudKey.remove()
        mutableMessage.value = "Key removed. Anu will stay on this phone."
    }

    fun dismissMessage() {
        mutableMessage.value = null
    }
}
