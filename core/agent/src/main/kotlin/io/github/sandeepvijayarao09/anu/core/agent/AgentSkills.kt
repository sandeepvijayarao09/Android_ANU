package io.github.sandeepvijayarao09.anu.core.agent

import io.github.sandeepvijayarao09.anu.core.model.DataClass
import io.github.sandeepvijayarao09.anu.core.model.Description
import io.github.sandeepvijayarao09.anu.core.model.UntrustedContent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An Agent Skill, as AI Edge Gallery defines one (ADR-120): a folder with a `SKILL.md` whose frontmatter names and
 * describes it, and whose body tells the model what to do, perhaps by running one of its scripts ([content]). The model
 * sees only the name and description until it loads the skill.
 */
data class AgentSkill(
    val name: String,
    val description: String,
    /** The whole `SKILL.md`, frontmatter and instructions, as `load_skill` returns it. */
    val content: String,
    /** Where the skill's files are: an asset folder, a folder in the app's storage, or the web address it was added from. */
    val base: String,
    val origin: Origin,
    /** The skill needs an API key or token for its script (`require-secret`). */
    val requireSecret: Boolean = false,
    val homepage: String? = null,
) {
    enum class Origin { BUILT_IN, ADDED }

    /** Added by the user from a web address or a file: someone else's words, which taint the run (P4). */
    val untrusted: Boolean get() = origin == Origin.ADDED
}

/** Reads a `SKILL.md`: its frontmatter between `---` lines, as Gallery's SkillManager does. */
object SkillMd {
    fun parse(text: String, base: String, origin: AgentSkill.Origin): AgentSkill {
        val lines = text.replace("\r\n", "\n").lines()
        require(lines.firstOrNull()?.trim() == "---") { "a SKILL.md starts with a --- line" }
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        require(end >= 0) { "a SKILL.md's frontmatter ends with a --- line" }
        val fields = lines.subList(1, end + 1).mapNotNull { line ->
            val colon = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim().removeSurrounding("\"")
        }.toMap()
        val name = fields["name"].orEmpty()
        require(NAME.matches(name)) { "a skill's name is lowercase words joined by hyphens, such as qr-code" }
        val description = fields["description"].orEmpty()
        require(description.isNotBlank()) { "the skill $name has no description" }
        return AgentSkill(
            name = name,
            description = description.take(MAX_DESCRIPTION),
            content = text.trim(),
            base = base,
            origin = origin,
            requireSecret = fields["require-secret"]?.lowercase() == "true",
            homepage = fields["homepage"],
        )
    }

    private val NAME = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    private const val MAX_DESCRIPTION = 300
}

/** The skills the model can use: the built-in ones and those the user added, less any the user turned off (ADR-120). */
interface AgentSkillCatalog {
    fun skills(): List<AgentSkill>

    /** A skill by name, as Gallery matches it: case, hyphens, underscores and spaces don't count. */
    fun find(name: String): AgentSkill? = skills().firstOrNull { normalized(it.name) == normalized(name) }

    companion object {
        fun normalized(name: String) = name.lowercase().filter { it.isLetterOrDigit() }
    }
}

class InMemorySkillCatalog(private val all: List<AgentSkill>) : AgentSkillCatalog {
    override fun skills() = all
}

/** What the system prompt says about skills (ADR-120): Gallery's routing steps, with each skill's name and description only. */
object SkillPrompt {
    fun section(skills: List<AgentSkill>): String = buildString {
        append("\n\nYou also have skills. If the request matches one of the skills below, call load_skill with its name, ")
        append("then follow the skill's instructions exactly, using run_js or run_intent as they say. ")
        append("Do not call run_js or run_intent before loading the skill. Output only the final result, ")
        append("with a one-sentence summary of what was done. If no skill matches, answer or use your other tools as usual.")
        append("\n--- SKILLS ---")
        skills.forEach { append("\n- Skill name: \"").append(it.name).append("\"\n- Description: ").append(it.description) }
    }
}

@Serializable
data class LoadSkillInput(@SerialName("skill_name") @Description("The name of the skill to load.") val skillName: String)

@Serializable
data class LoadedSkill(
    @SerialName("skill_name") val skillName: String,
    @SerialName("skill_instructions") val skillInstructions: String,
)

/**
 * Gallery's `load_skill` (ADR-120): the skill's whole `SKILL.md`, so its instructions enter the model's context only
 * when the request needs them. A skill the user added is someone else's text, so it taints the run (P4).
 */
class LoadSkillTool(private val catalog: AgentSkillCatalog) : Tool<LoadSkillInput, LoadedSkill> {
    override val name = NAME
    override val description = "Loads a skill."
    override val input: KSerializer<LoadSkillInput> = LoadSkillInput.serializer()
    override val output: KSerializer<LoadedSkill> = LoadedSkill.serializer()
    override val dataClasses: Set<DataClass> = emptySet()
    override val untrustedContent: UntrustedContent?
        get() = if (catalog.skills().any { it.untrusted }) UntrustedContent.SERVICE else null

    override fun risk(input: LoadSkillInput) = RiskTier.READ

    override fun describe(input: LoadSkillInput): String {
        val skill = requireNotNull(catalog.find(input.skillName)) {
            "there is no skill named “${input.skillName}”; the skills are ${catalog.skills().joinToString { it.name }}"
        }
        return "Load the ${skill.name} skill"
    }

    override suspend fun invoke(input: LoadSkillInput, context: ToolContext): LoadedSkill {
        val skill = requireNotNull(catalog.find(input.skillName)) { "Skill not found" }
        return LoadedSkill(skill.name, skill.content)
    }

    companion object {
        const val NAME = "load_skill"
    }
}
