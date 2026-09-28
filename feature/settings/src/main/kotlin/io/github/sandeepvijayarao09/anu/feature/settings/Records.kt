package io.github.sandeepvijayarao09.anu.feature.settings

import io.github.sandeepvijayarao09.anu.core.agent.ToolAuditEntry
import io.github.sandeepvijayarao09.anu.core.agent.ToolBroker
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ModelState
import io.github.sandeepvijayarao09.anu.core.security.LedgerEntry
import io.github.sandeepvijayarao09.anu.core.security.PiiKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Where Gemini requests go. The egress pipeline records this host (§12.2). */
internal const val GEMINI_HOST = "generativelanguage.googleapis.com"
private const val GMAIL_HOST = "gmail.googleapis.com"

/** Records by when they happened (DESIGN.md "Privacy report"): today, the six days before, and earlier. */
internal enum class Period(val title: String) { TODAY("Today"), WEEK("This week"), EARLIER("Earlier") }

internal data class PeriodGroup<T>(val period: Period, val items: List<T>)

internal fun periodOf(at: Instant, today: LocalDate, zone: ZoneId): Period {
    val day = at.atZone(zone).toLocalDate()
    return when {
        !day.isBefore(today) -> Period.TODAY
        day.isAfter(today.minusDays(7)) -> Period.WEEK
        else -> Period.EARLIER
    }
}

/** The records in their periods, newest first. Empty periods are left out. */
internal fun <T> byPeriod(items: List<T>, today: LocalDate, zone: ZoneId, at: (T) -> Instant): List<PeriodGroup<T>> {
    val grouped = items.sortedByDescending(at).groupBy { periodOf(at(it), today, zone) }
    return Period.entries.mapNotNull { period -> grouped[period]?.let { PeriodGroup(period, it) } }
}

/** One day of the seven-day row: how many times something left the phone. Real counts only. */
internal data class DayCount(val day: LocalDate, val count: Int)

/** The last seven days, oldest first, ending today. */
internal fun lastSevenDays(entries: List<LedgerEntry>, today: LocalDate, zone: ZoneId): List<DayCount> {
    val counts = entries.groupingBy { it.at.atZone(zone).toLocalDate() }.eachCount()
    return (6 downTo 0).map { back -> today.minusDays(back.toLong()).let { day -> DayCount(day, counts[day] ?: 0) } }
}

internal fun reportHeadline(sentToday: Int): String = when (sentToday) {
    0 -> "Nothing left your phone today"
    1 -> "1 item left your phone today"
    else -> "$sentToday items left your phone today"
}

/** A destination as the report names it. Anything that isn't a known Google API shows its host. */
internal fun destinationName(host: String): String = when (host) {
    GEMINI_HOST -> "Gemini, Google"
    GMAIL_HOST -> "Gmail, your account"
    else -> host
}

/** What the egress pipeline replaced before sending (§12.2), such as "2 email addresses replaced". */
internal fun redactionText(redacted: Map<PiiKind, Int>): String? {
    val parts = redacted.filterValues { it > 0 }.entries.sortedBy { it.key.ordinal }.map { (kind, count) -> "$count ${piiNoun(kind, count)}" }
    return if (parts.isEmpty()) null else parts.joinToString(", ") + " replaced"
}

private fun piiNoun(kind: PiiKind, count: Int): String {
    val one = when (kind) {
        PiiKind.EMAIL -> "email address"
        PiiKind.PHONE -> "phone number"
        PiiKind.CARD -> "card number"
        PiiKind.IBAN -> "bank account number"
        PiiKind.NATIONAL_ID -> "ID number"
    }
    return when {
        count == 1 -> one
        one.endsWith("ss") -> one + "es"
        else -> one + "s"
    }
}

internal fun characterCount(count: Int, locale: Locale = Locale.getDefault()): String =
    if (count == 1) "1 char" else String.format(locale, "%,d chars", count)

/** What happened to a tool call, from the broker's audit (§7.2). */
internal fun outcomeLabel(action: ToolAuditEntry): String = when {
    action.outcome == "no answer in time" -> "Not answered in time, so not done"
    action.approved == false -> "You declined"
    action.outcome == ToolBroker.OK_EDITED -> "You edited and approved it, and it was done"
    action.outcome == "ok" && action.approved == true -> "You approved, and it was done"
    action.outcome == "ok" -> "Done"
    action.outcome == "replayed" -> "Already done, not repeated"
    else -> action.outcome.replaceFirstChar { it.uppercase() }
}

internal fun modelName(id: BrainId): String = if (id == BrainId.BASE) "Gemma 4 E2B" else "Gemma 4 E4B"

internal fun modelTier(id: BrainId): String = if (id == BrainId.BASE) "Base" else "Pro"

internal fun gigabytes(bytes: Long, locale: Locale = Locale.getDefault()): String = String.format(locale, "%.1f GB", bytes / 1e9)

/**
 * The space a model takes (ADR-061): what it needs before it's downloaded, and what it uses once it is. Both
 * count the weight cache it writes the first time it runs, so an installed model grows once.
 */
internal fun modelSpace(row: ModelRow, locale: Locale = Locale.getDefault()): String = when (row.state) {
    ModelState.Ready -> "${gigabytes(row.spaceUsed, locale)} used"
    else -> "needs ${gigabytes(row.info.spaceNeeded, locale)}"
}

/** The Models row's value: a download in progress, else which models are on the phone. */
internal fun modelsSummary(rows: List<ModelRow>): String {
    rows.firstNotNullOfOrNull { it.state as? ModelState.Downloading }?.let { download ->
        return if (download.total > 0) "Downloading ${download.done * 100 / download.total}%" else "Downloading"
    }
    val ready = rows.filter { it.state == ModelState.Ready }
    return when (ready.size) {
        0 -> "None"
        1 -> modelName(ready.single().info.id)
        else -> "E2B and E4B"
    }
}
