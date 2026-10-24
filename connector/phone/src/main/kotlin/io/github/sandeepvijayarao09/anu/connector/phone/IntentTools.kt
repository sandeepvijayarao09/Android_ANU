package io.github.sandeepvijayarao09.anu.connector.phone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.sandeepvijayarao09.anu.core.agent.EditableField
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.agent.ToolContext
import io.github.sandeepvijayarao09.anu.core.model.Description
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
data class CallInput(
    @Description("The phone number to call, such as +15551234567. Find a person's number with contacts_find first") val number: String,
)

@Serializable
data class OpenAppInput(@Description("The app's name as the phone shows it, such as Spotify or Maps") val name: String)

@Serializable
data class NavigateInput(@Description("Where to go: a place, an address, or a kind of place such as the nearest pharmacy") val destination: String)

@Serializable
data class ShareInput(@Description("The text to share, in the user's words") val text: String)

/**
 * The dialer with the number typed in (ADR-107). Anu places no call: the user sees the number and taps call, or doesn't.
 * It readies something for another person, so it asks while the turn is tainted, as when the number came from a message.
 */
class CallTool(context: Context) : PhoneTool<CallInput>(context) {
    override val name = "phone_call"
    override val description = "Open the phone's dialer with a number ready to call. The user taps call there; nothing is dialed until then"
    override val input: KSerializer<CallInput> = CallInput.serializer()
    override fun risk(input: CallInput) = RiskTier.WRITE_PRIVATE

    override fun describe(input: CallInput): String {
        require(isPhoneNumber(input.number)) { "“${input.number}” isn't a phone number; find the person's number with contacts_find first" }
        return "Open the dialer with ${input.number.trim()}"
    }

    override suspend fun invoke(input: CallInput, context: ToolContext): Done {
        val number = input.number.trim().filter { it.isDigit() || it == '+' }
        start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")), "phone app")
        return Done("The dialer is open with ${input.number.trim()}. Tap call to place it.")
    }
}

/**
 * An installed app, opened by the name its icon shows (ADR-107). Opening an app changes nothing by itself, but the app may
 * start doing things (playing music, say), and the turn hands the screen away, so it asks while the turn is tainted.
 */
class OpenAppTool(context: Context, private val launcher: Launcher = Launcher(context)) : PhoneTool<OpenAppInput>(context) {
    override val name = "phone_open_app"
    override val description = "Open an app installed on the phone, by its name"
    override val input: KSerializer<OpenAppInput> = OpenAppInput.serializer()
    override fun risk(input: OpenAppInput) = RiskTier.WRITE_PRIVATE

    override fun describe(input: OpenAppInput): String {
        require(appQuery(input.name).isNotEmpty()) { "name the app to open" }
        return "Open ${input.name.trim()}"
    }

    override suspend fun invoke(input: OpenAppInput, context: ToolContext): Done {
        val app = when (val found = launcher.find(input.name)) {
            is AppPick.One -> found.app
            is AppPick.Several -> error("“${input.name.trim()}” could be ${found.labels.joinToString(" or ")}; say which")
            AppPick.None -> error("no app called “${input.name.trim()}” is installed")
        }
        start(requireNotNull(this.context.packageManager.getLaunchIntentForPackage(app.packageName)) { "${app.label} can't be opened" }, app.label)
        return Done("Opened ${app.label}.")
    }
}

/**
 * Directions in the phone's maps app (ADR-107): turn-by-turn navigation when Google Maps is there, else the maps app's
 * search for the place. It starts directions on the user's own phone, so it asks while the turn is tainted.
 */
class NavigateTool(context: Context) : PhoneTool<NavigateInput>(context) {
    override val name = "phone_navigate"
    override val description = "Start directions to a place in the phone's maps app"
    override val input: KSerializer<NavigateInput> = NavigateInput.serializer()
    override fun risk(input: NavigateInput) = RiskTier.WRITE_PRIVATE

    override fun describe(input: NavigateInput): String {
        require(input.destination.isNotBlank()) { "say where to go" }
        return "Start directions to ${input.destination.trim()}"
    }

    override suspend fun invoke(input: NavigateInput, context: ToolContext): Done {
        val place = Uri.encode(input.destination.trim())
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$place")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            this.context.startActivity(intent)
            return Done("Directions to ${input.destination.trim()} are open in Maps.")
        } catch (_: ActivityNotFoundException) {
            // No Google Maps: any maps app takes a geo search.
            start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$place")), "maps app")
            return Done("${input.destination.trim()} is open in your maps app.")
        }
    }
}

/**
 * The system share sheet with a text (ADR-107). Unlike a text draft, which opens one conversation the receipt names, the
 * sheet offers every app, and some post or send on one tap of a shortcut there, while the sheet shows only the text's start.
 * So the text is about to leave the user's control: `WRITE_EXTERNAL`, approved as exactly this text every time (I2).
 */
class ShareTool(context: Context) : PhoneTool<ShareInput>(context) {
    override val name = "phone_share"
    override val description = "Open the phone's share sheet with a text, for the user to pick an app to share it with"
    override val input: KSerializer<ShareInput> = ShareInput.serializer()
    override fun risk(input: ShareInput) = RiskTier.WRITE_EXTERNAL
    override val editable = listOf(EditableField("text", "Text", EditableField.Kind.TEXT))

    override fun describe(input: ShareInput): String {
        require(input.text.isNotBlank()) { "the text to share is empty" }
        return "Share “${input.text.trim()}”"
    }

    override suspend fun invoke(input: ShareInput, context: ToolContext): Done {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, input.text.trim())
        start(Intent.createChooser(send, null), "app to share with")
        return Done("The share sheet is open. Pick an app to share it with; nothing is shared until you do.")
    }
}

/** An app with a launcher icon: what its icon says, and its package. */
data class LauncherApp(val label: String, val packageName: String)

/** The result of looking an app up by name. */
sealed interface AppPick {
    data class One(val app: LauncherApp) : AppPick
    data class Several(val labels: List<String>) : AppPick
    data object None : AppPick
}

/** The phone's apps with a launcher icon, found by the names people use for them (ADR-107). */
class Launcher(private val context: Context) {
    fun apps(): List<LauncherApp> {
        val packages = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packages.queryIntentActivities(main, 0)
            .map { LauncherApp(it.loadLabel(packages).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
    }

    fun find(name: String): AppPick = pickApp(name, apps())
}

/** "the Spotify app" → "spotify". */
internal fun appQuery(name: String): String =
    name.trim().lowercase().removePrefix("the ").removePrefix("my ").removeSuffix(" app").trim()

/**
 * The app [name] means among [apps]: the exact name, else the one name that starts with it, else the one that holds it.
 * More than one at a step is [AppPick.Several], so "Play" never opens a guess between Play Store and Play Games.
 */
internal fun pickApp(name: String, apps: List<LauncherApp>): AppPick {
    val query = appQuery(name)
    if (query.isEmpty()) return AppPick.None
    val steps = listOf<(String) -> Boolean>({ it == query }, { it.startsWith(query) }, { query in it })
    for (step in steps) {
        val found = apps.filter { step(it.label.lowercase()) }
        when {
            found.size == 1 -> return AppPick.One(found.single())
            found.size > 1 -> return AppPick.Several(found.map { it.label }.distinct().sorted())
        }
    }
    return AppPick.None
}

private val PHONE_NUMBER = Regex("\\+?[\\d ().-]{3,20}")

internal fun isPhoneNumber(text: String) = PHONE_NUMBER.matches(text.trim()) && text.count(Char::isDigit) >= 3
