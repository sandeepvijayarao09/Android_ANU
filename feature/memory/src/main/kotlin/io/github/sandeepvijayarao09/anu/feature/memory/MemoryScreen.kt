package io.github.sandeepvijayarao09.anu.feature.memory

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sandeepvijayarao09.anu.core.memory.EventSource
import io.github.sandeepvijayarao09.anu.core.memory.Fact
import io.github.sandeepvijayarao09.anu.core.memory.FactOrigin
import io.github.sandeepvijayarao09.anu.core.memory.MemoryEvent
import io.github.sandeepvijayarao09.anu.core.memory.MemoryPipeline
import io.github.sandeepvijayarao09.anu.core.memory.MemoryState
import io.github.sandeepvijayarao09.anu.core.memory.ProfileSection
import io.github.sandeepvijayarao09.anu.core.ui.AnuButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuDialog
import io.github.sandeepvijayarao09.anu.core.ui.AnuIcons
import io.github.sandeepvijayarao09.anu.core.ui.AnuPage
import io.github.sandeepvijayarao09.anu.core.ui.AnuPill
import io.github.sandeepvijayarao09.anu.core.ui.AnuRoundButton
import io.github.sandeepvijayarao09.anu.core.ui.AnuSearchField
import io.github.sandeepvijayarao09.anu.core.ui.AnuShapes
import io.github.sandeepvijayarao09.anu.core.ui.AnuSheet
import io.github.sandeepvijayarao09.anu.core.ui.AnuSpacing
import io.github.sandeepvijayarao09.anu.core.ui.AnuTextButton
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
import io.github.sandeepvijayarao09.anu.core.ui.WhereLine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Memory tab (§8.4, DESIGN.md "Memory"): what Anu knows, grouped like a contacts list. The user's
 * own facts say so; guesses ask to be confirmed. Tapping a fact shows the words it came from, with
 * Correct, Delete and Forget.
 */
@Composable
fun MemoryScreen(viewModel: MemoryViewModel, sentToday: Int, onOpenPrivacy: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val adding by viewModel.adding.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var openFact by rememberSaveable { mutableStateOf<Long?>(null) }
    val shown = remember(state.facts, query) { state.facts.filter { matches(it, query) } }
    val requested by viewModel.requested.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        requested?.let { id ->
            openFact = id
            viewModel.shown()
        }
    }

    AnuPage(
        title = "Memory",
        subtitle = "What Anu knows about you, kept on this phone.",
        sentToday = sentToday,
        onOpenPrivacy = onOpenPrivacy,
        modifier = modifier,
        actions = { AnuRoundButton(AnuIcons.Plus, "Add to memory", onClick = { showAdd = true }, enabled = !adding) },
    ) {
        if (state.facts.isNotEmpty()) {
            item(key = "search") { AnuSearchField(query, { query = it }, Modifier.padding(horizontal = AnuSpacing.xl, vertical = 4.dp)) }
        }
        item(key = "status") { Status(state, adding, onLearnNow = viewModel::learnNow) }
        message?.let { text -> item(key = "message") { Notice(text, viewModel::dismissMessage) } }
        when {
            state.facts.isEmpty() -> item(key = "empty") { Empty() }
            shown.isEmpty() -> item(key = "none") { NoMatches(query) }
            else -> ProfileSection.entries.forEach { section ->
                val facts = shown.filter { it.section == section }.sortedWith(MemoryPipeline.IMPORTANCE)
                if (facts.isNotEmpty()) {
                    item(key = section.name) {
                        Column {
                            SectionHeader(section.shortName)
                            Group {
                                facts.forEachIndexed { index, fact ->
                                    if (index > 0) GroupDivider()
                                    FactRow(fact, onOpen = { openFact = fact.id }, onConfirm = { viewModel.confirm(fact.id) })
                                }
                            }
                        }
                    }
                }
            }
        }
        if (state.facts.isNotEmpty()) {
            item(key = "footer") { GroupFooter("Anu learns after you leave the app. Tap anything to see where it came from, or to change it.") }
        }
    }

    if (showAdd) AddSheet(onDismiss = { showAdd = false }, onAdd = viewModel::add)
    openFact?.let { id ->
        val fact = state.facts.find { it.id == id }
        // A corrected fact is replaced by a new one, and a deleted one is gone: either way, close.
        LaunchedEffect(fact == null) { if (fact == null) openFact = null }
        if (fact != null) FactSheet(fact, state.sources, viewModel, onDismiss = { openFact = null })
    }
}

/** Learning happens after the user leaves the app, or while charging (ADR-037). This offers it now. */
@Composable
private fun Status(state: MemoryState, adding: Boolean, onLearnNow: () -> Unit) {
    val colors = AnuTheme.colors
    val busy = adding || state.learning
    val text = when {
        adding -> "Remembering… Anu reads it on this phone."
        state.learning -> "Learning from your messages…"
        state.pending == 1 -> "1 message to learn from"
        state.pending > 1 -> "${state.pending} messages to learn from"
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), color = colors.textMuted, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, Modifier.weight(1f), style = AnuTheme.type.callout, color = colors.textMuted)
        if (!busy) AnuTextButton("Learn now", onClick = onLearnNow)
    }
}

@Composable
private fun Empty() {
    val colors = AnuTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(AnuIcons.Memory, contentDescription = null, Modifier.size(36.dp), tint = colors.textFaint)
        Spacer(Modifier.height(14.dp))
        Text("Nothing yet", style = AnuTheme.type.headline, color = colors.text)
        Spacer(Modifier.height(6.dp))
        Text(
            "Tell Anu about yourself as you chat, or say “Remember that…”. You can also tap + and write it in your own words.",
            style = AnuTheme.type.callout,
            color = colors.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NoMatches(query: String) {
    Text(
        "Nothing matches “${query.trim()}”.",
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 32.dp),
        style = AnuTheme.type.callout,
        color = AnuTheme.colors.textMuted,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun FactRow(fact: Fact, onOpen: () -> Unit, onConfirm: () -> Unit) {
    val words = factWords(fact)
    val yours = fact.origin == FactOrigin.USER_STATED
    GroupRow(
        title = words.label,
        subtitle = rowSubtitle(fact, words, ::shortDate),
        value = if (yours && words.relationship != null) "You said" else null,
        chevron = yours,
        onClick = onOpen,
        trailing = if (yours) null else ({ AnuPill("Confirm", onClick = onConfirm) }),
    )
}

/** A fact's details: how it relates to you, the words it came from, and the user's controls over it. */
@Composable
private fun FactSheet(fact: Fact, sources: Map<Long, MemoryEvent>, viewModel: MemoryViewModel, onDismiss: () -> Unit) {
    val colors = AnuTheme.colors
    val words = factWords(fact)
    var editing by remember(fact.id) { mutableStateOf(false) }
    var deleting by remember(fact.id) { mutableStateOf(false) }
    var forgetting by remember(fact.id) { mutableStateOf<MemoryEvent?>(null) }

    AnuSheet(onDismiss) { close ->
        SheetTitle(words.label, overline = words.relationship)
        WhereLine(leaves = false, text = "Kept on this phone", modifier = Modifier.padding(top = 10.dp))
        Text(
            if (fact.origin == FactOrigin.USER_STATED) {
                "You said this, so it outranks anything Anu works out on its own."
            } else {
                "Anu worked this out from what you wrote. Confirm it if it's right, or correct it."
            },
            Modifier.padding(top = 12.dp),
            style = AnuTheme.type.callout,
            color = colors.textMuted,
        )
        if (editing) {
            var value by remember(fact.id) { mutableStateOf(fact.value) }
            val focus = remember { FocusRequester() }
            AnuTextField(
                value,
                { value = it },
                Modifier.padding(top = 18.dp).focusRequester(focus),
                label = words.relationship ?: fact.predicate.replace('_', ' ').replaceFirstChar { it.titlecase() },
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Spacer(Modifier.height(18.dp))
            AnuButton("Save", onClick = { viewModel.correct(fact.id, value.trim()); close() }, Modifier.fillMaxWidth(), enabled = value.isNotBlank())
            Spacer(Modifier.height(10.dp))
            AnuButton("Cancel", onClick = { editing = false }, Modifier.fillMaxWidth(), secondary = true)
        } else {
            Text("Where it came from", Modifier.padding(top = 20.dp, bottom = 2.dp), style = AnuTheme.type.label, color = colors.textMuted)
            fact.sources.mapNotNull(sources::get).sortedByDescending { it.at }.forEach { event ->
                SourceQuote(event, onForget = if (event.source == EventSource.CHAT) ({ forgetting = event }) else null)
            }
            Spacer(Modifier.height(20.dp))
            if (fact.origin == FactOrigin.INFERRED) {
                AnuButton("That's right", onClick = { viewModel.confirm(fact.id) }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnuButton("Correct", onClick = { editing = true }, Modifier.weight(1f), secondary = true)
                AnuButton("Delete", onClick = { deleting = true }, Modifier.weight(1f), secondary = true, destructive = true)
            }
            SheetNote("Deleting a fact also stops Anu from learning it again.")
        }
    }

    if (deleting) {
        AnuDialog(
            title = "Delete “${words.label}”?",
            text = "Anu forgets it and won't learn it again.",
            confirm = "Delete",
            onConfirm = { deleting = false; viewModel.delete(fact.id) },
            dismiss = "Keep",
            onDismiss = { deleting = false },
            destructive = true,
        )
    }
    forgetting?.let { event ->
        AnuDialog(
            title = "Forget this message?",
            text = "Anu forgets the message, and anything it learned only from it. The chat itself isn't changed.",
            confirm = "Forget",
            onConfirm = { forgetting = null; viewModel.forgetSource(event.id) },
            dismiss = "Keep",
            onDismiss = { forgetting = null },
            destructive = true,
        )
    }
}

/** One source, in the user's own words, with its date (I4). A chat message can be forgotten (I5). */
@Composable
private fun SourceQuote(event: MemoryEvent, onForget: (() -> Unit)?) {
    val colors = AnuTheme.colors
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().clip(AnuShapes.md).background(colors.background).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 12.dp)) {
        Row(Modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${sourceName(event.source)} · ${longDate(event.at)}", Modifier.weight(1f), style = AnuTheme.type.label, color = colors.textMuted)
            onForget?.let { AnuTextButton("Forget", onClick = it) }
        }
        Text("“${event.content.take(280)}”", Modifier.padding(end = 8.dp), style = AnuTheme.type.callout, color = colors.text)
    }
}

/** Adding in the user's own words. The on-device model reads it, and the facts are kept as theirs (P5). */
@Composable
private fun AddSheet(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    AnuSheet(onDismiss) { close ->
        SheetTitle("Add to memory")
        WhereLine(leaves = false, text = "Read and kept on this phone", modifier = Modifier.padding(top = 10.dp))
        Text(
            "Tell Anu something about you or the people in your life.",
            Modifier.padding(top = 12.dp),
            style = AnuTheme.type.callout,
            color = AnuTheme.colors.textMuted,
        )
        AnuTextField(
            text,
            { text = it },
            Modifier.padding(top = 16.dp).focusRequester(focus),
            placeholder = "I'm vegetarian, and my sister is Priya",
            singleLine = false,
            minLines = 3,
        )
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Spacer(Modifier.height(18.dp))
        AnuButton("Remember", onClick = { onAdd(text.trim()); close() }, Modifier.fillMaxWidth(), enabled = text.isNotBlank())
        Spacer(Modifier.height(10.dp))
        AnuButton("Cancel", onClick = close, Modifier.fillMaxWidth(), secondary = true)
    }
}

private val SHORT_DATE: DateTimeFormatter by lazy { pattern("MMMd") }
private val LONG_DATE: DateTimeFormatter by lazy { pattern("EEEMMMd") }

private fun pattern(skeleton: String): DateTimeFormatter {
    val locale = Locale.getDefault()
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).withZone(ZoneId.systemDefault())
}

private fun shortDate(at: Instant): String = SHORT_DATE.format(at)

private fun longDate(at: Instant): String = LONG_DATE.format(at)
