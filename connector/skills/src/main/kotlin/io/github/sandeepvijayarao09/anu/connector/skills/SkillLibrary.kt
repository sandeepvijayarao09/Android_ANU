package io.github.sandeepvijayarao09.anu.connector.skills

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import io.github.sandeepvijayarao09.anu.core.agent.AgentSkill
import io.github.sandeepvijayarao09.anu.core.agent.AgentSkillCatalog
import io.github.sandeepvijayarao09.anu.core.agent.SkillMd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** A skill as the Skills page lists it: on or off, and where it came from. */
data class SkillEntry(val skill: AgentSkill, val enabled: Boolean)

/**
 * The phone's Agent Skills (ADR-120), as Gallery's SkillManager keeps them: the built-in ones in `assets/skills`, and
 * those the user added from a web address or a folder, each under `files/skills/<name>`. A skill added from a web
 * address keeps its `SKILL.md` here and runs its scripts from that address, as in Gallery. The user can turn any skill off.
 */
class SkillLibrary(private val context: Context, private val prefs: SharedPreferences, private val http: OkHttpClient) : AgentSkillCatalog {
    private val added = File(context.filesDir, "skills")
    private val mutableEntries = MutableStateFlow<List<SkillEntry>>(emptyList())

    /** Every skill with whether it's on, for the Skills page. */
    val entries: StateFlow<List<SkillEntry>> = mutableEntries

    @Volatile
    private var all: List<AgentSkill> = emptyList()

    init {
        reload()
    }

    override fun skills(): List<AgentSkill> {
        val off = disabled()
        return all.filter { it.name !in off }
    }

    /** Reads the built-in and added skills again. A folder that isn't a valid skill is left out. */
    fun reload() {
        val builtIn = context.assets.list(ASSETS).orEmpty().mapNotNull { dir ->
            runCatching {
                val text = context.assets.open("$ASSETS/$dir/$SKILL_MD").bufferedReader().use { it.readText() }
                SkillMd.parse(text, "$ASSET_BASE$ASSETS/$dir", AgentSkill.Origin.BUILT_IN)
            }.getOrNull()
        }
        val user = added.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            runCatching {
                val base = File(dir, BASE_FILE).takeIf { it.exists() }?.readText()?.trim() ?: "$FILE_BASE${dir.absolutePath}"
                SkillMd.parse(File(dir, SKILL_MD).readText(), base, AgentSkill.Origin.ADDED)
            }.getOrNull()
        }
        // An added skill can't take a built-in one's name.
        all = builtIn + user.filter { u -> builtIn.none { it.name == u.name } }
        publish()
    }

    fun setEnabled(name: String, on: Boolean) {
        val off = disabled().toMutableSet()
        if (on) off.remove(name) else off.add(name)
        prefs.edit().putStringSet(DISABLED, off).apply()
        publish()
    }

    /** Adds the skill whose folder is at [address], as Gallery does: it reads `<address>/SKILL.md`. */
    suspend fun addFromUrl(address: String): AgentSkill = withContext(Dispatchers.IO) {
        val folder = address.trim().removeSuffix("/$SKILL_MD").trimEnd('/')
        val url = runCatching { "$folder/$SKILL_MD".toHttpUrl() }.getOrElse { error("“$address” isn't a web address") }
        require(url.isHttps) { "a skill's address must start with https://" }
        val text = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            require(response.isSuccessful) { "No SKILL.md at $folder (HTTP ${response.code})" }
            response.body.string().take(MAX_SKILL_MD)
        }
        val skill = SkillMd.parse(text, folder, AgentSkill.Origin.ADDED)
        save(skill.name) { dir ->
            File(dir, SKILL_MD).writeText(text)
            File(dir, BASE_FILE).writeText(folder)
        }
        skill
    }

    /** Adds the skill in a folder the user picked, copying its files into Anu's storage. */
    suspend fun addFromFolder(tree: Uri): AgentSkill = withContext(Dispatchers.IO) {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val md = requireNotNull(children(tree, root).firstOrNull { it.name == SKILL_MD && !it.directory }) { "That folder has no SKILL.md" }
        val text = context.contentResolver.openInputStream(md.uri).use { requireNotNull(it).bufferedReader().readText() }
        val probe = SkillMd.parse(text, "", AgentSkill.Origin.ADDED)
        save(probe.name) { dir -> copy(tree, root, dir, depth = 0) }
        probe
    }

    fun remove(name: String) {
        File(added, name).takeIf { it.isDirectory }?.deleteRecursively()
        reload()
    }

    /** A file of [skill]'s folder, such as `scripts/index.html`, or null when it isn't there or the skill is on the web. */
    fun read(skill: AgentSkill, path: String): ByteArray? {
        val clean = path.trimStart('/')
        if (clean.split('/').any { it == ".." }) return null
        return when {
            skill.base.startsWith(ASSET_BASE) -> runCatching {
                context.assets.open(skill.base.removePrefix(ASSET_BASE) + "/" + clean).use { it.readBytes() }
            }.getOrNull()
            skill.base.startsWith(FILE_BASE) -> File(skill.base.removePrefix(FILE_BASE), clean).takeIf { it.isFile }?.readBytes()
            else -> null
        }
    }

    /** The text of every page and script in [skill]'s folder, for [JsNetwork]. Empty for a skill on the web. */
    fun sources(skill: AgentSkill): List<String> = when {
        skill.base.startsWith(ASSET_BASE) -> assetFiles(skill.base.removePrefix(ASSET_BASE))
            .filter { it.endsWith(".html") || it.endsWith(".js") || it.endsWith(".mjs") }
            .mapNotNull { runCatching { context.assets.open(it).bufferedReader().use { r -> r.readText() } }.getOrNull() }
        skill.base.startsWith(FILE_BASE) -> File(skill.base.removePrefix(FILE_BASE)).walk()
            .filter { it.isFile && (it.extension == "html" || it.extension == "js" || it.extension == "mjs") }
            .map { it.readText() }.toList()
        else -> emptyList()
    }

    private fun assetFiles(dir: String): List<String> {
        val children = context.assets.list(dir).orEmpty()
        if (children.isEmpty()) return listOf(dir)
        return children.flatMap { assetFiles("$dir/$it") }
    }

    private fun save(name: String, write: (File) -> Unit) {
        require(all.none { it.name == name && it.origin == AgentSkill.Origin.BUILT_IN }) { "A built-in skill is already called $name" }
        val dir = File(added, name)
        dir.deleteRecursively()
        dir.mkdirs()
        try {
            write(dir)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
        reload()
    }

    private data class Doc(val name: String, val uri: Uri, val directory: Boolean)

    private fun children(tree: Uri, parent: Uri): List<Doc> {
        val listing = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        return context.contentResolver.query(listing, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                    add(Doc(cursor.getString(1).orEmpty(), uri, cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            }
        }.orEmpty()
    }

    private fun copy(tree: Uri, from: Uri, to: File, depth: Int) {
        require(depth <= MAX_DEPTH) { "That folder is nested too deeply to be a skill" }
        to.mkdirs()
        children(tree, from).forEach { doc ->
            if (doc.name.isEmpty() || doc.name.startsWith(".") || doc.name.contains('/')) return@forEach
            if (doc.directory) {
                copy(tree, doc.uri, File(to, doc.name), depth + 1)
            } else {
                context.contentResolver.openInputStream(doc.uri)?.use { input -> File(to, doc.name).outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private fun disabled(): Set<String> = prefs.getStringSet(DISABLED, emptySet()).orEmpty()

    private fun publish() {
        val off = disabled()
        mutableEntries.value = all.map { SkillEntry(it, it.name !in off) }
    }

    companion object {
        const val ASSETS = "skills"
        const val SKILL_MD = "SKILL.md"
        const val ASSET_BASE = "asset:"
        const val FILE_BASE = "file:"
        private const val BASE_FILE = ".base"
        private const val DISABLED = "skills_disabled"
        private const val MAX_SKILL_MD = 100_000
        private const val MAX_DEPTH = 4
    }
}
