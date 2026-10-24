package io.github.sandeepvijayarao09.anu.connector.apps

/**
 * Live talk's control over other apps (ADR-108): a pill at the bottom of the screen with what Anu is doing and Stop,
 * shown through the accessibility service, so it needs no permission to draw over apps. Only while app control is on.
 */
object LiveControl {
    val available: Boolean get() = AnuAccessibilityService.instance != null

    /** Shows or updates the pill; false when app control is off. A tap on its words runs [onOpen], and Stop [onStop]. */
    fun show(heading: String, doing: String, description: String, onOpen: () -> Unit, onStop: () -> Unit): Boolean {
        val service = AnuAccessibilityService.instance ?: return false
        service.livePill.show(heading, doing, description, "End live talk", onOpen, onStop)
        return true
    }

    fun hide() {
        AnuAccessibilityService.instance?.livePill?.hide()
    }
}
