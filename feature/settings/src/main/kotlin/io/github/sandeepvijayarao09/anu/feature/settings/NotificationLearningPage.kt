package io.github.sandeepvijayarao09.anu.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandeepvijayarao09.anu.core.ui.AnuPage
import io.github.sandeepvijayarao09.anu.core.ui.AnuSwitch
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.Group
import io.github.sandeepvijayarao09.anu.core.ui.GroupDivider
import io.github.sandeepvijayarao09.anu.core.ui.GroupFooter
import io.github.sandeepvijayarao09.anu.core.ui.GroupRow
import io.github.sandeepvijayarao09.anu.core.ui.SectionHeader

/**
 * Learn from notifications (ADR-127): each app that has posted a notification, with a switch, all off at first.
 * Memory reads the chosen apps' notifications on the phone, while it's idle and charging, and what it notices
 * waits in the Memory tab for the user to confirm.
 */
@Composable
internal fun NotificationLearningPage(viewModel: SettingsViewModel, sentToday: Int, onOpenPrivacy: () -> Unit, onBack: () -> Unit) {
    val apps by viewModel.notifyingApps.collectAsStateWithLifecycle()
    val access by viewModel.messagingEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    AnuPage(
        "Learn from notifications",
        sentToday,
        onOpenPrivacy,
        back = BACK,
        onBack = onBack,
        subtitle = "Anu reads the apps you choose and notices lasting things, such as a friend's new job. Kept on this phone.",
    ) {
        item(key = "access") {
            val open = { viewModel.messagingSettings().firstOrNull { runCatching { context.startActivity(it) }.isSuccess }; Unit }
            Group {
                if (access != true) {
                    GroupRow("Allow notification access", titleColor = AnuTheme.colors.accent, onClick = open)
                } else {
                    // Access granted before ADR-127 keeps Android's filter of conversations only, and apps can't read the
                    // filter, so this opens Android's page, where its notification types are ticked.
                    GroupRow("Notification types", subtitle = "If an app is missing, turn on Notifications and Silent in Android's settings", chevron = true, onClick = open)
                }
            }
        }
        item(key = "apps") {
            Column {
                SectionHeader("Apps")
                if (apps.isEmpty()) {
                    Text(
                        "Apps appear here once they post a notification.",
                        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp),
                        style = AnuTheme.type.callout,
                        color = AnuTheme.colors.textMuted,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Group {
                        apps.forEachIndexed { i, app ->
                            if (i > 0) GroupDivider()
                            AppToggle(app, viewModel)
                        }
                    }
                }
                GroupFooter(
                    "Other people and apps write notifications, so Anu never follows what they say. It skips codes and " +
                        "passwords, keeps their words for 30 days, and asks before acting on its own when it uses what it noticed.",
                )
            }
        }
    }
}

@Composable
private fun AppToggle(app: NotifyingApp, viewModel: SettingsViewModel) {
    val haptics = LocalHapticFeedback.current
    GroupRow(
        app.label,
        modifier = Modifier.toggleable(value = app.learning, role = Role.Switch) { on ->
            haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            viewModel.setLearningFrom(app.packageName, on)
        },
        trailing = { AnuSwitch(app.learning, onCheckedChange = null) },
    )
}
