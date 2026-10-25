package io.github.sandeepvijayarao09.anu.connector.skills

import io.github.sandeepvijayarao09.anu.core.agent.AgentSkill
import io.github.sandeepvijayarao09.anu.core.agent.SkillMd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Gallery's built-in skills as Anu bundles them, and what their pages load (ADR-120). */
class SkillFilesTest {
    private val root = File("src/main/assets/skills")

    private fun sources(name: String) = File(root, name).walk()
        .filter { it.isFile && it.extension in setOf("html", "js", "mjs") }.map { it.readText() }.toList()

    @Test
    fun `every bundled skill parses, named as its folder`() {
        val folders = root.listFiles()!!.filter { it.isDirectory }
        assertEquals(12, folders.size)
        folders.forEach { dir ->
            val skill = SkillMd.parse(File(dir, "SKILL.md").readText(), "asset:skills/${dir.name}", AgentSkill.Origin.BUILT_IN)
            assertEquals(dir.name, skill.name)
        }
    }

    @Test
    fun `skills that load from the web are told apart from those that don't`() {
        assertEquals("a hash is worked out on the phone", emptySet<String>(), JsNetwork.hosts(sources("calculate-hash")))
        assertEquals(setOf("cdnjs.cloudflare.com"), JsNetwork.hosts(sources("qr-code")))
        assertTrue(JsNetwork.hosts(sources("query-wikipedia")).isNotEmpty())
        assertEquals("a link or a licence header isn't a load", emptySet<String>(), JsNetwork.hosts(listOf("<!-- http://www.apache.org/licenses/LICENSE-2.0 --><a href=\"https://github.com/x\">x</a>")))
        assertEquals(setOf(JsNetwork.WEB), JsNetwork.hosts(listOf("const r = await fetch(url);")))
    }

    @Test
    fun `a page's answer reads as Gallery reads it`() {
        assertEquals(RanJs(result = "ab12"), JsResult.parse("{\"result\":\"ab12\"}"))
        assertEquals("not JSON is the answer itself", RanJs(result = "plain"), JsResult.parse("plain"))
        assertEquals("No such page", JsResult.parse("{\"error\":\"No such page\"}").error)
        val picture = JsResult.parse("{\"result\":\"done\",\"image\":{\"base64\":\"AAA\"}}")
        assertEquals("done", picture.result)
        assertTrue(picture.note!!.contains("picture"))
        assertNull(JsResult.parse("{\"result\":\"x\"}").note)
    }

    @Test
    fun `data reaches the page as a JSON string, never as code`() {
        val call = JsSkillRunner.call("\"); alert(1); (\"")
        assertTrue(call.contains("ai_edge_gallery_get_result(\"\\\"); alert(1); (\\\"\", \"\")"))
        assertTrue(call.contains("AiEdgeGallery.onResultReady(result)"))
    }
}
