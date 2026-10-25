package io.github.sandeepvijayarao09.anu.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandeepvijayarao09.anu.core.ui.AnuButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuIcons
import io.github.sandeepvijayarao09.anu.core.ui.AnuPage
import io.github.sandeepvijayarao09.anu.core.ui.AnuSheet
import io.github.sandeepvijayarao09.anu.core.ui.AnuSwitch
import io.github.sandeepvijayarao09.anu.core.ui.AnuTextField
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.Group
import io.github.sandeepvijayarao09.anu.core.ui.GroupDivider
import io.github.sandeepvijayarao09.anu.core.ui.GroupFooter
import io.github.sandeepvijayarao09.anu.core.ui.GroupRow
import io.github.sandeepvijayarao09.anu.core.ui.Notice
import io.github.sandeepvijayarao09.anu.core.ui.SectionHeader
import io.github.sandeepvijayarao09.anu.core.ui.SheetNote
import io.github.sandeepvijayarao09.anu.core.ui.SheetTitle

/**
 * Agent Skills (ADR-120), as AI Edge Gallery offers them: its built-in skills, each one on or off, and skills the user
 * adds from a web address or a folder on the phone. The model sees each skill's name and description, and loads its
 * instructions only when a request matches.
 */
@Composable
internal fun SkillsPage(viewModel: SettingsViewModel, sentToday: Int, onOpenPrivacy: () -> Unit, onBack: () -> Unit) {
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let { viewModel.addSkillFromFolder(it.toString()) }
    }
    AnuPage(
        "Skills",
        sentToday,
        onOpenPrivacy,
        back = BACK,
        onBack = onBack,
        subtitle = "Instructions and small programs Anu loads when a request matches, as in Google's AI Edge Gallery.",
    ) {
        message?.let { text -> item(key = "message") { Notice(text, viewModel::dismissMessage) } }
        val builtIn = skills.filter { it.builtIn }
        val added = skills.filterNot { it.builtIn }
        item(key = "added") {
            Column {
                SectionHeader("Added by you")
                Group {
                    added.forEach { skill ->
                        GroupRow(
                            skill.name,
                            subtitle = skill.description,
                            value = if (skill.enabled) "On" else "Off",
                            chevron = true,
                            onClick = { removing = skill.name },
                        )
                        GroupDivider()
                    }
                    GroupRow(
                        if (connecting) "Adding…" else "Add from a web address",
                        icon = AnuIcons.Plus,
                        titleColor = AnuTheme.colors.accent,
                        enabled = !connecting,
                        onClick = { adding = true },
                    )
                    GroupDivider()
                    GroupRow(
                        "Add from a folder",
                        icon = AnuIcons.Plus,
                        titleColor = AnuTheme.colors.accent,
                        enabled = !connecting,
                        onClick = { folder.launch(null) },
                    )
                }
                GroupFooter(
                    "A skill you add is someone else's instructions: after Anu reads one, it asks before changing anything. " +
                        "A skill that loads from the web asks every time, and each run is in the Privacy report.",
                )
            }
        }
        item(key = "built-in") {
            Column {
                SectionHeader("Built in")
                Group {
                    builtIn.forEachIndexed { i, skill ->
                        if (i > 0) GroupDivider()
                        SkillToggle(skill, viewModel)
                    }
                }
                GroupFooter("From Google's AI Edge Gallery, under the Apache 2.0 licence.")
            }
        }
    }
    if (adding) AddSkillSheet(onDismiss = { adding = false }, onAdd = viewModel::addSkillFromUrl)
    removing?.let { name ->
        skills.find { it.name == name }?.let { skill ->
            AnuSheet(onDismiss = { removing = null }) { close ->
                SheetTitle(skill.name, overline = "Skill")
                SheetNote(listOfNotNull(skill.description, skill.source?.let { "Added from $it" }).joinToString("\n"))
                Spacer(Modifier.height(12.dp))
                Group { SkillToggle(skill, viewModel) }
                Spacer(Modifier.height(18.dp))
                AnuButton("Remove skill", onClick = { viewModel.removeSkill(name); close() }, Modifier.fillMaxWidth(), secondary = true)
            }
        }
    }
}

@Composable
private fun SkillToggle(skill: SkillRow, viewModel: SettingsViewModel) {
    val haptics = LocalHapticFeedback.current
    GroupRow(
        skill.name,
        modifier = Modifier.toggleable(value = skill.enabled, role = Role.Switch) { on ->
            haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            viewModel.setSkillEnabled(skill.name, on)
        },
        subtitle = skill.description,
        trailing = { AnuSwitch(skill.enabled, onCheckedChange = null) },
    )
}

@Composable
private fun AddSkillSheet(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    AnuSheet(onDismiss) { close ->
        SheetTitle("Add a skill")
        Text(
            "The web address of a skill's folder: Anu reads its SKILL.md.",
            Modifier.padding(top = 8.dp),
            style = AnuTheme.type.callout,
            color = AnuTheme.colors.textMuted,
        )
        AnuTextField(
            url,
            { url = it },
            Modifier.padding(top = 18.dp),
            label = "Skill address",
            placeholder = "https://…/my-skill",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        )
        SheetNote("Only add skills from people you trust. Its scripts run from that address, and each run asks first.")
        Spacer(Modifier.height(18.dp))
        AnuButton("Add", onClick = { onAdd(url); close() }, Modifier.fillMaxWidth(), enabled = url.isNotBlank())
        Spacer(Modifier.height(10.dp))
        AnuButton("Cancel", onClick = close, Modifier.fillMaxWidth(), secondary = true)
    }
}
