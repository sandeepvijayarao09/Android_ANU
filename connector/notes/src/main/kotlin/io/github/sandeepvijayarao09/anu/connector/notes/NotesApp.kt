package io.github.sandeepvijayarao09.anu.connector.notes

import io.github.sandeepvijayarao09.anu.core.agent.AppDriver
import io.github.sandeepvijayarao09.anu.core.agent.AppScreen
import io.github.sandeepvijayarao09.anu.core.agent.ScreenElement

/** Another app a new note is written into as well as Anu, such as Samsung Notes (ADR-117). */
interface NoteMirror {
    /** The app's name, as the phone shows it. */
    val app: String

    /** Whether it can write now: app control is on and the app is installed. */
    val ready: Boolean

    /** Writes a new note with [title] and [text] in [app] and saves it, or fails with why. */
    suspend fun save(title: String, text: String)
}

/**
 * Writes a note into Samsung Notes the way the user would, through app control (ADR-098, ADR-117), with no model:
 * its "Create note" button, the "Title" field, the note's text field, then "Navigate up", which saves. The steps
 * match Samsung Notes' screens on One UI 8.5, read with uiautomator on the Galaxy S26 Ultra; a screen without the
 * expected element fails with what was missing, and the note stays saved in Anu.
 */
class SamsungNotesMirror(private val driver: AppDriver, private val installed: () -> Boolean) : NoteMirror {
    override val app = "Samsung Notes"

    override val ready: Boolean get() = driver.ready && installed()

    override suspend fun save(title: String, text: String) {
        check(driver.ready) { "app control is off" }
        driver.open(app) ?: error("$app isn't on this phone")
        driver.showProgress(app, "Writing your note…")
        try {
            driver.tap(find(read(), "the Create note button") { it.kind == ScreenElement.Kind.BUTTON && CREATE.matches(it.label) })
            var screen = read()
            if (screen.elements.none(::titleField)) {
                // The title shows as words in the toolbar until it's tapped.
                driver.tap(find(screen, "the Title") { it.label.equals(TITLE, ignoreCase = true) })
                screen = read()
            }
            driver.type(find(screen, "the Title field", ::titleField), title)
            driver.type(find(read(), "the note's text field") { it.kind == ScreenElement.Kind.FIELD && !titleField(it) }, text)
            val after = read()
            val up = after.elements.firstOrNull { it.kind == ScreenElement.Kind.BUTTON && it.label.equals(UP, ignoreCase = true) }
            if (up != null) driver.tap(up.id) else driver.back()
        } finally {
            driver.hideProgress()
        }
    }

    private suspend fun read(): AppScreen = driver.read() ?: error("$app's screen couldn't be read")

    private fun find(screen: AppScreen, what: String, test: (ScreenElement) -> Boolean): Int =
        screen.elements.firstOrNull(test)?.id ?: error("$what wasn't on $app's screen")

    private fun titleField(element: ScreenElement) = element.kind == ScreenElement.Kind.FIELD && element.label.equals(TITLE, ignoreCase = true)

    private companion object {
        val CREATE = Regex("create note|new note|add note|write", RegexOption.IGNORE_CASE)
        const val TITLE = "Title"
        const val UP = "Navigate up"
    }
}
