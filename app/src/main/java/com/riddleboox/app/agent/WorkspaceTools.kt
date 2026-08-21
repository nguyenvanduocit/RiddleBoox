package com.riddleboox.app.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import com.riddleboox.app.reply.Toolbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.regex.Pattern

private const val LIST = "workspace_list"
private const val READ = "workspace_read"
private const val WRITE = "workspace_write"
private const val APPEND = "workspace_append"
private const val EDIT = "workspace_edit"
private const val DELETE = "workspace_delete"
private const val SEARCH = "workspace_search"
private const val GREP = "workspace_grep"
private const val REGEX_SEARCH = "workspace_regex_search"
private const val MKDIR = "workspace_mkdir"
private const val STAT = "workspace_stat"

private const val MAX_FILE_CHARS = 512_000
private const val MAX_RESULTS = 100
private const val MAX_VISITED = 2_000

/**
 * Filesystem tools scoped to exactly one agent's workspace.
 *
 * This is intentionally a file API, not a shell API. Every path is canonicalised
 * before use, so `../`, absolute paths, and symlinks that point outside the
 * workspace are rejected. Limits also keep a careless search from blocking a
 * turn or returning an entire book to the model.
 */
class WorkspaceTools(workspace: File) : Toolbox {

    private val root = workspace.canonicalFile.also { it.mkdirs() }

    override val tools: List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = LIST,
            description = "List files and directories in this agent's private workspace.",
            requiredParameters = emptyList(),
            optionalParameters = listOf(
                ToolParameterDescriptor("path", "Relative directory path; empty means workspace root.", ToolParameterType.String),
                ToolParameterDescriptor("depth", "Directory depth to show, maximum 5.", ToolParameterType.Integer),
            ),
        ),
        ToolDescriptor(
            name = READ,
            description = "Read a UTF-8 text artifact from the private workspace.",
            requiredParameters = listOf(
                ToolParameterDescriptor("path", "Relative file path.", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("offset", "Character offset, default 0.", ToolParameterType.Integer),
                ToolParameterDescriptor("limit", "Maximum characters, default 12000.", ToolParameterType.Integer),
            ),
        ),
        ToolDescriptor(
            name = WRITE,
            description = "Create or replace a UTF-8 text artifact. Parent directories are created.",
            requiredParameters = listOf(
                ToolParameterDescriptor("path", "Relative file path.", ToolParameterType.String),
                ToolParameterDescriptor("content", "Complete UTF-8 text content.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
        ToolDescriptor(
            name = APPEND,
            description = "Append UTF-8 text to an artifact, creating it when needed.",
            requiredParameters = listOf(
                ToolParameterDescriptor("path", "Relative file path.", ToolParameterType.String),
                ToolParameterDescriptor("content", "Text to append.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
        ToolDescriptor(
            name = EDIT,
            description = "Replace exact text in a UTF-8 artifact; use replace_all=true for every match.",
            requiredParameters = listOf(
                ToolParameterDescriptor("path", "Relative file path.", ToolParameterType.String),
                ToolParameterDescriptor("old_text", "Exact text to find.", ToolParameterType.String),
                ToolParameterDescriptor("new_text", "Replacement text.", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("replace_all", "String true to replace every match; default false.", ToolParameterType.String),
            ),
        ),
        ToolDescriptor(
            name = DELETE,
            description = "Delete a file or an empty directory in the private workspace.",
            requiredParameters = listOf(
                ToolParameterDescriptor("path", "Relative path; workspace root cannot be deleted.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
        ToolDescriptor(
            name = SEARCH,
            description = "Find artifact paths whose names contain a query, recursively.",
            requiredParameters = listOf(
                ToolParameterDescriptor("query", "Case-insensitive filename or path fragment.", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("path", "Relative directory to search from.", ToolParameterType.String),
                ToolParameterDescriptor("limit", "Maximum results, default 50.", ToolParameterType.Integer),
            ),
        ),
        ToolDescriptor(
            name = GREP,
            description = "Find a plain text query inside UTF-8 artifacts, recursively, with line numbers.",
            requiredParameters = listOf(
                ToolParameterDescriptor("query", "Case-insensitive text fragment.", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("path", "Relative file or directory to search from.", ToolParameterType.String),
                ToolParameterDescriptor("limit", "Maximum matching lines, default 50.", ToolParameterType.Integer),
            ),
        ),
        ToolDescriptor(
            name = REGEX_SEARCH,
            description = "Find a regular expression inside UTF-8 artifacts, recursively, with line numbers.",
            requiredParameters = listOf(
                ToolParameterDescriptor("pattern", "Java regular expression.", ToolParameterType.String),
            ),
            optionalParameters = listOf(
                ToolParameterDescriptor("path", "Relative file or directory to search from.", ToolParameterType.String),
                ToolParameterDescriptor("limit", "Maximum matching lines, default 50.", ToolParameterType.Integer),
            ),
        ),
        ToolDescriptor(
            name = MKDIR,
            description = "Create a directory in the private workspace.",
            requiredParameters = listOf(
                ToolParameterDescriptor("path", "Relative directory path.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
        ToolDescriptor(
            name = STAT,
            description = "Inspect whether an artifact exists and its basic size/type metadata.",
            requiredParameters = listOf(
                ToolParameterDescriptor("path", "Relative path.", ToolParameterType.String),
            ),
            optionalParameters = emptyList(),
        ),
    )

    override suspend fun call(name: String, arguments: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                LIST -> list(arguments.text("path"), arguments.int("depth", 2))
                READ -> read(arguments.text("path"), arguments.int("offset", 0), arguments.int("limit", 12_000))
                WRITE -> write(arguments.text("path"), arguments.text("content"), append = false)
                APPEND -> write(arguments.text("path"), arguments.text("content"), append = true)
                EDIT -> edit(
                    path = arguments.text("path"),
                    oldText = arguments.text("old_text"),
                    newText = arguments.text("new_text"),
                    replaceAll = arguments.boolean("replace_all"),
                )
                DELETE -> delete(arguments.text("path"))
                SEARCH -> search(arguments.text("query"), arguments.text("path"), arguments.int("limit", 50))
                GREP -> grep(arguments.text("query"), arguments.text("path"), arguments.int("limit", 50), null)
                REGEX_SEARCH -> regexSearch(arguments.text("pattern"), arguments.text("path"), arguments.int("limit", 50))
                MKDIR -> mkdir(arguments.text("path"))
                STAT -> stat(arguments.text("path"))
                else -> "There is nothing called $name in this workspace."
            }
        }.getOrElse { error -> "Workspace operation failed: ${error.message ?: error.javaClass.simpleName}" }
    }

    override fun note(name: String, arguments: JsonObject): String = when (name) {
        READ -> "đang đọc artifact…"
        WRITE, APPEND, EDIT -> "đang ghi artifact…"
        DELETE -> "đang xóa artifact…"
        SEARCH, GREP, REGEX_SEARCH -> "đang tìm trong workspace…"
        LIST, STAT -> "đang kiểm tra workspace…"
        MKDIR -> "đang tạo thư mục…"
        else -> "đang làm việc với workspace…"
    }

    private fun list(path: String, requestedDepth: Int): String {
        val directory = resolve(path)
        if (!directory.exists()) return "No such workspace path: ${path.ifBlank { "." }}"
        if (!directory.isDirectory) return "Not a directory: $path"
        val depth = requestedDepth.coerceIn(0, 5)
        val lines = ArrayList<String>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(directory to 0)
        while (queue.isNotEmpty() && lines.size < MAX_RESULTS) {
            val (current, currentDepth) = queue.removeFirst()
            val children = current.listFiles()
                ?.filter { it.canonicalFile.isWithin(root) }
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
                ?: continue
            for (child in children) {
                if (lines.size >= MAX_RESULTS) break
                val relative = child.relativeTo(root).path.ifBlank { "." }
                val suffix = if (child.isDirectory) "/" else " (${child.length()} bytes)"
                lines += relative + suffix
                if (child.isDirectory && currentDepth < depth) queue.add(child to currentDepth + 1)
            }
        }
        return if (lines.isEmpty()) "Workspace is empty at ${path.ifBlank { "." }}." else lines.joinToString("\n")
    }

    private fun read(path: String, requestedOffset: Int, requestedLimit: Int): String {
        val file = resolve(path)
        if (!file.isFile) return "Not a file: $path"
        val content = readText(file)
        val offset = requestedOffset.coerceIn(0, content.length)
        val limit = requestedLimit.coerceIn(1, MAX_FILE_CHARS)
        val end = (offset + limit).coerceAtMost(content.length)
        val body = content.substring(offset, end)
        val suffix = if (end < content.length) "\n[cut at $end of ${content.length}; call workspace_read with offset=$end]" else ""
        return body + suffix
    }

    private fun write(path: String, content: String, append: Boolean): String {
        require(path.isNotBlank()) { "Path cannot be blank" }
        val file = resolve(path)
        require(file != root) { "Workspace root is not a file" }
        val next = if (append && file.exists()) readText(file) + content else content
        require(next.length <= MAX_FILE_CHARS) { "Artifact is too large; maximum is $MAX_FILE_CHARS characters" }
        file.parentFile?.mkdirs()
        if (file.parentFile?.canonicalFile?.isWithin(root) != true) error("Path escapes workspace")
        val tmp = File.createTempFile("artifact.", ".tmp", file.parentFile)
        try {
            tmp.writeText(next)
            if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return "${if (append) "Appended to" else "Wrote"} ${file.relativeTo(root).path} ($next characters)."
    }

    private fun edit(path: String, oldText: String, newText: String, replaceAll: Boolean): String {
        require(oldText.isNotEmpty()) { "old_text cannot be blank" }
        val file = resolve(path)
        if (!file.isFile) return "Not a file: $path"
        val before = readText(file)
        val count = before.windowed(oldText.length, partialWindows = false).count { it == oldText }
        if (count == 0) return "Text not found in $path."
        val after = if (replaceAll) before.replace(oldText, newText) else before.replaceFirst(oldText, newText)
        return write(path, after, append = false) + " Replaced ${if (replaceAll) count else 1} occurrence(s)."
    }

    private fun delete(path: String): String {
        val file = resolve(path)
        require(file != root) { "Workspace root cannot be deleted" }
        if (!file.exists()) return "Nothing to delete at $path."
        val count = countNodes(file)
        require(count <= MAX_VISITED) { "Refusing to delete more than $MAX_VISITED workspace entries" }
        require(deleteRecursively(file)) { "Could not delete $path" }
        return "Deleted $path."
    }

    private fun search(query: String, path: String, requestedLimit: Int): String {
        require(query.isNotBlank()) { "query cannot be blank" }
        val start = resolve(path)
        val limit = requestedLimit.coerceIn(1, MAX_RESULTS)
        val matches = walk(start)
            .filter { it.name.contains(query, ignoreCase = true) || it.relativeTo(root).path.contains(query, ignoreCase = true) }
            .take(limit)
            .toList()
        return if (matches.isEmpty()) "No artifact names contain \"$query\"." else matches.joinToString("\n") { it.relativeTo(root).path }
    }

    private fun grep(query: String, path: String, requestedLimit: Int, pattern: Pattern?): String {
        require(query.isNotBlank()) { "query cannot be blank" }
        val start = resolve(path)
        val limit = requestedLimit.coerceIn(1, MAX_RESULTS)
        val needle = pattern ?: Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE)
        val matches = ArrayList<String>()
        for (file in walk(start)) {
            if (matches.size >= limit) break
            if (!file.isFile || file.length() > MAX_FILE_CHARS * 4L) continue
            matches += matchesIn(file, needle, limit - matches.size)
        }
        return if (matches.isEmpty()) "No workspace text matches \"$query\"." else matches.joinToString("\n")
    }

    /** Matching lines in [file], formatted as `path:lineNumber: text`, capped at [remaining] results. */
    private fun matchesIn(file: File, needle: Pattern, remaining: Int): List<String> {
        val lines = runCatching { readText(file).lineSequence().toList() }.getOrNull() ?: return emptyList()
        val found = ArrayList<String>()
        for ((index, line) in lines.withIndex()) {
            if (found.size >= remaining) break
            if (!needle.matcher(line).find()) continue
            found += "${file.relativeTo(root).path}:${index + 1}: ${line.take(500)}"
        }
        return found
    }

    private fun regexSearch(pattern: String, path: String, requestedLimit: Int): String {
        val compiled = try {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
        } catch (error: Exception) {
            return "Invalid regular expression: ${error.message ?: pattern}"
        }
        return grep(pattern, path, requestedLimit, compiled)
    }

    private fun mkdir(path: String): String {
        require(path.isNotBlank()) { "Path cannot be blank" }
        val directory = resolve(path)
        require(directory != root) { "Workspace root already exists" }
        require(directory.mkdirs() || directory.isDirectory) { "Could not create $path" }
        return "Created directory $path."
    }

    private fun stat(path: String): String {
        val file = resolve(path)
        if (!file.exists()) return "Path does not exist: ${path.ifBlank { "." }}"
        return buildString {
            append(file.relativeTo(root).path.ifBlank { "." })
            append(if (file.isDirectory) " directory" else " file")
            append(", ").append(file.length()).append(" bytes")
            if (file.isFile) append(", modified ").append(file.lastModified())
        }
    }

    private fun walk(start: File): Sequence<File> = sequence {
        if (!start.exists()) return@sequence
        val queue = ArrayDeque<File>()
        queue.add(start)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_VISITED) {
            val current = queue.removeFirst()
            if (current != root) yield(current)
            if (current.isDirectory) current.listFiles()?.forEach { child ->
                if (child.canonicalFile.isWithin(root)) queue.add(child)
            }
        }
    }

    private fun resolve(path: String): File {
        val clean = path.trim().removePrefix("./")
        require(!clean.contains('\u0000')) { "Invalid path" }
        val candidate = if (clean.isBlank()) root else File(root, clean)
        val canonical = candidate.canonicalFile
        require(canonical.isWithin(root)) { "Path escapes this agent's workspace" }
        return canonical
    }

    private fun readText(file: File): String {
        require(file.length() <= MAX_FILE_CHARS * 4L) { "Artifact is too large to inspect" }
        return file.readText()
    }

    private fun countNodes(file: File): Int = walk(file).count() + 1

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) file.listFiles()?.forEach { if (!deleteRecursively(it)) return false }
        return file.delete()
    }

    private fun File.isWithin(parent: File): Boolean = path == parent.path || path.startsWith(parent.path + File.separator)

    override fun toString(): String = "WorkspaceTools(${root.path})"
}

private fun JsonObject.text(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

private fun JsonObject.int(name: String, default: Int): Int =
    text(name).toIntOrNull() ?: default

private fun JsonObject.boolean(name: String): Boolean = text(name).equals("true", ignoreCase = true)
