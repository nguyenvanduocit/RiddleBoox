# SAF storage migration — design spec

## Why

Google Play rejected RiddleBoox twice (0.4.5, 0.4.6 — both "Rejected Sep 4, 2026" in Policy status) for the `MANAGE_EXTERNAL_STORAGE` ("All files access") permission: *"The app feature that you declared as dependent on the All files access permission didn't meet the policy review requirements for critical core functionality."* Google's own "How to fix" is explicit: *"If your app doesn't require access to the MANAGE_EXTERNAL_STORAGE permission, you must remove it from your app's manifest."* An appeal already tried the "this is core, epub reading needs it" argument on 2026-09-04 08:50 and was rejected the same day. Repeating that argument has no new evidence behind it.

The compliant path Android itself documents for "read/write specific files/folders in shared storage without a person re-approving every time" is the **Storage Access Framework**: the writer picks a folder once through the system picker, the app gets a **persistable** URI grant over that folder tree, and every future session reads/writes through that grant — no re-prompt, no `MANAGE_EXTERNAL_STORAGE`.

## Decision

Replace `MANAGE_EXTERNAL_STORAGE` + `READ_EXTERNAL_STORAGE` + `WRITE_EXTERNAL_STORAGE` with a **single SAF grant over the whole primary storage volume**, obtained via `StorageVolume.createOpenDocumentTreeIntent()` (the writer taps "USE THIS FOLDER" once, already pointed at the volume root — not a folder they have to navigate to). This keeps full read/write reach over every path the app currently touches (`Books/`, `note/`, `.ksync/`) without asking the writer to grant three separate folders, and without losing any capability the app has today: the grant is persisted (`takePersistableUriPermission`) and survives reboots.

**Root-level over per-folder grants** because `.ksync` is a dot-prefixed directory. The `ACTION_OPEN_DOCUMENT_TREE` picker's own folder browser does not display hidden directories, so a writer could never navigate into `.ksync` and pick it directly — this is a hard blocker for a per-folder-grant design (BOOX Notebook data lives there) and the reason the design grants the volume root once instead.

## Architecture

Two new files under `library/`:

- **`StorageAccess.kt`** — grant lifecycle (request/persist/check) and document access. Two pure functions (`documentIdFor`, `relativePathUnder`) are unit-testable; everything touching `ContentResolver`/`DocumentsContract`/`StorageManager` is an untested boundary, same convention as `SettingsStore` (see `manage-external-storage-insufficient-android11` / Robolectric+AndroidKeyStore precedent in this repo's CLAUDE.md) — Robolectric has no real `DocumentsProvider`, so this is device-verified, not unit-tested.
- **`FileTree.kt`** — a small filesystem-shaped interface (`isDirectory`, `listChildren()`, `delete()`, `openInputStream()`) with two implementations: `JavaFileTree` (wraps `java.io.File`, used by existing tests that build real trees with JUnit's `TemporaryFolder`) and `DocumentFileTree` (wraps `androidx.documentfile.provider.DocumentFile`, used in production). `OnyxBooxNotes`'s BFS/matching/delete logic is written against `FileTree` once and never has to know which implementation it's holding — this is what keeps `BooxNotesToolsTest`'s existing BFS/dedup/natural-sort assertions working almost unchanged.

**Reaching `.ksync` without ever asking a picker to show it**: `documentAt(context, ".ksync")` builds the document URI directly by ID (`DocumentsContract.buildDocumentUriUsingTree(treeUri, "primary:.ksync")`) rather than via `root.findFile(".ksync")`. A picker's hidden-file filter is a *display* concern in DocumentsUI; it does not apply to a `ContentResolver` query the app issues itself post-grant, and building the ID directly sidesteps the question entirely rather than depending on that distinction being correct on every OEM's provider. All of `noteRoot`, `ksyncRoot`, and `Books/` are resolved this same way — by direct ID, never by asking the granted root to enumerate itself and match a name.

**`book.path` stays a raw absolute path string** (its type is unchanged everywhere — `Library`, `Book`, existing test fixtures with literal `"/sdcard/Books/..."` strings all keep working). Only *how bytes get read from it* changes: `relativePathUnder(Environment.getExternalStorageDirectory().path, book.path)` (pure, testable) strips the volume root off, and the remainder becomes the SAF document ID. `MainActivity.openBookInReader()`'s hand-off to NeoReader already does exactly this relative-path computation today (`app/src/main/java/com/riddleboox/app/MainActivity.kt:754-770`) — the migration reuses that logic rather than inventing a second scheme.

## What does NOT change

- `Book`, `Library`, `OnyxLibrary` — NeoReader's own content-provider metadata (title, progress, highlights) needs no file access today and still needs none.
- The onboarding checkpoint mechanism (`OnboardingController.permissionCheckpointAfter`/`onPermissionCheckpoint`) — generic, unaware of what permission it's pausing for.
- `DiaryTools`'s existing `openBook`/`booksReadable` constructor seams — production wiring inside them changes; the seams themselves (and every test using them) do not.

## What does change (touchpoint inventory, verified 2026-09-04)

- `AndroidManifest.xml:32-34` — remove all three permissions; remove the rationale comment (`:8-30`) that describes and justifies them.
- `library/BookAccess.kt` — `canOpenBooks()`/`allFilesAccess()` replaced by `StorageAccess` calls.
- `tools/DiaryTools.kt` — `openBook` default, `fingerprint()`, `deleteBook()` (`:501-519` reads/deletes via raw `File`), `unopenable()`.
- `MainActivity.kt` — `diaryTools()` wiring (`:723-751`), `openBookInReader()` (`:754-770`), onboarding permission checkpoint (`:678-706`, `:648-651`, `:663-668`), `asksForBooks` (`:510`).
- `onboarding/PermissionOverlay.kt` — copy no longer promises "the switch it wants is called all files access"; promises a folder picker instead.
- `onboarding/WelcomeOverlay.kt`, `onboarding/OnboardingScript.kt` — copy referencing the ask.
- `dilib/DilibClient.kt` — `download()`/`fetchInto()`/`uniqueFile()`/`index()` write through SAF instead of `File(Environment.getExternalStorageDirectory(), "Books")`.
- `tools/BooxNotesTools.kt` — `OnyxBooxNotes`'s `noteRoot`/`ksyncRoot` become `FileTree`; every method that walked `File`s (`exportedFiles`, `visitExportCandidates`, `matchesTitle`, `readTypedShapeText`, `readShapeZip`, `clear`, `readPage`, `deleteNote`) walks `FileTree` instead. `OpenAiBooxNotesVisionReader.readPage()` reads through `FileTree.openInputStream()`.
- `settings/SettingsActivity.kt` — `refreshPermissionsRow()`/`checkPermissions()` re-point at the SAF grant; `hasLegacyStoragePermissions()`/`onRequestPermissionsResult()`/`LEGACY_STORAGE_PERMISSIONS` are deleted outright (SAF needs no runtime permission dialog at all).
- `app/build.gradle.kts` — add `androidx.documentfile:documentfile:1.0.1`.
- `res/xml/file_paths.xml` — the `shared_storage` `external-path` entry is unused today (every `FileProvider.getUriForFile` call in the app targets `cacheDir`, confirmed by grep across `SettingsActivity.kt`, `MemoriesActivity.kt`, `AgentsActivity.kt`, `TranscriptActivity.kt`, `HistoryActivity.kt`) — remove it while touching the manifest, after one more `grep -rn 'shared_storage'` confirms nothing new was added since.
- `README.md:168,193,218-226` — prose and tool-table rows describing the all-files-access design.
- Play Console **App content → Permissions declaration** — the "All files access" declaration itself needs removing once the manifest ships without the permission (manual Console action, out of repo scope, tracked as a follow-up after this plan's code lands).

## Risk this plan cannot retire in code

Every path above the `FileTree`/`StorageAccess` boundary is untestable under Robolectric (no real `DocumentsProvider`). The plan's last task is a manual device-verification pass on real BOOX hardware — nothing here should be called done from a green test suite alone.
