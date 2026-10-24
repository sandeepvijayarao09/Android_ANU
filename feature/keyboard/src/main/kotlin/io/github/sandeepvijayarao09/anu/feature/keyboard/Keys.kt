package io.github.sandeepvijayarao09.anu.feature.keyboard

/** One key. [weight] is its width in letter keys. */
sealed interface Key {
    val weight: Float

    data class Text(val text: String, override val weight: Float = 1f) : Key
    data class Shift(override val weight: Float = 1.5f) : Key
    data class Backspace(override val weight: Float = 1.5f) : Key
    /** Switches pages: to symbols ("?123"), more symbols ("=\<") or back to letters ("ABC"). */
    data class Page(val to: KeyPage, val label: String, override val weight: Float = 1.5f) : Key
    data class Globe(override val weight: Float = 1f) : Key
    data class Space(override val weight: Float = 4f) : Key
    data class Enter(override val weight: Float = 1.5f) : Key
    /** Space in a row: a half-key inset, as the second letter row has. */
    data class Gap(override val weight: Float = 0.5f) : Key
}

enum class KeyPage { LETTERS, SYMBOLS, MORE }

enum class Shift { OFF, ONCE, LOCKED }

/** The layouts: QWERTY, two symbol pages, and a number pad (ADR-109). */
object Keys {
    private fun texts(row: String) = row.map { Key.Text(it.toString()) }

    private fun bottom(page: KeyPage) = listOf(
        if (page == KeyPage.LETTERS) Key.Page(KeyPage.SYMBOLS, "?123") else Key.Page(KeyPage.LETTERS, "ABC"),
        Key.Text(","),
        Key.Globe(),
        Key.Space(),
        Key.Text("."),
        Key.Enter(),
    )

    fun rows(page: KeyPage, shift: Shift): List<List<Key>> = when (page) {
        KeyPage.LETTERS -> {
            val upper = shift != Shift.OFF
            fun case(row: String) = texts(if (upper) row.uppercase() else row)
            listOf(
                case("qwertyuiop"),
                listOf(Key.Gap()) + case("asdfghjkl") + Key.Gap(),
                listOf(Key.Shift()) + case("zxcvbnm") + Key.Backspace(),
                bottom(page),
            )
        }
        KeyPage.SYMBOLS -> listOf(
            texts("1234567890"),
            texts("@#$%&-+()/"),
            listOf(Key.Page(KeyPage.MORE, "=\\<")) + texts("*\"':;!?") + Key.Backspace(),
            bottom(page),
        )
        KeyPage.MORE -> listOf(
            texts("~`|•√π÷×¶∆"),
            texts("£€¥^°={}\\_"),
            listOf(Key.Page(KeyPage.SYMBOLS, "?123")) + texts("©®™✓[]<") + Key.Backspace(),
            bottom(page),
        )
    }

    /** Numbers, dates and phone numbers get a pad. */
    fun pad(layout: KeyLayout): List<List<Key>> {
        val phone = layout == KeyLayout.PHONE
        fun row(digits: String, last: Key) = texts(digits) + last
        return listOf(
            row("123", Key.Text(if (phone) "+" else "-")),
            row("456", Key.Text(if (phone) "*" else ",")),
            row("789", Key.Backspace(weight = 1f)),
            listOf(Key.Globe(), Key.Text(if (phone) "#" else "."), Key.Text("0"), Key.Enter(weight = 1f)),
        )
    }
}

/** Shift: a tap capitalizes the next letter, a quick second tap locks it, and a letter releases a one-time shift. */
object ShiftRules {
    const val DOUBLE_TAP_MS = 350L

    fun tap(shift: Shift, sinceLastTapMs: Long): Shift = when (shift) {
        Shift.OFF -> Shift.ONCE
        Shift.ONCE -> if (sinceLastTapMs <= DOUBLE_TAP_MS) Shift.LOCKED else Shift.OFF
        Shift.LOCKED -> Shift.OFF
    }

    fun afterText(shift: Shift): Shift = if (shift == Shift.ONCE) Shift.OFF else shift

    /** The field's caps mode at the cursor turns a one-time shift on, and off again; it never touches a lock. */
    fun auto(shift: Shift, capsNow: Boolean): Shift = when (shift) {
        Shift.LOCKED -> Shift.LOCKED
        else -> if (capsNow) Shift.ONCE else Shift.OFF
    }
}
