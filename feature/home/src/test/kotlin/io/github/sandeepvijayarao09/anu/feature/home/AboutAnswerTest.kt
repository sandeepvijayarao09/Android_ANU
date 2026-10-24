package io.github.sandeepvijayarao09.anu.feature.home

import io.github.sandeepvijayarao09.anu.core.agent.ActionRecord
import io.github.sandeepvijayarao09.anu.core.agent.AnswerDetails
import io.github.sandeepvijayarao09.anu.core.router.Tier
import org.junit.Assert.assertEquals
import org.junit.Test

/** What "About this answer" says, from the saved details alone (ADR-071). */
class AboutAnswerTest {

    private fun action(tool: String, status: ActionRecord.Status = ActionRecord.Status.DONE, destination: String? = null) =
        ActionRecord(tool, "summary", status, wrote = true, destination = destination)

    @Test
    fun `the headline says where your data went`() {
        assertEquals(false to "Nothing left your phone", whereItWent(AnswerDetails(Tier.PRO, actions = listOf(action("calendar_create_event")))))
        assertEquals(true to "1 item left your phone · to your Gmail", whereItWent(AnswerDetails(Tier.BASE, actions = listOf(action("email_send")))))
        assertEquals(
            true to "2 items left your phone · to your Gmail and shoes.example.com",
            whereItWent(AnswerDetails(Tier.PRO, actions = listOf(action("email_send"), action("shop_search", destination = "shoes.example.com")))),
        )
        assertEquals("a declined send didn't leave", false to "Nothing left your phone", whereItWent(AnswerDetails(Tier.PRO, actions = listOf(action("email_send", ActionRecord.Status.NOT_DONE)))))
        assertEquals(true to "Sent to Gemini (Google)", whereItWent(AnswerDetails(Tier.CLOUD)))
    }

    @Test
    fun `the second line names the model and where it ran`() {
        assertEquals("Gemma 4 E4B · GPU, on this phone", whoAnswered(AnswerDetails(Tier.PRO, backend = "GPU")))
        assertEquals("Gemma 4 E2B · Tensor TPU, on this phone", whoAnswered(AnswerDetails(Tier.BASE, backend = "NPU_TENSOR_G5")))
        assertEquals("Gemma 4 E2B · NPU, on this phone", whoAnswered(AnswerDetails(Tier.BASE, backend = "NPU_SNAPDRAGON_SM8750")))
        assertEquals("Gemma 4 E2B · on this phone", whoAnswered(AnswerDetails(Tier.BASE)))
        assertEquals("Gemini · in the cloud", whoAnswered(AnswerDetails(Tier.CLOUD)))
    }

    @Test
    fun `speed shows clock times exactly, and token rates as estimates unless counted`() {
        val estimated = AnswerDetails(Tier.PRO, firstWordMs = 131_566, totalMs = 190_000, read = 1_455, readMs = 115_000, written = 57, writeMs = 43_800)
        assertEquals(
            listOf("First word" to "2 min 11 s", "Reading" to "about 13 tokens/s", "Writing" to "about 1.3 tokens/s", "Total" to "3 min 10 s"),
            speedRows(estimated),
        )
        val counted = estimated.copy(firstWordMs = 1_200, totalMs = 14_000, read = 271, readMs = 1_000, counted = true)
        assertEquals("271 tokens/s", speedRows(counted).toMap()["Reading"])
        assertEquals("14 s", speedRows(counted).toMap()["Total"])
        assertEquals("the cloud has no token rates", listOf("First word", "Total"), speedRows(AnswerDetails(Tier.CLOUD, firstWordMs = 700, totalMs = 1_500, read = 10, readMs = 5)).map { it.first })
    }

    @Test
    fun `the choices under an answer follow the guard and whether Gemini can be asked`() {
        val heat = io.github.sandeepvijayarao09.anu.core.router.Guard.HEAT
        val lighter = Bubble(1, fromUser = false, text = "A", tier = Tier.BASE, guard = heat, guarded = true)
        assertEquals(listOf(RetryChoice.ON_PHONE, RetryChoice.GEMINI), retryChoices(lighter, cloudAllowed = true))
        assertEquals(listOf(RetryChoice.WITH_PRO), retryChoices(lighter, cloudAllowed = false))
        val shortened = Bubble(1, fromUser = false, text = "A", tier = Tier.PRO, guard = heat, guarded = true)
        assertEquals(listOf(RetryChoice.GEMINI), retryChoices(shortened, cloudAllowed = true))
        assertEquals(emptyList<RetryChoice>(), retryChoices(shortened, cloudAllowed = false))
        val failed = Bubble(1, fromUser = false, text = "I couldn't answer that.", tier = Tier.BASE, guard = heat, failed = true)
        assertEquals(listOf(RetryChoice.ON_PHONE, RetryChoice.GEMINI), retryChoices(failed, cloudAllowed = true))
        assertEquals(listOf(RetryChoice.WITH_PRO), retryChoices(Bubble(1, fromUser = false, text = "A", tier = Tier.BASE), cloudAllowed = true))
        assertEquals(emptyList<RetryChoice>(), retryChoices(Bubble(1, fromUser = false, text = "A", tier = Tier.PRO), cloudAllowed = true))
    }

    @Test
    fun `the reason line names what the guard changed, and only when it did`() {
        val battery = io.github.sandeepvijayarao09.anu.core.router.Guard.BATTERY
        assertEquals("Lighter model while the battery is low", guardLine(Bubble(1, fromUser = false, text = "A", tier = Tier.BASE, guard = battery, guarded = true)))
        assertEquals(null, guardLine(Bubble(1, fromUser = false, text = "A", tier = Tier.BASE, guard = battery, guarded = false)))
        val cut = AnswerDetails(Tier.PRO, guard = io.github.sandeepvijayarao09.anu.core.router.Guard.HEAT, cut = true)
        assertEquals("Kept short while the phone cools", guardLine(Bubble(1, fromUser = false, text = "A", tier = Tier.PRO, details = cut)))
        assertEquals("Kept short because the phone was warm.", guardReason(cut))
        assertEquals("The lighter model, because the battery was low.", guardReason(AnswerDetails(Tier.BASE, guard = battery)))
    }

    @Test
    fun `a phone command says no model was needed, and how long it took`() {
        val done = AnswerDetails(Tier.BASE, actions = listOf(action("phone_set_timer")), firstWordMs = 120, totalMs = 120, direct = true)
        assertEquals(false to "Nothing left your phone", whereItWent(done))
        assertEquals("Done on this phone · no model needed", whoAnswered(done))
        assertEquals(listOf("Done in" to "0.1 s"), speedRows(done))
        assertEquals(emptyList<RetryChoice>(), retryChoices(Bubble(1, fromUser = false, text = "Timer set.", tier = Tier.BASE, details = done), cloudAllowed = true))
    }

    @Test
    fun `how long the model thought is in About, not in the thread`() {
        val thought = AnswerDetails(Tier.PRO, firstWordMs = 28_700, totalMs = 40_000, thoughtMs = 20_000)
        assertEquals("20 s", speedRows(thought).toMap()["Thinking"])
        assertEquals(null, speedRows(AnswerDetails(Tier.PRO, firstWordMs = 1_000)).toMap()["Thinking"])
    }
}
