package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.BrainId
import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.ChatMessage
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain
import io.github.sandeepvijayarao09.anu.core.model.ScriptedBrain.Step.Reply
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import io.github.sandeepvijayarao09.anu.core.router.ComplexityScorer
import io.github.sandeepvijayarao09.anu.core.router.Router
import io.github.sandeepvijayarao09.anu.core.router.Tier
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppControlTest {

    /** A made-up shop whose screens change as Anu acts, recording every action. */
    private class FakeShop(var screens: MutableList<AppScreen>, override val ready: Boolean = true) : AppDriver {
        val actions = mutableListOf<String>()
        val lines = mutableListOf<String>()
        var setupOpened = false
        var hidden = false
        val stopButton = MutableSharedFlow<Unit>(replay = 1)
        override var current: AppScreen? = null
        override val stops = stopButton

        override fun openSetup() {
            setupOpened = true
        }

        override suspend fun open(name: String): LaunchedApp? =
            if (name.equals("test shop", ignoreCase = true)) LaunchedApp("Test Shop", SHOP) else null

        override suspend fun read(): AppScreen = (if (screens.size > 1) screens.removeAt(0) else screens.first()).also { current = it }

        override suspend fun tap(id: Int) {
            actions += "tap ${current!!.element(id)!!.label}"
        }

        override suspend fun type(id: Int, text: String) {
            actions += "type $text"
        }

        override suspend fun enter(id: Int) {
            actions += "enter"
        }

        override suspend fun scroll(down: Boolean) {
            actions += if (down) "scroll down" else "scroll up"
        }

        override suspend fun back() {
            actions += "back"
        }

        override fun showProgress(app: String, line: String) {
            lines += line
        }

        override fun hideProgress() {
            hidden = true
        }
    }

    private fun screen(vararg elements: ScreenElement, app: String = "Test Shop", pkg: String = SHOP) = AppScreen(app, pkg, elements.toList())

    private fun button(id: Int, label: String) = ScreenElement(id, ScreenElement.Kind.BUTTON, label)

    private fun field(id: Int, label: String, value: String? = null, password: Boolean = false) =
        ScreenElement(id, ScreenElement.Kind.FIELD, label, value, password)

    private fun step(action: String, element: Int = 0, text: String = "", note: String = "") = Reply(
        """{"action":"$action","element":$element,"text":"$text","note":"$note"}""",
    )

    private val home = screen(field(1, "Search"), button(2, "Cart"))
    private val results = screen(field(1, "Search", "trail runner"), button(2, "Trail Runner 2 · \$120"), button(3, "Road Glide · \$95"))
    private val product = screen(button(1, "Size 10"), button(2, "Add to cart"), button(3, "Buy now"))
    private val added = screen(ScreenElement(1, ScreenElement.Kind.TEXT, "Added to cart"), button(2, "Proceed to checkout"))

    private val gate = RecordingGate()

    private fun setUp(shop: FakeShop, brain: ScriptedBrain): ToolBroker {
        lateinit var broker: ToolBroker
        broker = ToolBroker(listOf(AppTaskTool(shop, { brain }, { broker }), AppStepTool(shop)), gate)
        return broker
    }

    private fun task(goal: String, app: String = "Test Shop") =
        ToolCall(AppTaskTool.NAME, buildJsonObject { put("app", app); put("goal", goal) })

    private fun run() = RunState("run-1", "turn-1", BrainLocation.ON_DEVICE, cloudConsented = false)

    private fun result(outcome: ToolOutcome) = (outcome as ToolOutcome.Ok).result.jsonObject

    @Test
    fun `a task asks once for its goal and app, then taps through without asking`() = runTest {
        val shop = FakeShop(mutableListOf(home, results, product, product, added))
        val brain = ScriptedBrain(
            BrainId.PRO,
            steps = listOf(
                step("type", 1, "trail runner"),
                step("tap", 2),
                step("tap", 1),
                step("tap", 2),
                step("done", note = "Added the Trail Runner 2 in size 10 to your cart."),
            ),
        )
        val outcome = setUp(shop, brain).execute(task("add the trail runner in size 10 to the cart"), run())

        assertEquals(listOf("type trail runner", "tap Trail Runner 2 · \$120", "tap Size 10", "tap Add to cart"), shop.actions)
        val result = result(outcome)
        assertEquals("Added the Trail Runner 2 in size 10 to your cart.", result["said"]!!.jsonPrimitive.content)
        assertTrue(result["finished"]!!.jsonPrimitive.boolean)
        // One approval, of the goal and the app, with the task's own reason.
        assertEquals(1, gate.requests.size)
        assertEquals("In Test Shop: add the trail runner in size 10 to the cart", gate.requests.single().summary)
        assertTrue(gate.requests.single().reason.startsWith("Anu will tap and type in Test Shop"))
        assertTrue(shop.hidden)
    }

    @Test
    fun `each step shows the model the screen and the steps so far, held to a schema`() = runTest {
        val shop = FakeShop(mutableListOf(home, results))
        val brain = ScriptedBrain(BrainId.PRO, steps = listOf(step("type", 1, "trail runner"), step("stuck", note = "No such shoe.")))
        setUp(shop, brain).execute(task("find the trail runner"), run())

        val second = brain.requests[1]
        val asked = (second.transcript.single() as ChatMessage.User).text
        assertTrue(asked.contains("1. Type “trail runner” into “Search” in Test Shop"))
        assertTrue(asked.contains("[2] button \"Trail Runner 2 · \$120\""))
        assertTrue(second.responseSchema != null)
        assertEquals("app control", second.purpose)
    }

    @Test
    fun `a purchase button is never tapped`() = runTest {
        val shop = FakeShop(mutableListOf(product))
        val brain = ScriptedBrain(BrainId.PRO, steps = listOf(step("tap", 3)))
        val result = result(setUp(shop, brain).execute(task("buy the trail runner"), run()))

        assertTrue(shop.actions.isEmpty())
        assertFalse(result["finished"]!!.jsonPrimitive.boolean)
        assertTrue(result["said"]!!.jsonPrimitive.content.startsWith("Stopped before “Buy now”: Anu never buys or pays"))
    }

    @Test
    fun `a delete button asks for that exact tap, and a no stops the task`() = runTest {
        val cart = screen(ScreenElement(1, ScreenElement.Kind.TEXT, "Trail Runner 2"), button(2, "Remove"))
        val shop = FakeShop(mutableListOf(cart))
        val brain = ScriptedBrain(BrainId.PRO, steps = listOf(step("tap", 2)))
        val gate = object : ApprovalGate {
            val requests = mutableListOf<ApprovalRequest>()
            override suspend fun request(request: ApprovalRequest): ApprovalDecision {
                requests += request
                return if (request.toolName == AppTaskTool.NAME) ApprovalDecision.Approved else ApprovalDecision.Denied()
            }
        }
        lateinit var broker: ToolBroker
        broker = ToolBroker(listOf(AppTaskTool(shop, { brain }, { broker }), AppStepTool(shop)), gate)
        val result = result(broker.execute(task("empty my cart"), run()))

        val asked = gate.requests.last()
        assertEquals("Tap “Remove” in Test Shop", asked.summary)
        assertEquals(RiskTier.DESTRUCTIVE, asked.risk)
        assertEquals(setOf(UntrustedContent.SCREEN), asked.untrusted)
        assertEquals(listOf(PayloadRow("In", "Test Shop"), PayloadRow("Tap", "“Remove”")), asked.rows)
        assertTrue(shop.actions.isEmpty())
        assertEquals("Stopped, since you didn't approve this step: Tap “Remove” in Test Shop.", result["said"]!!.jsonPrimitive.content)
    }

    @Test
    fun `enter outside a search box counts as sending`() = runTest {
        val chat = screen(field(1, "Message", "On my way"), button(2, "Send"))
        val shop = FakeShop(mutableListOf(chat))
        val brain = ScriptedBrain(BrainId.PRO, steps = listOf(step("enter", 1), step("done", note = "Sent.")))
        setUp(shop, brain).execute(task("tell Maya I'm on my way"), run())

        assertEquals(RiskTier.WRITE_EXTERNAL, gate.requests.last().risk)
        assertEquals("Press enter in “Message” in Test Shop", gate.requests.last().summary)
    }

    @Test
    fun `password fields are never typed into, and their text is never read`() = runTest {
        val login = screen(field(1, "Email", "maya@example.com"), field(2, "Password", password = true))
        assertEquals("[1] field \"Email\" = \"maya@example.com\"\n[2] password field \"Password\"", login.forModel())

        val shop = FakeShop(mutableListOf(login))
        val brain = ScriptedBrain(BrainId.PRO, steps = listOf(step("type", 2, "hunter2"), step("stuck", note = "It needs your password.")))
        val result = result(setUp(shop, brain).execute(task("sign in"), run()))
        assertTrue(shop.actions.isEmpty())
        assertTrue(result["steps"].toString().contains("Anu doesn't type into password fields"))
    }

    @Test
    fun `a screen that speaks of a code has its codes masked`() {
        val sms = screen(ScreenElement(1, ScreenElement.Kind.TEXT, "Your verification code is 482913"))
        assertFalse(sms.forModel().contains("482913"))
    }

    @Test
    fun `another app taking the screen stops the task`() = runTest {
        val browser = screen(button(1, "Accept"), app = "Chrome", pkg = "com.android.chrome")
        val shop = FakeShop(mutableListOf(browser))
        val result = result(setUp(shop, ScriptedBrain(BrainId.PRO)).execute(task("read the size guide"), run()))
        assertTrue(result["said"]!!.jsonPrimitive.content.startsWith("Stopped: Chrome came up"))
    }

    @Test
    fun `Stop ends the task at once`() = runTest {
        val shop = FakeShop(mutableListOf(home))
        val brain = ScriptedBrain(BrainId.PRO, steps = listOf(ScriptedBrain.Step.Hang()))
        val broker = setUp(shop, brain)
        val outcome = coroutineScope {
            val running = async { broker.execute(task("add milk to the cart"), run()) }
            while (brain.requests.isEmpty()) yield()
            shop.stopButton.tryEmit(Unit)
            running.await()
        }
        assertEquals("Stopped in Test Shop, as you asked.", result(outcome)["said"]!!.jsonPrimitive.content)
        assertEquals(1, brain.cancellations)
        assertTrue(shop.hidden)
    }

    @Test
    fun `the same step on the same screen three times stops the task`() = runTest {
        val shop = FakeShop(mutableListOf(home))
        val brain = ScriptedBrain(BrainId.PRO, steps = List(3) { step("tap", 2) })
        val result = result(setUp(shop, brain).execute(task("open the cart"), run()))
        assertEquals(2, shop.actions.size)
        assertTrue(result["said"]!!.jsonPrimitive.content.startsWith("Stopped: Anu kept trying the same thing"))
    }

    @Test
    fun `with app control off, Anu opens its setup and does nothing`() = runTest {
        val shop = FakeShop(mutableListOf(home), ready = false)
        val outcome = setUp(shop, ScriptedBrain(BrainId.PRO)).execute(task("add milk"), run())
        assertTrue(outcome is ToolOutcome.Failed)
        assertTrue(shop.setupOpened)
    }

    @Test
    fun `an app that isn't on the phone fails, and opening one needs no model or approval`() = runTest {
        val shop = FakeShop(mutableListOf(home))
        val broker = setUp(shop, ScriptedBrain(BrainId.PRO))
        assertTrue(broker.execute(task("add milk", app = "Nowhere"), run()) is ToolOutcome.Failed)
        val opened = broker.execute(ToolCall(AppTaskTool.NAME, buildJsonObject { put("app", "Test Shop") }), run())
        assertEquals("Opened Test Shop.", result(opened)["said"]!!.jsonPrimitive.content)
        // Only the task with a goal asked.
        assertEquals(1, gate.requests.size)
    }

    @Test
    fun `the model's step is parsed strictly`() {
        assertEquals(AppStep(StepAction.SCROLL_DOWN), AppTaskTool.parse("```json\n{\"action\":\"scroll_down\"}\n```"))
        assertNull(AppTaskTool.parse("{\"action\":\"jump\"}"))
        assertNull(AppTaskTool.parse("tap the button"))
    }

    @Test
    fun `final actions are matched by whole words`() {
        assertEquals(FinalActions.Kind.PURCHASE, FinalActions.of("Buy now"))
        assertEquals(FinalActions.Kind.PURCHASE, FinalActions.of("Proceed to checkout"))
        assertEquals(FinalActions.Kind.PURCHASE, FinalActions.of("Place your order"))
        assertEquals(FinalActions.Kind.SEND, FinalActions.of("Send"))
        assertEquals(FinalActions.Kind.DELETE, FinalActions.of("Remove"))
        assertNull(FinalActions.of("Add to cart"))
        assertNull(FinalActions.of("Payment methods"))
        assertNull(FinalActions.of("Size 10"))
        assertTrue(FinalActions.isSearch("Search products"))
    }

    @Test
    fun `messages that name an app become app tasks`() {
        fun args(text: String) = AppCommands.match(text)?.arguments?.let { it["app"]!!.jsonPrimitive.content to it["goal"]!!.jsonPrimitive.content }
        assertEquals("Test Shop" to "add the trail runner to my cart", args("In the Test Shop app, add the trail runner to my cart."))
        assertEquals("Clock" to "set an alarm for 7 am", args("Open the Clock app and set an alarm for 7 am"))
        assertEquals("clock" to "", args("open the clock app"))
        assertEquals("Shop" to "Add milk to my cart", args("Add milk to my cart in the Shop app"))
        assertNull(args("I'm working on an app"))
        assertNull(args("What's on my calendar today?"))
        assertNull(args("In the app, add milk"))
    }

    @Test
    fun `a granted tool's private steps run without asking even while tainted, and nothing riskier does`() = runTest {
        val shop = FakeShop(mutableListOf(product)).apply { current = product }
        val broker = ToolBroker(listOf(AppStepTool(shop)), gate)
        val tap = ToolCall(AppStepTool.NAME, buildJsonObject { put("action", "tap"); put("element", 1) })
        val tainted = RunState("r", "t1", BrainLocation.ON_DEVICE, false, setOf(UntrustedContent.SCREEN))
        broker.execute(tap, tainted)
        assertEquals(1, gate.requests.size)
        val granted = RunState("r", "t2", BrainLocation.ON_DEVICE, false, setOf(UntrustedContent.SCREEN), granted = setOf(AppStepTool.NAME))
        broker.execute(tap, granted)
        assertEquals(1, gate.requests.size)
        val buy = ToolCall(AppStepTool.NAME, buildJsonObject { put("action", "tap"); put("element", 3) })
        assertTrue(broker.execute(buy, granted) is ToolOutcome.Invalid)
    }

    private companion object {
        const val SHOP = "io.github.sandeepvijayarao09.anu.testshop"
    }
}

class AppTaskAnswerTest {
    /** An app task that says it used Pro, or no model when it only opened the app. */
    private class Task : Tool<AppTaskInput, AppTaskDone> {
        override val name = AppTaskTool.NAME
        override val description = "Do something in an app"
        override val input = AppTaskInput.serializer()
        override val output = AppTaskDone.serializer()
        override val dataClasses = emptySet<DataClass>()
        override val untrustedContent = UntrustedContent.SCREEN
        override fun risk(input: AppTaskInput) = RiskTier.WRITE_PRIVATE
        override fun describe(input: AppTaskInput) = "In ${input.app}: ${input.goal}"
        override suspend fun invoke(input: AppTaskInput, context: ToolContext) =
            if (input.goal.isEmpty()) AppTaskDone("Opened it.", finished = true) else AppTaskDone("Added it.", finished = true, brain = BrainId.PRO)
    }

    @Test
    fun `an app task's answer names the model that picked its steps, and opening an app needs none`() = runTest {
        val agent = Agent(Router(ComplexityScorer()), { null }, ToolBroker(listOf(Task()), RecordingGate()))
        suspend fun answer(goal: String) =
            agent.runDirect(ToolCall(AppTaskTool.NAME, buildJsonObject { put("app", "Test Shop"); put("goal", goal) }), "t-$goal").toList().last() as AgentEvent.Completed

        val acted = answer("add milk")
        assertEquals(Tier.PRO, acted.tier)
        assertFalse(acted.direct)
        assertTrue(answer("").direct)
    }
}
