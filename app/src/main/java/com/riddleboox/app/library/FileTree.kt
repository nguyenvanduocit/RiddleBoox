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

/**
 * [document] is null when nothing was found at the id it was built from — most often
 * because the storage folder grant isn't held yet (see [documentAt]). A null document
 * behaves as an empty, non-existent node rather than throwing, the same way asking for a
 * notebook's shared-storage pages before the grant is held should read as "nothing here
 * yet", not a crash.
 *
 * [path] mirrors [JavaFileTree]'s convention: the root's own name is never part of it (the
 * root's path is ""), only the names of nodes below the root — construct the root with the
 * default [parentPath]/[isRoot], and let [listChildren] build every descendant's path from
 * there, the same way `File.relativeTo(root)` does for [JavaFileTree].
 */
class DocumentFileTree(
    private val document: DocumentFile?,
    private val context: Context,
    private val parentPath: List<String> = emptyList(),
    private val isRoot: Boolean = true,
) : FileTree {
    override val name: String get() = document?.name.orEmpty()
    override val path: String get() = if (isRoot) "" else (parentPath + name).joinToString("/")
    override val isDirectory: Boolean get() = document?.isDirectory == true
    override val isFile: Boolean get() = document?.isFile == true
    override fun listChildren(): List<FileTree> =
        document?.listFiles()?.map {
            DocumentFileTree(it, context, if (isRoot) emptyList() else parentPath + name, isRoot = false)
        }.orEmpty()
    override fun delete(): Boolean = document?.delete() == true
    override fun openInputStream(): InputStream? =
        document?.let { runCatching { context.contentResolver.openInputStream(it.uri) }.getOrNull() }
}
