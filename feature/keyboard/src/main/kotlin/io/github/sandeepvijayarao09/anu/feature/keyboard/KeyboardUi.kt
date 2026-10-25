package io.github.sandeepvijayarao09.anu.feature.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sandeepvijayarao09.anu.core.router.Tier
import io.github.sandeepvijayarao09.anu.core.ui.AnuButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuIcons
import io.github.sandeepvijayarao09.anu.core.ui.AnuPill
import io.github.sandeepvijayarao09.anu.core.ui.AnuShapes
import io.github.sandeepvijayarao09.anu.core.ui.AnuTextButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.PrivacyDot
import io.github.sandeepvijayarao09.anu.core.ui.anuCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the keys do to the field. The service implements it through `InputConnection`. */
interface KeyboardInput {
    fun text(text: String)
    fun backspace()
    fun enter()
    fun nextKeyboard()
    fun pickKeyboard()
}

/** What the AI strip does. */
interface AssistInput {
    /** Runs [action]; [KeyboardAction.ASK] opens the Ask field instead. */
    fun run(action: KeyboardAction)

    /** Commits the preview, or the emoji [choice] tapped. */
    fun accept(choice: String?)

    /** Runs what was typed into the Ask field. */
    fun ask()

    /** Discard, Stop, OK, or closing the Ask field. */
    fun discard()
}

/**
 * Anu's keyboard (ADR-109, DESIGN.md): the AI strip, or its preview, above QWERTY keys on the page color. Neutral keys,
 * the ink enter key for a field's action, and the green dot only where it's true: the AI works on this phone.
 */
@Composable
fun AnuKeyboard(
    policy: FieldPolicy,
    assist: AssistState,
    shift: Shift,
    page: KeyPage,
    onShift: () -> Unit,
    onPage: (KeyPage) -> Unit,
    keys: KeyboardInput,
    ai: AssistInput,
    /** What's typed into the Ask field while it's open (the keys type there then), or null. */
    asking: String? = null,
) {
    val colors = AnuTheme.colors
    // While the Ask field is open, enter runs it.
    val keyPolicy = if (asking != null) policy.copy(enter = EnterKey.GO) else policy
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .navigationBarsPadding()
            .padding(horizontal = 3.dp)
            .padding(bottom = 4.dp),
    ) {
        if (policy.layout == KeyLayout.LETTERS) Top(policy, assist, ai, asking)
        val rows = if (policy.layout == KeyLayout.LETTERS) Keys.rows(page, shift) else Keys.pad(policy.layout)
        Column(Modifier.padding(top = if (policy.layout == KeyLayout.LETTERS) 0.dp else 8.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().height(54.dp)) {
                    row.forEach { key -> KeyCap(key, keyPolicy, shift, onShift, onPage, keys) }
                }
            }
        }
    }
}

@Composable
private fun Top(policy: FieldPolicy, assist: AssistState, ai: AssistInput, asking: String?) {
    val colors = AnuTheme.colors
    val type = AnuTheme.type
    when {
        policy.private -> StripLine {
            Icon(KeyIcons.Lock, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
            Text("Private field. Anu reads nothing here.", style = type.caption, color = colors.textMuted)
        }
        !policy.ai -> StripLine {
            Text("AI isn't offered in this field.", style = type.caption, color = colors.textMuted)
        }
        assist is AssistState.Ready && assist.choices.isNotEmpty() -> EmojiChoices(
            heading = heading(assist.action, null, assist.tier, assist.engine),
            choices = assist.choices,
            ai = ai,
        )
        assist is AssistState.Ready -> Preview(
            heading = heading(assist.action, assist.chatTitle, assist.tier, assist.engine, assist.asked),
            text = assist.result,
            done = true,
            replaces = assist.replaces,
            ai = ai,
        )
        assist is AssistState.Working && assist.partial.isNotBlank() -> Preview(
            heading = heading(assist.action, null, assist.tier),
            text = assist.partial,
            done = false,
            replaces = false,
            ai = ai,
        )
        assist is AssistState.Working -> StripLine {
            PrivacyDot(away = false)
            Text(assist.action.working, style = type.label, color = colors.textMuted, modifier = Modifier.weight(1f))
            AnuTextButton("Stop", onClick = ai::discard)
        }
        asking != null && assist !is AssistState.Working -> AskLine(asking, ai)
        assist is AssistState.Note -> StripLine {
            Text(assist.text, style = type.caption, color = colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            AnuTextButton("OK", onClick = ai::discard)
        }
        else -> Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.semantics(mergeDescendants = true) { contentDescription = "Anu's writing, on this phone" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(AnuIcons.Sparkle, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
                PrivacyDot(away = false)
            }
            Spacer(Modifier.size(2.dp))
            AskPill(onClick = { ai.run(KeyboardAction.ASK) })
            KeyboardAction.strip(policy.chat).forEach { action -> AnuPill(action.label, onClick = { ai.run(action) }) }
        }
    }
}

private fun heading(action: KeyboardAction, chatTitle: String?, tier: Tier?, engine: String? = null, asked: String? = null): String = buildString {
    append(if (asked != null) "“${asked.take(40)}${if (asked.length > 40) "…" else ""}”" else action.label)
    if (chatTitle != null) append(" to ").append(chatTitle)
    append(" · On this phone")
    if (engine != null) append(" · ").append(engine)
    else when (tier) {
        Tier.BASE -> append(" · Base")
        Tier.PRO -> append(" · Pro")
        else -> Unit
    }
}

@Composable
private fun StripLine(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** The result, before anything reaches the field: Replace or Insert commits it, Discard drops it. */
@Composable
private fun Preview(heading: String, text: String, done: Boolean, replaces: Boolean, ai: AssistInput) {
    val colors = AnuTheme.colors
    val type = AnuTheme.type
    Column(
        Modifier
            .padding(horizontal = 5.dp, vertical = 6.dp)
            .fillMaxWidth()
            .anuCard()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            PrivacyDot(away = false)
            Text(heading, style = type.label, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            text,
            style = type.body,
            color = if (done) colors.text else colors.textMuted,
            modifier = Modifier.heightIn(max = 116.dp).verticalScroll(rememberScrollState()),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnuButton(if (done) "Discard" else "Stop", onClick = ai::discard, secondary = true, modifier = Modifier.weight(1f))
            AnuButton(if (replaces) "Replace" else "Insert", onClick = { ai.accept(null) }, enabled = done, modifier = Modifier.weight(1f))
        }
    }
}

/** "Ask Anu": the first pill, ink rather than tinted, since it opens a field rather than running at once. */
@Composable
private fun AskPill(onClick: () -> Unit) {
    val colors = AnuTheme.colors
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(AnuShapes.full)
            .background(colors.surface)
            .clickable(role = Role.Button, onClickLabel = "Ask Anu to write or change your text", onClick = onClick)
            .padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(AnuIcons.Sparkle, contentDescription = null, tint = colors.text, modifier = Modifier.size(16.dp))
        Text("Ask Anu", style = AnuTheme.type.label.copy(fontWeight = FontWeight(600)), color = colors.text)
    }
}

/**
 * The Ask field (ADR-122): the keys type into it instead of the app, and enter (or Go) runs it on the field's text, or
 * writes new text into an empty field. It's Anu's own text, kept only until it runs or closes.
 */
@Composable
private fun AskLine(text: String, ai: AssistInput) {
    val colors = AnuTheme.colors
    val type = AnuTheme.type
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(AnuShapes.full).clickable(role = Role.Button, onClickLabel = "Close Ask Anu", onClick = ai::discard),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AnuIcons.Close, contentDescription = "Close", tint = colors.textMuted, modifier = Modifier.size(18.dp))
        }
        Row(
            Modifier
                .weight(1f)
                .height(36.dp)
                .clip(AnuShapes.full)
                .background(colors.surface)
                .padding(horizontal = 14.dp)
                .semantics(mergeDescendants = true) { contentDescription = if (text.isEmpty()) "Ask Anu, empty" else "Ask Anu: $text" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text.ifEmpty { "Ask Anu to write or change…" },
                style = type.callout,
                color = if (text.isEmpty()) colors.textFaint else colors.text,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (text.isNotEmpty()) Box(Modifier.padding(start = 1.dp).size(width = 1.5.dp, height = 18.dp).background(colors.accent))
        }
        AnuTextButton("Go", onClick = ai::ask)
    }
}

/** Emoji to choose from: one tap inserts that one at the cursor. */
@Composable
private fun EmojiChoices(heading: String, choices: List<String>, ai: AssistInput) {
    val colors = AnuTheme.colors
    Column(
        Modifier
            .padding(horizontal = 5.dp, vertical = 6.dp)
            .fillMaxWidth()
            .anuCard()
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            PrivacyDot(away = false)
            Text(heading, style = AnuTheme.type.label, color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            AnuTextButton("Discard", onClick = ai::discard, color = colors.textMuted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            choices.forEach { emoji ->
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(AnuShapes.sm)
                        .clickable(role = Role.Button, onClickLabel = "Insert $emoji") { ai.accept(emoji) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, fontSize = 28.sp)
                }
            }
        }
    }
}

@Composable
private fun RowScope.KeyCap(
    key: Key,
    policy: FieldPolicy,
    shift: Shift,
    onShift: () -> Unit,
    onPage: (KeyPage) -> Unit,
    keys: KeyboardInput,
) {
    if (key is Key.Gap) {
        Spacer(Modifier.weight(key.weight))
        return
    }
    val colors = AnuTheme.colors
    val type = AnuTheme.type
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val action = key is Key.Enter && policy.enter != EnterKey.NEWLINE
    val modifier = key !is Key.Text && key !is Key.Space
    val fill = when {
        action -> colors.primary
        pressed -> if (colors.isDark) colors.surface else colors.separator
        modifier -> if (colors.isDark) colors.surface else colors.separator
        else -> if (colors.isDark) colors.surface2 else colors.surface
    }
    val ink = if (action) colors.onPrimary else colors.text
    val letterStyle = type.title.copy(fontSize = 23.sp, fontWeight = FontWeight(400))
    val symbolStyle = type.headline.copy(fontSize = 20.sp, fontWeight = FontWeight(400))
    val label = when (key) {
        is Key.Text -> if (key.text == " ") "Space" else key.text
        is Key.Shift -> when (shift) {
            Shift.OFF -> "Shift"
            Shift.ONCE -> "Shift, on"
            Shift.LOCKED -> "Caps lock"
        }
        is Key.Backspace -> "Delete"
        is Key.Page -> when (key.to) {
            KeyPage.LETTERS -> "Letters"
            KeyPage.SYMBOLS -> "Symbols"
            KeyPage.MORE -> "More symbols"
        }
        is Key.Globe -> "Next keyboard"
        is Key.Space -> "Space"
        is Key.Enter -> policy.enter.label
        is Key.Gap -> ""
    }
    fun tap() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (key) {
            is Key.Text -> keys.text(key.text)
            is Key.Shift -> onShift()
            is Key.Backspace -> keys.backspace()
            is Key.Page -> onPage(key.to)
            is Key.Globe -> keys.nextKeyboard()
            is Key.Space -> keys.text(" ")
            is Key.Enter -> keys.enter()
            is Key.Gap -> Unit
        }
    }
    Box(
        Modifier
            .weight(key.weight)
            .fillMaxHeight()
            .padding(horizontal = 3.dp, vertical = 4.dp)
            .then(if (!colors.isDark && !pressed) Modifier.shadow(1.dp, AnuShapes.sm, ambientColor = Color.Black.copy(alpha = 0.1f), spotColor = Color.Black.copy(alpha = 0.25f)) else Modifier)
            .clip(AnuShapes.sm)
            .background(fill)
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick { tap(); true }
            }
            .pointerInput(key, policy, shift) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        // Delete repeats while held; a long press on the globe or space opens the keyboard picker.
                        val repeat: Job? = when (key) {
                            is Key.Backspace -> scope.launch {
                                delay(400)
                                while (true) {
                                    keys.backspace()
                                    delay(60)
                                }
                            }
                            else -> null
                        }
                        tryAwaitRelease()
                        repeat?.cancel()
                        pressed = false
                    },
                    onLongPress = { if (key is Key.Globe || key is Key.Space) keys.pickKeyboard() },
                    onTap = { tap() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when (key) {
            is Key.Text -> Text(key.text, style = if (key.text.first().isLetter()) letterStyle else symbolStyle, color = ink)
            is Key.Shift -> Glyph(
                when (shift) {
                    Shift.OFF -> KeyIcons.Shift
                    Shift.ONCE -> KeyIcons.ShiftOn
                    Shift.LOCKED -> KeyIcons.ShiftLocked
                },
                ink,
            )
            is Key.Backspace -> Glyph(KeyIcons.Backspace, ink)
            is Key.Globe -> Glyph(KeyIcons.Globe, ink)
            is Key.Page -> Text(key.label, style = type.label, color = ink)
            is Key.Space -> Text("Anu", style = type.label, color = colors.textFaint)
            is Key.Enter -> if (action) Text(policy.enter.label, style = type.label, color = ink) else Glyph(KeyIcons.Return, ink)
            is Key.Gap -> Unit
        }
    }
}

@Composable
private fun Glyph(icon: ImageVector, tint: Color) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
}
