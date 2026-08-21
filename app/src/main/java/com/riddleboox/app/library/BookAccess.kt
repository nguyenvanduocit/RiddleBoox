package com.riddleboox.app.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

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
