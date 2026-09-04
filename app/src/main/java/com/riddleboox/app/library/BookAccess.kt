package com.riddleboox.app.library

import android.content.ContentResolver
import android.content.Context
import android.os.Environment
import java.io.File

/**
 * What [checkBookAccess] found by actually trying to read a book, not just asking
 * [canOpenBooks] — a granted permission with a library the diary still can't read from
 * (unsupported format, a stale path, a corrupt file) would otherwise only surface later,
 * mid-conversation.
 */
sealed class BookAccessCheck {
    /** [canOpenBooks] is false — nothing was attempted. */
    object PermissionMissing : BookAccessCheck()

    /** Permission is granted but the shelf has nothing to try opening. */
    object LibraryEmpty : BookAccessCheck()

    /** A real book on the shelf opened and read back its chapters. */
    object Readable : BookAccessCheck()

    /** Permission is granted, the shelf isn't empty, but the first book still didn't open. */
    data class Unreadable(val reason: String?) : BookAccessCheck()
}

/** Opens [book]'s file through the SAF grant, copying it through a scratch cache file — see [Epub.open]. */
internal fun openBookViaGrant(context: Context, book: Book): Epub? {
    val relativePath = relativePathUnder(Environment.getExternalStorageDirectory().path, book.path) ?: return null
    val document = documentAt(context, relativePath)?.takeIf { it.exists() } ?: return null
    val stream = context.contentResolver.openInputStream(document.uri) ?: return null
    val tempFile = File(context.cacheDir, "epub-open-${System.nanoTime()}.tmp")
    return stream.use { Epub.open(it, tempFile) }
}

/** Opens the first book on the shelf to confirm reading actually works, not just that the grant exists. */
fun checkBookAccess(context: Context, resolver: ContentResolver): BookAccessCheck {
    if (!canOpenBooks(context)) return BookAccessCheck.PermissionMissing
    // OnyxLibrary.books() throws LibraryUnreachable rather than returning null/empty when the
    // content provider itself can't be reached — a separate failure from "shelf is genuinely empty".
    val book = runCatching { OnyxLibrary(resolver).books().firstOrNull() }
        .getOrElse { return BookAccessCheck.Unreadable(it.message) }
        ?: return BookAccessCheck.LibraryEmpty
    val opened = runCatching { openBookViaGrant(context, book)?.also { it.close() } }
    return if (opened.getOrNull() != null) {
        BookAccessCheck.Readable
    } else {
        BookAccessCheck.Unreadable(opened.exceptionOrNull()?.message)
    }
}
