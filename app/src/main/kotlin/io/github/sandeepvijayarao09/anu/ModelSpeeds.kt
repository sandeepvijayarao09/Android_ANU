package io.github.sandeepvijayarao09.anu

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.sandeepvijayarao09.anu.brain.litertlm.OnDeviceBrains
import io.github.sandeepvijayarao09.anu.brain.litertlm.SpeedTest
import io.github.sandeepvijayarao09.anu.core.data.AnswerDao
import io.github.sandeepvijayarao09.anu.core.data.realUse
import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.ModelSpeed
import io.github.sandeepvijayarao09.anu.core.model.OnDeviceModels
import io.github.sandeepvijayarao09.anu.core.model.Speed
import io.github.sandeepvijayarao09.anu.core.model.TestStep
import io.github.sandeepvijayarao09.anu.core.model.TestUpdate
import io.github.sandeepvijayarao09.anu.core.router.Guard
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The Models page's speed (ADR-073): real use from saved answers, and Test speed through the on-device brains. The
 * last test is kept in the app's settings, as numbers only. A test run while the phone is warm is marked so.
 */
class ModelSpeeds(
    private val answers: AnswerDao,
    private val brains: OnDeviceBrains,
    private val models: OnDeviceModels,
    private val device: DeviceStateProvider,
    private val prefs: SharedPreferences,
) : ModelSpeed {

    override suspend fun realUse(id: BrainId): Speed? = answers.realUse(if (id == BrainId.BASE) Tier.BASE else Tier.PRO, models.info(id).variant)

    override fun lastTest(id: BrainId): Speed? = prefs.getString(key(id), null)?.let(::decode)

    override fun test(id: BrainId): Flow<TestUpdate> {
        val warmAtStart = warm()
        return brains.test(id, SpeedTest.request).map { update ->
            if (update.step != TestStep.DONE || update.speed == null) return@map update
            val speed = update.speed!!.copy(warm = warmAtStart || warm())
            prefs.edit { putString(key(id), encode(speed)) }
            update.copy(speed = speed)
        }
    }

    override fun warm(): Boolean = device.current().guard == Guard.HEAT

    private fun key(id: BrainId) = "speed_test_${id.name}"

    private fun encode(speed: Speed): String =
        listOf(speed.firstWordMs, speed.readPerSecond, speed.writePerSecond, speed.counted, speed.atMillis, speed.warm).joinToString("|")

    private fun decode(saved: String): Speed? = runCatching {
        val parts = saved.split("|")
        Speed(
            firstWordMs = parts[0].toLongOrNull(),
            readPerSecond = parts[1].toDoubleOrNull(),
            writePerSecond = parts[2].toDoubleOrNull(),
            counted = parts[3].toBoolean(),
            atMillis = parts[4].toLong(),
            warm = parts[5].toBoolean(),
        )
    }.getOrNull()
}
