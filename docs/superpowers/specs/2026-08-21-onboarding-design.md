# Onboarding — thiết kế

**Ngày:** 2026-08-21
**Trạng thái:** đã duyệt qua thảo luận theo từng phần (kiến trúc, nội dung, data flow/timing) + `/goal` yêu cầu hoàn thành tự động; không có review riêng trên file này trước khi implement — xem "Ghi chú duyệt" cuối file.

## 1. Mục tiêu

Lần đầu tiên app được mở (chưa từng hoàn tất onboarding), thay vì mở thẳng vào trang trống với `greet()`, app hiện một màn hình chào mừng toàn màn hình với tiêu đề lớn và nút "bắt đầu" (mục 7.1) — chạm vào đó mới khởi động chuỗi 6 đoạn text giới thiệu tính năng, mỗi đoạn được "viết tay" lên trang bằng đúng cơ chế app dùng để viết reply, tự động chuyển sang đoạn kế sau một khoảng chờ, không cần bút chạm vào. Bút bị khoá hoàn toàn từ màn hình chào mừng cho tới khi chuỗi giới thiệu xong. Người dùng có thể xem lại onboarding bất cứ lúc nào qua một dòng trong Settings.

## 2. Ngoài phạm vi (out of scope)

- Không cho phép ngắt/skip bằng bút giữa chừng (bút khoá cứng — đã chốt qua `AskUserQuestion`). Nút "bắt đầu" (mục 7.1) chỉ *khởi động* chuỗi 6 đoạn, không phải cơ chế skip — sau khi chạm, mọi thứ chạy tự động y hệt thiết kế ban đầu, không có nút "bỏ qua" nào giữa các đoạn.
- Không random thứ tự đoạn (khác `Greetings.kt`) — thứ tự cố định, có tính sư phạm.
- Không lưu tiến độ dở dang nếu app bị kill giữa chừng — mở lại chạy lại từ đầu.
- Không xử lý một đoạn tràn quá một trang (`WriteCursor.pageFull`) — nội dung được soạn đủ ngắn để luôn vừa một trang; nếu tương lai đoạn dài hơn làm tràn, coi là lỗi nội dung, log cảnh báo (mục 6.4), không build lại cơ chế `turnPage()` cho việc này.
- Không đụng vào `RiddleStateMachine`'s state machine chính (`Listening/Drinking/Thinking/Replying`) — chỉ di chuyển 3 hằng số dùng chung ra khỏi companion object của nó (mục 5).

## 3. Kiến trúc

Package mới: `com.riddleboox.app.onboarding` (flat, ngang hàng `riddle`, `handwriting`, `settings`...).

```
app/src/main/java/com/riddleboox/app/onboarding/
├── OnboardingScript.kt      # hằng số nội dung (pure data)
├── OnboardingState.kt       # sealed state (pure)
├── OnboardingDecide.kt      # decideOnboarding(): pure decision function
├── OnboardingController.kt  # shell: nối Decision -> RegionView/EinkRefresher/Ticker thật
└── WelcomeOverlay.kt        # màn hình chào mừng + nút "bắt đầu" (mục 7.1)

app/src/test/java/com/riddleboox/app/onboarding/
└── DecideOnboardingTest.kt
```

`OnboardingStore.kt` đặt trong `com.riddleboox.app.settings` (cùng package với `SendModeStore.kt`, `ReplyFontSizeStore.kt` — nó là một preference đơn giản, giống các store khác trong package đó, không phải logic viết chữ).

Lý do tách khỏi `RiddleStateMachine` thay vì tái dùng `writeWholeReply()`/`commitDemoText()` trực tiếp (đã trình bày và được duyệt ở phần chat): `RiddleStateMachine` tự khai phạm vi là vòng lặp hội thoại thật (network, conversation, ink capture), đã ~1050 dòng, là hotspot phức tạp nhất repo theo `get_health()` (6.89/10). Onboarding không cần network/conversation/ink — nhét vào đó tăng cognitive load của đúng file rủi ro nhất cho một tính năng không liên quan tới hội thoại.

## 4. Nội dung (`OnboardingScript.kt`)

```kotlin
package com.riddleboox.app.onboarding

/**
 * Thứ tự cố định — có tính sư phạm, không random như [com.riddleboox.app.riddle.GREETINGS].
 * Giọng văn nhất quán với DEFAULT_AGENT_GREETINGS (Agent.kt:282-293): "Ta" xưng hô cổ,
 * gọi người dùng là "ngươi".
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

## 5. Hằng số dùng chung — di chuyển ra khỏi `RiddleStateMachine`

`RiddleStateMachine.kt:1054-1055,1062` hiện có 3 `private const val` mà onboarding cần dùng lại **đúng giá trị** để nét chữ viết ra cùng tốc độ với một reply thật:

```kotlin
private const val REPLY_TICK_MS = 14L
private const val REPLY_POINTS_PER_TICK = 26
private const val REPLY_REFRESH_INTERVAL_MS = 120L
```

Vì chúng `private`, chuyển thành `internal const val` ở **`Decide.kt`** (không phải `PageGeometry.kt`) — đây đã là nơi giữ hằng số timing dùng chung của package `riddle` (`DRINK_STAGE_MS`, `IDLE_COMMIT_MS` ở `Decide.kt:18,21`), khác với `PageGeometry.kt` vốn giữ toạ độ/kích thước (`REPLY_TOP_PX`, `REPLY_BOTTOM_PX`). `RiddleStateMachine.kt` xoá 3 dòng const cũ, dùng thẳng 3 hằng số top-level mới (cùng package `riddle`, không cần import). `onboarding` package import chúng qua `com.riddleboox.app.riddle.*` (internal — cùng module `app`, hợp lệ).

**`REPLY_TOP_PX = 100f`/`REPLY_BOTTOM_PX = 80f`** (`PageGeometry.kt:25,28`) đã là `internal const val` sẵn — dùng thẳng, không cần sửa gì.

**Bắt buộc theo `CLAUDE.md` gốc repo:** đây là sửa `RiddleStateMachine.kt` — phải kiểm tra rủi ro trước khi sửa. `CLAUDE.md` gốc repo mô tả `impact()` của GitNexus, nhưng phiên làm việc này không có tool GitNexus nào kết nối (không có `mcp__gitnexus__*`, không có thư mục `.gitnexus/` trên đĩa) — chỉ có MCP server `repowise` (`.mcp.json`) là thật sự khả dụng. Dùng `mcp__repowise__get_risk(targets: ["app/src/main/java/com/riddleboox/app/riddle/RiddleStateMachine.kt"])` thay thế, dù thay đổi chỉ là di chuyển hằng số ra ngoài (không đổi giá trị, không đổi hành vi runtime).

## 6. Data flow & timing

### 6.1 State (pure)

```kotlin
// OnboardingState.kt
sealed class OnboardingState {
    /** Đang viết đoạn [segmentIndex] (0-based); reveal cursor còn strokes chưa lộ. */
    data class Writing(val segmentIndex: Int) : OnboardingState()

    /** Đoạn [segmentIndex] đã viết xong, đứng yên tới [holdUntilMs] rồi mới sang đoạn kế. */
    data class Holding(val segmentIndex: Int, val holdUntilMs: Long) : OnboardingState()

    /** Đã chạy hết toàn bộ segments. */
    object Done : OnboardingState()
}
```

### 6.2 Quyết định (pure, test được trên JVM)

```kotlin
// OnboardingDecide.kt
internal const val ONBOARDING_HOLD_MS = 4_000L

data class OnboardingDecision(val state: OnboardingState, val advance: Boolean, val finished: Boolean)

/**
 * @param caughtUp true khi ReplyRevealCursor của đoạn hiện tại đã lộ hết strokes.
 * @param advance true nghĩa là shell phải: clear trang, sang segmentIndex kế (hoặc Done nếu hết).
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

`DecideOnboardingTest.kt` cover: `Writing` chưa `caughtUp` → state không đổi; `Writing` `caughtUp` → `Holding` với `holdUntilMs = now + 4000`; `Holding` chưa hết giờ → không đổi; `Holding` hết giờ, còn đoạn → `Writing(index+1)`, `advance=true`; `Holding` hết giờ, đoạn cuối → `Done`, `finished=true`; `Done` luôn trả `finished=true`.

### 6.3 Shell (`OnboardingController.kt`)

```kotlin
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
    private var state: OnboardingState = OnboardingState.Writing(0)
    private var replyCursor: ReplyRevealCursor? = null
    private var lastRefreshAtMs: Long = 0L
    private var started = false
    // Bounds newly revealed since the last flush — mirrors
    // RiddleState.Replying.pendingDirtyRect; see tickWriting below.
    private var pendingDirtyRect: PageRect? = null

    fun start() { ticker.start(TICK_MS, ::tick) }
    fun stop() { ticker.stop() }

    private fun tick() {
        val now = ticker.nowMs()
        if (!started) {
            if (pageWidthPx() <= 0) return   // chờ layout đo xong, giống tickListening/greet
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
        pendingDirtyRect = null
        state = OnboardingState.Writing(index)
    }

    private fun tickWriting(s: OnboardingState.Writing, now: Long) {
        val cursor = replyCursor ?: return
        val newlyRevealed = cursor.revealMore(REPLY_POINTS_PER_TICK)
        val newBounds = writeBounds(newlyRevealed)
        if (newlyRevealed.isNotEmpty()) {
            regionView.appendReplyStrokes(newlyRevealed, newBounds ?: regionView.drawingRect())
        }
        // Only this tick's new ink is painted, same accumulate-then-flush
        // shape as RiddleStateMachine.tickReplying's `dirty`/`pendingDirtyRect`
        // — a throttled tick still has to carry its bounds to the flush that
        // actually happens next, or that ink never gets refreshed onto the
        // panel.
        pendingDirtyRect = union(pendingDirtyRect, newBounds)
        if (now - lastRefreshAtMs >= REPLY_REFRESH_INTERVAL_MS || (cursor.caughtUp && newlyRevealed.isNotEmpty())) {
            refresher.requestHandwritingRefresh(regionView, pendingDirtyRect ?: regionView.drawingRect())
            pendingDirtyRect = null
            lastRefreshAtMs = now
        }
        val decision = decideOnboarding(s, now, caughtUp = cursor.caughtUp, totalSegments = segments.size)
        state = decision.state
    }

    private fun tickHolding(s: OnboardingState.Holding, now: Long) {
        val decision = decideOnboarding(s, now, caughtUp = true, totalSegments = segments.size)
        state = decision.state
        if (decision.finished) { finish(); return }
        if (decision.advance) {
            regionView.clearReplyLayer()
            regionView.render(PageRenderState.EMPTY)
            // Not requestFullRefresh: this fires once per segment (5 times
            // across 6 segments), and the GC16 flash that clears ghosting is
            // jarring at that cadence. Quality mode redraws the whole region
            // clean, without the flash — see 6.4.
            refresher.requestQualityPartialRefresh(regionView, regionView.drawingRect())
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

        /**
         * No RiddleStateMachine.tickReplying-style `nextTickAtMs` pacing gate
         * here: this ticker already runs at TICK_MS = 16L, which is >=
         * REPLY_TICK_MS (14L, from com.riddleboox.app.riddle) — that gate
         * would almost never actually block a tick in practice, so leaving it
         * out doesn't change the observed reveal speed. It just drops a field
         * this controller has no use for over static, non-streamed content.
         */
        const val TICK_MS = 16L
    }
}
```

Ghi chú: `writeBounds()` (`PageGeometry.kt:82`) đã là hàm top-level `public` trong package `riddle` — dùng lại trực tiếp qua `import com.riddleboox.app.riddle.writeBounds`, không cần đổi visibility gì.

Đơn giản hoá có chủ đích so với `tickReplying`: bỏ qua gate `nextTickAtMs` (`RiddleStateMachine.kt:747`) — vì ticker ngoài luôn chạy ở `TICK_MS = 16L` ≥ `REPLY_TICK_MS = 14L`, gate đó trong thực tế gần như không bao giờ chặn được tick nào, nên bỏ nó không đổi tốc độ lộ chữ quan sát được, chỉ bỏ một field state không cần thiết cho nội dung tĩnh (không streaming, không cần chờ dữ liệu tới).

### 6.4 Refresh giữa các đoạn

Dùng `regionView.render(PageRenderState.EMPTY)` để xoá trang — **không** replicate 14-stage dissolve của `Drinking` (không cần thiết, tăng phức tạp không tương xứng lợi ích). Refresh mode khác nhau tuỳ điểm dừng:

- **Giữa các đoạn** (`tickHolding`'s `advance` branch, chạy 5 lần cho 6 đoạn): `refresher.requestQualityPartialRefresh(regionView, regionView.drawingRect())` — "full grayscale không có flash, cho cả vùng ổn định cùng lúc" (docstring `EinkRefresher.kt` cho `RefreshMode.Quality`). Ban đầu spec này dùng `requestFullRefresh()` (GC16 flash) giống nhánh "excuse" (`RiddleStateMachine.kt:539`) — đổi sau phản hồi trực tiếp của user: 5 lần flash liên tiếp khi chuyển câu gây khó chịu, trong khi flash chỉ cần thiết để dọn ghosting tích luỹ qua nhiều turns thật (`TURNS_PER_FULL_REFRESH`), không phải mỗi lần đổi một câu ngắn.
- **Khi xong toàn bộ** (`finish()`): giữ `refresher.requestFullRefresh(regionView)` — đây là điểm chuyển một lần duy nhất (onboarding → trang trống bình thường), không lặp lại, giống các điểm "dọn sạch ghosting một lần" đã có trong app.

## 7. Tích hợp `MainActivity.kt`

### 7.1 Màn hình chào mừng (`WelcomeOverlay.kt`)

Bổ sung sau phản hồi trực tiếp của user (ban đầu spec không có màn hình này — chuỗi giới thiệu tự chạy ngay). File mới `com.riddleboox.app.onboarding.WelcomeOverlay.kt`, một hàm builder thuần Android View (không XML layout, đúng convention hiện tại của `MainActivity.kt`/`SettingsActivity.kt`):

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

Không đưa nội dung khác ngoài tiêu đề + nút (YAGNI — user chỉ yêu cầu đúng hai thứ này). Tiêu đề tiếng Việt "Chào mừng" thay vì tiếng Anh "Welcome" nguyên văn user gõ — **Giả định:** giữ nhất quán giọng văn tiếng Việt xuyên suốt app (mọi copy khác đều tiếng Việt, kể cả giọng cổ "Ta"/"ngươi" của `Greetings.kt`), coi việc user gõ "welcome" là code-switch khi gõ nhanh chứ không phải yêu cầu literal tiếng Anh — rẻ để đổi nếu sai.

### 7.2 Wiring vào `onCreate`/`onResume`/`onPause`

- `OnboardingStore(this).read()` đọc ngay đầu `onCreate`, trước khi build `stateMachine`.
- Nếu `false`:
  - Vẫn build `stateMachine` như hiện tại (không đổi cách wiring) nhưng **không** gọi `stateMachine.start()` trong `onResume` — gọi `onboardingController.start()` thay vào đó, và chỉ sau khi nút "bắt đầu" đã được chạm (mục 7.1) — không phải ngay khi `onCreate` chạy.
  - `chromeRow.visibility = View.GONE` cho tới khi onboarding xong.
  - `inkCapture.setInputEnabled(false)` giữ nguyên bất kể `diaryBusy`, từ màn hình chào mừng cho tới khi onboarding xong — thêm cờ `private var onboardingStarted = false` (đã chạm "bắt đầu" chưa) cạnh `onboardingSeen`, sửa dòng `inkCapture.setInputEnabled(!diaryBusy)` ở `onResume` thành `inkCapture.setInputEnabled(!diaryBusy && onboardingSeen)` (bút đã khoá sẵn bất kể `onboardingStarted`, vì biểu thức chỉ mở bút khi `onboardingSeen == true`).
  - `root.addView(welcomeOverlay(this) { overlay -> root.removeView(overlay); onboardingStarted = true; onboardingController?.start() })` — thêm overlay lên trên cùng cây view gốc, chỉ khi `!onboardingSeen`. Chạm nút: gỡ overlay, đặt `onboardingStarted = true`, gọi `start()` trực tiếp (không cần đợi `onResume` vì đang chạy giữa lúc Activity đã resumed).
  - `onResume`: nhánh `else` (khi `!onboardingSeen`) chỉ gọi `onboardingController?.start()` nếu `onboardingStarted == true` — nếu chưa chạm nút, không làm gì, màn hình chào mừng vẫn đứng yên.
  - `onPause`: nhánh `else` chỉ gọi `onboardingController?.stop()` nếu `onboardingStarted == true` — chưa bắt đầu thì không có gì để dừng.
  - `onDone` callback: `OnboardingStore(this).write(true)`, `onboardingSeen = true`, `chromeRow.visibility = View.VISIBLE`, `inkCapture.setInputEnabled(!diaryBusy)`, rồi `stateMachine.start()`.
- Nếu `true`: hành vi y hệt hiện tại, không có nhánh nào chạy, không có overlay nào được thêm — zero regression cho user cũ.

## 8. Tích hợp `SettingsActivity.kt` — nút "giới thiệu lại"

Thêm field mới trong `column` (`SettingsActivity.kt:98-106`), style giống các `chooserField` khác:

```kotlin
val onboardingStore = OnboardingStore(this)
// ...
addView(field("giới thiệu", chooserField("chạm để xem lại") {
    onboardingStore.write(false)
    save()
}))
```

Gọi lại `save()` có sẵn (không tự `finish()` riêng) để không đánh mất các field khác đang sửa dở trên cùng màn hình — `save()` đã `setResult(RESULT_OK)` + `finish()`, và `MainActivity.onActivityResult` (`MainActivity.kt:335`) đã tự `recreate()` khi `RESULT_OK`, kích hoạt lại nhánh onboarding ở mục 7.

## 9. `OnboardingStore.kt` (`com.riddleboox.app.settings`)

```kotlin
package com.riddleboox.app.settings

import android.content.Context

/** Đã xem hết phần giới thiệu lần mở app đầu tiên chưa — cùng pattern [SendModeStore]. */
class OnboardingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(): Boolean = prefs.getBoolean(KEY_SEEN, false)
    fun write(seen: Boolean) { prefs.edit().putBoolean(KEY_SEEN, seen).apply() }

    private companion object {
        const val FILE = "onboarding"
        const val KEY_SEEN = "seen"
    }
}
```

## 10. Testing

- `DecideOnboardingTest.kt` — xem mục 6.2, test pure function, không cần Android/Robolectric.
- Không có test cho `OnboardingStore` — đã kiểm tra, không có Robolectric trong `testImplementation` (`app/build.gradle.kts:115` chỉ có `junit:junit:4.13.2`), và ba store SharedPreferences hiện có (`AgentSelectionStore`, `SendModeStore`, `ReplyFontSizeStore`) đều không có test tương ứng — chúng là wrapper `getX`/`putX` một dòng, coi là quá đơn giản để cần test theo convention hiện tại của repo. `OnboardingStore` đi theo đúng convention đó.
- Không viết instrumented/Espresso test cho phần render — không phải convention hiện tại của repo.
- Sau khi implement: `mcp__repowise__get_change_risk()` (thay cho `detect_changes()` của GitNexus, không kết nối trong phiên này) để xác nhận phạm vi ảnh hưởng đúng như spec (chỉ `RiddleStateMachine.kt` companion object, `MainActivity.kt`, `SettingsActivity.kt`, và các file mới trong `onboarding`/`settings`), không lan ra symbol không liên quan.

## 11. Rủi ro & edge case đã cân nhắc

| Rủi ro | Xử lý |
|---|---|
| `pageWidthPx() == 0` kéo dài | Tick tự chờ, giống `attachRetry`/`tickListening` hiện có — không cần cơ chế mới. |
| App bị kill giữa onboarding | `OnboardingStore` chỉ ghi `true` ở `Done` → mở lại chạy lại từ đầu (chấp nhận được). |
| Đoạn tràn quá 1 trang | `Log.w` cảnh báo, không silently cắt — mục 6.3. Không xảy ra với nội dung hiện tại: ở cỡ chữ lớn nhất được hỗ trợ, vùng reply vẫn đủ dòng cho từng đoạn — nội dung đã đo bằng mắt, đủ ngắn để vừa một trang ở mọi cỡ chữ hỗ trợ — xem `OnboardingScript.kt`. |
| User cũ (đã có flag) | Không chạm nhánh nào mới — `OnboardingStore.read() == true` đi thẳng qua code path hiện tại. |
| Sửa `RiddleStateMachine.kt` companion object | Bắt buộc kiểm tra rủi ro trước khi sửa theo `CLAUDE.md` gốc repo (mục 5) — dùng `mcp__repowise__get_risk` (GitNexus không kết nối trong phiên này), dù thay đổi chỉ di chuyển hằng số, không đổi giá trị/hành vi. |

## Ghi chú duyệt

Thiết kế được trình bày và duyệt theo từng phần trong chat (kiến trúc §3, nội dung §4, data flow/timing §6) qua các câu hỏi `AskUserQuestion` + xác nhận trực tiếp của user. User sau đó gọi `/goal hoàn thành onboarding feature`, đặt session vào chế độ tự trị và yêu cầu không dừng lại hỏi thêm. Theo quy tắc "Interview Before Execution" của `CLAUDE.md` cho chế độ tự trị: **Giả định:** toàn bộ thiết kế trong file này được xem là đã duyệt đầy đủ (bao gồm các chi tiết kỹ thuật ở §5–§9 được bổ sung sau lần duyệt cuối cùng trong chat, suy ra trực tiếp từ những gì đã duyệt, không đổi hướng kiến trúc) — vì `/goal` chỉ định rõ không dừng lại chờ xác nhận. Không có bước "user review file spec" riêng trước khi chuyển sang implementation.

## 12. Bổ sung 2026-09-02 — dòng tiến độ và copy mới

**Vấn đề quan sát được:** người dùng xem xong một đoạn thì không biết còn đoạn nữa không, bao lâu nữa nó tới, hay có phải chạm gì không. Nguyên nhân: sau khi viết xong, trang đứng im đúng `ONBOARDING_HOLD_MS` (4 giây) rồi tự xoá — không có gì trên màn hình nói ra điều đó, và màn hình chào ("Welcome" / "begin") cũng không nói phần giới thiệu tự lật trang.

**Thay đổi:**

- **Dòng tiến độ** (`onboardingCaption()`, `OnboardingDecide.kt`, hàm thuần, test trong `DecideOnboardingTest.kt`): một caption kiểu chrome (`Context.caption()` của `Paper.kt`) đặt đúng chỗ thanh chrome sẽ hiện sau khi giới thiệu xong — cùng `chromeTopInset()`, cùng lề trái. Nội dung: đang viết → `page 2 of 6`; đang giữ → `page 2 of 6 · next in 3`, đếm lùi từng giây (làm tròn lên, không bao giờ xuống 0 vì tick chạm hạn là tick đã lật trang); trang cuối → `page 6 of 6 · your turn in 3`. `OnboardingController` phát chuỗi qua `onCaptionChanged` chỉ khi chuỗi đổi (≈ 1 lần/giây lúc giữ), `MainActivity` sở hữu view — cùng hình với `onStatusChanged` của `RiddleStateMachine`.
- **Refresh:** bút bị khoá suốt onboarding (`setRawDrawingEnabled(false)`), nên caption đi theo tiền lệ `statusView` ("Thinking…", "Writing a reply…"): gán `text`, để framework invalidate — không gọi `EpdController` riêng cho dòng này. Khác với offline banner, thứ phải tự refresh vì hiện/ẩn lúc bút đang mở (`docs/refresh-strategy.md`, "Invariant khi chuyển view"). Chưa xác nhận trên máy BOOX thật tại thời điểm viết — nếu dòng không đổi trên panel, thêm `requestQualityPartialRefresh` trên rect của caption khi text đổi.
- **Màn hình chào** có thêm một dòng thân bài nói rõ: sáu trang ngắn, tự lật, không cần chạm.
- **Copy** của cả sáu đoạn, màn hình chào, overlay quyền truy cập, và dòng "introduction" trong Settings được viết lại; ràng buộc giữ trong `OnboardingScriptTest.kt`: đúng 6 trang, trang 5 nói về sách (vì `ONBOARDING_PERMISSION_CHECKPOINT = 4`), mỗi trang ≤ 220 ký tự (vừa một trang Note Air 2 ở cỡ chữ extra large), nhãn trong dấu nháy phải tồn tại trên chrome. Lỗi copy cũ được sửa: đoạn 2 bảo chạm `send` trong khi chế độ mặc định là automatic và nhãn đó bị ẩn (`MainActivity.kt`, `sendVisibility`).

- **Checkpoint quyền đọc sách nói thật ở cả ba chỗ.** `MainActivity` quyết định một lần (`asksForBooks = !canOpenBooks() && allFilesAccess(this) != null`) rồi dùng cho cả ba: (1) `permissionCheckpointAfter` chỉ được nối khi có gì để hỏi; (2) dòng thân bài màn hình chào đổi giữa "you need not touch anything" và "I will stop once to ask about your books"; (3) caption lúc giữ trang 5 là `page 5 of 6 · a question in 2` thay vì hứa "next" rồi màn hình quyền hiện ra (`onboardingCaption(..., checkpointAfter)`).
- **Sửa bug có sẵn từ v0.3.0 (2a231e6):** đang đỗ ở checkpoint, `MainActivity.onResume` gọi `OnboardingController.start()` vô điều kiện (người viết bấm nút nguồn hoặc bị notification lúc overlay đang hiện), ticker chạy lại, `tickHolding` lọt qua guard `checkpointPendingSegment == null` và viết trang 6 — thậm chí kết thúc cả intro — bên dưới overlay. `start()` giờ là no-op khi `checkpointPendingSegment != null`; checkpoint giữ ticker cho tới `proceedFromCheckpoint`. Test: `OnboardingControllerTest` (Robolectric, `@Config(sdk = [35])`) dựng controller thật với `RegionView` + `EinkRefresher` (mọi call Onyx bọc `runCatching`, off-device tự thất bại êm) và ticker giả — đây là test đầu tiên chạm tới shell của onboarding.

**Giữ nguyên:** tự lật sau 4 giây, bút khoá, không có nút bỏ qua (§2). "Chạm để lật sớm" là bước tiếp theo có thể cân nhắc nếu đếm lùi vẫn chưa đủ.
