# SAF Storage Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove `MANAGE_EXTERNAL_STORAGE`/`READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE` from RiddleBoox and replace every touchpoint (onboarding, Settings, book reading/deleting/downloading, BOOX Notebook notes) with a Storage Access Framework grant over the primary storage volume, so the app is compliant with Google Play's "All Files Access Permission: Not a core feature" policy without losing any file it currently reads or writes.

**Architecture:** One SAF tree-URI grant over the whole primary volume (picked once, via `StorageVolume.createOpenDocumentTreeIntent()`, persisted with `takePersistableUriPermission`). A new `library/StorageAccess.kt` owns the grant lifecycle and builds document URIs directly by ID (`DocumentsContract.buildDocumentUriUsingTree`) rather than by picker-style folder traversal, which is what lets it reach the hidden `.ksync` directory. A new `library/FileTree.kt` seam (`JavaFileTree` / `DocumentFileTree`) lets `OnyxBooxNotes`'s existing BFS/matching/delete logic run unchanged against either a real `java.io.File` tree (tests) or a `DocumentFile` tree (production).

**Tech Stack:** Kotlin, Android SDK (`androidx.documentfile:documentfile`), Robolectric (JVM unit tests only — no real `DocumentsProvider` exists under Robolectric, so every SAF I/O call is device-verified, not unit-tested).

**Spec:** `docs/superpowers/specs/2026-09-04-saf-storage-migration.md`

## Global Constraints

- `minSdk = 29`, `targetSdk = compileSdk = 36` (`app/build.gradle.kts:37-43`) — `StorageVolume.createOpenDocumentTreeIntent()` (API 26+), `DocumentsContract.buildDocumentUriUsingTree` (API 21+), and `DocumentFile` are all available at `minSdk = 29`; no version guards needed.
- No Robolectric test may call real `ContentResolver`/`DocumentsContract`/`StorageManager` SAF APIs — these are the untestable boundary, same convention as `SettingsStore`'s `AndroidKeyStore` boundary (project CLAUDE.md, "Robolectric/AndroidKeyStore trap").
- `Book.path` (and every existing test fixture that hardcodes `"/sdcard/Books/..."` strings) keeps its current type and format — nothing above the SAF boundary should need to change what a book path *looks like*, only how bytes get read from it.
- Positive framing: manifest comments, README, and in-app copy describe the SAF grant as it now works, not as a change from the old permission — history belongs in this plan/spec and in commit messages, not in the shipped code (project's `clean-slate` convention).
- Every production file this plan touches keeps its existing constructor-injection test seams (`openBook`, `booksReadable` on `DiaryTools`; `noteRoot`/`ksyncRoot` on `OnyxBooxNotes`) so existing tests are minimally disturbed.

---

### Task 1: Storage access foundation — `StorageAccess.kt` and `FileTree.kt`

**Files:**
- Modify: `app/build.gradle.kts:116-122` (add dependency)
- Create: `app/src/main/java/com/riddleboox/app/library/StorageAccess.kt`
- Create: `app/src/main/java/com/riddleboox/app/library/FileTree.kt`
- Test: `app/src/test/java/com/riddleboox/app/library/StorageAccessTest.kt`
- Test: `app/src/test/java/com/riddleboox/app/library/FileTreeTest.kt`

**Interfaces:**
- Produces: `fun documentIdFor(relativePath: String): String`, `fun relativePathUnder(root: String, absolutePath: String): String?` (pure, in `StorageAccess.kt`)
- Produces: `fun requestStorageAccessIntent(context: Context): Intent`, `fun persistStorageGrant(context: Context, uri: Uri)`, `fun canOpenBooks(context: Context): Boolean`, `fun storageRoot(context: Context): DocumentFile?`, `fun documentAt(context: Context, relativePath: String): DocumentFile?`, `fun getOrCreateDirectoryAt(context: Context, relativePath: String): DocumentFile?` (boundary, in `StorageAccess.kt`) — every later task that needs SAF access calls these, never raw `ContentResolver`/`DocumentsContract` itself.
- Produces: `interface FileTree { val name: String; val path: String; val isDirectory: Boolean; val isFile: Boolean; fun listChildren(): List<FileTree>; fun delete(): Boolean; fun openInputStream(): InputStream? }`, `class JavaFileTree(file: File, root: File = file) : FileTree`, `class DocumentFileTree(document: DocumentFile, context: Context, rootSegments: List<String> = emptyList()) : FileTree` (in `FileTree.kt`)

- [ ] **Step 1: Add the documentfile dependency**

In `app/build.gradle.kts`, inside the `dependencies { ... }` block (after line 122's `implementation("androidx.security:security-crypto:1.1.0-alpha06")`):

```kotlin
    implementation("androidx.documentfile:documentfile:1.0.1")
```

- [ ] **Step 2: Write the failing tests for the pure functions**

```kotlin
package com.riddleboox.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageAccessTest {

    @Test
    fun `documentIdFor prefixes the primary volume and trims slashes`() {
        assertEquals("primary:Books/foo.epub", documentIdFor("Books/foo.epub"))
        assertEquals("primary:.ksync/document/abc", documentIdFor("/.ksync/document/abc/"))
    }

    @Test
    fun `relativePathUnder strips the root and a trailing slash`() {
        assertEquals(
            "Books/foo.epub",
            relativePathUnder("/storage/emulated/0", "/storage/emulated/0/Books/foo.epub"),
        )
        assertEquals(
            "Books/foo.epub",
            relativePathUnder("/storage/emulated/0/", "/storage/emulated/0/Books/foo.epub"),
        )
    }

    @Test
    fun `relativePathUnder is null when the path is not under the root`() {
        assertNull(relativePathUnder("/storage/emulated/0", "/data/data/com.riddleboox.app/foo"))
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riddleboox.app.library.StorageAccessTest"`
Expected: FAIL — `StorageAccess.kt` does not exist yet, compile error.

- [ ] **Step 4: Write `StorageAccess.kt`**

```kotlin
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
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riddleboox.app.library.StorageAccessTest"`
Expected: PASS (3/3)

- [ ] **Step 6: Write the failing test for `JavaFileTree`**

```kotlin
package com.riddleboox.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileTreeTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `JavaFileTree reports the same shape as the wrapped File`() {
        val root = folder.newFolder()
        File(root, "sub").mkdirs()
        File(root, "sub/leaf.txt").writeText("hi")

        val tree = JavaFileTree(root)
        val sub = tree.listChildren().single()
        val leaf = sub.listChildren().single()

        assertTrue(sub.isDirectory)
        assertFalse(leaf.isDirectory)
        assertTrue(leaf.isFile)
        assertEquals("sub/leaf.txt", leaf.path)
        assertEquals("hi", leaf.openInputStream()!!.bufferedReader().readText())
    }

    @Test
    fun `JavaFileTree delete removes the file`() {
        val root = folder.newFolder()
        val file = File(root, "leaf.txt").apply { writeText("x") }

        assertTrue(JavaFileTree(file, root).delete())
        assertFalse(file.exists())
    }
}
```

- [ ] **Step 7: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riddleboox.app.library.FileTreeTest"`
Expected: FAIL — `FileTree.kt` does not exist yet, compile error.

- [ ] **Step 8: Write `FileTree.kt`**

```kotlin
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
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riddleboox.app.library.FileTreeTest"`
Expected: PASS (2/2)

- [ ] **Step 10: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/riddleboox/app/library/StorageAccess.kt app/src/main/java/com/riddleboox/app/library/FileTree.kt app/src/test/java/com/riddleboox/app/library/StorageAccessTest.kt app/src/test/java/com/riddleboox/app/library/FileTreeTest.kt
git commit -m "feat(library): add SAF storage-access foundation and FileTree seam"
```

---

### Task 2: Onboarding permission flow

**Files:**
- Modify: `app/src/main/java/com/riddleboox/app/onboarding/PermissionOverlay.kt`
- Modify: `app/src/main/java/com/riddleboox/app/onboarding/WelcomeOverlay.kt:37-41`
- Modify: `app/src/main/java/com/riddleboox/app/onboarding/OnboardingScript.kt` (segment 5, around line 31-32)
- Modify: `app/src/main/java/com/riddleboox/app/MainActivity.kt:510`, `:648-651`, `:663-706`

**Interfaces:**
- Consumes: `requestStorageAccessIntent(context: Context): Intent`, `persistStorageGrant(context: Context, uri: Uri)`, `canOpenBooks(context: Context): Boolean` (Task 1)
- Produces: `permissionOverlay(context, onAllow, onSkip)` keeps its exact signature — only its copy and what `onAllow` triggers change; no other task depends on new symbols from this one.

- [ ] **Step 1: Reword `PermissionOverlay.kt`**

In `app/src/main/java/com/riddleboox/app/onboarding/PermissionOverlay.kt`, replace the body text (lines 29-37):

```kotlin
    val body = TextView(context).apply {
        text = "To read the words inside your books, I need one folder: where they live on this " +
            "device. Pick it once and I will remember it."
        textSize = 18f
        typeface = Typeface.SERIF
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        setPadding(context.dp(32), context.dp(16), context.dp(32), 0)
    }
```

Update the doc comment above `permissionOverlay` (lines 14-19) to match:

```kotlin
/**
 * Shown once, mid-onboarding, right after the diary explains it can read the
 * writer's books — the natural place to ask for the folder grant that makes
 * that true. Same full-screen paper look as [welcomeOverlay], with two ways
 * off it: [onAllow] sends the writer to the system folder picker, [onSkip]
 * leaves it for later (see the "books on this device" row in Settings).
 */
```

- [ ] **Step 2: Reword `WelcomeOverlay.kt`**

In `app/src/main/java/com/riddleboox/app/onboarding/WelcomeOverlay.kt:37-41`, the body text already says *"I will stop once to ask about your books"* — this stays accurate as written (it doesn't name the permission mechanism) and needs no change. Verify by reading the file that no other line names "all files access"; if a later edit already changed it, skip this step.

- [ ] **Step 3: Reword `OnboardingScript.kt`**

Read `app/src/main/java/com/riddleboox/app/onboarding/OnboardingScript.kt` around `ONBOARDING_SEGMENTS[4]` (line 31-32). If the segment text names "all files access" or a system Settings switch specifically, reword to name a folder instead, e.g. replace any occurrence of "see them" / "all files access" phrasing with "the one about where your books live". If the existing text ("Before my last page I may ask to see them") is already permission-mechanism-agnostic, leave it unchanged.

- [ ] **Step 4: Rewire `MainActivity.kt`'s onboarding checkpoint**

Replace `showOnboardingPermissionOverlay()` (`app/src/main/java/com/riddleboox/app/MainActivity.kt:678-706`):

```kotlin
    private fun showOnboardingPermissionOverlay() {
        val grantIntent = requestStorageAccessIntent(this)
        if (canOpenBooks(this)) {
            onboardingController?.proceedFromCheckpoint()
            return
        }
        val overlay = permissionOverlay(
            this,
            onAllow = { startActivityForResult(grantIntent, REQUEST_ONBOARDING_PERMISSION) },
            onSkip = { testBookAccessThenResumeOnboarding() },
        )
        onboardingPermissionOverlay = overlay
        root.addView(
            overlay,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
    }
```

Replace `testBookAccessThenResumeOnboarding()` (`:663-668`):

```kotlin
    private fun testBookAccessThenResumeOnboarding() {
        onboardingPermissionOverlay?.let { root.removeView(it) }
        onboardingPermissionOverlay = null
        if (canOpenBooks(this)) Log.i(TAG, "onboarding permission check: ${checkBookAccess(contentResolver)}")
        onboardingController?.proceedFromCheckpoint()
    }
```

Replace the `onActivityResult` branch (`:648-651`) — `ACTION_OPEN_DOCUMENT_TREE` returns `RESULT_OK` with the granted `Uri` in `data.data`, unlike the old all-files-access screen, so the grant must actually be persisted here:

```kotlin
        // ACTION_OPEN_DOCUMENT_TREE returns RESULT_OK with the granted folder's Uri in
        // data.data; RESULT_CANCELED (or a null Uri) means the writer backed out.
        if (requestCode == REQUEST_ONBOARDING_PERMISSION) {
            data?.data?.let { persistStorageGrant(this, it) }
            testBookAccessThenResumeOnboarding()
        }
```

Update `asksForBooks` (`:510`):

```kotlin
            val asksForBooks = !canOpenBooks(this)
```

- [ ] **Step 5: Update imports**

In `MainActivity.kt`, remove any now-unused imports (`android.provider.Settings` if only used for the old intent construction, `androidx.core.app.ActivityCompat` if `REQUEST_READ_STORAGE`'s legacy-permission request is fully gone after this step — check Task 4 removes the rest) and add:

```kotlin
import com.riddleboox.app.library.canOpenBooks
import com.riddleboox.app.library.persistStorageGrant
import com.riddleboox.app.library.requestStorageAccessIntent
```

Remove the `REQUEST_READ_STORAGE` constant and its `ActivityCompat.requestPermissions(...)` call from the old `onAllow` block — SAF needs no separate runtime-permission dialog, so there is nothing left to request there.

- [ ] **Step 6: Build and run the existing onboarding tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riddleboox.app.onboarding.*"`
Expected: PASS — these tests exercise `OnboardingController`'s generic checkpoint mechanism, not the permission UI itself, so they should be unaffected. If any fails, read the failure before changing test code — it likely means an unrelated regression, not a Task 2 miss.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/onboarding/PermissionOverlay.kt app/src/main/java/com/riddleboox/app/onboarding/WelcomeOverlay.kt app/src/main/java/com/riddleboox/app/onboarding/OnboardingScript.kt app/src/main/java/com/riddleboox/app/MainActivity.kt
git commit -m "feat(onboarding): ask for a folder grant instead of all-files-access"
```

---

### Task 3: `BookAccess.kt` rewrite

**Files:**
- Modify: `app/src/main/java/com/riddleboox/app/library/BookAccess.kt`

**Interfaces:**
- Consumes: `canOpenBooks(context: Context): Boolean`, `documentAt(context: Context, relativePath: String): DocumentFile?`, `relativePathUnder(root: String, absolutePath: String): String?` (Task 1)
- Produces: `fun checkBookAccess(context: Context, resolver: ContentResolver): BookAccessCheck` — note the added `Context` parameter; Task 4's `SettingsActivity.kt` and `MainActivity.kt` call sites (`checkBookAccess(contentResolver)` today) must pass `this` too. `BookAccessCheck` sealed class is unchanged.

- [ ] **Step 1: Replace `BookAccess.kt`**

```kotlin
package com.riddleboox.app.library

import android.content.ContentResolver
import android.content.Context
import android.os.Environment

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

/** Opens the first book on the shelf to confirm reading actually works, not just that the grant exists. */
fun checkBookAccess(context: Context, resolver: ContentResolver): BookAccessCheck {
    if (!canOpenBooks(context)) return BookAccessCheck.PermissionMissing
    // OnyxLibrary.books() throws LibraryUnreachable rather than returning null/empty when the
    // content provider itself can't be reached — a separate failure from "shelf is genuinely empty".
    val book = runCatching { OnyxLibrary(resolver).books().firstOrNull() }
        .getOrElse { return BookAccessCheck.Unreadable(it.message) }
        ?: return BookAccessCheck.LibraryEmpty
    val relativePath = relativePathUnder(Environment.getExternalStorageDirectory().path, book.path)
        ?: return BookAccessCheck.Unreadable("book path is not under shared storage: ${book.path}")
    val document = documentAt(context, relativePath)?.takeIf { it.exists() }
        ?: return BookAccessCheck.Unreadable("no document at $relativePath")
    val opened = runCatching {
        context.contentResolver.openInputStream(document.uri)?.use { stream ->
            com.riddleboox.app.library.Epub.open(stream)
        }
    }
    return if (opened.getOrNull() != null) {
        BookAccessCheck.Readable
    } else {
        BookAccessCheck.Unreadable(opened.exceptionOrNull()?.message)
    }
}
```

- [ ] **Step 2: Check `Epub.open` accepts a stream**

Read `app/src/main/java/com/riddleboox/app/library/Epub.kt` (or wherever `Epub` is declared — search `grep -rn "object Epub\|class Epub" app/src/main/java`). If `Epub.open` only accepts a `File`, add an overload `fun open(stream: InputStream): Epub?` that reads the zip from the stream (epub files are small enough to buffer; if `Epub.open(File)` uses `ZipFile` specifically — which requires random-access seeking a `File` gives and a plain `InputStream` doesn't — copy the stream to a temp file under `context.cacheDir` first and open that with the existing `File`-based `Epub.open`, then delete the temp file after). Write this overload with a test in `app/src/test/java/com/riddleboox/app/library/EpubTest.kt` (or the existing Epub test file) covering: a valid epub stream opens successfully, an empty stream returns null.

- [ ] **Step 3: Run the library tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riddleboox.app.library.*"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/library/BookAccess.kt app/src/main/java/com/riddleboox/app/library/Epub.kt app/src/test/java/com/riddleboox/app/library/EpubTest.kt
git commit -m "feat(library): read book access through the SAF grant"
```

---

### Task 4: `DiaryTools.kt` + `MainActivity.kt` book read/delete/open-in-reader wiring

**Files:**
- Modify: `app/src/main/java/com/riddleboox/app/tools/DiaryTools.kt:105-137`, `:487-521`, `:593-599`
- Modify: `app/src/main/java/com/riddleboox/app/MainActivity.kt:723-770`
- Test: `app/src/test/java/com/riddleboox/app/tools/DiaryToolsTest.kt`

**Interfaces:**
- Consumes: `documentAt`, `relativePathUnder`, `canOpenBooks` (Task 1), `checkBookAccess(context, resolver)` (Task 3)
- Produces: `DiaryTools`'s constructor gains `fileExists: (String) -> Boolean` and `deleteFile: (String) -> Boolean` params alongside the existing `openBook`/`booksReadable`. No other task depends on new `DiaryTools` symbols — `MainActivity.diaryTools()` is the only production call site.

- [ ] **Step 1: Add the two new seams to `DiaryTools`'s constructor**

In `app/src/main/java/com/riddleboox/app/tools/DiaryTools.kt:105-118`, add two params after `openBook` (defaults preserve today's raw-`File` behavior for every existing test that doesn't override them):

```kotlin
class DiaryTools(
    private val library: Library,
    private val memory: DiaryMemory,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val openBook: (Book) -> Epub? = { Epub.open(File(it.path)) },
    private val fileExists: (String) -> Boolean = { File(it).isFile },
    private val deleteFile: (String) -> Boolean = { File(it).delete() },
    private val openReader: suspend (Book) -> Boolean = { false },
    private val booksReadable: () -> Boolean = ::canOpenBooksLegacy,
    private val textCacheDir: File? = null,
) : Toolbox {
```

`fingerprint()` (`:134-137`) is unaffected — it already only reads `File(book.path).length()`/`.lastModified()` for cache-key purposes on a path string, not for actual byte access, and this plan does not change `fingerprint`'s behavior or its lack of a seam; leave it as-is.

Note `booksReadable`'s default changes from `::canOpenBooks` (the old zero-arg `library.BookAccess.canOpenBooks()`) to a private local default, since the real `canOpenBooks(context: Context)` now needs a `Context` this file doesn't have — define at the bottom of `DiaryTools.kt`:

```kotlin
private fun canOpenBooksLegacy(): Boolean = false
```

This local default only matters if some caller constructs `DiaryTools` without overriding `booksReadable` in production, which Step 3 below prevents — `MainActivity.diaryTools()` always passes a real `context`-bound lambda. Tests that care about `booksReadable` already inject their own lambda (per the Explore inventory, `DiaryToolsTest.kt:63,71,73,230,238`), so this default is never exercised by them either.

- [ ] **Step 2: Rewrite `deleteBook`'s file handling**

In `app/src/main/java/com/riddleboox/app/tools/DiaryTools.kt:487-521`, replace the raw `File` calls:

```kotlin
        val book = candidates.single()
        val fileStood = book.path.isNotBlank() && fileExists(book.path)
        if (fileStood && !keepFile && !deleteFile(book.path)) {
            return "\"${book.title}\" is still here: its file at ${book.path} would not delete, so nothing " +
                "was taken out of the library either. The storage may be read-only, or the folder grant off."
        }
```

(Only the `File(book.path)`/`file.isFile`/`file.delete()` calls and the "all-files access off" phrase change; the rest of `deleteBook` — the `library.deleteBook(book.id)` call and the summary string building below it — is untouched.)

- [ ] **Step 3: Reword `unopenable()`**

In `app/src/main/java/com/riddleboox/app/tools/DiaryTools.kt:593-599`, only the phrase naming the mechanism changes:

```kotlin
    private fun unopenable(book: Book): String = if (booksReadable()) {
        "\"${book.title}\" will not open — the file may be gone, or not readable from here."
    } else {
        "\"${book.title}\" cannot be opened: reading inside books is switched off for this diary. " +
            "Tell the writer it can be turned on in the diary's Settings, on the row about reading " +
            "whole books; titles, progress and marked passages all work without it."
    }
```

- [ ] **Step 4: Write the failing tests for the new seams**

Add to `app/src/test/java/com/riddleboox/app/tools/DiaryToolsTest.kt` (find the existing `deleteBook`-covering test(s) — per the Explore inventory these already exist around the fixture usages at lines 33/44; add alongside them):

```kotlin
    @Test
    fun `deleteBook uses the injected fileExists and deleteFile seams, not java-io-File`() {
        var deletedPath: String? = null
        val tools = DiaryTools(
            library = fakeLibraryWith(book),
            memory = fakeMemory(),
            fileExists = { it == book.path },
            deleteFile = { path -> deletedPath = path; true },
        )

        val result = runBlocking { tools.call("delete_book", buildJsonObject { put("book", book.title) }) }

        assertTrue(result.contains("gone"))
        assertEquals(book.path, deletedPath)
    }

    @Test
    fun `deleteBook reports the file could not be deleted without touching the library entry`() {
        val tools = DiaryTools(
            library = fakeLibraryWith(book),
            memory = fakeMemory(),
            fileExists = { true },
            deleteFile = { false },
        )

        val result = runBlocking { tools.call("delete_book", buildJsonObject { put("book", book.title) }) }

        assertTrue(result.contains("would not delete"))
    }
```

(Use whatever `fakeLibraryWith`/`fakeMemory`/`book` helpers the existing test file already defines — read the file first to match its exact fixture names before pasting this in; the assertions above are the contract, not the fixture wiring.)

- [ ] **Step 5: Run the test to verify it fails, then implement, then pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riddleboox.app.tools.DiaryToolsTest"`
Expected first: FAIL (constructor has no `fileExists`/`deleteFile` params yet, if Step 1-3 aren't applied yet — apply them, then:)
Expected after Steps 1-3: PASS

- [ ] **Step 6: Rewire `MainActivity.diaryTools()` and `openBookInReader()`**

Replace the relevant lines in `diaryTools()` (`app/src/main/java/com/riddleboox/app/MainActivity.kt:739-750`):

```kotlin
        return DiaryTools(
            library = library,
            memory = StoredMemory(conversationStore),
            openBook = { book -> openBookStream(book)?.use { com.riddleboox.app.library.Epub.open(it) } },
            fileExists = { path -> documentForPath(path)?.exists() == true },
            deleteFile = { path -> documentForPath(path)?.delete() == true },
            openReader = { book ->
                withContext(Dispatchers.Main.immediate) { openBookInReader(book) }
            },
            booksReadable = { canOpenBooks(this) },
            textCacheDir = File(cacheDir, "booktext"),
        )
    }

    /** The book's SAF document, resolved from its raw path — see [documentAt]. */
    private fun documentForPath(path: String): androidx.documentfile.provider.DocumentFile? {
        val relative = relativePathUnder(Environment.getExternalStorageDirectory().path, path) ?: return null
        return documentAt(this, relative)
    }

    private fun openBookStream(book: Book): java.io.InputStream? {
        val document = documentForPath(book.path)?.takeIf { it.exists() } ?: return null
        return contentResolver.openInputStream(document.uri)
    }
```

Replace `openBookInReader()` (`:754-770`) — the existence check moves from `file.isFile` to the SAF document, everything after that (building NeoReader's own `content://` URI from the relative path) is unchanged since it never needed file *bytes*, only the relative path string:

```kotlin
    /** Opens a library file through NeoReader's own external-storage provider. */
    private fun openBookInReader(book: Book): Boolean = runCatching {
        val relativePath = relativePathUnder(Environment.getExternalStorageDirectory().path, book.path)
            ?: return@runCatching false
        if (documentForPath(book.path)?.exists() != true) return@runCatching false
        // NeoReader treats a third-party content URI as a download and rejects it. Its own
        // provider maps /external/... to shared storage and is the same handoff used when
        // BOOX opens a book from its library.
        val encodedPath = relativePath.split('/').joinToString("/") { Uri.encode(it) }
        val uri = Uri.Builder()
            .scheme("content")
            .authority("com.onyx.kreader.onyx.fileprovider")
```

(Keep whatever `.path(...)`/`.build()` lines followed the original `uri` builder — those are unchanged, only the value fed into them switches from `relativePath` built off `canonicalPath.removePrefix(prefix)` to the one built off `relativePathUnder(...)`.)

Add imports to `MainActivity.kt`:

```kotlin
import com.riddleboox.app.library.canOpenBooks
import com.riddleboox.app.library.documentAt
import com.riddleboox.app.library.relativePathUnder
```

- [ ] **Step 7: Update the debug log line**

`MainActivity.kt:736` logs `canOpenBooks()` (no args, old zero-arg function) — change to `canOpenBooks(this)`.

- [ ] **Step 8: Run the full DiaryTools + library test suites**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riddleboox.app.tools.DiaryToolsTest" --tests "com.riddleboox.app.library.*"`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/tools/DiaryTools.kt app/src/main/java/com/riddleboox/app/MainActivity.kt app/src/test/java/com/riddleboox/app/tools/DiaryToolsTest.kt
git commit -m "feat(tools): read and delete book files through the SAF grant"
```

---

### Task 5: `DilibClient.kt` rewrite

**Files:**
- Modify: `app/src/main/java/com/riddleboox/app/dilib/DilibClient.kt:1-17`, `:57-128`, `:220-230`
- Modify: `app/src/main/java/com/riddleboox/app/tools/DilibTools.kt:52,117` (copy only)
- Test: `app/src/test/java/com/riddleboox/app/dilib/DilibClientTest.kt` (create if it does not exist; check first)

**Interfaces:**
- Consumes: `getOrCreateDirectoryAt(context: Context, relativePath: String): DocumentFile?` (Task 1)
- Produces: `DilibClient.download(...)` now returns `android.net.Uri` instead of `java.io.File` — `DilibTools.kt:117`'s reply string (`saved.absolutePath`) must switch to `saved.toString()` or a display-friendly form; check every other caller of `download()`/`index()` with `grep -rn "DilibClient()" app/src/main/java` before assuming `DilibTools.kt` is the only one.

- [ ] **Step 1: Check for other callers**

Run: `grep -rn "\.download(\|DilibClient(" app/src/main/java`
Note every call site found — the plan assumes only `DilibTools.kt` calls `download()`; if more exist, apply the same `Uri`-instead-of-`File` change to each.

- [ ] **Step 2: Rewrite `download`/`fetchInto`/`uniqueFile`/`index`**

In `app/src/main/java/com/riddleboox/app/dilib/DilibClient.kt`, replace the imports (`:1-17`) — drop `android.os.Environment`, `android.media.MediaScannerConnection`, `java.io.File`; add:

```kotlin
import android.content.Context
import android.net.Uri
import com.riddleboox.app.library.getOrCreateDirectoryAt
```

Replace `download()`/`fetchInto()`/`index()` (`:57-128`):

```kotlin
    /** Downloads one file into the BOOX library folder. */
    suspend fun download(book: DilibBook, file: DilibFile, context: Context): Uri {
        if (file.url.isBlank()) throw DilibException("\"${book.title}\" has no download link.")
        val destination = getOrCreateDirectoryAt(context, "Books")
            ?: throw DilibException("Could not reach the library folder — grant folder access in Settings first.")

        var lastError: Throwable? = null
        for (attempt in 0..DOWNLOAD_RETRIES) {
            try {
                currentCoroutineContext().ensureActive()
                return fetchInto(destination, context, book, file)
            } catch (error: IOException) {
                lastError = error
                if (attempt == DOWNLOAD_RETRIES) break
                delay(RETRY_DELAY_MS shl attempt)
            }
        }
        throw DilibException("The book would not download after ${DOWNLOAD_RETRIES + 1} attempts.", lastError)
    }

    private suspend fun fetchInto(destination: DocumentFile, context: Context, book: DilibBook, file: DilibFile): Uri {
        var connection = openFollowing(file.url)
        // Drive answers a large file with a scan-warning page instead of bytes.
        // The page is not an error, so only the content type gives it away.
        if (isHtml(connection)) {
            val url = connection.url.toString()
            connection.disconnect()
            connection = openFollowing(confirmed(url))
            if (isHtml(connection)) {
                connection.disconnect()
                throw DilibException(
                    "Google Drive has not handed over the file for \"${book.title}\"; try again later or open ${book.url} in a browser.",
                )
            }
        }

        val name = uniqueName(destination, filename(connection, book, file))
        val target = destination.createFile(mimeType(name.substringAfterLast('.', "")), name)
            ?: throw DilibException("Could not create \"$name\" in the library folder.")
        try {
            connection.inputStream.use { input ->
                context.contentResolver.openOutputStream(target.uri)!!.use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally {
            connection.disconnect()
        }
        return target.uri
    }
```

Delete the old `index(file: File, context: Context)` function entirely — `MediaScannerConnection.scanFile` needs a raw filesystem path, which downloading purely through SAF no longer produces, and NeoReader's own library scan (the same content-provider polling every other book-list read in this app already relies on) picks up files written under `Books/` without it. Remove its call site too:

Run: `grep -rn "\.index(" app/src/main/java` and delete each call site found (expected: `DilibTools.kt`).

- [ ] **Step 3: Rewrite `uniqueFile` as `uniqueName`**

Replace `uniqueFile` (`:220-230`) — the dedup logic moves from checking `File.exists()` to checking `DocumentFile.findFile(name) != null`:

```kotlin
    private fun uniqueName(directory: DocumentFile, requested: String): String {
        if (directory.findFile(requested) == null) return requested
        val base = requested.substringBeforeLast('.', requested)
        val ext = requested.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        for (i in 2..999) {
            val candidate = "$base ($i)$ext"
            if (directory.findFile(candidate) == null) return candidate
        }
        throw DilibException("Too many files in the library share this name.")
    }
```

Add import: `import androidx.documentfile.provider.DocumentFile`.

- [ ] **Step 4: Update `DilibTools.kt`'s copy and caller**

In `app/src/main/java/com/riddleboox/app/tools/DilibTools.kt`, the tool description at line 52 ("into the BOOX library at /sdcard/Books") stays accurate (the folder name is unchanged) — no edit needed there. At line 117, `saved.absolutePath` (a `File` property) must become `saved.toString()` or a Uri-appropriate display (the `Uri` returned by `download()` now); update the reply string accordingly, e.g.:

```kotlin
"Downloaded \"${book.title}\" into the library ($saved)."
```

Read the surrounding lines first to keep the sentence's exact phrasing consistent with the rest of the file.

- [ ] **Step 5: Update or write `DilibClientTest.kt`**

Check `app/src/test/java/com/riddleboox/app/dilib/DilibClientTest.kt` (or wherever DilibClient tests currently live — search `grep -rln "DilibClient" app/src/test`). If tests call `download()` and assert on a `File` result, update those assertions for the new `Uri` return type. If no test exercises `download()`'s SAF-facing behavior at all (likely, since it needs a real `ContentResolver`/`DocumentsProvider`), leave it untested at this boundary — consistent with this plan's Global Constraints — and instead add one small pure-logic test for `uniqueName`'s naming pattern, using a fake `DocumentFile`-like check via a lambda if `uniqueName` is refactored to take a `(String) -> Boolean` "exists" predicate instead of a raw `DocumentFile`:

```kotlin
    // If Step 3's uniqueName is hard to unit-test against a real DocumentFile, extract its
    // naming logic into a pure function taking an `exists: (String) -> Boolean` predicate:
    private fun uniqueName(exists: (String) -> Boolean, requested: String): String {
        if (!exists(requested)) return requested
        val base = requested.substringBeforeLast('.', requested)
        val ext = requested.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        for (i in 2..999) {
            val candidate = "$base ($i)$ext"
            if (!exists(candidate)) return candidate
        }
        throw DilibException("Too many files in the library share this name.")
    }
```

with a call site `uniqueName({ directory.findFile(it) != null }, requested)`, and a test:

```kotlin
    @Test
    fun `uniqueName appends a counter when the name is taken`() {
        val taken = setOf("book.epub", "book (2).epub")
        assertEquals("book (3).epub", uniqueName({ it in taken }, "book.epub"))
    }
```

- [ ] **Step 6: Run the dilib test suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riddleboox.app.dilib.*" --tests "com.riddleboox.app.tools.DilibToolsTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/dilib/DilibClient.kt app/src/main/java/com/riddleboox/app/tools/DilibTools.kt app/src/test/java/com/riddleboox/app/dilib/DilibClientTest.kt
git commit -m "feat(dilib): download books through the SAF grant"
```

---

### Task 6: `BooxNotesTools.kt` rewrite

**Files:**
- Modify: `app/src/main/java/com/riddleboox/app/tools/BooxNotesTools.kt`
- Modify: `app/src/test/java/com/riddleboox/app/tools/BooxNotesToolsTest.kt:220-260+` (`OnyxBooxNotesExportedFilesTest` and any other class in this file constructing `OnyxBooxNotes` with `File` roots)

**Interfaces:**
- Consumes: `FileTree`, `JavaFileTree`, `DocumentFileTree` (Task 1), `documentAt(context, relativePath): DocumentFile?` (Task 1)
- Produces: `OnyxBooxNotes(resolver: ContentResolver, noteRoot: FileTree, ksyncRoot: FileTree)` — the two `File`-typed constructor params become `FileTree`-typed. `BooxNotePage.imageFile` changes type from `File?` to `FileTree?`. Any other file referencing `BooxNotePage.imageFile` or `OnyxBooxNotes.exportedFiles`'s `List<File>` return type must be checked (`grep -rn "imageFile\|exportedFiles" app/src/main/java app/src/test/java`) before this task is considered done.

- [ ] **Step 1: Change `OnyxBooxNotes`'s constructor and every internal `File` op to `FileTree`**

In `app/src/main/java/com/riddleboox/app/tools/BooxNotesTools.kt`, remove `import android.os.Environment` and `import java.io.File` (no longer used directly — `FileTree` is used instead), add:

```kotlin
import com.riddleboox.app.library.FileTree
```

Replace the constructor (`:109-113`):

```kotlin
class OnyxBooxNotes(
    private val resolver: ContentResolver,
    private val noteRoot: FileTree,
    private val ksyncRoot: FileTree,
) : BooxNotesSource {
```

(No production default — `noteRoot`/`ksyncRoot` are always production-wired explicitly now, since a `FileTree` needs either a `Context` (`DocumentFileTree`) or a `File` (`JavaFileTree`) and neither has an argument-free constructor that makes sense as a class-level default.)

Replace `readPage` (`:127-143`)'s private-document check:

```kotlin
    override fun readPage(note: String, pageNumber: Int): BooxNotePage {
        require(pageNumber > 0) { "page must be at least 1" }
        val selected = resolveNote(note)
        val pageId = selected.pageIds.getOrNull(pageNumber - 1)
        val privateDocument = childPath(ksyncRoot, "document/${selected.id}")
        val exported = exportedFiles(selected)
        val image = exported.filter { it.isImageFile() }.getOrNull(pageNumber - 1)
        val shapeText = pageId?.let { readTypedShapeText(selected.id, it) }.orEmpty()
        return BooxNotePage(
            note = selected,
            pageNumber = pageNumber,
            pageId = pageId,
            text = shapeText,
            imageFile = image,
            hasPrivatePageData = privateDocument?.isDirectory == true,
        )
    }
```

Replace `deleteNote` (`:154-161`):

```kotlin
    override fun deleteNote(note: String): DeletedNote {
        val selected = resolveNote(note)
        val exported = exportedFiles(selected)
        val rows = resolver.delete(NOTE_URI, "uniqueId = ?", arrayOf(selected.id))
        val files = exported.count { it.delete() } +
            (childPath(ksyncRoot, "document/${selected.id}")?.let { clear(it) } ?: 0)
        return DeletedNote(note = selected, entry = rows > 0, files = files)
    }
```

Replace `clear` (`:206-213`) — `FileTree` has no `walkBottomUp()`, so this becomes an explicit recursive delete:

```kotlin
    /** Deletes a directory and everything under it, counting the files that went. */
    private fun clear(directory: FileTree): Int {
        if (!directory.isDirectory) return 0
        var files = 0
        for (child in directory.listChildren()) {
            if (child.isDirectory) files += clear(child) else if (child.delete()) files++
        }
        directory.delete()
        return files
    }
```

Replace `exportedFiles` (`:234-247`)'s type and root check:

```kotlin
    internal fun exportedFiles(note: BooxNote): List<FileTree> {
        if (!noteRoot.isDirectory) return emptyList()
        val titleWords = searchWords(note.title)
        if (titleWords.isBlank()) return emptyList()
        val candidates = ArrayList<FileTree>()
        val queue = ArrayDeque<Pair<FileTree, Int>>()
        queue.add(noteRoot to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_EXPORTED_FILES) {
            val (directory, depth) = queue.removeFirst()
            visited = visitExportCandidates(directory, depth, titleWords, visited, queue, candidates)
        }
        return candidates.sortedWith(compareBy<FileTree> { naturalKey(it.name) }.thenBy { it.path })
    }
```

Replace `visitExportCandidates` (`:257-275`):

```kotlin
    private fun visitExportCandidates(
        directory: FileTree,
        depth: Int,
        titleWords: String,
        visited: Int,
        queue: ArrayDeque<Pair<FileTree, Int>>,
        candidates: MutableList<FileTree>,
    ): Int {
        var count = visited
        val children = directory.listChildren().sortedBy { it.name.lowercase(Locale.ROOT) }
        for (child in children) {
            if (++count >= MAX_EXPORTED_FILES) break
            when {
                child.isDirectory && depth < 5 -> queue.add(child to depth + 1)
                child.isFile && child.isReadableExport() && matchesTitle(child, titleWords) -> candidates += child
            }
        }
        return count
    }
```

Replace `matchesTitle` (`:277-280`) — `FileTree.path` is already root-relative (see `FileTree.kt`'s `JavaFileTree`/`DocumentFileTree` `path` implementations from Task 1), so the old `file.relativeTo(noteRoot)` call is no longer needed:

```kotlin
    private fun matchesTitle(file: FileTree, titleWords: String): Boolean {
        val relativeWords = searchWords(file.path)
        return " $relativeWords ".contains(" $titleWords ")
    }
```

Replace `readTypedShapeText` (`:282-290`) and `readShapeZip` (`:292-308`) — `ZipFile` needs random-access seeking a plain `InputStream` doesn't give, so the shape zip is read fully into memory first (these files are already bounded by `MAX_SHAPE_BYTES`, so this is safe):

```kotlin
    private fun readTypedShapeText(noteId: String, pageId: String): String {
        val shapeDirectory = childPath(ksyncRoot, "document/$noteId/shape") ?: return ""
        val revisions = shapeDirectory.listChildren()
            .filter { it.isFile && it.name.startsWith("$pageId#") && it.name.endsWith(".zip", true) }
            .sortedBy { it.name }
        val latest = revisions.lastOrNull() ?: return ""
        return runCatching { readShapeZip(latest) }.getOrDefault("")
    }

    private fun readShapeZip(entry: FileTree): String {
        val bytes = entry.openInputStream()?.use { it.readBounded(MAX_SHAPE_BYTES) } ?: return ""
        val values = ArrayList<String>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            val first = generateSequence { zip.nextEntry }.firstOrNull { !it.isDirectory } ?: return ""
            val content = zip.readBounded(MAX_SHAPE_BYTES) ?: return ""
            values += ShapeProtoText.read(content)
        }
        return values.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
            .take(MAX_READ_CHARS)
    }
```

(Note: `readTypedShapeText`'s revision sort changes from `compareBy { it.lastModified() }.thenBy { it.name }` to `sortedBy { it.name }` because `FileTree` has no `lastModified()` — `DocumentFile.lastModified()` exists and could be added to the `FileTree` interface if this ordering matters in practice; check with the person who wrote this originally whether name-based lexicographic ordering of the `$pageId#<revision>` suffix already produces the same "latest" result before shipping this change, since it changes tie-breaking behavior for same-second revisions. If it does not, add `val lastModified: Long` to `FileTree`/`JavaFileTree`/`DocumentFileTree` instead of dropping the field.)

Add a small helper for `childPath` (used by `readPage`, `deleteNote`, `readTypedShapeText` above) near the other private helpers:

```kotlin
    /** Walks [relativePath] ("a/b/c") from [root] one path segment at a time, or null if any segment is missing. */
    private fun childPath(root: FileTree, relativePath: String): FileTree? {
        var current = root
        for (segment in relativePath.split('/')) {
            if (segment.isBlank()) continue
            current = current.listChildren().firstOrNull { it.name == segment } ?: return null
        }
        return current
    }
```

Replace `isReadableExport`/`isImageFile` (`:340-342`) — extension functions on `File` become extension functions on `FileTree`:

```kotlin
    private fun FileTree.isReadableExport(): Boolean = isImageFile() || name.substringAfterLast('.', "").equals("pdf", true)

    private fun FileTree.isImageFile(): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("png", "jpg", "jpeg", "webp")
```

Update `BooxNotePage`'s `imageFile` field (top of file, `data class BooxNotePage`):

```kotlin
data class BooxNotePage(
    val note: BooxNote,
    val pageNumber: Int,
    val pageId: String?,
    val text: String,
    val imageFile: FileTree?,
    val hasPrivatePageData: Boolean,
)
```

Update `OpenAiBooxNotesVisionReader.readPage()` (`:600-609`):

```kotlin
    override suspend fun readPage(page: BooxNotePage): String {
        val image = page.imageFile ?: return ""
        val bytes = image.openInputStream()?.use { it.readBounded(MAX_VISION_BYTES) } ?: return ""
        val format = image.name.substringAfterLast('.', "").lowercase(Locale.ROOT).ifBlank { "png" }
```

(The rest of `readPage` after building `bytes`/`format` is unchanged.)

Add the constructor overload production code (`MainActivity.kt` and any other `OnyxBooxNotes(resolver)` call site) will use — check `grep -rn "OnyxBooxNotes(" app/src/main/java` for every call site and update each to:

```kotlin
OnyxBooxNotes(
    resolver = resolver,
    noteRoot = DocumentFileTree(
        documentAt(context, "note") ?: throw IllegalStateException("no folder grant"),
        context,
        listOf("note"),
    ),
    ksyncRoot = DocumentFileTree(
        documentAt(context, ".ksync") ?: throw IllegalStateException("no folder grant"),
        context,
        listOf(".ksync"),
    ),
)
```

Read the actual call site(s) first — this snippet is the production-wiring shape, but where it's guarded (e.g. only constructed when `canOpenBooks(context)` is already true) depends on the surrounding function found by the grep above.

- [ ] **Step 2: Update `BooxNotesToolsTest.kt`'s `OnyxBooxNotesExportedFilesTest`**

In `app/src/test/java/com/riddleboox/app/tools/BooxNotesToolsTest.kt`, the `onyxNotes(root: File)` helper (around line 237-240) wraps roots in `JavaFileTree`:

```kotlin
    private fun onyxNotes(noteRoot: File): OnyxBooxNotes {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        return OnyxBooxNotes(
            resolver,
            noteRoot = JavaFileTree(noteRoot),
            ksyncRoot = JavaFileTree(folder.newFolder()),
        )
    }
```

Add `import com.riddleboox.app.library.JavaFileTree` and `import com.riddleboox.app.library.FileTree`.

Every assertion in this class currently compares `List<File>` results — e.g. `assertEquals(listOf(match), result)` where `match: File`. Since `exportedFiles` now returns `List<FileTree>`, change each such assertion to compare by path instead of object identity:

```kotlin
    @Test
    fun `a file directly under noteRoot that matches the title is found`() {
        val root = folder.newFolder()
        File(root, "travel-journal-cover.png").apply { writeText("x") }
        File(root, "other.png").apply { writeText("x") }

        val result = onyxNotes(root).exportedFiles(note("Travel Journal"))

        assertEquals(listOf("travel-journal-cover.png"), result.map { it.path })
    }
```

Apply the same `result.map { it.path }` (or `.map { it.name }` where the original compared just names) pattern to every other test in this class asserting on the raw `File` list — read each test first to know whether it compared full paths or just names, and match that shape exactly rather than guessing.

- [ ] **Step 3: Check for other `OnyxBooxNotes`/`BooxNotePage.imageFile` references**

Run: `grep -rn "OnyxBooxNotes(\|\.imageFile\b" app/src/main/java app/src/test/java`
Fix every remaining `File`-typed usage found beyond what Steps 1-2 already covered (the two production call sites and this test file are the ones known from the Explore inventory, but re-verify — code may have moved since).

- [ ] **Step 4: Run the full BooxNotesTools test suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.riddleboox.app.tools.BooxNotesToolsTest" --tests "com.riddleboox.app.tools.OnyxBooxNotesExportedFilesTest"`
Expected: PASS — every existing BFS/dedup/natural-sort assertion should still hold, since the traversal logic itself is unchanged, only the type it walks.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/tools/BooxNotesTools.kt app/src/test/java/com/riddleboox/app/tools/BooxNotesToolsTest.kt
git commit -m "feat(tools): walk BOOX Notebook notes through FileTree instead of java.io.File"
```

---

### Task 7: `SettingsActivity.kt` rewrite

**Files:**
- Modify: `app/src/main/java/com/riddleboox/app/settings/SettingsActivity.kt:264-339`, `:605-606`

**Interfaces:**
- Consumes: `canOpenBooks(context)`, `requestStorageAccessIntent(context)`, `persistStorageGrant(context, uri)` (Task 1), `checkBookAccess(context, resolver)` (Task 3)

- [ ] **Step 1: Rewrite the permissions row**

Replace `refreshPermissionsRow()` (`:269-279`):

```kotlin
    private fun refreshPermissionsRow() {
        val canOpen = canOpenBooks(this)
        libraryField.text = if (canOpen) {
            "can read the words inside your books"
        } else {
            "can only see titles, progress and notes — tap to grant the folder"
        }
        libraryField.isClickable = !canOpen
        libraryField.setOnClickListener(
            if (canOpen) null else View.OnClickListener {
                startActivityForResult(requestStorageAccessIntent(this), REQUEST_STORAGE_GRANT)
            },
        )
    }
```

Replace `checkPermissions()` (`:297-317`) — drop the legacy-permission request branch entirely, SAF needs none:

```kotlin
    private fun checkPermissions() {
        refreshPermissionsRow()
        val message = when (val result = checkBookAccess(this, contentResolver)) {
            BookAccessCheck.PermissionMissing -> "not granted — tap the row above to allow it"
            BookAccessCheck.LibraryEmpty -> "granted, but there's no book on the shelf to try reading"
            BookAccessCheck.Readable -> "granted, and a book opened and read back fine"
            is BookAccessCheck.Unreadable -> "granted, but opening a book failed" +
                (result.reason?.let { ": $it" } ?: "")
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
```

Delete `hasLegacyStoragePermissions()` (`:319-321`), `onRequestPermissionsResult()` (`:330-339`), and the `LEGACY_STORAGE_PERMISSIONS` constant (`:605-606`) — nothing calls them once `checkPermissions()` no longer requests legacy runtime permissions.

Add a new `onActivityResult` handling for `REQUEST_STORAGE_GRANT` (find `SettingsActivity`'s existing `onActivityResult` override — if none exists yet, add one; if one exists for `PairActivity`'s result per the doc comment at `:341-344`, add a branch to it rather than a second override):

```kotlin
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_STORAGE_GRANT) {
            data?.data?.let { persistStorageGrant(this, it) }
            refreshPermissionsRow()
            return
        }
        // ... existing branches (e.g. PairActivity's result) stay below, unmodified.
    }
```

Add the `REQUEST_STORAGE_GRANT` constant near wherever `REQUEST_STORAGE_PERMISSIONS`/other request codes are already declared in this file (reuse the same companion object).

Add imports:

```kotlin
import com.riddleboox.app.library.canOpenBooks
import com.riddleboox.app.library.checkBookAccess
import com.riddleboox.app.library.requestStorageAccessIntent
import com.riddleboox.app.library.persistStorageGrant
```

Remove now-unused imports: `androidx.core.app.ActivityCompat` (if `checkPermissions` was its only user in this file — verify with `grep -n "ActivityCompat" app/src/main/java/com/riddleboox/app/settings/SettingsActivity.kt` before removing), `android.content.pm.PackageManager`, `androidx.core.content.ContextCompat` if `hasLegacyStoragePermissions` was their only user.

- [ ] **Step 2: Build (Robolectric cannot construct `SettingsActivity` — see project CLAUDE.md's "Robolectric/AndroidKeyStore trap" — so there is no test to run here beyond compilation)**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/settings/SettingsActivity.kt
git commit -m "feat(settings): re-grant folder access instead of legacy storage permissions"
```

---

### Task 8: Manifest cleanup and docs

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:8-34`
- Modify: `app/src/main/res/xml/file_paths.xml`
- Modify: `README.md:168,193,218-226`
- Modify: `/Users/firegroup/projects/RiddleBoox/CLAUDE.md` (append a new section)
- Modify: `docs/superpowers/specs/2026-08-21-onboarding-design.md` (the `asksForBooks` note, if it still names all-files-access specifically)

- [ ] **Step 1: Remove the three permissions and their rationale comment**

In `app/src/main/AndroidManifest.xml`, delete lines 8-34 entirely (the comment block and all three `<uses-permission>` lines for `MANAGE_EXTERNAL_STORAGE`/`READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE`).

- [ ] **Step 2: Confirm and remove the unused `file_paths.xml` entry**

Run: `grep -rn "shared_storage" app/src/main/java app/src/main/res`
Expected: no results (per the spec's confirmed-vestigial finding). If any result appears, stop and investigate that caller before removing anything — do not remove a still-used entry.

If confirmed unused, edit `app/src/main/res/xml/file_paths.xml` to drop the `<external-path name="shared_storage" .../>` element, keeping only:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <cache-path
        name="exports"
        path="exports/" />
</paths>
```

- [ ] **Step 3: Update README.md**

Read `README.md:160-230` in full first (line numbers may have shifted since the Explore inventory was taken). Replace the prose section (originally `:218-226`) describing all-files-access with a description of the SAF folder grant — the writer picks the storage folder once from Settings or during onboarding, the app remembers it, `.ksync`/`note`/`Books` are all reached through that one grant. Update the two tool-table rows (`delete_boox_note` mentioning `/sdcard/note`, `download_dilib_book` mentioning `/sdcard/Books`) only if their wording specifically claims "all files access" as the mechanism — the folder names themselves (`/sdcard/note`, `/sdcard/Books`) are still accurate and don't need to change.

- [ ] **Step 4: Append a CLAUDE.md section**

In `/Users/firegroup/projects/RiddleBoox/CLAUDE.md`, add a new section after the existing "Unit test (Robolectric)" section, following that section's established format:

```markdown
## Storage access (`library/StorageAccess.kt`, `library/FileTree.kt`)

- The app reads/writes shared storage through one SAF grant over the whole primary volume (`StorageVolume.createOpenDocumentTreeIntent()`), not `MANAGE_EXTERNAL_STORAGE` — Google Play rejects that permission for this app's use case (declared not a core feature). The grant is requested once (onboarding, or Settings' "books on this device" row) and persisted with `takePersistableUriPermission`.
- Every document is reached by building its id directly (`documentAt(context, relativePath)`, `DocumentsContract.buildDocumentUriUsingTree`), never by asking a directory to list itself and match a name. This is what reaches `.ksync` — a dot-prefixed directory the `ACTION_OPEN_DOCUMENT_TREE` picker's own folder browser never displays, so a person could never navigate into it and grant it directly; granting the volume root once and then addressing `.ksync` by id sidesteps that picker limitation entirely.
- `OnyxBooxNotes` (BOOX Notebook tools) walks a `FileTree` seam, not `java.io.File`, so its BFS/matching/delete logic runs identically over a real `java.io.File` tree in tests (`JavaFileTree`, built with JUnit's `TemporaryFolder`) and over `DocumentFile` in production (`DocumentFileTree`) — neither the traversal code nor its tests needed to know which one they're holding.
- None of `StorageAccess.kt`'s `ContentResolver`/`DocumentsContract`/`StorageManager` calls are unit-tested — Robolectric has no real `DocumentsProvider`, same boundary as `SettingsStore`'s `AndroidKeyStore` dependency above. Verify storage access on real BOOX hardware, not from a green test suite.
```

- [ ] **Step 5: Check the onboarding-design spec**

Read `docs/superpowers/specs/2026-08-21-onboarding-design.md` around its `asksForBooks` description (Explore inventory located it at line 421). If it names "all files access" as the specific mechanism, reword to name the folder grant instead; if it only describes the checkpoint *mechanism* (pause-and-resume) without naming the permission, leave it unchanged.

- [ ] **Step 6: Build the whole app**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL — this is the first point every file this plan touches compiles together.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/xml/file_paths.xml README.md CLAUDE.md docs/superpowers/specs/2026-08-21-onboarding-design.md
git commit -m "chore: remove MANAGE_EXTERNAL_STORAGE and document the SAF grant"
```

---

### Task 9: Full regression pass

**Files:** none (verification only)

- [ ] **Step 1: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failures. If any test outside the ones this plan touched now fails, stop and diagnose before continuing — do not assume it's unrelated without checking what changed under it (e.g. any other file that happened to import `library.BookAccess.canOpenBooks`'s old zero-arg signature).

- [ ] **Step 2: Search for any remaining reference to the removed permission or old signatures**

Run: `grep -rn "MANAGE_EXTERNAL_STORAGE\|isExternalStorageManager\|allFilesAccess\|canOpenBooks()" app/src/main/java app/src/test/java app/src/main/AndroidManifest.xml`
Expected: no results. `canOpenBooks()` (zero-arg) specifically should not appear anywhere in production code — every call site now passes a `Context`.

- [ ] **Step 3: Build the release variant**

Run: `./gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL — this is the variant Play Store review actually inspects; a release-only compile error (e.g. from R8/minification touching a reflectively-used class) would otherwise ship undetected.

- [ ] **Step 4: Commit (if this task's steps produced any diff — most likely none)**

Only commit if Step 1-3 surfaced a fix; otherwise this task is verification-only and needs no commit.

---

### Task 10: Device verification (manual, on real BOOX hardware — cannot be automated)

**Files:** none (manual QA)

- [ ] **Step 1: Install the debug build and grant the folder**

Install per `scripts/install.sh`. Launch the app fresh (or clear app data first to exercise onboarding's permission checkpoint from scratch). At the permission overlay, tap "allow" — confirm the system folder picker opens already pointed at the device's internal storage root with "USE THIS FOLDER" visible (not requiring manual navigation). Tap it. Confirm onboarding resumes.

- [ ] **Step 2: Verify book reading**

Ask the diary to read from an existing book on the shelf (or use the `com.riddleboox.app.DEBUG_CONTROL` broadcast per this repo's CLAUDE.md to drive the flow without touching the e-ink screen). Confirm chapter text comes back — this exercises `checkBookAccess`/`DiaryTools.openBook`'s new SAF path end to end.

- [ ] **Step 3: Verify book deletion**

Ask the diary to delete a test book (`keep_file=false`). Confirm the reply says the file is gone, and confirm on-device (`adb shell ls /sdcard/Books/`) that the file is actually gone — this is the one operation this migration could silently no-op if `deleteFile`'s SAF path fails permission-silently instead of throwing.

- [ ] **Step 4: Verify dilib download**

Ask the diary to search and download a book from dilib.vn. Confirm the reply names the saved location, and confirm on-device that the file exists under `/sdcard/Books/` with readable content (open it in NeoReader to be sure it isn't a zero-byte or corrupt write — SAF `OutputStream` writes that don't get properly closed/flushed are a plausible new failure mode this migration introduces).

- [ ] **Step 5: Verify BOOX Notebook notes, especially `.ksync` reachability**

This is the step that retires this plan's one open risk (see spec's "Risk this plan cannot retire in code"). Create a note by hand in BOOX Notebook with actual pen strokes (not through this app — through BOOX Notebook itself, so real `.ksync` shape data exists). Ask the diary to `read_boox_note` that note. Confirm real transcribed/typed text comes back, not "no readable text or exported page image was found" — that specific failure message would mean `documentAt(context, ".ksync")` isn't reaching the real directory.

**If Step 5 fails specifically at `.ksync` (book reading in Step 2 and note listing/creation in this step work, but reading page text from real handwritten strokes doesn't):** the fallback is seeding the SAF grant's `EXTRA_INITIAL_URI` with a `DocumentsContract`-built URI pointing directly at `.ksync` when launching `ACTION_OPEN_DOCUMENT_TREE`, forcing the person to grant that specific hidden folder instead of relying on root-grant reachability — this changes `requestStorageAccessIntent` in `StorageAccess.kt` (Task 1) and would need a second, separate grant flow for `.ksync` specifically alongside the root grant. Do not build this fallback speculatively; only implement it if Step 5 actually demonstrates root-grant reachability fails.

- [ ] **Step 6: Verify BOOX Notebook note deletion**

Delete the test note created in Step 5 via the diary. Confirm on-device that both its `.ksync/document/<id>/` tree and its `note/`-exported pages (if any were exported) are actually gone.

- [ ] **Step 7: Verify Settings' permissions row and re-grant**

Open Settings. Confirm the "books on this device" row reads "can read the words inside your books" (grant already held from Step 1). Revoke the grant manually (Android Settings → Apps → RiddleBoox → Permissions, or clear app data) and confirm the row switches to the "tap to grant the folder" wording and re-grants correctly when tapped.

- [ ] **Step 8: Record the outcome**

If every step above passes, this plan's implementation is verified end to end on real hardware — safe to bump the version and submit to Play Store review per the `release` skill. If any step fails, do not proceed to a release submission; fix the specific failing path first (each step above names which task's code it's exercising).
