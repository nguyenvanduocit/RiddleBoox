package com.riddleboox.app.library

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Whether the app may open the files the writer's books actually live in.
 *
 * Two things have to be true and only one of them is a permission. From
 * Android 11 the answer is all-files access, granted from Settings and never
 * from a dialog. On Android 10 there is no such switch at all — the way an app
 * targeting a modern SDK reached shared storage there was
 * `requestLegacyExternalStorage`, which stopped working above target 29 — so
 * the honest answer on that version is no, and [allFilesAccess] has nowhere to
 * send anyone.
 *
 * Nothing else the diary knows about the reader depends on this. The shelf,
 * the reading progress and every marked passage come out of NeoReader's own
 * database through [OnyxLibrary] and need no file access whatever; only
 * reading the words inside a book does.
 */
fun canOpenBooks(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

/**
 * Where the writer turns that permission on, or null when this device has
 * nowhere to turn it on.
 *
 * The per-app screen is asked for first because it lands on this app's own
 * switch; the list of every app is the fallback, since a manufacturer ROM may
 * ship one and not the other. Both are resolved before being handed back
 * rather than started hopefully: an `ActivityNotFoundException` out of a
 * settings row is a crash, and "this device has no such screen" is an answer.
 */
fun allFilesAccess(context: Context): Intent? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val candidates = listOf(
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        ),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    )
    return candidates.firstOrNull { context.packageManager.resolveActivity(it, 0) != null }
}

/**
 * What [checkBookAccess] found by actually trying to read a book, not just
 * asking [canOpenBooks] — a granted permission with a library the diary still
 * can't read from (unsupported format, a stale path, a corrupt file) would
 * otherwise only surface later, mid-conversation.
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

/** Opens the first book on the shelf to confirm reading actually works, not just that the switch is on. */
fun checkBookAccess(resolver: ContentResolver): BookAccessCheck {
    if (!canOpenBooks()) return BookAccessCheck.PermissionMissing
    // OnyxLibrary.books() throws LibraryUnreachable rather than returning
    // null/empty when the content provider itself can't be reached — a
    // separate failure from "shelf is genuinely empty".
    val book = runCatching { OnyxLibrary(resolver).books().firstOrNull() }
        .getOrElse { return BookAccessCheck.Unreadable(it.message) }
        ?: return BookAccessCheck.LibraryEmpty
    val opened = runCatching { Epub.open(File(book.path))?.also { it.close() } }
    return if (opened.getOrNull() != null) {
        BookAccessCheck.Readable
    } else {
        BookAccessCheck.Unreadable(opened.exceptionOrNull()?.message)
    }
}
