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

## 3. `WriteCursor` drops a page-boundary line break, which can glue two paragraphs onto one line (Medium-High) — pure logic, but the fix trades one bug for a different one without a product call

`app/src/main/java/com/riddleboox/app/handwriting/WriteCursor.kt:133-135`:

```kotlin
// A line break with nothing after it has nothing to put on the next
// page; dropping it keeps `pageFull` meaning "words are waiting".
if (held.all { it == LINE_BREAK }) held.clear()
```

`writeWhatFits()`'s `while` loop can only leave `held` non-empty by hitting one of
its two `break`s, both gated on `!hasRoomForAnotherLine()` — i.e. the page has no
room for one more line. When that happens on a `LINE_BREAK` token specifically
(the model's stream ended a line/paragraph right as the page ran out), and nothing
has been queued behind it yet (the next streamed chunk hasn't arrived from a later
`append()` call), this cleanup clears it — which also clears `pageFull` back to
`false`, even though the page genuinely has no room for another line.

The next `append()` call (streaming continues; this class has no way to know
whether more text is coming) then queues its words starting from an empty `held`,
with `lineHasWord` still `true` and `penXPx` still parked mid-line from before —
so if the new word's *width* still fits the current line (true whenever the page
is wide enough for more than one word per line), it gets written onto the same,
already-final line as the text before the dropped break, with no line break and no
page turn. Verified by full manual trace against the real algorithm and the actual
production caller (`RiddleStateMachine.feedReply()` → `writeText()`, which calls
`cursor.append()` once per `writableCut`-settled chunk — a real multi-call
streaming pattern, not synthetic). Not a data-loss bug — the stored transcript
stays correct — but it visibly corrupts the on-screen handwritten reply layout at
page boundaries, a path every multi-page, multi-sentence reply exercises.

**Why not fixed here — this is not a clean-cut fix:** the obvious-looking
correction (stop clearing `held` in this case, so a break that can't be honored
behaves like a word that can't be honored — deferred to the next page) directly
contradicts an existing, apparently deliberate test:
`WriteCursorTest.kt`'s `a line break with nothing after it does not call for a new
page` asserts `pageFull == false` for exactly this state. Worse, `pageFull` feeds
`RiddleStateMachine`'s end-of-reply detection directly —
`RiddleStateMachine.kt:776`: `val done = next.streamEnded && cursor.caughtUp &&
writeCursor?.pageFull != true`. Making `pageFull` correctly `true` in this case
would also make it `true` at the *end of a finished reply* whenever the very last
line break happens to land exactly at the page's foot, which would stop `done`
from ever becoming true there and force an extra, unnecessary page-turn onto a
page with nothing left to show, before the reply is recognized as finished. Fixing
the mid-reply corruption this way risks trading it for a stuck/extra-blank-page
regression at reply endings — a real product trade-off between two failure modes,
not an oversight with one obviously-correct answer. Needs a human to pick a
direction (e.g. distinguish "no room, but more may be coming" from "stream truly
ended" some other way — `WriteCursor` doesn't currently know which) rather than a
guess made unattended.

**Testability:** 100% pure logic, JVM-testable with the existing `FakeRaster` test
double — no Android runtime needed to fix or verify this, only a product decision
about which failure mode to accept.

## 4. `PageArchive.pages()` assumes constant-width timestamps when sorting (Low, debug-only)

`app/src/main/java/com/riddleboox/app/ink/PageArchive.kt:34-36` sorts
`page-$timestampMs.png` filenames lexicographically, which only matches
chronological order because `System.currentTimeMillis()` is currently always 13
digits. A device that boots with its clock reset near epoch (no battery-backed
RTC, before NTP sync — a known failure mode on cheap tablets) would produce a
shorter numeric string that sorts out of true order, and `prune()`
(`PageArchive.kt:38-40`) would delete the wrong files relative to "newest."
`pageArchive` is DEBUG-build-only (`MainActivity.kt:291-294`), so impact is low.

## 5. `SkeletonTracer`'s greedy walk can switch branches at a stroke junction (Low, likely not worth fixing)

`app/src/main/java/com/riddleboox/app/handwriting/SkeletonTracer.kt:59-69`
(`neighbours`) scans a pixel's 8 neighbours in fixed top-left-to-bottom-right
order with no preference for "the direction the walk was already going in."
`trace()`'s greedy walk (`SkeletonTracer.kt:44-48`) always takes
`neighbours(...).firstOrNull { !visited }` — so at a degree-≥3 pixel (a
junction, e.g. where a letter's stem crosses its crossbar), the walk can jump
onto a different branch than the one it was tracing, rather than continuing
straight through. Verified by a 1:1 Python transliteration of the algorithm
against a "+"-shaped mask: the walk starting at the stem's top goes one step
down, then turns onto the *left arm* of the crossbar instead of continuing down
the stem — the stem's bottom half and the crossbar's right arm end up in a
*different* stroke.

**Why this is probably not worth fixing:** `SkeletonTracer.trace()` is an
explicit, faithful port of `references/Riddle/src/script.rs:128-196` (see the
class doc) — this exact greedy-first-unvisited-neighbour behavior at junctions
is inherited from the already-validated reference implementation, not a
porting mistake. No ink is lost (every consecutive pair in a path is
8-connected, so `drawLine` between them never skips pixels) — the only
possible effect is the *order* strokes are drawn/animated in at a junction,
which might read slightly differently than a hand's natural stroke order but
has not been verified as visually wrong on a real device. `SkeletonTracerTest.kt`
has no junction/degree-≥3 case, so this is genuinely untested, but the
"obvious" fix (prefer the neighbour that continues the current direction)
would diverge from the deliberately-mirrored Rust reference and rewrite every
existing test's exact expected coordinate sequence. Add a junction test case
first if this turns out to matter visually; don't change the algorithm blind.

## 6. `Conversation.ask()` bakes a permanent empty-content message into live history on an illegible page (Medium-High) — same class of trade-off as #3, needs a product call

`app/src/main/java/com/riddleboox/app/reply/Conversation.kt:418`:

```kotlin
history = prompt(page.withMessages { messages -> messages.dropLast(1) }) { user(turn.transcript) }
if (turn.reply.isNotBlank()) history = prompt(history) { assistant(turn.reply) }
```

Builds `user(turn.transcript)` unconditionally, even when `turn.transcript == ""`
(the documented, expected outcome for an illegible page — `TURN_PROTOCOL` at
`Conversation.kt:76` tells the model to leave transcript empty then, and
`PERSONA` has it say "the ink blurred"). `restore()`, four lines below
(`Conversation.kt:474`), guards the identical construction:
`if (turn.transcript.isNotBlank()) history = prompt(history) { user(turn.transcript) }`,
with a comment explaining why: *"Replayed literally it would become an empty
user message, which some OpenAI-compatible servers reject outright."*

That guard only protects a conversation reloaded from storage. `ask()`'s empty
`user("")` gets baked into the **live** `history` and replayed on every
subsequent `ask()` call in the same session (until it slides out of
`keptMessages`'s window) — so a server that would reject an empty user message
risks failing on *later* turns too, not just on resume.

**Why not fixed here:** the obvious mirror-image fix (guard `ask()`'s
`user(turn.transcript)` the same way `restore()` does) reproduces the exact
trade-off `restore()`'s own test already accepts for the single-turn case:
skipping the user message entirely can leave two `assistant()` messages back
to back with no `user()` between them once a real reply follows an illegible
one — an alternation gap of a different kind than the one being fixed. A
non-empty placeholder (e.g. `user("(trang không đọc được)")`) avoids that, but
choosing what a placeholder says to the model is a prompt-design decision, not
a mechanical fix. Needs a human call on the replacement, not a guess made
unattended at the end of a bug-hunt pass.

**Testability:** pure logic, JVM-testable — `ConversationTest.kt` already has
the `FakeChatServer` infrastructure this would need (call `ask()` twice, first
with an illegible page then a legible one, inspect the second request body).

## 7. `create_agent` can permanently orphan a folder if `create()` fails between writing its manifest and its prompt (Medium, narrow trigger)

`app/src/main/java/com/riddleboox/app/agent/AgentTools.kt:109-112`
(`AgentManagerTools.createAgent`'s id-dedup loop):

```kotlin
var id = requested
var suffix = 2
while (store.load(id) != null) id = "$requested-${suffix++}"
```

treats `store.load(id) == null` as "id is free." But `AgentStore.load()`'s own
doc (`Agent.kt:71-76`) says it can't tell "never existed" apart from "folder
exists but `system.md` is missing" — both return `null`. `AgentStore.create()`
(`Agent.kt:142-174`) writes `agent.json` first, `system.md` second; if the
second write throws (I/O error, storage full — plausible on a
storage-constrained e-ink device), the folder is left holding a manifest with
no prompt file, and:
- `list_agents` can't see it (`load()` returning `null` makes `list()`'s
  `mapNotNull` skip it).
- `delete_agent` can't see it either (`store.load(id) ?: return "Unknown
  agent: $id"`), so there's no way to remove it through the tool.
- Retrying `create_agent` with the same name hits the dedup loop's blind spot
  (`load(id) == null` says the slug is free), so `store.create()` throws
  `"Agent already exists: $id"` on every retry — that slug is permanently
  squatted.

**Why not fixed here:** the real fix is in `AgentStore` itself — `load()` and
the dedup loop need a way to tell "no folder" apart from "folder exists but is
broken" (e.g. check `folder(id).exists()` directly rather than trusting
`load()`'s null), and `ensureDefaults()` (a related, already-known gap — see
`get_health` findings from earlier tonight, not separately filed here) has the
same blind spot for built-in agents. That's a small but real change to
`AgentStore`'s create/load contract, worth doing carefully with its own test
rather than folded into an unattended bug-hunt commit. Reproducing it needs a
forced I/O failure between the two writes (same technique
`DiaryToolsTest.kt`'s `a file that will not delete leaves the book on the
shelf` test already uses via `folder.setWritable(false)`) — not reachable on
the happy path, so this is real but lower-probability.

**Testability:** pure logic, JVM-testable with `TemporaryFolder`. No
`AgentManagerToolsTest.kt` exists yet — this would be its first test.

## 8. `RiddleStateMachineTest`'s `a real streamed reply is answered, drawn, and recorded` is flaky (Low, test-only)

Observed once (2026-08-22, ~09:22) during an unrelated `SettingsActivity.kt`
change's verification: `./gradlew testDebugUnitTest` failed with
`org.junit.ComparisonFailure: expected:<H[ôm nay tôi buồn.]> but was:<H[]>` at
`RiddleStateMachineTest.kt:178` (`assertEquals("Hôm nay tôi buồn.",
turn.transcript)`) — the persisted turn's transcript came back empty.
Re-running the same test alone, then the full suite, both passed immediately
after with no code changes in between. Not caused by the `SettingsActivity.kt`
change in flight at the time (disjoint package, no shared code path).

Likely an eventually-consistent race in the test harness itself — `h.waitForConversations(1)`
(`RiddleStateMachineTest.kt:176`) polls for a conversation to appear, but the
one it observed once had the entry created before its `turn.transcript` was
fully written, not a production bug reproduced without the test's async
polling shape. Recorded because a flaky test erodes trust in the suite over
time (a real regression could hide behind "oh, that one's flaky"); worth a
tighter look if it recurs, not treated as this session's problem to fix per
this repo's convention for test failures outside the file(s) actually being
changed.

**Recurred 2026-08-28 (~21:15), sibling test, different signature.** `an svg
block streamed inside the reply becomes a drawn figure, not ink` failed with
`did not settle back to Listening within 10000ms real time (last status:
Thinking…)` — `driveUntilIdle`'s 10s *wall-clock* deadline expired while two
Gradle builds shared the machine (another session was running
`assembleRelease testReleaseUnitTest` on the same tree; the failing run's own
log noted "2 busy Daemons"). Re-run alone immediately after: green, no code
change in between. So this family of tests has two load-sensitive failure
shapes, both in the harness's real-time waits, and neither reproduces solo.
If a CI or a busy machine hits one, re-run the single test before suspecting
the change in flight; a real fix would be scaling `driveUntilIdle`'s deadline
with load or replacing wall-clock waits with virtual time.

**Recurred 2026-09-02 (~10:31 and ~11:10-11:12), third sibling.** `a demo
write lands below the standing reply instead of overlapping it` failed with
`did not settle back to Listening within 60000ms real time (last status:
Writing a reply…)` — even the 60s deadline expired, twice while the daemon
was recompiling the whole app for the AGP 8.10.1 / SDK 36 toolchain switch
and once right after. Isolated: 5/5 green in ~4s per class run; next warm
full run: 600/600 green. Same wall-clock shape as above, now seen stuck in
the writing phase as well.
