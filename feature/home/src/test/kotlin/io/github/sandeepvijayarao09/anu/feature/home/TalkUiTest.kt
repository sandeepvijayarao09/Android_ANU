package io.github.sandeepvijayarao09.anu.feature.home

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.github.sandeepvijayarao09.anu.core.agent.ActionRecord
import io.github.sandeepvijayarao09.anu.core.agent.AnswerDetails
import io.github.sandeepvijayarao09.anu.core.agent.ApprovalRequest
import io.github.sandeepvijayarao09.anu.core.agent.EditableField
import io.github.sandeepvijayarao09.anu.core.agent.RiskTier
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import io.github.sandeepvijayarao09.anu.core.router.Tier
import io.github.sandeepvijayarao09.anu.core.ui.AnuTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Talk page's surfaces on screen (ENG-9): what each shows, what TalkBack is told, and what each button does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TalkUiTest {

    @get:Rule val compose = createComposeRule()

    private val onDevice = AnswerDetails(
        tier = Tier.PRO,
        backend = "GPU",
        factIds = listOf(7L, 8L),
        summaryShown = true,
        actions = listOf(ActionRecord("mcp_notes_search", "Search notes for \"groceries\"", ActionRecord.Status.DONE, wrote = false, destination = "notes.example.com")),
        firstWordMs = 1_200,
        totalMs = 4_000,
        read = 45,
        readMs = 1_000,
        written = 16,
        writeMs = 3_000,
    )

    @Test
    fun `the caption under an answer opens About this answer, and so does a long press`() {
        var opened = 0
        compose.setContent {
            AnuTheme(dark = false) {
                AnuTurn(Bubble(1, fromUser = false, text = "Milk, eggs and spinach.", tier = Tier.PRO, details = onDevice), onAbout = { opened++ })
            }
        }
        val caption = compose.onNodeWithText("On device · Gemma 4 E4B").assertHasClickAction()
        assertEquals("About this answer", caption.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        caption.performClick()
        assertEquals(1, opened)

        val answer = compose.onNodeWithText("Milk, eggs and spinach.")
        answer.performTouchInput { longClick() }
        assertEquals(2, opened)
        assertEquals(listOf("About this answer"), answer.fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label })
    }

    @Test
    fun `an answer saved before details were kept has a plain caption`() {
        compose.setContent {
            AnuTheme(dark = false) { AnuTurn(Bubble(1, fromUser = false, text = "Hello.", tier = Tier.BASE)) }
        }
        compose.onNodeWithText("On device · Gemma 4 E2B").assertHasNoClickAction()
    }

    @Test
    fun `About this answer says where your data went first, then the model, facts, actions and speed`() {
        var memory: Long? = -1
        compose.setContent {
            AnuTheme(dark = false) {
                AboutSheet(AboutAnswer(onDevice, listOf(7L to "manager: Maya Chen", 8L to null)), onOpenMemory = { memory = it }, onOpenPrivacy = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText("About this answer").assertExists()
        compose.onNodeWithText("1 item left your phone · to notes.example.com").assertExists()
        compose.onNodeWithText("Gemma 4 E4B · GPU, on this phone").assertExists()
        compose.onNodeWithText("A fact you've since deleted or changed").assertHasNoClickAction()
        compose.onNodeWithText("Your memory summary").assertExists()
        compose.onNodeWithText("Search notes for \"groceries\"").assertExists()
        compose.onNodeWithText("1.2 s").assertExists()
        compose.onNodeWithText("about 45 tokens/s").assertExists()
        compose.onNodeWithText("Reading and writing speeds are estimates.").assertExists()
        compose.onNodeWithText("View in Privacy report").assertDoesNotExist()

        compose.onNodeWithText("manager: Maya Chen").performScrollTo().performClick()
        assertEquals(7L, memory)
    }

    @Test
    fun `a Gemini answer says it was sent, lists the facts shared, and links to the Privacy report`() {
        var privacy = 0
        val cloud = AnswerDetails(tier = Tier.CLOUD, factIds = listOf(7L), firstWordMs = 700, totalMs = 1_500)
        compose.setContent {
            AnuTheme(dark = false) {
                AboutSheet(AboutAnswer(cloud, listOf(7L to "manager: Maya Chen")), onOpenMemory = {}, onOpenPrivacy = { privacy++ }, onDismiss = {})
            }
        }
        compose.onNodeWithText("Sent to Gemini (Google)").assertExists()
        compose.onNodeWithText("Gemini · in the cloud").assertExists()
        compose.onNodeWithText("Facts you shared").assertExists()
        compose.onNodeWithText("Reading").assertDoesNotExist()
        compose.onNodeWithText("View in Privacy report").performScrollTo().performClick()
        assertEquals(1, privacy)
    }

    @Test
    fun `the approval sheet shows the exact payload, where it goes, why it asks, and its buttons work`() {
        val decided = mutableListOf<String>()
        val request = ApprovalRequest(
            id = "a1",
            toolName = "mcp_notes_add",
            summary = "Add a note to Notes",
            payload = buildJsonObject { put("title", "Groceries"); put("text", "Oat milk, eggs") },
            risk = RiskTier.WRITE_EXTERNAL,
            reason = "This goes to notes.example.com, outside your phone. This chat shows an email, which others can write, so check this closely",
            destination = "notes.example.com",
            untrusted = setOf(UntrustedContent.EMAIL),
            editable = listOf(EditableField("text", "Text", EditableField.Kind.TEXT)),
        )
        compose.setContent {
            AnuTheme(dark = false) {
                ConversationSheets(ChatUiState(approval = request), { decided += "approve $it" }, { decided += "deny $it" }, { _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithText("Send this to a service?").assertExists()
        compose.onNodeWithText("Leaves your phone · to notes.example.com").assertExists()
        compose.onNodeWithText("Add a note to Notes").assertExists()
        compose.onNodeWithText("Groceries").assertExists()
        compose.onNodeWithText("Oat milk, eggs").assertExists()
        compose.onNodeWithText(
            "This goes to notes.example.com, outside your phone. This chat shows an email, which others can write, so check this closely. " +
                "Nothing happens until you tap Send.",
        ).assertExists()
        compose.onNodeWithText("Edit").assertHasClickAction()
        compose.onNodeWithText("Send").performScrollTo().performClick()
        compose.onNodeWithText("Don't send").performScrollTo().performClick()
        assertEquals(listOf("approve a1", "deny a1"), decided)
    }

    @Test
    fun `the consent sheet lists the facts that would go, and one switch keeps them on the phone`() {
        val shared = mutableListOf<Boolean>()
        var declined = 0
        compose.setContent {
            AnuTheme(dark = false) {
                ConversationSheets(ChatUiState(consentFor = "Gift for my manager?", consentFacts = listOf("manager: Maya Chen")), {}, {}, { _, _ -> }, { shared += it }, { declined++ })
            }
        }
        compose.onNodeWithText("Use Gemini for this?").assertExists()
        compose.onNodeWithText("Leaves your phone · to Gemini (Google)").assertExists()
        compose.onNodeWithText("manager: Maya Chen").assertExists()
        compose.onNodeWithText("Send to Gemini").performScrollTo().performClick()
        compose.onNodeWithText("Include what Anu knows").performClick()
        compose.onNodeWithText("Send to Gemini").performScrollTo().performClick()
        compose.onNodeWithText("Keep on this phone").performScrollTo().performClick()
        assertEquals(listOf(true, false), shared)
        assertEquals(1, declined)
    }

    @Test
    fun `a photo shows in the thread, and one no longer kept keeps its place with a quiet line`() {
        val bitmap = android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888)
        val jpeg = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        compose.setContent {
            AnuTheme(dark = false) {
                androidx.compose.foundation.layout.Column {
                    PhotoView(io.github.sandeepvijayarao09.anu.core.model.Photo(0, jpeg), load = { null })
                    PhotoView(io.github.sandeepvijayarao09.anu.core.model.Photo(9), load = { null })
                }
            }
        }
        compose.onNodeWithContentDescription("Photo you shared").assertExists()
        compose.onNodeWithContentDescription("Photo you shared, no longer kept").assertExists()
    }

    @Test
    fun `a voice note shows its kind, length and words as one item, and one not written down says so`() {
        compose.setContent {
            AnuTheme(dark = false) {
                androidx.compose.foundation.layout.Column {
                    VoiceClipView(VoiceClip(17, words = "Call Maya on Thursday."))
                    VoiceClipView(VoiceClip(62, shared = true, interrupted = true))
                }
            }
        }
        compose.onNodeWithText("Voice note · 0:17", substring = true).assertExists()
        val merged = compose.onNodeWithText("Call Maya on Thursday.", substring = true).fetchSemanticsNode()
        assertEquals(
            "TalkBack reads the label and the words together",
            listOf("Voice note · 0:17", "Call Maya on Thursday."),
            merged.config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { it.text },
        )
        compose.onNodeWithText("Not written down", substring = true).assertExists()
        compose.onNodeWithText("Shared recording · 1:02 · Stopped early", substring = true).assertExists()
    }

    @Test
    fun `the recording bar tells TalkBack the time, and Stop and Cancel do what they say`() {
        var stopped = 0
        var cancelled = 0
        compose.setContent {
            AnuTheme(dark = false) { RecordingBar(12, level = 0.5f, onCancel = { cancelled++ }, onStop = { stopped++ }) }
        }
        compose.onNodeWithContentDescription("Recording a voice note, 0:12").assertExists()
        compose.onNodeWithContentDescription("Stop recording").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, stopped)
        assertEquals(1, cancelled)
    }

    @Test
    fun `the plus sheet offers a photo and a voice note, and a voice note needs a model that can hear`() {
        var voice = 0
        val hears = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            AnuTheme(dark = false) { AddSheet(canHear = hears.value, onPhoto = {}, onVoiceNote = { voice++ }, onDismiss = {}) }
        }
        compose.onNodeWithText("Photo").assertExists()
        compose.onNodeWithText("Needs Gemma 4 on this phone").assertExists()
        compose.onNodeWithText("Voice note").performClick()
        assertEquals("disabled without a model that can hear", 0, voice)

        hears.value = true
        compose.onNodeWithText("Record it, and Anu writes it down on this phone").assertExists()
        compose.onNodeWithText("Voice note").performClick()
        compose.waitForIdle()
        assertEquals(1, voice)
    }
}
