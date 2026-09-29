package io.github.sandeepvijayarao09.anu.core.router

/**
 * Why the phone is guarded (§6, Stage A; EXP-4, BATTERY-1, ADR-072). While it is, a request scored for Pro goes to
 * Base when it fits, Pro gives shorter answers, and nothing moves to the cloud unless the user asks.
 */
enum class Guard(val reason: String) {
    HEAT("the phone is warm"),
    BATTERY("the battery is low"),
}

/** The guard's numbers (ADR-072). Measured on the reference phone before they're called final. */
object GuardLimits {
    /** The longest answer, in tokens, Pro writes while guarded: about 40 s on the Pixel's CPU. */
    const val ANSWER_TOKENS = 256

    /** How far ahead Android's heat forecast looks. One on-device answer takes 5 to 30 s. */
    const val FORECAST_SECONDS = 10

    /** The forecast that counts as warm when Android doesn't say where moderate throttling starts. */
    const val HEADROOM = 0.8f

    /** Low battery: this percentage or less, and not charging. */
    const val LOW_BATTERY_PERCENT = 15
}

/**
 * Android's heat forecast (ENG-1). On Android 16 and later a listener [push]es every new forecast. Earlier versions
 * are asked at most every [minIntervalMs], because Android answers NaN to anyone who asks more often; between
 * readings the last one is reused. Null when no forecast is known.
 */
class HeatForecast(
    private val read: () -> Float,
    private val clock: () -> Long,
    private val minIntervalMs: Long = 10_000,
) {
    @Volatile private var last = Float.NaN

    @Volatile private var readAt: Long? = null

    @Volatile private var pushed = false

    fun push(forecast: Float) {
        pushed = true
        if (!forecast.isNaN()) last = forecast
    }

    fun current(): Float? {
        if (!pushed) {
            val now = clock()
            val previous = readAt
            if (previous == null || now - previous >= minIntervalMs) {
                readAt = now
                read().let { if (!it.isNaN()) last = it }
            }
        }
        return last.takeUnless { it.isNaN() }
    }
}

/**
 * The guard from the phone's readings (ADR-072): warm when the forecast reaches [threshold], or when Android
 * already reports severe heat, which is all there is without a forecast; otherwise low battery when at
 * [GuardLimits.LOW_BATTERY_PERCENT] or less and not charging.
 */
fun guardFor(forecast: Float?, threshold: Float, severe: Boolean, batteryPercent: Int, charging: Boolean): Guard? = when {
    severe || (forecast != null && forecast >= threshold) -> Guard.HEAT
    batteryPercent in 0..GuardLimits.LOW_BATTERY_PERCENT && !charging -> Guard.BATTERY
    else -> null
}
