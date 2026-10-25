package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gallery's Agent Skills in Anu (ADR-120): SKILL.md, load_skill and the prompt's list. */
class AgentSkillsTest {

    private val qr = """
        ---
        name: qr-code
        description: Generate QR code given a url.
        ---

        # QR Code

        ## Instructions

        Call the `run_js` tool with the following exact parameters:
        - data: The URL.
    """.trimIndent()

    private fun skill(text: String, origin: AgentSkill.Origin = AgentSkill.Origin.BUILT_IN) = SkillMd.parse(text, "asset:skills/x", origin)

    @Test
    fun `a SKILL md gives its name, description and whole text, as Gallery reads it`() {
        val parsed = skill(qr)
        assertEquals("qr-code", parsed.name)
        assertEquals("Generate QR code given a url.", parsed.description)
        assertTrue(parsed.content.contains("Call the `run_js` tool"))
        assertFalse(parsed.requireSecret)
        val secret = skill("---\nname: weather\ndescription: \"Today's weather\"\nrequire-secret: true\n---\nUse run_js.")
        assertTrue(secret.requireSecret)
        assertEquals("quotes come off", "Today's weather", secret.description)
    }

    @Test
    fun `a SKILL md without frontmatter, a name or a description is refused`() {
        listOf("# no frontmatter", "---\nname: a\n", "---\nname: Bad Name\ndescription: x\n---", "---\nname: ok\n---").forEach { text ->
            assertTrue(text, runCatching { skill(text) }.isFailure)
        }
    }

    @Test
    fun `a skill is found by name whatever its case, hyphens, underscores or spaces`() {
        val catalog = InMemorySkillCatalog(listOf(skill(qr)))
        listOf("qr-code", "QR Code", "qr_code", "qrcode").forEach { assertEquals(it, "qr-code", catalog.find(it)?.name) }
        assertNull(catalog.find("calculate-hash"))
    }

    @Test
    fun `load_skill returns the whole SKILL md, and an added skill taints the run`() = runBlocking {
        val builtIn = LoadSkillTool(InMemorySkillCatalog(listOf(skill(qr))))
        val loaded = builtIn.invoke(LoadSkillInput("QR code"), ToolContext("run", "key"))
        assertEquals("qr-code", loaded.skillName)
        assertEquals(skill(qr).content, loaded.skillInstructions)
        assertEquals(RiskTier.READ, builtIn.risk(LoadSkillInput("qr-code")))
        assertNull("built-in skills are Anu's own", builtIn.untrustedContent)
        assertTrue(runCatching { builtIn.describe(LoadSkillInput("weather")) }.exceptionOrNull()!!.message!!.contains("the skills are qr-code"))

        val added = LoadSkillTool(InMemorySkillCatalog(listOf(skill(qr), skill(qr.replace("qr-code", "mine"), AgentSkill.Origin.ADDED))))
        assertEquals(UntrustedContent.SERVICE, added.untrustedContent)
        assertEquals(listOf("skill_name"), added.spec().parameters["properties"]!!.let { (it as kotlinx.serialization.json.JsonObject).keys.toList() })
    }

    @Test
    fun `the prompt lists each skill by name and description only, with Gallery's routing steps`() {
        val section = SkillPrompt.section(listOf(skill(qr)))
        assertTrue(section.contains("- Skill name: \"qr-code\"\n- Description: Generate QR code given a url."))
        assertTrue(section.contains("call load_skill"))
        assertFalse("instructions wait for load_skill", section.contains("Call the `run_js` tool"))
    }
}
