package io.github.sandeepvijayarao09.anu

import io.github.sandeepvijayarao09.anu.brain.gemini.GeminiBrain
import io.github.sandeepvijayarao09.anu.brain.ollama.OllamaBrain
import io.github.sandeepvijayarao09.anu.connector.mcp.McpTool
import io.github.sandeepvijayarao09.anu.core.security.SecretRef
import io.github.sandeepvijayarao09.anu.core.vault.ItemType
import io.github.sandeepvijayarao09.anu.core.vault.Protection
import io.github.sandeepvijayarao09.anu.core.vault.Vault
import io.github.sandeepvijayarao09.anu.core.vault.VaultItemInfo
import io.github.sandeepvijayarao09.anu.core.vault.VaultLockedException
import io.github.sandeepvijayarao09.anu.feature.vault.Revealed
import io.github.sandeepvijayarao09.anu.feature.vault.VaultEntry
import io.github.sandeepvijayarao09.anu.feature.vault.VaultShelf

/** The Vault page's view of `core:vault` (ADR-135): items without their secrets, and a reveal for the screen only. */
class VaultShelfImpl(private val vault: Vault) : VaultShelf {
    override suspend fun items(): List<VaultEntry> = vault.list().map(::entry)

    override suspend fun delete(id: String) {
        vault.delete(SecretRef(id))
    }

    override suspend fun reveal(id: String): Revealed = try {
        Revealed.Value(vault.revealForUser(SecretRef(id)))
    } catch (e: VaultLockedException) {
        Revealed.Locked
    }

    private fun entry(info: VaultItemInfo) = VaultEntry(
        id = info.id,
        kind = when (info.type) {
            ItemType.API_KEY -> "API key"
            ItemType.PASSWORD_LOGIN -> "Password"
            ItemType.OAUTH_TOKEN -> "Token"
            ItemType.APP_PASSWORD -> "App password"
            ItemType.SECURE_NOTE -> "Secure note"
        },
        label = info.label,
        service = info.service,
        hosts = info.boundHosts.sorted(),
        usedBy = info.allowedUsers.map(::caller).sorted(),
        whileUnlocked = info.protection == Protection.DEVICE_UNLOCKED,
        savedAtMillis = info.createdAtMillis,
        lastUsedAtMillis = info.lastUsedAtMillis,
    )

    companion object {
        /** The part of Anu a caller is, in words. */
        fun caller(id: String): String = when (id) {
            GeminiBrain.CALLER -> "Gemini"
            OllamaBrain.CALLER -> "Your Mac"
            McpTool.CALLER -> "Services"
            else -> id
        }
    }
}
