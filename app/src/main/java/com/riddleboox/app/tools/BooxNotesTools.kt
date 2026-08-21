package com.riddleboox.app.tools

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import com.riddleboox.app.reply.Toolbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileInputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

private const val LIST_NOTES = "list_boox_notes"
private const val SEARCH_NOTES = "search_boox_notes"
private const val OPEN_NOTE = "open_note"
private const val READ_NOTE = "read_boox_note"
private const val DELETE_NOTE = "delete_boox_note"
private const val CREATE_NOTE = "create_boox_note"
private const val RENAME_NOTE = "rename_boox_note"

private const val NOTES_LISTED = 12
private const val SEARCH_RESULTS = 20
private const val MAX_READ_CHARS = 12_000
private const val MAX_EXPORTED_FILES = 4_000
private const val MAX_SHAPE_BYTES = 4 * 1024 * 1024
private const val MAX_VISION_BYTES = 8 * 1024 * 1024

/** A notebook returned by BOOX's own Notebook ContentProvider. */
data class BooxNote(
    val id: String,
    val title: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val pageCount: Int,
    val pageIds: List<String>,
    val richTextPageIds: List<String>,
    val source: String?,
    val favorite: Boolean,
)

/** One page, with text recovered from BOOX's typed-shape data when available. */
data class BooxNotePage(
    val note: BooxNote,
    val pageNumber: Int,
    val pageId: String?,
    val text: String,
    val imageFile: File?,
    val hasPrivatePageData: Boolean,
)

/** Platform seam: the tool logic can be tested without an Android provider. */
interface BooxNotesSource {
    fun listNotes(): List<BooxNote>
    fun readPage(note: String, pageNumber: Int): BooxNotePage

    /**
     * Removes a notebook from BOOX Notebook: its row, its stroke data, and its
     * exported pages. Nothing of it is kept anywhere.
     */
    fun deleteNote(note: String): DeletedNote

    /** Starts an empty notebook with one page, and hands back what was made. */
    fun createNote(title: String): BooxNote

    /** Gives an existing notebook a new title. */
    fun renameNote(note: String, title: String): BooxNote
}

/** What a [BooxNotesSource.deleteNote] actually took off the device. */
data class DeletedNote(val note: BooxNote, val entry: Boolean, val files: Int)

/** Optional second-stage reader for exported page images. */
fun interface BooxNotesVisionReader {
    suspend fun readPage(page: BooxNotePage): String
}

/**
 * BOOX Notebook's public provider and its shared-storage export/canonical data.
 *
 * This is deliberately separate from [OnyxLibrary]: NeoReader highlights and
 * Notebook pages are two different products, databases, and user concepts.
 */
class OnyxBooxNotes(
    private val resolver: ContentResolver,
    private val noteRoot: File = File(Environment.getExternalStorageDirectory(), "note"),
    private val ksyncRoot: File = File(Environment.getExternalStorageDirectory(), ".ksync"),
) : BooxNotesSource {

    override fun listNotes(): List<BooxNote> {
        val found = LinkedHashMap<String, BooxNote>()
        resolver.query(NOTE_URI, PROJECTION, null, null, "updatedAt DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.string("uniqueId") ?: continue
                if (id.isBlank()) continue
                found.putIfAbsent(id, cursor.toNote(id))
            }
        }
        return found.values.toList()
    }

    override fun readPage(note: String, pageNumber: Int): BooxNotePage {
        require(pageNumber > 0) { "page must be at least 1" }
        val selected = resolveNote(note)
        val pageId = selected.pageIds.getOrNull(pageNumber - 1)
        val privateDocument = File(ksyncRoot, "document/${selected.id}")
        val exported = exportedFiles(selected)
        val image = exported.filter { it.isImageFile() }.getOrNull(pageNumber - 1)
        val shapeText = pageId?.let { readTypedShapeText(selected.id, it) }.orEmpty()
        return BooxNotePage(
            note = selected,
            pageNumber = pageNumber,
            pageId = pageId,
            text = shapeText,
            imageFile = image,
            hasPrivatePageData = privateDocument.isDirectory,
        )
    }

    /**
     * Takes the row out first, then everything on disk that belonged to it.
     *
     * The row is what BOOX Notebook lists the note by, so losing it is what
     * makes the note gone as far as the writer is concerned; the stroke data
     * under `.ksync` and the exported pages under `note/` are dead weight after
     * that, and are cleared so a later notebook of the same name does not find
     * a previous one's pages waiting for it.
     */
    override fun deleteNote(note: String): DeletedNote {
        val selected = resolveNote(note)
        val exported = exportedFiles(selected)
        val rows = resolver.delete(NOTE_URI, "uniqueId = ?", arrayOf(selected.id))
        val files = exported.count { it.delete() } +
            clear(File(ksyncRoot, "document/${selected.id}"))
        return DeletedNote(note = selected, entry = rows > 0, files = files)
    }

    /**
     * Inserts a notebook row with one empty page.
     *
     * The page id is generated here rather than left out: BOOX Notebook reads
     * `pageNameList` to know a notebook has anything at all, and a row without
     * one opens as a notebook with no pages.
     */
    override fun createNote(title: String): BooxNote {
        val wanted = title.trim()
        require(wanted.isNotBlank()) { "a title is needed" }
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString().replace("-", "")
        val values = ContentValues().apply {
            put("uniqueId", id)
            put("title", wanted)
            put("createdAt", now)
            put("updatedAt", now)
            put("type", 1)
            put("pageNameList", """{"pageNameList":["${UUID.randomUUID()}"]}""")
            put("richTextPageNameList", """{"pageNameList":[]}""")
            put("source", "Local")
            put("favorite", 0)
        }
        resolver.insert(NOTE_URI, values)
            ?: throw IllegalStateException("BOOX Notebook would not take a new note")
        return listNotes().firstOrNull { it.id == id }
            ?: throw IllegalStateException("BOOX Notebook took the note but does not list it")
    }

    override fun renameNote(note: String, title: String): BooxNote {
        val wanted = title.trim()
        require(wanted.isNotBlank()) { "a new title is needed" }
        val selected = resolveNote(note)
        val values = ContentValues().apply {
            put("title", wanted)
            put("updatedAt", System.currentTimeMillis())
        }
        val rows = resolver.update(NOTE_URI, values, "uniqueId = ?", arrayOf(selected.id))
        check(rows > 0) { "BOOX Notebook did not rename \"${selected.title}\"" }
        return selected.copy(title = wanted)
    }

    /** Deletes a directory and everything under it, counting the files that went. */
    private fun clear(directory: File): Int {
        if (!directory.isDirectory) return 0
        var files = 0
        directory.walkBottomUp().forEach { entry ->
            if (entry.isFile && entry.delete()) files++ else if (entry.isDirectory) entry.delete()
        }
        return files
    }

    private fun resolveNote(query: String): BooxNote {
        require(query.isNotBlank()) { "note is required" }
        val notes = listNotes()
        val exactId = notes.firstOrNull { it.id.equals(query, ignoreCase = true) }
        if (exactId != null) return exactId
        val exactTitle = notes.filter { it.title.equals(query.trim(), ignoreCase = true) }
        if (exactTitle.size == 1) return exactTitle.single()
        val matches = notes.filter { it.title.contains(query.trim(), ignoreCase = true) }
        if (matches.size == 1) return matches.single()
        if (matches.isEmpty()) throw NoSuchElementException("No BOOX Notebook note matches \"$query\".")
        throw IllegalArgumentException(
            "More than one BOOX Notebook note matches \"$query\": " +
                matches.take(8).joinToString(", ") { "\"${it.title}\" (${it.id})" },
        )
    }

    // internal (not private) so BooxNotesToolsTest can characterize this BFS's
    // depth cutoff, dedup, and natural-sort behavior directly; OnyxBooxNotes has
    // no other seam that exercises it without a real BOOX ContentProvider.
    internal fun exportedFiles(note: BooxNote): List<File> {
        if (!noteRoot.isDirectory) return emptyList()
        val titleKey = searchKey(note.title)
        if (titleKey.isBlank()) return emptyList()
        val candidates = ArrayList<File>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(noteRoot to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_EXPORTED_FILES) {
            val (directory, depth) = queue.removeFirst()
            visited = visitExportCandidates(directory, depth, titleKey, visited, queue, candidates)
        }
        return candidates.sortedWith(compareBy<File> { naturalKey(it.name) }.thenBy { it.path })
    }

    /**
     * Walks one directory's children for [exportedFiles]'s BFS: every child counts
     * against the shared [MAX_EXPORTED_FILES] visit budget (files and directories
     * alike, hence the budget check runs before either branch below), matching
     * subdirectories up to depth 5 are queued for a later visit, and readable files
     * whose relative path matches [titleKey] are appended to [candidates]. Returns
     * the updated visited count.
     */
    private fun visitExportCandidates(
        directory: File,
        depth: Int,
        titleKey: String,
        visited: Int,
        queue: ArrayDeque<Pair<File, Int>>,
        candidates: MutableList<File>,
    ): Int {
        var count = visited
        val children = directory.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }.orEmpty()
        for (child in children) {
            if (++count >= MAX_EXPORTED_FILES) break
            when {
                child.isDirectory && depth < 5 -> queue.add(child to depth + 1)
                child.isFile && child.isReadableExport() && matchesTitle(child, titleKey) -> candidates += child
            }
        }
        return count
    }

    private fun matchesTitle(file: File, titleKey: String): Boolean {
        val relativeKey = searchKey(runCatching { file.relativeTo(noteRoot).path }.getOrDefault(file.name))
        return relativeKey.contains(titleKey)
    }

    private fun readTypedShapeText(noteId: String, pageId: String): String {
        val shapeDirectory = File(ksyncRoot, "document/$noteId/shape")
        val revisions = shapeDirectory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$pageId#") && it.extension.equals("zip", true) }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .orEmpty()
        val latest = revisions.lastOrNull() ?: return ""
        return runCatching { readShapeZip(latest) }.getOrDefault("")
    }

    private fun readShapeZip(zipFile: File): String {
        val values = ArrayList<String>()
        ZipFile(zipFile).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { !it.isDirectory } ?: return ""
            if (entry.size > MAX_SHAPE_BYTES) return ""
            zip.getInputStream(entry).use { input ->
                val bytes = input.readBounded(MAX_SHAPE_BYTES) ?: return ""
                values += ShapeProtoText.read(bytes)
            }
        }
        return values.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
            .take(MAX_READ_CHARS)
    }

    private fun Cursor.toNote(id: String): BooxNote = BooxNote(
        id = id,
        title = string("title")?.trim().orEmpty().ifBlank { "Untitled BOOX note" },
        createdAtMs = long("createdAt"),
        updatedAtMs = long("updatedAt"),
        pageCount = int("pageCount").coerceAtLeast(0),
        pageIds = pageIds(string("pageNameList")),
        richTextPageIds = pageIds(string("richTextPageNameList")),
        source = string("source")?.takeIf { it.isNotBlank() },
        favorite = int("favorite") != 0,
    )

    private fun Cursor.string(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.long(column: String): Long = string(column)?.toLongOrNull() ?: 0L

    private fun Cursor.int(column: String): Int = string(column)?.toIntOrNull() ?: 0

    private fun pageIds(raw: String?): List<String> = runCatching {
        val element = json.parseToJsonElement(raw.orEmpty())
        when {
            element is kotlinx.serialization.json.JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull }
            element is JsonObject -> element["pageNameList"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    private fun File.isReadableExport(): Boolean = isImageFile() || extension.equals("pdf", true)

    private fun File.isImageFile(): Boolean = extension.lowercase(Locale.ROOT) in setOf("png", "jpg", "jpeg", "webp")

    private fun searchKey(value: String): String = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun naturalKey(value: String): String = Regex("\\d+").replace(value.lowercase(Locale.ROOT)) {
        it.value.padStart(12, '0')
    }

    companion object {
        val NOTE_URI: Uri = Uri.parse("content://com.onyx.android.sdk.note.ContentProvider/NoteModel")
        private val PROJECTION = arrayOf(
            "uniqueId", "title", "createdAt", "updatedAt", "pageCount",
            "pageNameList", "richTextPageNameList", "source", "favorite",
        )
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Tools for BOOX Notebook notes, distinct from NeoReader books/highlights and
 * distinct from the agent's private workspace files.
 */
class BooxNotesTools(
    private val source: BooxNotesSource,
    private val visionReader: BooxNotesVisionReader? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val openNote: suspend (BooxNote?) -> Boolean = { false },
) : Toolbox {

    constructor(
        resolver: ContentResolver,
        visionReader: BooxNotesVisionReader? = null,
        openNote: suspend (BooxNote?) -> Boolean = { false },
    ) : this(OnyxBooxNotes(resolver), visionReader, ZoneId.systemDefault(), openNote)

    override val tools: List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = LIST_NOTES,
            description = "List handwritten and typed notebooks created in the BOOX Notebook app. " +
                "This is separate from books and NeoReader highlights.",
            requiredParameters = emptyList(),
            optionalParameters = listOf(
                ToolParameterDescriptor("query", "Optional words from a notebook title.", ToolParameterType.String),
                ToolParameterDescriptor("limit", "Maximum notes to return; default $NOTES_LISTED.", ToolParameterType.Integer),
            ),
        ),
        ToolDescriptor(
            name = SEARCH_NOTES,
            description = "Find BOOX Notebook notes by title and return their page counts and update times.",
            requiredParameters = listOf(
                ToolParameterDescriptor("query", "Words from the note title.", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("limit", "Maximum matching notes; default $SEARCH_RESULTS.", ToolParameterType.Integer),
            ),
        ),
        ToolDescriptor(
            name = OPEN_NOTE,
            description = "Open the BOOX Notebook app. Optionally name a notebook so it can be identified " +
                "before opening; without a name, open the Notebook app directly.",
            requiredParameters = emptyList(),
            optionalParameters = listOf(
                ToolParameterDescriptor("note", "A notebook title, title fragment, or BOOX note id.", ToolParameterType.String),
            ),
        ),
        ToolDescriptor(
            name = READ_NOTE,
            description = "Read one page of a BOOX Notebook note. Typed text is extracted from BOOX data; " +
                "when an exported page image exists, the configured vision model transcribes legible handwriting. " +
                "Page numbers start at 1 and default to the first page.",
            requiredParameters = listOf(
                ToolParameterDescriptor("note", "A note title, title fragment, or BOOX note id.", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("page", "1-based page number; default 1.", ToolParameterType.Integer),
            ),
        ),
        ToolDescriptor(
            name = DELETE_NOTE,
            description = "Delete one BOOX Notebook note for good: the notebook itself, its handwriting, " +
                "and its exported pages. Nothing is kept and nothing can be undone. Only when the writer " +
                "asked for that note to go; name it back to them in the answer.",
            requiredParameters = listOf(
                ToolParameterDescriptor("note", "A note title, title fragment, or BOOX note id.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
        ToolDescriptor(
            name = CREATE_NOTE,
            description = "Start a new, empty BOOX Notebook note with one blank page, for the writer to " +
                "write in later by hand. Text cannot be put into it from here.",
            requiredParameters = listOf(
                ToolParameterDescriptor("title", "The title for the new notebook.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
        ToolDescriptor(
            name = RENAME_NOTE,
            description = "Give an existing BOOX Notebook note a new title. Its pages are untouched.",
            requiredParameters = listOf(
                ToolParameterDescriptor("note", "A note title, title fragment, or BOOX note id.", ToolParameterType.String),
                ToolParameterDescriptor("title", "The new title.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
    )

    override suspend fun call(name: String, arguments: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                LIST_NOTES -> list(arguments.text("query"), arguments.int("limit", NOTES_LISTED))
                SEARCH_NOTES -> search(arguments.text("query"), arguments.int("limit", SEARCH_RESULTS))
                OPEN_NOTE -> openNoteInNotebook(arguments.text("note"))
                READ_NOTE -> read(arguments.text("note"), arguments.int("page", 1))
                DELETE_NOTE -> delete(arguments.text("note"))
                CREATE_NOTE -> create(arguments.text("title"))
                RENAME_NOTE -> rename(arguments.text("note"), arguments.text("title"))
                else -> "There is nothing called $name in BOOX Notebook."
            }
        }.getOrElse { error -> "BOOX Notebook lookup failed: ${error.message ?: error.javaClass.simpleName}" }
    }

    override fun note(name: String, arguments: JsonObject): String = when (name) {
        LIST_NOTES -> "đang xem các note BOOX…"
        SEARCH_NOTES -> "đang tìm trong note BOOX…"
        OPEN_NOTE -> "đang mở Notebook BOOX…"
        READ_NOTE -> "đang đọc note BOOX…"
        DELETE_NOTE -> "đang xóa note BOOX…"
        CREATE_NOTE -> "đang mở một cuốn sổ mới…"
        RENAME_NOTE -> "đang đổi tên note BOOX…"
        else -> "đang xem note BOOX…"
    }

    private suspend fun openNoteInNotebook(query: String): String {
        val selected = if (query.isBlank()) null else resolveNote(query)
        return if (openNote(selected)) {
            if (selected == null) {
                "Opened BOOX Notebook."
            } else {
                "Opened BOOX Notebook for \"${selected.title}\"."
            }
        } else {
            "Could not open BOOX Notebook. The Notebook app may not be available."
        }
    }

    private fun resolveNote(query: String): BooxNote {
        val wanted = query.trim()
        require(wanted.isNotBlank()) { "note is required" }
        val notes = source.listNotes()
        notes.firstOrNull { it.id.equals(wanted, ignoreCase = true) }?.let { return it }
        notes.firstOrNull { it.title.equals(wanted, ignoreCase = true) }?.let { return it }
        val matches = notes.filter { it.title.contains(wanted, ignoreCase = true) }
        return when (matches.size) {
            0 -> throw NoSuchElementException("No BOOX Notebook note matches \"$query\".")
            1 -> matches.single()
            else -> throw IllegalArgumentException(
                "More than one BOOX Notebook note matches \"$query\": " +
                    matches.take(8).joinToString(", ") { "\"${it.title}\" (${it.id})" },
            )
        }
    }

    private fun list(query: String, limit: Int): String {
        val notes = source.listNotes()
        if (notes.isEmpty()) return "No BOOX Notebook notes were found."
        val matches = notes.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
        if (matches.isEmpty()) return "No BOOX Notebook note matches \"$query\"."
        val shown = matches.take(limit.coerceIn(1, NOTES_LISTED * 4))
        val head = if (query.isBlank()) "${notes.size} BOOX Notebook notes, newest first:" else
            "${matches.size} BOOX Notebook notes match \"$query\":"
        return head + "\n" + shown.joinToString("\n") { it.summary() }
    }

    private fun search(query: String, limit: Int): String {
        if (query.isBlank()) return "Give a word or phrase from the BOOX note title to search for."
        val matches = source.listNotes().filter { it.title.contains(query, ignoreCase = true) }
        if (matches.isEmpty()) return "No BOOX Notebook note matches \"$query\"."
        return matches.take(limit.coerceIn(1, SEARCH_RESULTS * 4)).joinToString("\n") { it.summary() }
    }

    private suspend fun read(query: String, pageNumber: Int): String {
        val page = source.readPage(query, pageNumber.coerceAtLeast(1))
        val visionText = if (page.text.isBlank()) visionReader?.readPage(page).orEmpty() else ""
        val text = page.text.ifBlank { visionText }
        val header = "BOOX Notebook: \"${page.note.title}\" — page ${page.pageNumber}/${page.note.pageCount.coerceAtLeast(page.pageNumber)}"
        if (text.isNotBlank()) return "$header\n$text"

        val reason = when {
            page.imageFile != null && visionReader == null ->
                "An exported page image exists, but no vision reader is configured for this agent."
            page.hasPrivatePageData ->
                "This page contains BOOX handwritten stroke data, but no typed text or OCR result is available."
            else -> "No readable text or exported page image was found for this page."
        }
        return "$header\n$reason Ask for a page number if another page is needed."
    }

    private fun delete(query: String): String {
        if (query.isBlank()) return "Name the BOOX note to delete."
        val removed = source.deleteNote(query)
        val where = if (removed.entry) "is gone from BOOX Notebook" else "was not listed in BOOX Notebook"
        val onDisk = when (removed.files) {
            0 -> "no page files were left on disk"
            1 -> "1 page file went with it"
            else -> "${removed.files} page files went with it"
        }
        return "\"${removed.note.title}\" $where; $onDisk. It cannot be brought back."
    }

    private fun create(title: String): String {
        if (title.isBlank()) return "Give the new BOOX note a title."
        val made = source.createNote(title)
        return "Started BOOX Notebook note \"${made.title}\" with one blank page | id=${made.id}. " +
            "The writer can open it in BOOX Notebook and write in it by hand."
    }

    private fun rename(query: String, title: String): String {
        if (query.isBlank()) return "Name the BOOX note to rename."
        if (title.isBlank()) return "Give the new title for the BOOX note."
        val before = source.listNotes().firstOrNull {
            it.id.equals(query, ignoreCase = true) || it.title.equals(query.trim(), ignoreCase = true)
        }?.title
        val renamed = source.renameNote(query, title)
        val was = before ?: query
        return "The BOOX note \"$was\" is now called \"${renamed.title}\"; its pages are untouched."
    }

    private fun BooxNote.summary(): String =
        "${title} | pages=$pageCount | updated=${updatedAt()} | id=$id" +
            if (favorite) " | favorite" else ""

    private fun BooxNote.updatedAt(): String = if (updatedAtMs <= 0L) "unknown" else
        Instant.ofEpochMilli(updatedAtMs).atZone(zone).toLocalDateTime().toString()
}

/** Reads an exported PNG/JPEG page through the same configured vision model as the diary. */
class OpenAiBooxNotesVisionReader(
    private val client: OpenAILLMClient,
    private val model: LLModel,
    private val reasoning: ReasoningEffort? = null,
) : BooxNotesVisionReader {

    override suspend fun readPage(page: BooxNotePage): String {
        val image = page.imageFile ?: return ""
        if (!image.isFile || image.length() > MAX_VISION_BYTES) return ""
        val bytes = FileInputStream(image).use { it.readBounded(MAX_VISION_BYTES) } ?: return ""
        val format = image.extension.lowercase(Locale.ROOT).ifBlank { "png" }
        val request = prompt(
            id = "riddleboox-boox-note",
            params = OpenAIChatParams(maxTokens = 1200, reasoningEffort = reasoning),
        ) {
            system(
                "Read this page from a BOOX Notebook. Transcribe all legible handwriting and typed text " +
                    "faithfully in its original language. Preserve line breaks when useful. If there is a " +
                    "diagram or non-text content, describe it briefly. Return only the useful transcription " +
                    "or description, with no preamble. Note title: ${page.note.title}; page ${page.pageNumber}.",
            )
            user(
                listOf(
                    MessagePart.Attachment(
                        AttachmentSource.Image(
                            content = AttachmentContent.Binary.Bytes(bytes),
                            format = format,
                        ),
                    ),
                ),
            )
        }
        val result = StringBuilder()
        client.executeStreaming(request, model, emptyList()).collect { frame ->
            if (frame is StreamFrame.TextDelta) result.append(frame.text)
        }
        return result.toString().trim()
    }
}

private fun JsonObject.text(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

private fun JsonObject.int(name: String, fallback: Int): Int = text(name).toIntOrNull() ?: fallback

private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray? {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (output.size() + count > maxBytes) return null
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private object ShapeProtoText {
    fun read(bytes: ByteArray): List<String> {
        val result = ArrayList<String>()
        ProtoReader(bytes).readMessage { field, wire, value ->
            if (field == 1 && wire == 2) result += shape(value)
        }
        return result
    }

    private fun shape(bytes: ByteArray): List<String> {
        val result = ArrayList<String>()
        ProtoReader(bytes).readMessage { field, wire, value ->
            if (wire == 2 && (field == 10 || field == 22)) {
                val text = value.toString(StandardCharsets.UTF_8).trim()
                if (text.isNotEmpty()) result += text
            }
        }
        return result
    }

    private class ProtoReader(private val bytes: ByteArray) {
        private var offset = 0

        fun readMessage(visit: (field: Int, wire: Int, value: ByteArray) -> Unit) {
            while (offset < bytes.size) {
                val tag = readVarint().toInt()
                if (tag == 0) return
                val wire = tag and 7
                val field = tag ushr 3
                when (wire) {
                    0 -> {
                        readVarint()
                        visit(field, wire, ByteArray(0))
                    }
                    1 -> {
                        skip(8)
                        visit(field, wire, ByteArray(0))
                    }
                    2 -> {
                        val length = readVarint().toInt()
                        require(length >= 0 && length <= bytes.size - offset) { "Invalid protobuf length" }
                        val value = bytes.copyOfRange(offset, offset + length)
                        offset += length
                        visit(field, wire, value)
                    }
                    3 -> skipGroup()
                    4 -> return
                    5 -> {
                        skip(4)
                        visit(field, wire, ByteArray(0))
                    }
                    else -> error("Invalid protobuf wire type: $wire")
                }
            }
        }

        private fun readVarint(): Long {
            var value = 0L
            var shift = 0
            while (shift < 64) {
                require(offset < bytes.size) { "Truncated protobuf" }
                val next = bytes[offset++].toInt() and 0xff
                value = value or ((next and 0x7f).toLong() shl shift)
                if (next and 0x80 == 0) return value
                shift += 7
            }
            error("Invalid protobuf varint")
        }

        private fun skip(count: Int) {
            require(count <= bytes.size - offset) { "Truncated protobuf" }
            offset += count
        }

        private fun skipGroup() {
            while (offset < bytes.size) {
                val tag = readVarint().toInt()
                when (tag and 7) {
                    0 -> readVarint()
                    1 -> skip(8)
                    2 -> skip(readVarint().toInt())
                    3 -> skipGroup()
                    4 -> return
                    5 -> skip(4)
                    else -> error("Invalid protobuf group")
                }
            }
        }
    }
}
