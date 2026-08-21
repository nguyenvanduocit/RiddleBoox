# Onboarding (first-run introduction) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** On the first app launch (never before completed), a full-screen welcome view (large title + "bắt đầu" button) appears; tapping it starts the diary auto-writing a fixed sequence of 6 introductory segments in its own hand — no pen input needed — then hands off to the normal empty-page/greeting flow; a "giới thiệu lại" row in Settings lets the writer replay it any time.

**Architecture:** A new, self-contained `com.riddleboox.app.onboarding` package (pure `decideOnboarding()` state-transition function + a thin `OnboardingController` shell + a `WelcomeOverlay` view builder) reuses the existing handwriting-reveal building blocks (`HandwritingPlanner`, `WriteCursor`, `ReplyRevealCursor`, `RegionView`, `EinkRefresher`, `Ticker`) without touching `RiddleStateMachine`'s own Listening/Drinking/Thinking/Replying loop. `MainActivity` branches at startup on a `OnboardingStore` flag: unseen → show the welcome overlay with pen locked and chrome hidden, then run `OnboardingController` once "bắt đầu" is tapped; seen → today's unchanged path.

**Tech Stack:** Kotlin, plain `android.app.Activity`, JUnit 4 (no Robolectric — matches existing test setup), Gradle/AGP single module `app`.

**Spec:** `docs/superpowers/specs/2026-08-21-onboarding-design.md` — read it alongside this plan; this plan implements it section by section (§ references below point back to it).

## Global Constraints

- Content is 6 fixed-order Vietnamese segments, copied verbatim from spec §4 — do not paraphrase, do not randomize order (unlike `Greetings.kt`).
- `ONBOARDING_HOLD_MS = 4_000L` (spec §6.2) — how long a finished segment stands before the next one begins.
- `REPLY_TICK_MS = 14L`, `REPLY_POINTS_PER_TICK = 26`, `REPLY_REFRESH_INTERVAL_MS = 120L` (spec §5) — moved verbatim (same values) from `RiddleStateMachine.kt`'s companion object to top-level `internal const val` in `riddle/Decide.kt`, so onboarding's reveal speed matches a real reply's exactly.
- Pen stays locked (`inkCapture.setInputEnabled(false)`) from the welcome screen through the end of the run, regardless of `diaryBusy` (spec §7.2) — no skip-by-writing.
- `chromeRow` is `View.GONE` for the entire run, from the welcome screen through the end (spec §7.2).
- Between-segment refresh is `requestQualityPartialRefresh`, not `requestFullRefresh` — only `finish()`'s one-time end-of-sequence refresh stays full (spec §6.4, Task 8) — a direct user correction against the original full-refresh design.
- Welcome screen (spec §7.1, Task 9): full-screen, opaque, large title "Chào mừng" (Vietnamese, not literal "Welcome" — see spec §7.1's noted assumption), one "bắt đầu" button. Tapping it only starts the sequence; it is not a skip mechanism and does not appear again once tapped for that run.
- No test written for `OnboardingStore` — no Robolectric dependency exists (`app/build.gradle.kts:115` only has `junit:junit:4.13.2`), and none of the three existing `SharedPreferences`-backed stores (`AgentSelectionStore`, `SendModeStore`, `ReplyFontSizeStore`) have one either; this follows that convention (spec §10).
- **Before Task 1's edit to `RiddleStateMachine.kt`:** this repo's root `CLAUDE.md` mandates a GitNexus `impact()` check before editing any symbol, but GitNexus has no MCP tools connected in this session and no `.gitnexus/` directory exists on disk (verified via `ToolSearch` and `ls`) — only the `repowise` MCP server is actually wired (`.mcp.json`). Use its equivalent instead: `mcp__repowise__get_risk(targets: ["app/src/main/java/com/riddleboox/app/riddle/RiddleStateMachine.kt"])`, and report `hotspot_score`/`defect_profile`/`episodes` before proceeding.
- **Before Task 7 is considered done:** same substitution — `detect_changes()` isn't available; use `mcp__repowise__get_change_risk()` (uncommitted working tree, `revspec` omitted) and confirm the affected-symbol scope matches this plan (only the files listed in each task below).

---

### Task 1: Move shared reply-timing constants out of `RiddleStateMachine`

**Files:**
- Modify: `app/src/main/java/com/riddleboox/app/riddle/Decide.kt:16-21`
- Modify: `app/src/main/java/com/riddleboox/app/riddle/RiddleStateMachine.kt:1045-1079`

**Interfaces:**
- Produces: `com.riddleboox.app.riddle.REPLY_TICK_MS: Long`, `com.riddleboox.app.riddle.REPLY_POINTS_PER_TICK: Int`, `com.riddleboox.app.riddle.REPLY_REFRESH_INTERVAL_MS: Long` — `internal`, top-level in `riddle` package, importable from `com.riddleboox.app.onboarding` (Task 4).

This is a pure relocation — same values, no behavior change. `RiddleStateMachine.kt`'s own three usages (`REPLY_POINTS_PER_TICK` at line 757, `REPLY_TICK_MS` at lines 789/801, `REPLY_REFRESH_INTERVAL_MS` at line 786) need **no edits**: Kotlin resolves unqualified top-level identifiers from the same package without an import, which is exactly how `REPLY_TOP_PX`/`REPLY_BOTTOM_PX` (`PageGeometry.kt:25,28`) are already used unqualified inside this same file (e.g. `RiddleStateMachine.kt:481`).

- [ ] **Step 1: Check risk on `RiddleStateMachine.kt` before touching it**

GitNexus's `impact()` (named in the repo's root `CLAUDE.md`) is not connected in this session — no `mcp__gitnexus__*` tool exists and `.gitnexus/` is absent from disk. Run the connected equivalent instead:

`mcp__repowise__get_risk(targets: ["app/src/main/java/com/riddleboox/app/riddle/RiddleStateMachine.kt"])`

Report `hotspot_score`, any `defect_profile`, and `episodes` before editing. This edit only relocates three constants (same values, no logic change), so a high score here is context, not a blocker — but read it before proceeding regardless.

- [ ] **Step 2: Add the three constants to `Decide.kt`**

Current `Decide.kt:16-21` reads:

```kotlin
data class Decision(val state: RiddleState, val effects: List<Effect>)

/** Milliseconds a single stage of the ink-drinking dissolve holds for. */
internal const val DRINK_STAGE_MS = 70L

/** How long the pen rests before [SendMode.Auto] reads the page as finished. */
internal const val IDLE_COMMIT_MS = 2_800L
```

Change it to:

```kotlin
data class Decision(val state: RiddleState, val effects: List<Effect>)

/** Milliseconds a single stage of the ink-drinking dissolve holds for. */
internal const val DRINK_STAGE_MS = 70L

/** How long the pen rests before [SendMode.Auto] reads the page as finished. */
internal const val IDLE_COMMIT_MS = 2_800L

/** How often the reply pen ticks while a turn is being written out — see [RiddleStateMachine]. */
internal const val REPLY_TICK_MS = 14L

/** Points revealed per [REPLY_TICK_MS] tick while a turn is being written out. */
internal const val REPLY_POINTS_PER_TICK = 26

/**
 * Floor between actual panel refreshes while a turn is being written out —
 * points still reveal every [REPLY_TICK_MS], this just throttles how often
 * that gets pushed to the (much slower) e-ink hardware.
 */
internal const val REPLY_REFRESH_INTERVAL_MS = 120L
```

- [ ] **Step 3: Remove the now-duplicate constants from `RiddleStateMachine.kt`**

Current `RiddleStateMachine.kt:1045-1079`:

```kotlin
    companion object {
        private const val TAG = "RiddleStateMachine"

        /** File-name-safe, collision-free, and meaningless on purpose: a
         * conversation is recognised by its first line, not by its id. */
        private fun newConversationId(): String = UUID.randomUUID().toString()

        private const val TICK_MS = 16L

        private const val REPLY_TICK_MS = 14L
        private const val REPLY_POINTS_PER_TICK = 26

        /**
         * Floor between actual panel refreshes during Replying — points still
         * reveal every [REPLY_TICK_MS], this just throttles how often that
         * gets pushed to the (much slower) e-ink hardware.
         */
        private const val REPLY_REFRESH_INTERVAL_MS = 120L

        /** Where [commitDemoText]'s synthetic "user" writing starts — near the page top, like a real first line. */
        private const val DEMO_TEXT_TOP_PX = 120f

        /** Mid-weight, uniform pressure — a demo pen never presses harder or softer. */
        private const val DEMO_PRESSURE = 0.6f



        /**
         * How many turns of rect-scoped updates the page carries before one
         * full-screen GC clears their accumulated ghosting. Low enough that
         * faint leftovers never build into something readable, high enough
         * that the flash is a rare event rather than punctuation between turns.
         */
        private const val TURNS_PER_FULL_REFRESH = 5
    }
}
```

Change it to:

```kotlin
    companion object {
        private const val TAG = "RiddleStateMachine"

        /** File-name-safe, collision-free, and meaningless on purpose: a
         * conversation is recognised by its first line, not by its id. */
        private fun newConversationId(): String = UUID.randomUUID().toString()

        private const val TICK_MS = 16L

        /** Where [commitDemoText]'s synthetic "user" writing starts — near the page top, like a real first line. */
        private const val DEMO_TEXT_TOP_PX = 120f

        /** Mid-weight, uniform pressure — a demo pen never presses harder or softer. */
        private const val DEMO_PRESSURE = 0.6f

        /**
         * How many turns of rect-scoped updates the page carries before one
         * full-screen GC clears their accumulated ghosting. Low enough that
         * faint leftovers never build into something readable, high enough
         * that the flash is a rare event rather than punctuation between turns.
         */
        private const val TURNS_PER_FULL_REFRESH = 5
    }
}
```

(`REPLY_TICK_MS`, `REPLY_POINTS_PER_TICK`, `REPLY_REFRESH_INTERVAL_MS` and their doc comment are gone from here — they now live in `Decide.kt`, same package, still resolve unqualified.)

- [ ] **Step 4: Build to confirm nothing broke**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL — the three bare identifiers in `RiddleStateMachine.kt` now resolve to the top-level constants in `Decide.kt`.

- [ ] **Step 5: Run the existing riddle test suite**

Run: `./gradlew testDebugUnitTest --tests "com.riddleboox.app.riddle.*"`
Expected: all existing tests still pass (this was a pure relocation, no logic changed).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/riddle/Decide.kt app/src/main/java/com/riddleboox/app/riddle/RiddleStateMachine.kt
git commit -m "refactor: share reply-timing constants via Decide.kt"
```

---

### Task 2: Onboarding content and "seen" flag

**Files:**
- Create: `app/src/main/java/com/riddleboox/app/onboarding/OnboardingScript.kt`
- Create: `app/src/main/java/com/riddleboox/app/settings/OnboardingStore.kt`

**Interfaces:**
- Produces: `com.riddleboox.app.onboarding.ONBOARDING_SEGMENTS: List<String>` (6 elements, fixed order) — consumed by Task 4 (`OnboardingController`) and Task 5 (`MainActivity`).
- Produces: `com.riddleboox.app.settings.OnboardingStore(context: Context)` with `fun read(): Boolean` (default `false`) and `fun write(seen: Boolean)` — consumed by Task 5 (`MainActivity`) and Task 6 (`SettingsActivity`).

No test for `OnboardingStore` — see Global Constraints.

- [ ] **Step 1: Create `OnboardingScript.kt`**

```kotlin
package com.riddleboox.app.onboarding

/**
 * Thứ tự cố định — có tính sư phạm, không random như
 * [com.riddleboox.app.riddle.GREETINGS]. Giọng văn nhất quán với
 * DEFAULT_AGENT_GREETINGS (Agent.kt:282-293): "Ta" xưng hô cổ, gọi người
 * dùng là "ngươi".
 */
val ONBOARDING_SEGMENTS: List<String> = listOf(
    "Ta là một cuốn nhật ký, nhưng không câm lặng như những cuốn khác. " +
        "Ngươi viết bằng bút lên đây, ta sẽ viết lại bằng chính nét chữ của mình.",
    "Ngừng bút một lúc, ta sẽ hiểu là ngươi đã viết xong và tự trả lời. " +
        "Muốn tự tay trao trang cho ta thì chạm vào chữ 'gửi' trên đầu trang.",
    "Chạm 'trang mới' khi ngươi muốn bắt đầu một chuyện khác — " +
        "ta sẽ không mang chuyện cũ ra so sánh, dù vẫn nhớ nó.",
    "Mỗi buổi trò chuyện đều được ta cất lại. Chạm 'lịch sử' để tìm và mở lại " +
        "bất cứ trang nào ngươi từng viết.",
    "Ta có nhiều gương mặt khác nhau — có gương mặt còn đọc được cả những cuốn sách " +
        "ngươi đang đọc dở. Chạm vào tên ở góc phải để chọn ai sẽ lắng nghe ngươi.",
    "Nếu chữ ta viết ra quá nhỏ, hay ngươi muốn đổi nơi ta lấy trí khôn của mình, " +
        "mọi thứ đều nằm trong 'settings'.",
)
```

- [ ] **Step 2: Create `OnboardingStore.kt`**

```kotlin
package com.riddleboox.app.settings

import android.content.Context

/** Đã xem hết phần giới thiệu lần mở app đầu tiên chưa — cùng pattern [SendModeStore]. */
class OnboardingStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(): Boolean = prefs.getBoolean(KEY_SEEN, false)

    fun write(seen: Boolean) {
        prefs.edit().putBoolean(KEY_SEEN, seen).apply()
    }

    private companion object {
        const val FILE = "onboarding"
        const val KEY_SEEN = "seen"
    }
}
```

- [ ] **Step 3: Build to confirm both files compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/onboarding/OnboardingScript.kt app/src/main/java/com/riddleboox/app/settings/OnboardingStore.kt
git commit -m "feat: add onboarding content and seen-flag store"
```

---

### Task 3: Pure onboarding decision logic (TDD)

**Files:**
- Create: `app/src/main/java/com/riddleboox/app/onboarding/OnboardingState.kt`
- Create: `app/src/main/java/com/riddleboox/app/onboarding/OnboardingDecide.kt`
- Test: `app/src/test/java/com/riddleboox/app/onboarding/DecideOnboardingTest.kt`

**Interfaces:**
- Consumes: nothing (pure, no dependency on Tasks 1-2).
- Produces: `sealed class OnboardingState` (`Writing(segmentIndex: Int)`, `Holding(segmentIndex: Int, holdUntilMs: Long)`, `Done`); `data class OnboardingDecision(val state: OnboardingState, val advance: Boolean, val finished: Boolean)`; `fun decideOnboarding(state: OnboardingState, now: Long, caughtUp: Boolean, totalSegments: Int): OnboardingDecision` — all consumed by Task 4 (`OnboardingController`).

- [ ] **Step 1: Create `OnboardingState.kt`**

```kotlin
package com.riddleboox.app.onboarding

/**
 * Where the onboarding sequence is: writing one segment out, holding on a
 * finished one so it can be read, or done. See
 * `docs/superpowers/specs/2026-08-21-onboarding-design.md` §6.1.
 */
sealed class OnboardingState {

    /** Đang viết đoạn [segmentIndex] (0-based); reveal cursor còn strokes chưa lộ. */
    data class Writing(val segmentIndex: Int) : OnboardingState()

    /** Đoạn [segmentIndex] đã viết xong, đứng yên tới [holdUntilMs] rồi mới sang đoạn kế. */
    data class Holding(val segmentIndex: Int, val holdUntilMs: Long) : OnboardingState()

    /** Đã chạy hết toàn bộ segments. */
    object Done : OnboardingState()
}
```

- [ ] **Step 2: Write the failing test — `DecideOnboardingTest.kt`**

```kotlin
package com.riddleboox.app.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DecideOnboardingTest {

    private val totalSegments = 3

    @Test
    fun `writing not caught up leaves state unchanged`() {
        val state = OnboardingState.Writing(0)
        val decision = decideOnboarding(state, now = 100, caughtUp = false, totalSegments = totalSegments)

        assertSame(state, decision.state)
        assertTrue(!decision.advance)
        assertTrue(!decision.finished)
    }

    @Test
    fun `writing caught up starts a hold`() {
        val decision = decideOnboarding(OnboardingState.Writing(0), now = 1_000, caughtUp = true, totalSegments = totalSegments)

        val holding = decision.state as OnboardingState.Holding
        assertEquals(0, holding.segmentIndex)
        assertEquals(1_000 + ONBOARDING_HOLD_MS, holding.holdUntilMs)
        assertTrue(!decision.advance)
        assertTrue(!decision.finished)
    }

    @Test
    fun `holding before its deadline leaves state unchanged`() {
        val state = OnboardingState.Holding(segmentIndex = 0, holdUntilMs = 5_000)
        val decision = decideOnboarding(state, now = 4_999, caughtUp = true, totalSegments = totalSegments)

        assertSame(state, decision.state)
        assertTrue(!decision.advance)
        assertTrue(!decision.finished)
    }

    @Test
    fun `holding past its deadline advances to the next segment`() {
        val state = OnboardingState.Holding(segmentIndex = 0, holdUntilMs = 5_000)
        val decision = decideOnboarding(state, now = 5_000, caughtUp = true, totalSegments = totalSegments)

        assertEquals(OnboardingState.Writing(1), decision.state)
        assertTrue(decision.advance)
        assertTrue(!decision.finished)
    }

    @Test
    fun `holding past its deadline on the last segment finishes instead of advancing`() {
        val state = OnboardingState.Holding(segmentIndex = totalSegments - 1, holdUntilMs = 5_000)
        val decision = decideOnboarding(state, now = 5_000, caughtUp = true, totalSegments = totalSegments)

        assertEquals(OnboardingState.Done, decision.state)
        assertTrue(!decision.advance)
        assertTrue(decision.finished)
    }

    @Test
    fun `done always reports finished`() {
        val decision = decideOnboarding(OnboardingState.Done, now = 9_999, caughtUp = true, totalSegments = totalSegments)

        assertEquals(OnboardingState.Done, decision.state)
        assertTrue(decision.finished)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero segments is a programming error`() {
        decideOnboarding(OnboardingState.Writing(0), now = 0, caughtUp = true, totalSegments = 0)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.riddleboox.app.onboarding.DecideOnboardingTest"`
Expected: FAIL to compile — `decideOnboarding`, `OnboardingDecision`, `ONBOARDING_HOLD_MS` don't exist yet.

- [ ] **Step 4: Create `OnboardingDecide.kt`**

```kotlin
package com.riddleboox.app.onboarding

/** How long a finished segment stands before the next one begins. */
internal const val ONBOARDING_HOLD_MS = 4_000L

/**
 * What one tick of onboarding came to: where it is now ([state]), whether
 * the shell should clear the page and start the next segment ([advance]),
 * and whether the whole sequence is over ([finished]).
 */
data class OnboardingDecision(val state: OnboardingState, val advance: Boolean, val finished: Boolean)

/**
 * @param caughtUp whether the current segment's [com.riddleboox.app.handwriting.ReplyRevealCursor]
 * has revealed every stroke.
 */
fun decideOnboarding(
    state: OnboardingState,
    now: Long,
    caughtUp: Boolean,
    totalSegments: Int,
): OnboardingDecision {
    require(totalSegments > 0) { "Onboarding cần ít nhất một đoạn." }
    return when (state) {
        is OnboardingState.Writing -> {
            if (!caughtUp) return OnboardingDecision(state, advance = false, finished = false)
            val holding = OnboardingState.Holding(state.segmentIndex, holdUntilMs = now + ONBOARDING_HOLD_MS)
            OnboardingDecision(holding, advance = false, finished = false)
        }
        is OnboardingState.Holding -> {
            if (now < state.holdUntilMs) return OnboardingDecision(state, advance = false, finished = false)
            val next = state.segmentIndex + 1
            if (next >= totalSegments) {
                OnboardingDecision(OnboardingState.Done, advance = false, finished = true)
            } else {
                OnboardingDecision(OnboardingState.Writing(next), advance = true, finished = false)
            }
        }
        OnboardingState.Done -> OnboardingDecision(state, advance = false, finished = true)
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.riddleboox.app.onboarding.DecideOnboardingTest"`
Expected: PASS, all 7 tests green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/onboarding/OnboardingState.kt app/src/main/java/com/riddleboox/app/onboarding/OnboardingDecide.kt app/src/test/java/com/riddleboox/app/onboarding/DecideOnboardingTest.kt
git commit -m "feat: add pure onboarding decision logic"
```

---

### Task 4: `OnboardingController` shell

**Files:**
- Create: `app/src/main/java/com/riddleboox/app/onboarding/OnboardingController.kt`

**Interfaces:**
- Consumes: `ONBOARDING_SEGMENTS` (Task 2), `OnboardingState`/`decideOnboarding` (Task 3), `com.riddleboox.app.riddle.{REPLY_TOP_PX, REPLY_BOTTOM_PX, REPLY_POINTS_PER_TICK, REPLY_REFRESH_INTERVAL_MS, Ticker, PageRenderState, writeBounds}` (Task 1 / pre-existing), `com.riddleboox.app.handwriting.{HandwritingPlanner, WriteCursor, ReplyRevealCursor}` (pre-existing), `com.riddleboox.app.ui.RegionView`, `com.riddleboox.app.ink.EinkRefresher` (pre-existing).
- Produces: `class OnboardingController(segments, regionView, refresher, ticker, handwritingPlanner, replyFontSizePx, pageWidthPx, onDone)` with `fun start()` / `fun stop()` — consumed by Task 5 (`MainActivity`).

No test for this class — matches the existing convention that shell classes wiring pure decisions to real Android objects (`RiddleStateMachine` itself) have no unit test in this repo; only the pure decision functions do (Task 3).

- [ ] **Step 1: Create `OnboardingController.kt`**

```kotlin
package com.riddleboox.app.onboarding

import android.util.Log
import com.riddleboox.app.handwriting.HandwritingPlanner
import com.riddleboox.app.handwriting.ReplyRevealCursor
import com.riddleboox.app.handwriting.WriteCursor
import com.riddleboox.app.ink.EinkRefresher
import com.riddleboox.app.riddle.PageRenderState
import com.riddleboox.app.riddle.REPLY_BOTTOM_PX
import com.riddleboox.app.riddle.REPLY_POINTS_PER_TICK
import com.riddleboox.app.riddle.REPLY_REFRESH_INTERVAL_MS
import com.riddleboox.app.riddle.REPLY_TOP_PX
import com.riddleboox.app.riddle.Ticker
import com.riddleboox.app.riddle.writeBounds
import com.riddleboox.app.ui.RegionView

/**
 * Runs the first-run introduction: writes [segments] out one at a time, in
 * the diary's own hand, with no pen input and no network — see
 * `docs/superpowers/specs/2026-08-21-onboarding-design.md` §6.3.
 *
 * Deliberately separate from [com.riddleboox.app.riddle.RiddleStateMachine]:
 * that class is scoped to the real Listening/Drinking/Thinking/Replying
 * conversation loop, and this needs none of it — no conversation, no ink
 * capture, no network.
 */
class OnboardingController(
    private val segments: List<String>,
    private val regionView: RegionView,
    private val refresher: EinkRefresher,
    private val ticker: Ticker,
    private val handwritingPlanner: HandwritingPlanner,
    private val replyFontSizePx: Float,
    private val pageWidthPx: () -> Int,
    private val onDone: () -> Unit,
) {
    init {
        require(segments.isNotEmpty()) { "Onboarding cần ít nhất một đoạn." }
    }

    private var state: OnboardingState = OnboardingState.Writing(0)
    private var replyCursor: ReplyRevealCursor? = null
    private var lastRefreshAtMs: Long = 0L
    private var started = false

    fun start() {
        ticker.start(TICK_MS, ::tick)
    }

    fun stop() {
        ticker.stop()
    }

    private fun tick() {
        val now = ticker.nowMs()
        if (!started) {
            // Layout may not have measured yet right after onCreate/onResume —
            // same guard RiddleStateMachine.tickListening uses for greet().
            if (pageWidthPx() <= 0) return
            started = true
            beginSegment(0)
        }
        when (val s = state) {
            is OnboardingState.Writing -> tickWriting(s, now)
            is OnboardingState.Holding -> tickHolding(s, now)
            OnboardingState.Done -> Unit
        }
    }

    private fun beginSegment(index: Int) {
        val cursor: WriteCursor = handwritingPlanner.cursor(
            pageWidthPx = pageWidthPx(),
            fontSizePx = replyFontSizePx,
            lineHeightPx = replyFontSizePx * HandwritingPlanner.LINE_HEIGHT_RATIO,
            startYPx = REPLY_TOP_PX,
            bottomLimitPx = regionView.drawingRect().height - REPLY_BOTTOM_PX,
        )
        val reveal = ReplyRevealCursor()
        replyCursor = reveal
        regionView.beginReply()
        reveal.add(cursor.append(segments[index]))
        if (cursor.pageFull) {
            Log.w(TAG, "onboarding segment $index tràn quá một trang — rút ngắn nội dung")
        }
        lastRefreshAtMs = 0L
        state = OnboardingState.Writing(index)
    }

    private fun tickWriting(s: OnboardingState.Writing, now: Long) {
        val cursor = replyCursor ?: return
        val newlyRevealed = cursor.revealMore(REPLY_POINTS_PER_TICK)
        if (newlyRevealed.isNotEmpty()) {
            regionView.appendReplyStrokes(newlyRevealed, writeBounds(newlyRevealed) ?: regionView.drawingRect())
        }
        if (now - lastRefreshAtMs >= REPLY_REFRESH_INTERVAL_MS || (cursor.caughtUp && newlyRevealed.isNotEmpty())) {
            refresher.requestHandwritingRefresh(regionView, regionView.drawingRect())
            lastRefreshAtMs = now
        }
        state = decideOnboarding(s, now, caughtUp = cursor.caughtUp, totalSegments = segments.size).state
    }

    private fun tickHolding(s: OnboardingState.Holding, now: Long) {
        val decision = decideOnboarding(s, now, caughtUp = true, totalSegments = segments.size)
        state = decision.state
        if (decision.finished) {
            finish()
            return
        }
        if (decision.advance) {
            regionView.clearReplyLayer()
            regionView.render(PageRenderState.EMPTY)
            refresher.requestFullRefresh(regionView)
            beginSegment((state as OnboardingState.Writing).segmentIndex)
        }
    }

    private fun finish() {
        ticker.stop()
        regionView.clearReplyLayer()
        regionView.render(PageRenderState.EMPTY)
        refresher.requestFullRefresh(regionView)
        onDone()
    }

    private companion object {
        const val TAG = "OnboardingController"
        const val TICK_MS = 16L
    }
}
```

- [ ] **Step 2: Build to confirm it compiles against the real APIs**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If it fails on `WriteCursor`/`ReplyRevealCursor`/`RegionView`/`EinkRefresher` member names, re-check current signatures with `Read` on `app/src/main/java/com/riddleboox/app/handwriting/WriteCursor.kt`, `ReplyRevealCursor.kt`, `app/src/main/java/com/riddleboox/app/ui/RegionView.kt`, `app/src/main/java/com/riddleboox/app/ink/EinkRefresher.kt` before changing anything blind.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/onboarding/OnboardingController.kt
git commit -m "feat: add OnboardingController shell"
```

---

### Task 5: Wire onboarding into `MainActivity`

**Files:**
- Modify: `app/src/main/java/com/riddleboox/app/MainActivity.kt`

**Interfaces:**
- Consumes: `OnboardingStore` (Task 2), `ONBOARDING_SEGMENTS` (Task 2), `OnboardingController` (Task 4).

- [ ] **Step 1: Add imports**

`MainActivity.kt:44` currently reads:

```kotlin
import com.riddleboox.app.riddle.idleStatus
import com.riddleboox.app.settings.ReplyFontSizeStore
```

Change to:

```kotlin
import com.riddleboox.app.riddle.idleStatus
import com.riddleboox.app.onboarding.ONBOARDING_SEGMENTS
import com.riddleboox.app.onboarding.OnboardingController
import com.riddleboox.app.settings.OnboardingStore
import com.riddleboox.app.settings.ReplyFontSizeStore
```

- [ ] **Step 2: Add fields**

`MainActivity.kt:93-95` currently reads:

```kotlin
    /** State-machine input gate, kept separately from the temporary Activity lifecycle gate. */
    private var diaryBusy = false
```

Change to:

```kotlin
    /** State-machine input gate, kept separately from the temporary Activity lifecycle gate. */
    private var diaryBusy = false
    /** Non-null only while the first-run introduction hasn't finished yet. */
    private var onboardingController: OnboardingController? = null
    /** Whether the first-run introduction has already run; gates [onResume]/[onPause]. */
    private var onboardingSeen = true
```

- [ ] **Step 3: Build `OnboardingController` right after `stateMachine`, hide chrome while unseen**

`MainActivity.kt:251-279` currently reads (ending right before the `relimit` layout-listener comment):

```kotlin
        stateMachine = RiddleStateMachine(
            strokeStore = strokeStore,
            inkCapture = inkCapture,
            regionView = regionView,
            refresher = refresher,
            ticker = HandlerTicker(),
            replySettings = replySettings,
            agent = selectedAgent,
            toolbox = agentToolbox(replySettings),
            handwritingPlanner = handwritingPlanner,
            replyFontSizePx = replyFontSize.px,
            conversationStore = conversationStore,
            // Debug builds only, and deliberately in external files: this
            // exists so the writer can open the page on the tablet itself and
            // see what the diary was handed.
            pageArchive = if (BuildConfig.DEBUG) {
                getExternalFilesDir("pages")?.let { PageArchive(it) }
            } else {
                null
            },
            initialSendMode = sendMode,
            pageWidthPx = { penSurface.width },
            onStatusChanged = { statusView.text = it },
            onBusyChanged = { busy ->
                diaryBusy = busy
                stopLabel.visibility = if (busy) View.VISIBLE else View.GONE
            },
        )

        // Layout runs whenever the chrome re-measures — the status caption
```

Insert between the closing `)` of `stateMachine` and the `// Layout runs...` comment:

```kotlin
        stateMachine = RiddleStateMachine(
            strokeStore = strokeStore,
            inkCapture = inkCapture,
            regionView = regionView,
            refresher = refresher,
            ticker = HandlerTicker(),
            replySettings = replySettings,
            agent = selectedAgent,
            toolbox = agentToolbox(replySettings),
            handwritingPlanner = handwritingPlanner,
            replyFontSizePx = replyFontSize.px,
            conversationStore = conversationStore,
            // Debug builds only, and deliberately in external files: this
            // exists so the writer can open the page on the tablet itself and
            // see what the diary was handed.
            pageArchive = if (BuildConfig.DEBUG) {
                getExternalFilesDir("pages")?.let { PageArchive(it) }
            } else {
                null
            },
            initialSendMode = sendMode,
            pageWidthPx = { penSurface.width },
            onStatusChanged = { statusView.text = it },
            onBusyChanged = { busy ->
                diaryBusy = busy
                stopLabel.visibility = if (busy) View.VISIBLE else View.GONE
            },
        )

        val onboardingStore = OnboardingStore(this)
        onboardingSeen = onboardingStore.read()
        if (!onboardingSeen) {
            // Full-screen intro: no controls to tap into mid-sequence.
            chromeRow.visibility = View.GONE
            onboardingController = OnboardingController(
                segments = ONBOARDING_SEGMENTS,
                regionView = regionView,
                refresher = refresher,
                ticker = HandlerTicker(),
                handwritingPlanner = handwritingPlanner,
                replyFontSizePx = replyFontSize.px,
                pageWidthPx = { penSurface.width },
                onDone = {
                    onboardingStore.write(true)
                    onboardingSeen = true
                    chromeRow.visibility = View.VISIBLE
                    inkCapture.setInputEnabled(!diaryBusy)
                    stateMachine.start()
                },
            )
        }

        // Layout runs whenever the chrome re-measures — the status caption
```

- [ ] **Step 4: Branch `onResume` on `onboardingSeen`**

`MainActivity.kt:537-556` currently reads:

```kotlin
    override fun onResume() {
        super.onResume()
        maybeAttach()
        // Keep the raw-drawing session alive while a child Activity is open.
        // Recreating TouchHelper on every return makes the Onyx SDK perform
        // another full-panel refresh. The lifecycle pause temporarily forces
        // input off; [diaryBusy] restores the state-machine gate without
        // opening the pen during an in-flight reply.
        inkCapture.setInputEnabled(!diaryBusy)
        stateMachine.start()
        resumePending()
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this,
                demoWriteReceiver,
                IntentFilter(ACTION_DEMO_WRITE),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }
```

Change to:

```kotlin
    override fun onResume() {
        super.onResume()
        maybeAttach()
        // Keep the raw-drawing session alive while a child Activity is open.
        // Recreating TouchHelper on every return makes the Onyx SDK perform
        // another full-panel refresh. The lifecycle pause temporarily forces
        // input off; [diaryBusy] restores the state-machine gate without
        // opening the pen during an in-flight reply. The pen stays shut for
        // the whole of onboarding regardless of [diaryBusy].
        inkCapture.setInputEnabled(!diaryBusy && onboardingSeen)
        if (onboardingSeen) {
            stateMachine.start()
            resumePending()
        } else {
            onboardingController?.start()
        }
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this,
                demoWriteReceiver,
                IntentFilter(ACTION_DEMO_WRITE),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }
```

- [ ] **Step 5: Branch `onPause` on `onboardingSeen`**

`MainActivity.kt:558-567` currently reads:

```kotlin
    override fun onPause() {
        if (BuildConfig.DEBUG) unregisterReceiver(demoWriteReceiver)
        penSurface.removeCallbacks(attachRetry)
        stateMachine.stop()
        // Losing focus is temporary when Settings, Agents, or History is on
        // top. Disable input without closing the raw session so returning does
        // not recreate the TouchHelper and trigger avoidable E-Ink refreshes.
        inkCapture.setInputEnabled(false)
        super.onPause()
    }
```

Change to:

```kotlin
    override fun onPause() {
        if (BuildConfig.DEBUG) unregisterReceiver(demoWriteReceiver)
        penSurface.removeCallbacks(attachRetry)
        if (onboardingSeen) stateMachine.stop() else onboardingController?.stop()
        // Losing focus is temporary when Settings, Agents, or History is on
        // top. Disable input without closing the raw session so returning does
        // not recreate the TouchHelper and trigger avoidable E-Ink refreshes.
        inkCapture.setInputEnabled(false)
        super.onPause()
    }
```

- [ ] **Step 6: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Install and manually verify on-device or emulator (this is Activity wiring — no unit test covers it in this repo, matching how `RiddleStateMachine`'s own wiring in `MainActivity` has none)**

Run: `./gradlew :app:installDebug`
Then, with the app's data cleared (`adb shell pm clear com.riddleboox.app` — confirm the actual applicationId in `app/build.gradle.kts` first, do not guess it) or on a fresh install, open the app and confirm: chrome is hidden, the 6 segments write out and hold in order with the pen inert (touching the panel does nothing), and the app lands on a normal empty page with a greeting afterward, chrome visible, pen working. Report the actual observed applicationId and command used — do not claim this step passed without having run it.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/MainActivity.kt
git commit -m "feat: run onboarding on first launch"
```

---

### Task 6: "giới thiệu lại" row in Settings

**Files:**
- Modify: `app/src/main/java/com/riddleboox/app/settings/SettingsActivity.kt`

**Interfaces:**
- Consumes: `OnboardingStore` (Task 2 — same package, no import needed).

- [ ] **Step 1: Instantiate the store and add the row**

`SettingsActivity.kt:91-106` currently reads:

```kotlin
        sendModeStore = SendModeStore(this)
        val currentSendMode = sendModeStore.read()
        loadedSendMode = currentSendMode
        chosenSendMode = currentSendMode
        sendModeField = chooserField(currentSendMode.label) { pickSendMode() }

        libraryField = statusField()
        val column = textBlock().apply {
            addView(field("base url", baseUrlField))
            addView(field("api key", apiKeyField))
            addView(field("model", modelField))
            addView(field("cỡ chữ trả lời", fontSizeField))
            addView(fontSizePreview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(140)))
            addView(field("chế độ gửi", sendModeField))
            addView(field("sách trên máy", libraryField))
        }
```

Change to:

```kotlin
        sendModeStore = SendModeStore(this)
        val currentSendMode = sendModeStore.read()
        loadedSendMode = currentSendMode
        chosenSendMode = currentSendMode
        sendModeField = chooserField(currentSendMode.label) { pickSendMode() }

        val onboardingStore = OnboardingStore(this)

        libraryField = statusField()
        val column = textBlock().apply {
            addView(field("base url", baseUrlField))
            addView(field("api key", apiKeyField))
            addView(field("model", modelField))
            addView(field("cỡ chữ trả lời", fontSizeField))
            addView(fontSizePreview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(140)))
            addView(field("chế độ gửi", sendModeField))
            addView(field("sách trên máy", libraryField))
            // Không tự finish() ở đây — gọi lại save() để không đánh mất các
            // field khác đang sửa dở trên cùng màn hình.
            addView(field("giới thiệu", chooserField("chạm để xem lại") {
                onboardingStore.write(false)
                save()
            }))
        }
```

`OnboardingStore` is in the same `com.riddleboox.app.settings` package as `SettingsActivity`, so no new import is needed.

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manually verify**

Run: `./gradlew :app:installDebug`, open Settings, tap "giới thiệu" → "chạm để xem lại", confirm Settings closes and the app relaunches straight into the onboarding sequence (Task 5, Step 7's flow). Report what was actually observed.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/settings/SettingsActivity.kt
git commit -m "feat: add replay-onboarding row to Settings"
```

---

### Task 8: Lighter refresh between onboarding segments

**Files:**
- Modify: `app/src/main/java/com/riddleboox/app/onboarding/OnboardingController.kt`

**Interfaces:** no signature changes — internal behavior only.

Added after direct user feedback: `refresher.requestFullRefresh(regionView)` (a GC16 flash) fires once per segment transition — 5 times across 6 segments — which reads as jarring at that cadence. Spec §6.4 now calls for `refresher.requestQualityPartialRefresh(...)` between segments instead (full grayscale redraw of the region, no flash), while `finish()`'s one-time full refresh at the very end of the sequence is unchanged (a single flash at a genuine "done" boundary is fine — the complaint was about the repeated mid-sequence flash, not this one).

- [ ] **Step 1: Change the between-segment refresh call**

In `OnboardingController.kt`, find `tickHolding`'s `advance` branch. It currently reads:

```kotlin
        if (decision.advance) {
            regionView.clearReplyLayer()
            regionView.render(PageRenderState.EMPTY)
            refresher.requestFullRefresh(regionView)
            beginSegment((state as OnboardingState.Writing).segmentIndex)
        }
```

Change it to:

```kotlin
        if (decision.advance) {
            regionView.clearReplyLayer()
            regionView.render(PageRenderState.EMPTY)
            // Not requestFullRefresh: this fires once per segment (5 times
            // across 6 segments), and the GC16 flash that clears ghosting is
            // jarring at that cadence. Quality mode redraws the whole region
            // clean, without the flash.
            refresher.requestQualityPartialRefresh(regionView, regionView.drawingRect())
            beginSegment((state as OnboardingState.Writing).segmentIndex)
        }
```

Leave `finish()`'s `refresher.requestFullRefresh(regionView)` untouched — that is the one-time transition out of onboarding entirely, not a between-segment switch, and is intentionally unchanged (spec §6.4).

Confirm `EinkRefresher.requestQualityPartialRefresh(view: View, area: PageRect)` exists with this signature before editing — `Read` `app/src/main/java/com/riddleboox/app/ink/EinkRefresher.kt` if you have any doubt; it was confirmed present during the original design research (`requestQualityPartialRefresh(view: View, area: PageRect)`).

- [ ] **Step 2: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/onboarding/OnboardingController.kt
git commit -m "fix: use quality refresh instead of full flash between onboarding segments"
```

---

### Task 9: Welcome screen with a "bắt đầu" (start) button

**Files:**
- Create: `app/src/main/java/com/riddleboox/app/onboarding/WelcomeOverlay.kt`
- Modify: `app/src/main/java/com/riddleboox/app/MainActivity.kt`

**Interfaces:**
- Produces: `fun welcomeOverlay(context: Context, onStart: (View) -> Unit): View` — a full-screen opaque view with a large title and a bordered "bắt đầu" button; calls `onStart(overlay)` exactly once, on tap, passing itself so the caller can remove it.
- Consumes (in `MainActivity.kt`): the `onboardingController`, `onboardingSeen` fields Task 5 added, plus a new field `onboardingStarted: Boolean`.

Added after direct user feedback: onboarding no longer auto-starts the moment the app opens. Instead, a full-screen welcome view with a large title and a start button appears first (pen already locked underneath, per Task 5's wiring); tapping the button removes the welcome view and starts the auto-playing sequence exactly as already built (Tasks 1-8 unchanged from here on).

- [ ] **Step 1: Create `WelcomeOverlay.kt`**

```kotlin
package com.riddleboox.app.onboarding

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.riddleboox.app.ui.dp

/**
 * Toàn màn hình, đè lên mọi thứ khác — trang giấy và bút bên dưới đã bị khoá
 * (xem MainActivity), đây chỉ là thứ khiến điều đó hiện ra trước khi có chữ
 * nào được viết. [onStart] gọi đúng một lần, từ cú chạm nút, nhận lại chính
 * overlay để nơi gọi tự gỡ nó khỏi cây view.
 */
fun welcomeOverlay(context: Context, onStart: (View) -> Unit): View {
    lateinit var overlay: View
    val title = TextView(context).apply {
        text = "Chào mừng"
        textSize = 40f
        typeface = Typeface.create("serif", Typeface.BOLD)
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
    }
    val startButton = TextView(context).apply {
        text = "bắt đầu"
        textSize = 20f
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        setPadding(dp(48), dp(16), dp(48), dp(16))
        background = GradientDrawable().apply {
            setStroke(dp(2), Color.BLACK)
            setColor(Color.TRANSPARENT)
        }
        setOnClickListener { onStart(overlay) }
    }
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(title)
        addView(startButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(48) })
    }
    overlay = FrameLayout(context).apply {
        setBackgroundColor(Color.WHITE)
        addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
    }
    return overlay
}
```

Confirm `com.riddleboox.app.ui.dp(value: Int): Int` exists with this signature before relying on it — it is already imported and used extensively in `MainActivity.kt` and `SettingsActivity.kt` (e.g. `dp(24)`, `dp(6)`); `Read` `app/src/main/java/com/riddleboox/app/ui/Paper.kt` if you have any doubt about its exact signature or return type.

- [ ] **Step 2: Add the `onboardingStarted` field to `MainActivity.kt`**

Find the two fields Task 5 added (search for `onboardingController` and `onboardingSeen` — they sit together, right after `diaryBusy`):

```kotlin
    /** Non-null only while the first-run introduction hasn't finished yet. */
    private var onboardingController: OnboardingController? = null
    /** Whether the first-run introduction has already run; gates [onResume]/[onPause]. */
    private var onboardingSeen = true
```

Change to:

```kotlin
    /** Non-null only while the first-run introduction hasn't finished yet. */
    private var onboardingController: OnboardingController? = null
    /** Whether the first-run introduction has already run; gates [onResume]/[onPause]. */
    private var onboardingSeen = true
    /** Whether the "bắt đầu" button on the welcome screen has been tapped yet. */
    private var onboardingStarted = false
```

- [ ] **Step 3: Add the import**

Add `import com.riddleboox.app.onboarding.welcomeOverlay` alongside the other `com.riddleboox.app.onboarding.*` imports Task 5 added (`ONBOARDING_SEGMENTS`, `OnboardingController`).

- [ ] **Step 4: Show the welcome overlay instead of auto-starting**

Find the block Task 5 added right after `stateMachine = RiddleStateMachine(...)` (search for `OnboardingStore(this)`):

```kotlin
        val onboardingStore = OnboardingStore(this)
        onboardingSeen = onboardingStore.read()
        if (!onboardingSeen) {
            // Full-screen intro: no controls to tap into mid-sequence.
            chromeRow.visibility = View.GONE
            onboardingController = OnboardingController(
                segments = ONBOARDING_SEGMENTS,
                regionView = regionView,
                refresher = refresher,
                ticker = HandlerTicker(),
                handwritingPlanner = handwritingPlanner,
                replyFontSizePx = replyFontSize.px,
                pageWidthPx = { penSurface.width },
                onDone = {
                    onboardingStore.write(true)
                    onboardingSeen = true
                    chromeRow.visibility = View.VISIBLE
                    inkCapture.setInputEnabled(!diaryBusy)
                    stateMachine.start()
                },
            )
        }
```

Change it to add the welcome overlay after the controller is built (the controller must exist before the button can start it):

```kotlin
        val onboardingStore = OnboardingStore(this)
        onboardingSeen = onboardingStore.read()
        if (!onboardingSeen) {
            // Full-screen intro: no controls to tap into mid-sequence.
            chromeRow.visibility = View.GONE
            onboardingController = OnboardingController(
                segments = ONBOARDING_SEGMENTS,
                regionView = regionView,
                refresher = refresher,
                ticker = HandlerTicker(),
                handwritingPlanner = handwritingPlanner,
                replyFontSizePx = replyFontSize.px,
                pageWidthPx = { penSurface.width },
                onDone = {
                    onboardingStore.write(true)
                    onboardingSeen = true
                    chromeRow.visibility = View.VISIBLE
                    inkCapture.setInputEnabled(!diaryBusy)
                    stateMachine.start()
                },
            )
            root.addView(
                welcomeOverlay(this) { overlay ->
                    root.removeView(overlay)
                    onboardingStarted = true
                    onboardingController?.start()
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
```

`root` is the `FrameLayout` local `val` built earlier in `onCreate` (holding `penSurface`, `regionView`, `chromeRow`) and passed to `setContentView(root)` — it is still in scope at this point in the same function, so no new field is needed to reach it.

- [ ] **Step 5: Gate `onResume`'s start call on `onboardingStarted`**

Find the branch Task 5 added in `onResume`:

```kotlin
        inkCapture.setInputEnabled(!diaryBusy && onboardingSeen)
        if (onboardingSeen) {
            stateMachine.start()
            resumePending()
        } else {
            onboardingController?.start()
        }
```

Change to:

```kotlin
        inkCapture.setInputEnabled(!diaryBusy && onboardingSeen)
        if (onboardingSeen) {
            stateMachine.start()
            resumePending()
        } else if (onboardingStarted) {
            onboardingController?.start()
        }
```

(When `!onboardingSeen && !onboardingStarted`, the welcome screen is still showing — there is nothing to start yet, and the pen is already locked by the `inkCapture.setInputEnabled` line above regardless.)

- [ ] **Step 6: Gate `onPause`'s stop call on `onboardingStarted`**

Find the branch Task 5 added in `onPause`:

```kotlin
        if (onboardingSeen) stateMachine.stop() else onboardingController?.stop()
```

Change to:

```kotlin
        if (onboardingSeen) stateMachine.stop() else if (onboardingStarted) onboardingController?.stop()
```

- [ ] **Step 7: Build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Manually verify (no device available — disclose this, don't claim it passed)**

Report that this step needs a device/emulator to actually confirm: welcome screen appears full-screen with "Chào mừng" large and centered, "bắt đầu" button below it with a visible border; tapping it removes the welcome screen and the first onboarding segment begins writing; pen remains inert throughout (both on the welcome screen and during the sequence); backgrounding the app while the welcome screen is still showing and returning leaves the welcome screen exactly as it was (not skipped, not restarted into the sequence).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/riddleboox/app/onboarding/WelcomeOverlay.kt app/src/main/java/com/riddleboox/app/MainActivity.kt
git commit -m "feat: add welcome screen with start button before onboarding"
```

---

### Task 10: Final verification

**Files:** none (verification only).

- [ ] **Step 1: Full unit test suite**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass, including the 7 `DecideOnboardingTest` cases and every pre-existing `riddle` test unaffected by Task 1's relocation.

- [ ] **Step 2: Score the change before calling it done**

`detect_changes()` (GitNexus) is likewise not connected — use `mcp__repowise__get_change_risk()` (omit `revspec` to score the uncommitted working tree; this repo has zero commits per `git log`, so there is no `base..head` range to diff against). Confirm the affected files match exactly: `riddle/Decide.kt`, `riddle/RiddleStateMachine.kt` (constants only), `MainActivity.kt`, `settings/SettingsActivity.kt`, and the new `onboarding/*` + `settings/OnboardingStore.kt` files. Report anything outside this list before calling the feature done.

- [ ] **Step 3: Spec cross-check**

Re-read `docs/superpowers/specs/2026-08-21-onboarding-design.md` §1-11 against the final diff. Confirm every section has a corresponding implemented piece (content §4 → Task 2, shared constants §5 → Task 1, state/decide/controller §6 → Tasks 3-4/8, `MainActivity` §7.2 → Task 5/9, welcome screen §7.1 → Task 9, `SettingsActivity` §8 → Task 6, `OnboardingStore` §9 → Task 2, testing §10 → Tasks 3/10). Report the mapping, not just "looks done."
