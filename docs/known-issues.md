# Known issues (found 2026-08-22, needs device verification)

Found during an unattended bug-hunt review pass. Not fixed: each requires either a
real BOOX device to verify (raw pen SDK behavior can't be simulated on the JVM) or
a product decision this session couldn't make alone. Verified against the code by
reading, not by running on hardware — treat severity as a hypothesis to confirm,
not a settled fact.

## 1. An in-flight stroke is silently dropped — and its ink stays on screen — when pen input is gated off mid-stroke (Medium-High)

`app/src/main/java/com/riddleboox/app/ink/InkCaptureController.kt:60-84` (`onEndRawDrawing`):

```kotlin
override fun onEndRawDrawing(b: Boolean, touchPoint: TouchPoint) {
    try {
        if (!inputEnabled || !strokeAccepted) return
        ...
        strokeStore.finishCurrent()
        callbacks.onPenUp()
        publishCapturedInk()
    } finally {
        lastRawTouchPointList = null
        strokeAccepted = false
    }
}
```

If `inputEnabled` flips to `false` while a stroke is already in progress (accepted at
`onBeginRawDrawing`, points already accumulated into `StrokeStore.current`), the
`onEndRawDrawing` that eventually fires for that same stroke takes the early-return
branch: `strokeStore.finishCurrent()` and `callbacks.onPenUp()` never run. The
`finally` block only resets local booleans — it never touches `strokeStore`, so the
orphaned in-progress stroke sits in `StrokeStore.current` until the next
`beginStroke()` silently overwrites it (`StrokeStore.kt:29-31`). It never reaches
`strokes`, is never rasterized, never sent to the diary, never archived.

Concrete repro: pen touches down while `Listening` (accepted) and is still mid-word
when either:
- `MainActivity.onPause()` fires (`MainActivity.kt:635`, unconditional
  `inkCapture.setInputEnabled(false)`, **no** `clearRawInkLayer()` call) — e.g. a
  system interruption (call, notification) arrives while the stylus is still down; or
- the writer taps "gửi" with a free hand while the stylus is still touching the
  panel (`RiddleStateMachine.commitStrokes()`, `RiddleStateMachine.kt:524-531`, does
  call `inkCapture.clearRawInkLayer()` right after `setInputEnabled(false)` — this
  path may already self-heal the visible-ink half of the bug via the session
  restart, but the `strokeStore.current` state loss happens either way).

For the `onPause()` path specifically, nothing clears the raw ink layer, so
whatever the SDK already rendered for that stroke before the gate flipped stays
visible on the e-ink panel while the app's model has silently discarded it — a
"page received ≠ page written" discrepancy on the *capture* side (the analogous
*send* side is what `PageArchive.kt`'s own class doc already worries about).
Secondary consequence: `callbacks.onPenUp()` never fires, so
`RiddleStateMachine.lastPenUpAtMs` stays unset, and `shouldCommitOnPause`
(`Decide.kt:60-70`, requires `lastPenUpAtMs != null`) can't auto-commit again until
one full, ungated pen-down/pen-up cycle happens — auto-send-on-pause silently
stalls after this.

**Why not fixed here:** `InkCaptureController` has no test file at all
(`InkCaptureControllerTest.kt` doesn't exist) and can't reasonably get one without
mocking the Onyx SDK's `RawInputCallback`/`TouchHelper` — this module exists
specifically to isolate that SDK, so mocking it away would mostly test the mock.
The correct fix (at minimum, discard `strokeStore.current` on this early-return
path; possibly also clear the raw ink layer, which means calling
`restartRawDrawing()` from inside an SDK callback — unclear whether that is safe to
do from that thread/context without a device to check) needs verification on a
real BOOX device before landing.

## 2. `AgentStore` can't always tell "no agent" from "a broken agent folder" (Medium)

`app/src/main/java/com/riddleboox/app/agent/Agent.kt:78-104` (`ensureDefaults`) and
`AgentTools.kt:109-112` (`AgentManagerTools.createAgent`'s slug-collision loop).

`ensureDefaults()`'s "system.md alone is missing" repair branch also catches every
other reason `load()` returns null (missing/corrupt `manifest.json`, mismatched
`id`) but only ever repairs the prompt file, never the manifest — so a default
agent's folder left behind by an interrupted `create()` (process killed between
`agentFolder.mkdirs()` and `writeManifest(manifest)`) can never self-heal, and
`create()` itself refuses to touch an existing folder
(`check(!folder(id).exists())`, `Agent.kt:154`).

Same `load(id) == null` ambiguity resurfaces in `createAgent`'s slug-collision loop:
a leftover folder from a partially-failed `delete()` (`deleteRecursively` at
`Agent.kt:260-263` ignores individual file-delete failures) can make the loop pick
an id it thinks is free, and `store.create()` then throws
`IllegalStateException("Agent already exists: $id")` — surfaced to the model as a
confusing failure for an agent `list()`/`load()` both say doesn't exist.

Reproducing this needs an interrupted `create()`/`delete()` (app killed mid-write,
or a filesystem delete partially failing) — plausible on RAM-constrained e-ink
hardware, not an everyday path. `AgentStoreTest.kt`'s reseed test only deletes
`system.md`, never `agent.json` — this exact state isn't covered.

## 3. First-run `ReplySettings` are used un-sanitized (Low-Medium)

`app/src/main/java/com/riddleboox/app/settings/SettingsStore.kt:74-82`
(`readOrDefault`) does no trimming; only `ReplySettings.sanitized()`
(`SettingsStore.kt:27-31`) does, and only `SettingsActivity`'s save path calls it
(`SettingsActivity.kt:205-210`). `MainActivity.kt:270-275` reads settings via
`readOrDefault()` directly and never sanitizes. On a cold start before the writer
ever opens Settings, the fallback comes straight from `BuildConfig.LLM_API_KEY` /
`LLM_BASE_URL` / `LLM_MODEL`, populated verbatim from `local.properties` — trailing
whitespace on a `local.properties` line would flow untrimmed into the Bearer-token/
model string, likely causing auth failures until the writer visits Settings once
(which finally persists a trimmed copy). Narrow — depends on a slightly malformed
`local.properties` — but real, since the sanitizer exists specifically to prevent
this class of bug and one legitimate read path bypasses it.

## 4. `PageArchive.pages()` assumes constant-width timestamps when sorting (Low, debug-only)

`app/src/main/java/com/riddleboox/app/ink/PageArchive.kt:34-36` sorts
`page-$timestampMs.png` filenames lexicographically, which only matches
chronological order because `System.currentTimeMillis()` is currently always 13
digits. A device that boots with its clock reset near epoch (no battery-backed
RTC, before NTP sync — a known failure mode on cheap tablets) would produce a
shorter numeric string that sorts out of true order, and `prune()`
(`PageArchive.kt:38-40`) would delete the wrong files relative to "newest."
`pageArchive` is DEBUG-build-only (`MainActivity.kt:291-294`), so impact is low.
