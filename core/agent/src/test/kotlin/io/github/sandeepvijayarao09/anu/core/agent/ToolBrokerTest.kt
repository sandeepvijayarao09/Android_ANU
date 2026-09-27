package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.BrainLocation
import io.github.sandeepvijayarao09.anu.core.model.ToolCall
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBrokerTest {

    private val create = FakeCreateEvent()
    private val inbox = FakeReadInbox()
    private val gate = RecordingGate()
    private val audit = mutableListOf<ToolAuditEntry>()
    private fun broker(gate: ApprovalGate = this.gate, timeout: Long = 60_000) =
        ToolBroker(listOf(create, inbox, FakeHealthRecords(), FakeBrokenTool()), gate, audit = { audit += it }, approvalTimeoutMillis = timeout)

    private fun run(location: BrainLocation = BrainLocation.ON_DEVICE, consented: Boolean = false) =
        RunState("run-1", "turn-1", location, consented)

    private fun event(title: String, vararg attendees: String) = ToolCall(
        "calendar_create_event",
        buildJsonObject {
            put("title", title)
            putJsonArray("attendees") { attendees.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        },
    )

    private val search = ToolCall("email_search", buildJsonObject { put("query", "invites") })

    @Test
    fun `unknown tools and invalid arguments are never executed`() = runTest {
        val broker = broker()
        assertTrue(broker.execute(ToolCall("shell_exec", JsonObject(emptyMap())), run()) is ToolOutcome.Invalid)
        assertTrue(broker.execute(ToolCall("calendar_create_event", buildJsonObject { put("title", 42) }), run()) is ToolOutcome.Invalid)
        assertTrue(broker.execute(ToolCall("calendar_create_event", JsonObject(emptyMap())), run()) is ToolOutcome.Invalid)
        assertTrue(create.created.isEmpty())
    }

    @Test
    fun `reads and private writes run without asking`() = runTest {
        val broker = broker()
        assertTrue(broker.execute(event("Dentist"), run()) is ToolOutcome.Ok)
        assertEquals(listOf(EventInput("Dentist")), create.created)
        assertTrue(gate.requests.isEmpty())
    }

    @Test
    fun `anything others will see needs approval of the exact payload`() = runTest {
        val outcome = broker().execute(event("Design review", "maya@example.com"), run())
        assertTrue(outcome is ToolOutcome.Ok)
        val request = gate.requests.single()
        assertEquals(RiskTier.WRITE_EXTERNAL, request.risk)
        assertEquals("Create \"Design review\" and invite maya@example.com", request.summary)
        assertEquals(buildJsonObject {
            put("title", "Design review")
            putJsonArray("attendees") { add(kotlinx.serialization.json.JsonPrimitive("maya@example.com")) }
        }, request.payload)
    }

    private fun ApprovalRequest.change(key: String, value: kotlinx.serialization.json.JsonElement) =
        ApprovalDecision.Revised(JsonObject(payload + (key to value)))

    private fun names(vararg emails: String) = kotlinx.serialization.json.JsonArray(emails.map { kotlinx.serialization.json.JsonPrimitive(it) })

    @Test
    fun `an edit approves nothing, and the user approves exactly what will run`() = runTest {
        val gate = ScriptedGate({ it.change("title", kotlinx.serialization.json.JsonPrimitive("Design review, moved")) }, { ApprovalDecision.Approved })
        val outcome = broker(gate).execute(event("Design review", "maya@example.com"), run())
        assertTrue(outcome is ToolOutcome.Ok)
        val (first, second) = gate.requests
        assertEquals(false, first.edited)
        assertEquals(listOf("title", "attendees"), first.editable.map { it.key })
        assertEquals(true, second.edited)
        assertEquals("Create \"Design review, moved\" and invite maya@example.com", second.summary)
        assertEquals("Design review, moved", second.payload["title"]!!.jsonPrimitive.content)
        assertTrue("an edit is a new payload, so a new key", first.id != second.id)
        assertEquals(listOf(EventInput("Design review, moved", listOf("maya@example.com"))), create.created)
        assertEquals(ToolBroker.OK_EDITED, audit.last().outcome)
        assertEquals("Create \"Design review, moved\" and invite maya@example.com", audit.last().summary)
    }

    @Test
    fun `an invalid edit keeps asking, with the problem, and runs nothing`() = runTest {
        val gate = ScriptedGate({ it.change("title", kotlinx.serialization.json.JsonPrimitive(42)) }, { ApprovalDecision.Denied("never mind") })
        val outcome = broker(gate).execute(event("Standup", "raj@example.com"), run())
        assertEquals("declined", (outcome as ToolOutcome.Rejected).code)
        val (first, second) = gate.requests
        assertEquals("the same payload is asked for again", first.payload, second.payload)
        assertEquals(first.id, second.id)
        assertTrue(second.problem!!.isNotBlank())
        assertEquals(false, second.edited)
        assertTrue(create.created.isEmpty())
    }

    @Test
    fun `only the fields a tool allows can be changed`() = runTest {
        val gate = ScriptedGate({ it.change("calendar", kotlinx.serialization.json.JsonPrimitive("work")) }, { ApprovalDecision.Approved })
        broker(gate).execute(event("Standup", "raj@example.com"), run())
        assertEquals("Only title, invite can be changed here.", gate.requests[1].problem)
        assertEquals(listOf(EventInput("Standup", listOf("raj@example.com"))), create.created)
    }

    @Test
    fun `an edit that invites people is asked about as an invite`() = runTest {
        val broker = broker(ScriptedGate({ ApprovalDecision.Approved }))
        val state = run()
        broker.execute(search, state) // tainted: even a private event now asks
        val gate = ScriptedGate({ it.change("attendees", names("maya@example.com")) }, { ApprovalDecision.Approved })
        broker(gate).execute(event("Lunch"), state)
        assertEquals(RiskTier.WRITE_PRIVATE, gate.requests[0].risk)
        assertEquals(RiskTier.WRITE_EXTERNAL, gate.requests[1].risk)
        assertEquals("Other people will see this", gate.requests[1].reason)
        assertEquals(listOf(EventInput("Lunch", listOf("maya@example.com"))), create.created)
    }

    @Test
    fun `an edit that no longer needs asking is still shown before it runs`() = runTest {
        val gate = ScriptedGate({ it.change("attendees", names()) }, { ApprovalDecision.Approved })
        broker(gate).execute(event("Focus time", "raj@example.com"), run())
        assertEquals(2, gate.requests.size)
        assertEquals(RiskTier.WRITE_PRIVATE, gate.requests[1].risk)
        assertEquals("You changed this", gate.requests[1].reason)
        assertEquals(listOf(EventInput("Focus time")), create.created)
    }

    @Test
    fun `after an edit, a retry of the original call replays instead of acting again`() = runTest {
        val gate = ScriptedGate({ it.change("title", kotlinx.serialization.json.JsonPrimitive("Review")) }, { ApprovalDecision.Approved })
        val broker = broker(gate)
        val state = run()
        broker.execute(event("Design review", "maya@example.com"), state)
        val retry = broker.execute(event("Design review", "maya@example.com"), state)
        assertTrue(retry is ToolOutcome.Ok)
        assertEquals(1, create.created.size)
        assertEquals("no new approval", 2, gate.requests.size)
        assertEquals("replayed", audit.last().outcome)
    }

    @Test
    fun `a denial blocks the call and tells the model why`() = runTest {
        gate.decision = ApprovalDecision.Denied("wrong day")
        val outcome = broker().execute(event("Design review", "maya@example.com"), run())
        assertEquals(
            ToolOutcome.Rejected(
                "The user was shown this and declined (wrong day), so nothing was done. Don't try again unless they ask.",
                "Create \"Design review\" and invite maya@example.com",
                code = "declined",
            ),
            outcome,
        )
        assertEquals("declined", (outcome as ToolOutcome.Rejected).toResultJson().jsonObject["error"]!!.jsonPrimitive.content)
        assertTrue(create.created.isEmpty())
        assertEquals(false, audit.last().approved)
    }

    @Test
    fun `no answer in time counts as a denial`() = runTest {
        val outcome = broker(RecordingGate(decision = null), timeout = 50).execute(event("Standup", "raj@example.com"), run())
        assertEquals("no_answer", (outcome as ToolOutcome.Rejected).code)
        assertTrue(create.created.isEmpty())
        assertEquals("no answer in time", audit.last().outcome)
    }

    @Test
    fun `after reading untrusted content even private writes need approval`() = runTest {
        val broker = broker()
        val state = run()
        broker.execute(search, state)
        assertTrue(state.tainted)
        broker.execute(event("Injected event"), state)
        assertEquals("This request read content written by others", gate.requests.single().reason)
    }

    @Test
    fun `a repeated side effect in the same turn is replayed, not repeated`() = runTest {
        val broker = broker()
        val first = broker.execute(event("Design review", "maya@example.com"), run())
        val again = broker.execute(event("Design review", "maya@example.com"), run())
        assertEquals(first, again)
        assertEquals(1, create.created.size)
        assertEquals(1, gate.requests.size)
        assertEquals("replayed", audit.last().outcome)
    }

    @Test
    fun `sensitive results stay on the phone unless the user consented to the cloud`() = runTest {
        val call = ToolCall("health_search", buildJsonObject { put("query", "allergies") })
        assertTrue(broker().execute(call, run(BrainLocation.CLOUD)) is ToolOutcome.Rejected)
        assertTrue(broker().execute(call, run(BrainLocation.CLOUD, consented = true)) is ToolOutcome.Ok)
        assertTrue(broker().execute(call, run(BrainLocation.ON_DEVICE)) is ToolOutcome.Ok)
    }

    @Test
    fun `a failing tool is reported, and cancellation is never swallowed`() = runTest {
        val call = ToolCall("broken_tool", buildJsonObject { put("query", "x") })
        assertEquals(ToolOutcome.Failed("disk on fire", "Break"), broker().execute(call, run()))

        val hanging = ToolBroker(listOf(FakeBrokenTool(hang = true)), gate)
        val job = async { hanging.execute(call, run()) }
        yield()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `the approval center shows pending requests until they are decided or withdrawn`() = runTest {
        val center = ApprovalCenter()
        val broker = ToolBroker(listOf(create), center)
        val pending = async { broker.execute(event("Design review", "maya@example.com"), run()) }
        yield()
        val request = center.requests.value.single()
        center.decide(request.id, ApprovalDecision.Approved)
        assertTrue(pending.await() is ToolOutcome.Ok)
        assertTrue(center.requests.value.isEmpty())

        val withdrawn = launch { broker.execute(event("Other", "raj@example.com"), run()) }
        yield()
        assertEquals(1, center.requests.value.size)
        withdrawn.cancelAndJoin()
        assertTrue(center.requests.value.isEmpty())
    }

    @Test
    fun `tools that come and go are found, and follow the same policy`() = runTest {
        val remote = FakeRemoteLookup()
        var available = listOf<Tool<*, *>>()
        val broker = ToolBroker(listOf(create, inbox), gate, dynamicTools = { available })
        val lookup = ToolCall(remote.name, buildJsonObject { put("query", "groceries") })
        assertTrue(broker.execute(lookup, run()) is ToolOutcome.Invalid)

        available = listOf(remote)
        assertEquals(listOf(remote.name), broker.specs(listOf(remote.name)).map { it.name })
        assertTrue(broker.execute(lookup, run()) is ToolOutcome.Ok)
        assertEquals(listOf("groceries"), remote.queries)
        assertTrue("an untainted lookup runs without asking", gate.requests.isEmpty())
    }

    @Test
    fun `once a run is tainted, a lookup that sends data to a third party needs approval`() = runTest {
        val remote = FakeRemoteLookup()
        val broker = ToolBroker(listOf(create, inbox, remote), gate)
        val state = run()
        assertTrue(broker.execute(search, state) is ToolOutcome.Ok) // reads text written by others
        gate.decision = ApprovalDecision.Denied("no")
        val outcome = broker.execute(ToolCall(remote.name, buildJsonObject { put("query", "my address") }), state)
        assertTrue(outcome is ToolOutcome.Rejected)
        assertEquals(RiskTier.READ, gate.requests.single().risk)
        assertTrue(gate.requests.single().reason, gate.requests.single().reason.contains("notes.example.com"))
        assertTrue("the sheet can ask for a closer read", gate.requests.single().tainted)
        assertTrue(remote.queries.isEmpty())
        // A lookup that stays on the phone still runs without asking.
        assertTrue(broker.execute(search, state) is ToolOutcome.Ok)
        assertEquals(1, gate.requests.size)
    }

    @Test
    fun `an approval for a third party says where the data goes`() = runTest {
        val order = object : Tool<QueryInput, ItemsOutput> {
            override val name = "shop_checkout"
            override val description = "Start a checkout"
            override val input = QueryInput.serializer()
            override val output = ItemsOutput.serializer()
            override val dataClasses = setOf(io.github.sandeepvijayarao09.anu.core.model.DataClass.PERSONAL)
            override val returnsUntrustedContent = true
            override fun risk(input: QueryInput) = RiskTier.WRITE_EXTERNAL
            override fun describe(input: QueryInput) = "Start a checkout for ${input.query}"
            override fun sendsTo(input: QueryInput) = "shoes.example.com"
            override suspend fun invoke(input: QueryInput, context: ToolContext) = ItemsOutput(listOf("chk_1"))
        }
        val broker = ToolBroker(listOf(order), gate)
        assertTrue(broker.execute(ToolCall("shop_checkout", buildJsonObject { put("query", "shoes") }), run()) is ToolOutcome.Ok)
        assertEquals("This goes to shoes.example.com, outside your phone", gate.requests.single().reason)
        assertEquals("shoes.example.com", gate.requests.single().destination)
        assertEquals(false, gate.requests.single().tainted)
    }
}
