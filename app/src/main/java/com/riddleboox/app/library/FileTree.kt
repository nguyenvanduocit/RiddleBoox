package com.riddleboox.app.library

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream

/**
 * A file-system-shaped seam. [JavaFileTree] wraps `java.io.File` for tests that build real
 * trees with JUnit's `TemporaryFolder`; [DocumentFileTree] wraps a Storage Access Framework
 * [DocumentFile] for the app's real storage, which since scoped storage cannot be reached
 * through `java.io.File` at all outside the app's own sandbox. Both read the exact same
 * shape so [com.riddleboox.app.tools.OnyxBooxNotes]'s traversal/matching/delete logic never
 * has to know which one it's holding.
 */
interface FileTree {
    val name: String

    /** A path for logging and title-matching only — not a real filesystem path. */
    val path: String
    val isDirectory: Boolean
    val isFile: Boolean
    fun listChildren(): List<FileTree>
    fun delete(): Boolean
    fun openInputStream(): InputStream?
}

class JavaFileTree(private val file: File, private val root: File = file) : FileTree {
    override val name: String get() = file.name
    override val path: String get() = runCatching { file.relativeTo(root).path }.getOrDefault(file.name)
    override val isDirectory: Boolean get() = file.isDirectory
    override val isFile: Boolean get() = file.isFile
    override fun listChildren(): List<FileTree> =
        file.listFiles()?.map { JavaFileTree(it, root) }.orEmpty()
    override fun delete(): Boolean = file.delete()
    override fun openInputStream(): InputStream? = runCatching { file.inputStream() }.getOrNull()
}

class DocumentFileTree(
    private val document: DocumentFile,
    private val context: Context,
    private val rootSegments: List<String> = emptyList(),
) : FileTree {
    override val name: String get() = document.name.orEmpty()
    override val path: String get() = (rootSegments + name).joinToString("/")
    override val isDirectory: Boolean get() = document.isDirectory
    override val isFile: Boolean get() = document.isFile
    override fun listChildren(): List<FileTree> =
        document.listFiles().map { DocumentFileTree(it, context, rootSegments + name) }
    override fun delete(): Boolean = document.delete()
    override fun openInputStream(): InputStream? =
        runCatching { context.contentResolver.openInputStream(document.uri) }.getOrNull()
}
