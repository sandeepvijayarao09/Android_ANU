package io.github.sandeepvijayarao09.anu.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.security.LedgerEntry
import io.github.sandeepvijayarao09.anu.core.ui.AnuPage
import io.github.sandeepvijayarao09.anu.core.ui.AnuShapes
import io.github.sandeepvijayarao09.anu.core.ui.AnuSpacing
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import io.github.sandeepvijayarao09.anu.core.ui.Group
import io.github.sandeepvijayarao09.anu.core.ui.GroupDivider
import io.github.sandeepvijayarao09.anu.core.ui.GroupFooter
import io.github.sandeepvijayarao09.anu.core.ui.GroupRow
import io.github.sandeepvijayarao09.anu.core.ui.PrivacyDot
import io.github.sandeepvijayarao09.anu.core.ui.SectionHeader
import io.github.sandeepvijayarao09.anu.core.ui.anuCard
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Privacy report (DESIGN.md "Privacy report"): what Anu sent from this phone, where it went, and how
 * much, straight from the Privacy Ledger (I3), never from model text. It opens over any page, from the
 * privacy indicator or from Settings. Replies and invites the user approves are sent by other apps, so
 * they are in Activity instead, and the page says so.
 */
@Composable
fun PrivacyReportScreen(entries: List<LedgerEntry>, back: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val stamps = rememberStamps()
    val week = remember(entries, today) { lastSevenDays(entries, today, zone) }
    val groups = remember(entries, today) { byPeriod(entries, today, zone) { it.at } }
    val sentToday = week.last().count
    AnuPage("Privacy report", sentToday, onOpenPrivacy = null, modifier = modifier, back = back, onBack = onBack) {
        item(key = "summary") { Summary(sentToday, week) }
        if (entries.isEmpty()) item(key = "empty") { EmptyLine("Nothing has left this phone yet.") }
        groups.forEach { group ->
            item(key = group.period.name) {
                Column {
                    SectionHeader(group.period.title)
                    Group {
                        group.items.forEachIndexed { index, entry ->
                            if (index > 0) GroupDivider()
                            LedgerRow(entry, stamps.format(entry.at, group.period), group.period)
                        }
                    }
                }
            }
        }
        item(key = "footer") {
            GroupFooter("Replies and invites you approve are sent by their own apps, so they're listed in Settings → Activity rather than here.")
        }
    }
}

@Composable
private fun Summary(sentToday: Int, week: List<DayCount>) {
    val colors = AnuTheme.colors
    val lineHeight = with(LocalDensity.current) { AnuTheme.type.title.lineHeight.toDp() }
    Column(Modifier.fillMaxWidth().padding(horizontal = AnuSpacing.xl, vertical = 4.dp).anuCard(AnuShapes.lg).padding(20.dp)) {
        Row {
            // The dot sits on the headline's first line, however many lines it wraps to.
            Box(Modifier.height(lineHeight), contentAlignment = Alignment.Center) { PrivacyDot(away = sentToday > 0, size = 10.dp) }
            Spacer(Modifier.width(12.dp))
            Text(reportHeadline(sentToday), Modifier.semantics { heading() }, style = AnuTheme.type.title, color = colors.text)
        }
        Text(
            "Anu records what it sends and where, never the content.",
            Modifier.padding(top = 8.dp),
            style = AnuTheme.type.callout,
            color = colors.textMuted,
        )
        WeekBars(week, Modifier.padding(top = 20.dp))
    }
}

/** Seven days, oldest first. Only days when something left the phone get an orange bar and a count. */
@Composable
private fun WeekBars(days: List<DayCount>, modifier: Modifier = Modifier) {
    val colors = AnuTheme.colors
    val most = days.maxOf { it.count }.coerceAtLeast(1)
    val locale = Locale.getDefault()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        days.forEachIndexed { index, day ->
            val today = index == days.lastIndex
            val name = if (today) "Today" else day.day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            Column(
                Modifier.weight(1f).clearAndSetSemantics {
                    contentDescription = "$name: " + when (day.count) {
                        0 -> "nothing sent"
                        1 -> "1 item sent"
                        else -> "${day.count} items sent"
                    }
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (day.count > 0) {
                    Text("${day.count}", style = AnuTheme.type.mono, color = colors.awayText)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.width(14.dp).height((10 + 16 * day.count / most).dp).clip(AnuShapes.full).background(colors.away))
                } else {
                    Box(Modifier.width(14.dp).height(3.dp).clip(AnuShapes.full).background(colors.separator))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    day.day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                    style = AnuTheme.type.caption.copy(fontWeight = if (today) FontWeight(600) else FontWeight.Normal),
                    color = if (today) colors.text else colors.textMuted,
                )
            }
        }
    }
}

/** One send: where it went, what for, its size, and what was replaced first (§12.2). */
@Composable
private fun LedgerRow(entry: LedgerEntry, stamp: String, period: Period) {
    val mono = AnuTheme.type.mono
    val subtitle = buildAnnotatedString {
        append(entry.purpose.replaceFirstChar { it.titlecase() })
        append(" · ")
        withStyle(SpanStyle(fontFamily = mono.fontFamily, fontSize = mono.fontSize)) { append(characterCount(entry.characters)) }
        redactionText(entry.redacted)?.let { append(" · $it") }
        if (DataClass.SENSITIVE in entry.dataClasses) append(" · sensitive")
    }
    GroupRow(destinationName(entry.destination), richSubtitle = subtitle, trailing = { Stamp(stamp, period) })
}
