package io.github.sandeepvijayarao09.anu.connector.skills

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import io.github.sandeepvijayarao09.anu.connector.phone.Reminders
import io.github.sandeepvijayarao09.anu.core.agent.AgentSkill
import io.github.sandeepvijayarao09.anu.core.agent.AgentSkillCatalog
import io.github.sandeepvijayarao09.anu.core.agent.EditableField
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.Tool
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Description
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import io.github.sandeepvijayarao09.anu.core.security.LedgerEntry
import io.github.sandeepvijayarao09.anu.core.security.PrivacyLedger
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Whether a JS skill's pages load anything from the web, and from where (ADR-120): scripts, styles, frames and modules
 * with an http(s) address, and `fetch`, `XMLHttpRequest` or WebSocket calls. Links and licence headers don't count.
 */
object JsNetwork {
    private val LOADS = Regex(
        """<(?:script|iframe|img|source)[^>]+src\s*=\s*["'](https?://[^"']+)|<link[^>]+href\s*=\s*["'](https?://[^"']+)|""" +
            """import[^;'"]*["'](https?://[^"']+)|fetch\(\s*[`"']?(https?://[^`"')]+)?|new\s+WebSocket|XMLHttpRequest""",
        RegexOption.IGNORE_CASE,
    )

    /** The hosts a skill's [sources] load from; "the web" stands for calls whose address the code builds. */
    fun hosts(sources: List<String>): Set<String> = sources.flatMap { source ->
        LOADS.findAll(source).map { match ->
            val url = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
            url?.let { runCatching { URI(it).host }.getOrNull() } ?: WEB
        }.toList()
    }.toSet()

    const val WEB = "the web"
}

/** What a JS skill's page returned, as Gallery reads it: `{result, error, image, webview}`, or the plain text. */
object JsResult {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): RanJs {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return RanJs(result = raw.take(MAX_RESULT))
        val result = (obj["result"] as? JsonPrimitive)?.contentOrNull ?: (obj["result"]?.toString())
        val error = (obj["error"] as? JsonPrimitive)?.contentOrNull
        val note = when {
            obj["image"] is JsonObject -> "The skill made a picture, which Anu doesn't show yet."
            obj["webview"] is JsonObject -> "The skill made a page, which Anu doesn't show yet."
            else -> null
        }
        return RanJs(result = result?.take(MAX_RESULT), error = error?.take(MAX_RESULT), note = note)
    }

    private const val MAX_RESULT = 4_000
}

@Serializable
data class RunJsInput(
    @SerialName("skill_name") @Description("The name of skill") val skillName: String,
    @SerialName("script_name") @Description("The script name to run. Use 'index.html' if not provided by user") val scriptName: String = "index.html",
    @Description("The data to pass to the script. Use empty string if not provided by user") val data: String = "",
)

@Serializable
data class RanJs(val result: String? = null, val error: String? = null, val note: String? = null)

/**
 * Gallery's `run_js` (ADR-120), through the broker. A skill whose pages load nothing from the web runs with the
 * WebView's network off, as a `READ`. One that loads from the web, or was added from a web address, sends the user's
 * data away, so it's `WRITE_EXTERNAL`: the user approves the exact data every time (I2) and each run writes a ledger
 * entry (I3). Its result is a program's output, which taints the run (P4). Skills that need an API key aren't run yet:
 * Anu's secrets never reach a page (I1).
 */
class RunJsTool(
    private val library: SkillLibrary,
    private val runner: JsSkillRunner,
    private val ledger: PrivacyLedger?,
    private val clock: () -> Instant = Instant::now,
) : Tool<RunJsInput, RanJs> {
    override val name = NAME
    override val description = "Runs JS script"
    override val input: KSerializer<RunJsInput> = RunJsInput.serializer()
    override val output: KSerializer<RanJs> = RanJs.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val untrustedContent = UntrustedContent.SERVICE
    override val editable = listOf(EditableField("data", "Data", EditableField.Kind.TEXT, optional = true))

    override fun risk(input: RunJsInput) = if (hosts(input).isEmpty()) RiskTier.READ else RiskTier.WRITE_EXTERNAL

    override fun sendsTo(input: RunJsInput): String? = hosts(input).takeIf { it.isNotEmpty() }?.sorted()?.joinToString()

    override fun describe(input: RunJsInput): String {
        val skill = skill(input)
        val script = input.scriptName.ifBlank { "index.html" }
        require(SCRIPT.matches(script)) { "“$script” isn't one of the skill's scripts" }
        require(!skill.requireSecret) { "the ${skill.name} skill needs an API key, and Anu doesn't give keys to skills yet" }
        require(input.data.length <= MAX_DATA) { "the data is longer than $MAX_DATA characters" }
        val with = if (input.data.isBlank()) "" else " with “${input.data.trim()}”"
        return "Run the ${skill.name} skill$with"
    }

    override suspend fun invoke(input: RunJsInput, context: ToolContext): RanJs {
        val skill = skill(input)
        val hosts = hosts(input)
        if (hosts.isNotEmpty()) {
            ledger?.record(LedgerEntry(clock(), hosts.sorted().joinToString(), "Skill ${skill.name}", dataClasses, emptyMap(), input.data.length))
        }
        return JsResult.parse(runner.run(skill, input.scriptName, input.data, network = hosts.isNotEmpty()))
    }

    private fun skill(input: RunJsInput): AgentSkill = requireNotNull(library.find(input.skillName)) {
        "there is no skill named “${input.skillName}”; load a skill with load_skill first"
    }

    /** Where the skill's pages load from: its web address for a skill added from one, else what its files load. */
    private fun hosts(input: RunJsInput): Set<String> {
        val skill = library.find(input.skillName) ?: return emptySet()
        if (skill.base.startsWith("https://")) return setOfNotNull(runCatching { URI(skill.base).host }.getOrNull())
        return JsNetwork.hosts(library.sources(skill))
    }

    companion object {
        const val NAME = "run_js"
        private val SCRIPT = Regex("[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*\\.html")
        private const val MAX_DATA = 4_000
    }
}

@Serializable
data class RunIntentInput(
    @Description("The intent to run.") val intent: String,
    @Description("A JSON string containing the parameter values required for the intent.") val parameters: String = "{}",
)

@Serializable
data class RanIntent(val action: String, val parameters: String, val result: String)

/**
 * Gallery's `run_intent` (ADR-120), through the broker, with Gallery's six intents and their parameters. Email, text and
 * a new event open the app that owns them with everything filled in, and the user finishes there (`WRITE_PRIVATE`). The
 * date and the calendar are read (`READ`); calendar titles are others' words (P4). A notification is one of Anu's
 * reminders (ADR-107).
 */
class RunIntentTool(
    private val context: Context,
    private val reminders: Reminders,
    private val catalog: AgentSkillCatalog,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : Tool<RunIntentInput, RanIntent> {
    override val name = NAME
    override val description = "Run an Android intent. It is used to interact with the app to perform certain actions."
    override val input: KSerializer<RunIntentInput> = RunIntentInput.serializer()
    override val output: KSerializer<RanIntent> = RanIntent.serializer()
    override val dataClasses = setOf(DataClass.PERSONAL)
    override val untrustedContent = UntrustedContent.CALENDAR

    override fun risk(input: RunIntentInput) = when (input.intent) {
        READ_CALENDAR, DATE_AND_TIME -> RiskTier.READ
        else -> RiskTier.WRITE_PRIVATE
    }

    override fun describe(input: RunIntentInput): String {
        val p = params(input)
        return when (input.intent) {
            SEND_EMAIL -> "Open an email to ${p.text("extra_email")} in your email app: “${p.text("extra_subject")}”. Nothing is sent until you tap send there"
            SEND_SMS -> "Open a text to ${p.text("phone_number")} in Messages: “${p.text("sms_body")}”. Nothing is sent until you tap send there"
            CREATE_CALENDAR_EVENT -> "Open a new event in your calendar: “${p.text("title")}” at ${time(p.text("begin_time"))}. It's saved only when you save it there"
            READ_CALENDAR -> "Read your calendar for ${LocalDate.parse(p.text("date"))}"
            DATE_AND_TIME -> "Read the date and time"
            SCHEDULE_NOTIFICATION -> {
                require(p.flag("repeat_daily") != true) { "Anu's reminders don't repeat daily yet" }
                "Remind you “${p.text("title")}” at ${notificationTime(p)}"
            }
            else -> {
                val isSkill = catalog.find(input.intent) != null
                error(if (isSkill) "Intent not found. Try to run it as a skill" else "Tool not found")
            }
        }
    }

    override suspend fun invoke(input: RunIntentInput, context: ToolContext): RanIntent {
        val p = params(input)
        val result = when (input.intent) {
            SEND_EMAIL -> start(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                    .putExtra(Intent.EXTRA_EMAIL, arrayOf(p.text("extra_email")))
                    .putExtra(Intent.EXTRA_SUBJECT, p.text("extra_subject"))
                    .putExtra(Intent.EXTRA_TEXT, p.text("extra_text", required = false)),
            )
            SEND_SMS -> start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + p.text("phone_number"))).putExtra("sms_body", p.text("sms_body")))
            CREATE_CALENDAR_EVENT -> start(
                Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, p.text("title"))
                    .putExtra(CalendarContract.Events.DESCRIPTION, p.text("description", required = false))
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, millis(time(p.text("begin_time"))))
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, millis(time(p.text("end_time")))),
            )
            READ_CALENDAR -> readCalendar(LocalDate.parse(p.text("date")))
            DATE_AND_TIME -> LocalDateTime.now(zone()).format(NOW)
            SCHEDULE_NOTIFICATION -> {
                if (!reminders.canNotify()) {
                    reminders.openNotificationSettings()
                    error("Notifications are off for Anu. Allow them on the page that opened, then ask again")
                }
                val text = listOfNotNull(p.text("title"), p.text("message", required = false).takeIf { it.isNotBlank() }).joinToString(": ")
                reminders.set(text, notificationTime(p))
                "succeeded"
            }
            else -> error("Tool not found")
        }
        return RanIntent(input.intent, input.parameters, result)
    }

    private fun readCalendar(day: LocalDate): String {
        check(context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            "Calendar access is off for Anu"
        }
        val from = day.atStartOfDay(zone()).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val columns = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END)
        val events = context.contentResolver.query(uri, columns, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < MAX_EVENTS) {
                    add(
                        buildMap {
                            put("title", cursor.getString(0).orEmpty())
                            put("description", cursor.getString(1).orEmpty().take(200))
                            put("begin_time", at(cursor.getLong(2)))
                            put("end_time", at(cursor.getLong(3)))
                        },
                    )
                }
            }
        }.orEmpty()
        return Json.encodeToString(mapOf("events" to events))
    }

    private fun start(intent: Intent): String = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "succeeded"
    } catch (_: ActivityNotFoundException) {
        error("no app on this phone can do that")
    }

    private fun notificationTime(p: JsonObject): LocalDateTime {
        val now = LocalDateTime.now(zone())
        val hour = requireNotNull(p.int("hour")) { "hour is required" }
        val minute = p.int("minute") ?: 0
        val year = p.int("year")
        val month = p.int("month")
        val day = p.int("day")
        val at = if (year != null && month != null && day != null) {
            LocalDateTime.of(year, month, day, hour, minute)
        } else {
            now.toLocalDate().atTime(hour, minute).let { if (it.isAfter(now)) it else it.plusDays(1) }
        }
        require(at.isAfter(now)) { "$at has passed" }
        return at
    }

    private fun params(input: RunIntentInput): JsonObject =
        runCatching { Json.parseToJsonElement(input.parameters.ifBlank { "{}" }).jsonObject }.getOrElse { error("parameters must be a JSON object") }

    private fun JsonObject.text(key: String, required: Boolean = true): String {
        val value = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        require(!required || value.isNotEmpty()) { "$key is required" }
        return value
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun time(text: String): LocalDateTime = runCatching { LocalDateTime.parse(text) }.getOrElse { error("“$text” isn't a time like 2026-10-25T14:00:00") }

    private fun millis(time: LocalDateTime) = time.atZone(zone()).toInstant().toEpochMilli()

    private fun at(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone()).toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    companion object {
        const val NAME = "run_intent"
        const val SEND_EMAIL = "send_email"
        const val SEND_SMS = "send_sms"
        const val CREATE_CALENDAR_EVENT = "create_calendar_event"
        const val READ_CALENDAR = "read_calendar_events"
        const val DATE_AND_TIME = "get_current_date_and_time"
        const val SCHEDULE_NOTIFICATION = "schedule_notification"
        private const val MAX_EVENTS = 30

        /** Gallery's own format, "2026-10-24T14:05:00 Saturday". */
        private val NOW = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss EEEE", Locale.ENGLISH)
    }
}
