package com.riddleboox.app.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

private const val PREFS_NAME = "storage_access"
private const val KEY_TREE_URI = "tree_uri"
private const val PRIMARY_VOLUME_PREFIX = "primary:"

/**
 * Turns "Books/foo.epub" into the document id `ExternalStorageProvider` expects for the
 * primary volume. Building an id this way — rather than asking a directory to list itself
 * and match a name — is what lets [documentAt] reach a dot-prefixed directory like `.ksync`
 * that a folder picker would never let a person navigate into and select.
 */
fun documentIdFor(relativePath: String): String = PRIMARY_VOLUME_PREFIX + relativePath.trim('/')

/** Strips [root] off [absolutePath], or null when [absolutePath] isn't under it. */
fun relativePathUnder(root: String, absolutePath: String): String? {
    val normalizedRoot = root.trimEnd('/') + "/"
    return absolutePath.takeIf { it.startsWith(normalizedRoot) }?.removePrefix(normalizedRoot)
}

/**
 * Where the writer grants (or re-grants) access to the whole primary storage volume, in one
 * tap — [StorageManager.getPrimaryStorageVolume] already points the system picker at that
 * volume's root with "USE THIS FOLDER" pre-selected, so nobody has to navigate to it.
 */
fun requestStorageAccessIntent(context: Context): Intent {
    val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    return storageManager.primaryStorageVolume.createOpenDocumentTreeIntent()
}

/** Persists [uri] past this process's lifetime and remembers it for [canOpenBooks]/[storageRoot]. */
fun persistStorageGrant(context: Context, uri: Uri) {
    context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(KEY_TREE_URI, uri.toString()).apply()
}

private fun storedTreeUri(context: Context): Uri? =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_TREE_URI, null)?.let(Uri::parse)

/** Whether the diary can currently read/write the writer's shared storage. */
fun canOpenBooks(context: Context): Boolean {
    val uri = storedTreeUri(context) ?: return false
    return context.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission && it.isWritePermission
    }
}

/** The granted tree's root, or null when nothing is granted (see [canOpenBooks]). */
fun storageRoot(context: Context): DocumentFile? {
    val uri = storedTreeUri(context)?.takeIf { canOpenBooks(context) } ?: return null
    return DocumentFile.fromTreeUri(context, uri)
}

/**
 * A document under the granted tree at [relativePath], built directly by id. Existence is
 * not implied — call `.exists()` on the result, same as any other [DocumentFile].
 */
fun documentAt(context: Context, relativePath: String): DocumentFile? {
    val uri = storedTreeUri(context)?.takeIf { canOpenBooks(context) } ?: return null
    val documentUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentIdFor(relativePath))
    return DocumentFile.fromSingleUri(context, documentUri)
}

/**
 * Walks [relativePath] segment by segment from the granted root, creating any directory
 * that is missing. Unlike [documentAt] this must traverse via `findFile`/`createDirectory`
 * rather than a direct id, because creating a document is an operation on its *parent*
 * [DocumentFile], not on a raw id — there is nothing to create *at* until the parent exists.
 */
fun getOrCreateDirectoryAt(context: Context, relativePath: String): DocumentFile? {
    var current = storageRoot(context) ?: return null
    for (segment in relativePath.trim('/').split('/')) {
        if (segment.isBlank()) continue
        current = current.findFile(segment)?.takeIf { it.isDirectory }
            ?: current.createDirectory(segment)
            ?: return null
    }
    return current
}
