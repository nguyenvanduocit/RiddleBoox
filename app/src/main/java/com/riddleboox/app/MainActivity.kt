package com.riddleboox.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.graphics.Rect
import android.os.Bundle
import android.os.Environment
import android.text.TextUtils
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.util.Log
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.riddleboox.app.agent.AgentDefinition
import com.riddleboox.app.agent.AgentCapability
import com.riddleboox.app.agent.AgentManagerTools
import com.riddleboox.app.agent.AgentSelectionStore
import com.riddleboox.app.agent.AgentStore
import com.riddleboox.app.agent.CompositeToolbox
import com.riddleboox.app.agent.WorkspaceTools
import com.riddleboox.app.handwriting.HandwritingPlanner
import com.riddleboox.app.handwriting.PaintTextRaster
import com.riddleboox.app.handwriting.ShapeAwareTextRaster
import com.riddleboox.app.history.ConversationStore
import com.riddleboox.app.history.HistoryActivity
import com.riddleboox.app.ink.EinkRefresher
import com.riddleboox.app.ink.InkCaptureController
import com.riddleboox.app.ink.InkStroke
import com.riddleboox.app.ink.PageArchive
import com.riddleboox.app.ink.StrokeStore
import com.riddleboox.app.riddle.HandlerTicker
import com.riddleboox.app.riddle.PendingTurnMarker
import com.riddleboox.app.riddle.RiddleStateMachine
import com.riddleboox.app.riddle.idleStatus
import com.riddleboox.app.onboarding.ONBOARDING_SEGMENTS
import com.riddleboox.app.onboarding.OnboardingController
import com.riddleboox.app.onboarding.welcomeOverlay
import com.riddleboox.app.settings.OnboardingStore
import com.riddleboox.app.settings.ReplyFontSizeStore
import com.riddleboox.app.settings.ReplySettings
import com.riddleboox.app.settings.SettingsActivity
import com.riddleboox.app.library.Book
import com.riddleboox.app.library.OnyxLibrary
import com.riddleboox.app.library.canOpenBooks
import com.riddleboox.app.settings.SendMode
import com.riddleboox.app.settings.SendModeStore
import com.riddleboox.app.settings.SettingsStore
import com.riddleboox.app.tools.DilibTools
import com.riddleboox.app.tools.DiaryTools
import com.riddleboox.app.tools.BooxNotesTools
import com.riddleboox.app.tools.BooxNote
import com.riddleboox.app.tools.MemoryTools
import com.riddleboox.app.tools.OpenAiBooxNotesVisionReader
import com.riddleboox.app.tools.StoredMemory
import com.riddleboox.app.reply.Toolbox
import com.riddleboox.app.reply.reasoningFor
import com.riddleboox.app.reply.replyClient
import com.riddleboox.app.reply.replyModel
import com.riddleboox.app.ui.RegionView
import com.riddleboox.app.ui.RegionViewPanel
import com.riddleboox.app.ui.caption
import com.riddleboox.app.ui.chromeTopInset
import com.riddleboox.app.ui.dp
import com.riddleboox.app.ui.openPaperWindow
import java.io.File
import java.util.UUID

/**
 * Wires the diary together: pen capture -> [RiddleStateMachine] -> page
 * render. The Activity itself only builds views and forwards lifecycle/pen
 * events — all behaviour lives in [RiddleStateMachine].
 */
class MainActivity : Activity() {

    private val strokeStore = StrokeStore()
    private val refresher = EinkRefresher()
    private lateinit var conversationStore: ConversationStore
    private lateinit var penSurface: SurfaceView
    private lateinit var regionView: RegionView
    private lateinit var inkCapture: InkCaptureController
    private lateinit var statusView: TextView

    /** On the chrome only while a turn is in flight — see [RiddleStateMachine.stopNow]. */
    private lateinit var stopLabel: TextView
    private lateinit var chromeRow: LinearLayout
    private lateinit var stateMachine: RiddleStateMachine
    private lateinit var buildDefaults: ReplySettings
    private lateinit var agentStore: AgentStore
    private lateinit var selectedAgent: AgentDefinition
    /** State-machine input gate, kept separately from the temporary Activity lifecycle gate. */
    private var diaryBusy = false
    /** Non-null only while the first-run introduction hasn't finished yet. */
    private var onboardingController: OnboardingController? = null
    /** Whether the first-run introduction has already run; gates [onResume]/[onPause]. */
    private var onboardingSeen = true
    /** Whether the "bắt đầu" button on the welcome screen has been tapped yet. */
    private var onboardingStarted = false

    private val inkCallbacks = object : InkCaptureController.Callbacks {
        override fun onPenDown(): Boolean = stateMachine.onPenDown()
        override fun onPenUp() = stateMachine.onPenUp()
        override fun onStrokeCaptured(strokes: List<InkStroke>) = Unit
    }

    /**
     * Demo/dev backdoor, debug builds only: `adb shell am broadcast -a
     * com.riddleboox.app.DEMO_WRITE --es text "..."` writes [text] as if a
     * pen had just written it — see [RiddleStateMachine.commitDemoText].
     */
    private val demoWriteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(EXTRA_DEMO_TEXT)
            Log.i(TAG, "demo write received: ${text?.take(60)}")
            if (text == null) return
            stateMachine.commitDemoText(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPaperWindow()
        // This must be set before the SurfaceView is created. The Onyx SDK can
        // otherwise spend its own full-screen GC refresh on a new surface
        // before the page gets to schedule the one refresh it actually needs.
        refresher.configureNewSurfaces()

        // Checked once, at cold start, before anything writes a new marker of
        // its own — see PendingTurnMarker's own doc for what leaves this set.
        val pendingTurnMarker = PendingTurnMarker(filesDir)
        if (pendingTurnMarker.consume()) {
            Toast.makeText(
                this,
                "Trang trước có thể đã bị gián đoạn khi ứng dụng đóng — nội dung chưa chắc đã được lưu.",
                Toast.LENGTH_LONG,
            ).show()
        }

        agentStore = AgentStore(this)
        agentStore.ensureDefaults()
        val selectedId = AgentSelectionStore(this).read()
        selectedAgent = agentStore.load(selectedId) ?: agentStore.load("chat")!!

        penSurface = SurfaceView(this)
        regionView = RegionView(this)
        val replyFontSize = ReplyFontSizeStore(this).read()
        regionView.replyFontSizePx = replyFontSize.px

        val sendMode = SendModeStore(this).read()

        // An imprint line, not a status bar: quiet caption type, low contrast,
        // so it reads like the tiny print at the foot of a book page rather
        // than app chrome competing with the ink.
        statusView = caption(idleStatus(sendMode)).apply {
            setPadding(dp(24), 0, dp(8), 0)
            // The stop label sits directly after these words, so the line's
            // slack moves behind it ([chromeSpacer]) and the caption takes
            // only the room it needs. A lookup note can run long — it is cut
            // rather than allowed to push the controls off the far end.
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        // It belongs to the status line, not to the controls at the far end:
        // what it stops is the thing the status line is reporting, and a word
        // that acts on "đang viết…" has to stand next to those words to be
        // read as answering them. Same plain caption style as every other
        // control on this line — it is only ever up while a turn is in
        // flight, so there is never another label beside it to confuse it with.
        stopLabel = caption("dừng").apply {
            setPadding(dp(6), 0, dp(6), 0)
            visibility = View.GONE
            setOnClickListener { stateMachine.stopNow() }
        }
        val sendLabel = caption("gửi").apply {
            setPadding(dp(6), 0, dp(6), 0)
            visibility = sendVisibility(sendMode)
        }
        val newPageLabel = caption("trang mới").apply {
            setPadding(dp(6), 0, dp(6), 0)
        }
        val agentLabel = caption(selectedAgent.name.lowercase()).apply {
            setPadding(dp(6), 0, dp(6), 0)
        }
        val historyLabel = caption("lịch sử").apply {
            setPadding(dp(6), 0, dp(6), 0)
        }
        val settingsLabel = caption("settings").apply {
            setPadding(dp(6), 0, dp(24), 0)
        }
        // What splits the line in two: what this page does, and where else the
        // writer can go. Everything used to sit at one even spacing, which read
        // as six unrelated words rather than two handfuls.
        val groupRule = View(this).apply { setBackgroundColor(Color.BLACK) }

        chromeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // The line's vertical rhythm is set once, here, so every label on
            // it sits on the same centre line instead of each padding itself
            // down by a different amount.
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, chromeTopInset(), 0, dp(6))
            addView(
                statusView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { weight = 0f },
            )
            addView(stopLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            // The empty middle of the line: what keeps the page's own controls
            // at the far end while the status and its stop label stay together
            // at this one.
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(1), 1f))
            listOf(sendLabel, newPageLabel).forEach {
                addView(it, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            }
            addView(groupRule, LinearLayout.LayoutParams(dp(1), dp(18)).apply {
                setMargins(dp(22), 0, dp(22), 0)
            })
            listOf(agentLabel, historyLabel, settingsLabel).forEach {
                addView(it, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            }
        }

        val root = FrameLayout(this).apply {
            addView(penSurface, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            addView(regionView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            addView(chromeRow, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ))
        }
        setContentView(root)

        inkCapture = InkCaptureController(this, penSurface, strokeStore, inkCallbacks, ::drawingRect)
        conversationStore = ConversationStore(this)

        buildDefaults = ReplySettings(
            baseUrl = BuildConfig.LLM_BASE_URL,
            apiKey = BuildConfig.LLM_API_KEY,
            model = BuildConfig.LLM_MODEL,
        )
        val settings = SettingsStore(this).readOrDefault(
            buildDefaults.baseUrl,
            buildDefaults.apiKey,
            buildDefaults.model,
        )
        val replySettings: ReplySettings? = settings.takeIf { it.apiKey.isNotBlank() }
        val handwritingPlanner = HandwritingPlanner(ShapeAwareTextRaster(PaintTextRaster.fromAsset(this)))
        stateMachine = RiddleStateMachine(
            strokeStore = strokeStore,
            inkCapture = inkCapture,
            panel = RegionViewPanel(regionView, refresher),
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
            pendingTurnMarker = pendingTurnMarker,
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
                    onboardingController = null
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

        // Layout runs whenever the chrome re-measures — the status caption
        // changes on every state transition, and its height is what the pen's
        // limit rect is measured down from. Attaching and re-limiting the pen
        // are cheap and belong here; a panel refresh is not, so the one clean
        // slate at startup is left to surfaceChanged.
        val relimit = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            maybeAttach()
            refreshRegion()
        }
        regionView.addOnLayoutChangeListener(relimit)
        chromeRow.addOnLayoutChangeListener(relimit)

        penSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceNeedsFullRefresh = true
                maybeAttach()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                inkCapture.refreshLimits()
                if (surfaceNeedsFullRefresh) {
                    surfaceNeedsFullRefresh = false
                    refresher.requestFullRefresh(regionView)
                }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceNeedsFullRefresh = false
            }
        })

        sendLabel.setOnClickListener { stateMachine.sendNow() }
        newPageLabel.setOnClickListener { stateMachine.newPage() }
        historyLabel.setOnClickListener {
            startActivityForResult(HistoryActivity.intent(this, selectedAgent.id), REQUEST_HISTORY)
        }
        agentLabel.setOnClickListener {
            startActivityForResult(AgentsActivity.intent(this), REQUEST_AGENTS)
        }
        settingsLabel.setOnClickListener {
            startActivityForResult(SettingsActivity.intent(this, buildDefaults), REQUEST_SETTINGS)
        }
    }

    /**
     * The send label belongs to [SendMode.Manual] alone. Under [SendMode.Auto]
     * the pause already hands the page over, so a button beside it would be a
     * second way to do the same thing taking room on a line the ink shares.
     */
    private fun sendVisibility(mode: SendMode): Int =
        if (mode == SendMode.Manual) View.VISIBLE else View.GONE

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Settings returns OK only after saving. Recreate then picks up the
        // changed key, since the conversation is built once in onCreate;
        // cancelling or backing out leaves the current page untouched.
        if (requestCode == REQUEST_SETTINGS && resultCode == RESULT_OK) recreate()
        if (requestCode == REQUEST_AGENTS && resultCode == RESULT_OK) recreate()
        if (requestCode == REQUEST_HISTORY && resultCode == RESULT_OK) {
            pendingResumeId = data?.getStringExtra(HistoryActivity.EXTRA_RESUME_ID)
        }
    }

    /**
     * What the diary may look up while it answers: the writer's own reader —
     * their books, how far into each they have read, and every passage they
     * marked — plus the evenings this diary has already had with them.
     *
     * The books come from NeoReader's own database over a content provider and
     * cost nothing but a query. Opening a book to read the words inside is the
     * one part that needs all-files access, which is granted in Settings and
     * not by a dialog (see AndroidManifest.xml); without it the shelf, the
     * progress and the highlights all still work and only [DiaryTools]'
     * read_book says it cannot open the file. Which of the two the device is in
     * is logged here, because from the page itself the difference is one
     * sentence of the diary's and easy to mistake for a mood.
     */
    private fun diaryTools(): DiaryTools {
        val library = OnyxLibrary(contentResolver)
        if (BuildConfig.DEBUG) {
            // Two things can be wrong here and they look identical from the
            // page — a provider the manifest never declared, and a shelf that
            // is genuinely empty — so the count is read once and said out loud.
            // Debug only: it is a cross-process query of the whole library on
            // the way to first paint.
            val shelf = runCatching { library.books().size to library.highlights(null).size }
            Log.i(
                TAG,
                "library: ${shelf.getOrNull()?.let { (books, marks) -> "$books books, $marks marked passages" }
                    ?: "unreachable (${shelf.exceptionOrNull()?.message})"}" +
                    ", books readable from disk: ${canOpenBooks()}",
            )
        }
        return DiaryTools(
            library = library,
            memory = StoredMemory(conversationStore),
            openReader = { book ->
                withContext(Dispatchers.Main.immediate) { openBookInReader(book) }
            },
        )
    }

    /** Opens a library file through NeoReader's own external-storage provider. */
    private fun openBookInReader(book: Book): Boolean = runCatching {
        val file = File(book.path)
        if (!file.isFile) return@runCatching false
        // NeoReader treats a third-party content URI as a download and rejects
        // it. Its own provider maps /external/... to shared storage and is the
        // same handoff used when BOOX opens a book from its library.
        val externalRoot = Environment.getExternalStorageDirectory().canonicalFile.path
        val canonicalPath = file.canonicalFile.path
        val prefix = "$externalRoot${File.separator}"
        if (!canonicalPath.startsWith(prefix)) return@runCatching false
        val relativePath = canonicalPath.removePrefix(prefix)
            .split(File.separatorChar)
            .joinToString("/") { Uri.encode(it) }
        val uri = Uri.Builder()
            .scheme("content")
            .authority("com.onyx.kreader.onyx.fileprovider")
            .encodedPath("/external/$relativePath")
            .build()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName("com.onyx.kreader", "com.onyx.kreader.ui.ReaderHomeActivity")
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) == null) return@runCatching false
        startActivity(intent)
        true
    }.getOrDefault(false)

    /** BOOX exposes Notebook through this small app-to-app intent contract. */
    private fun openBooxNotebook(@Suppress("UNUSED_PARAMETER") note: BooxNote?): Boolean = runCatching {
        val intent = Intent().apply {
            component = ComponentName("com.onyx", "com.onyx.main.ui.MainActivity")
            putExtra("json", "{\"action\":\"OPEN_NOTE\"}")
        }
        if (intent.resolveActivity(packageManager) == null) return@runCatching false
        startActivity(intent)
        true
    }.getOrDefault(false)

    /** Capability composition is determined by the selected user-defined agent. */
    private fun agentToolbox(replySettings: ReplySettings?): Toolbox {
        val boxes = buildList {
            if (AgentCapability.WORKSPACE in selectedAgent.toolIds) {
                add(WorkspaceTools(selectedAgent.workspace))
                // A fresh id per toolbox build, not per turn: this is rebuilt
                // exactly when a new evening starts (app open, agent switch),
                // the same boundary RiddleStateMachine.conversationId uses.
                add(MemoryTools(selectedAgent.workspace, conversationId = UUID.randomUUID().toString()))
            }
            if (AgentCapability.LIBRARY in selectedAgent.toolIds) {
                add(diaryTools())
            }
            if (AgentCapability.DILIB in selectedAgent.toolIds) {
                add(DilibTools(this@MainActivity))
            }
            if (AgentCapability.BOOX_NOTES in selectedAgent.toolIds) {
                val visionReader = replySettings?.let {
                    OpenAiBooxNotesVisionReader(
                        client = replyClient(it.baseUrl, it.apiKey),
                        model = replyModel(it.model),
                        reasoning = reasoningFor(it.model),
                    )
                }
                add(
                    BooxNotesTools(
                        contentResolver,
                        visionReader,
                        openNote = { note ->
                            withContext(Dispatchers.Main.immediate) { openBooxNotebook(note) }
                        },
                    ),
                )
            }
            // This check is intentionally stricter than the manifest alone:
            // no custom agent can gain awareness of the agent-management API.
            if (selectedAgent.builtin && selectedAgent.id == "agent-manager" &&
                AgentCapability.AGENT_MANAGEMENT in selectedAgent.toolIds
            ) {
                add(AgentManagerTools(agentStore))
            }
        }
        return CompositeToolbox(boxes)
    }

    /**
     * A conversation chosen in the history, waiting for the page to be ready
     * for it.
     *
     * It is not resumed here in `onActivityResult`, because resuming writes the
     * last standing reply back out in ink — and at this point the pen is still
     * detached and the state machine's tick is stopped ([onPause] did both).
     * [onResume] runs a moment later with everything standing up again, which
     * is where it is taken.
     */
    private var pendingResumeId: String? = null

    private fun resumePending() {
        val id = pendingResumeId ?: return
        pendingResumeId = null
        val past = conversationStore.load(id)
        if (past == null) {
            Log.w(TAG, "conversation to resume is gone: $id")
            return
        }
        stateMachine.resume(past)
    }

    private var attached = false
    private var surfaceNeedsFullRefresh = true

    /**
     * The retry [maybeAttach] arms while the surface has not been measured yet.
     *
     * A named runnable rather than a fresh lambda, so it can be taken back off
     * the queue: [maybeAttach] is called from three places at startup, which
     * without this leaves three retry chains racing each other, and a chain
     * still pending when the writer opens settings would stand the Onyx raw
     * drawing session back up over a screen this Activity no longer owns.
     */
    private val attachRetry = Runnable { maybeAttach() }

    /** The writing area is the whole screen — no margin held back from the pen. */
    /**
     * Where the pen is allowed to write: the page below the chrome.
     *
     * The status line and the settings/reset labels sit across the top, and a
     * hand resting there while writing would otherwise land strokes on them —
     * or press them. Handing the SDK a limit rect that stops at the chrome's
     * bottom edge means the pen simply is not read up there, which is a
     * stronger guarantee than hoping nobody touches it.
     */
    private fun drawingRect(): Rect {
        val w = penSurface.width
        val h = penSurface.height
        if (w <= 0 || h <= 0) return Rect(0, 0, 0, 0)
        val top = if (::chromeRow.isInitialized) chromeRow.height else 0
        return Rect(0, top.coerceAtMost(h), w, h)
    }

    private fun refreshRegion() {
        if (penSurface.width > 0 && penSurface.height > 0) {
            inkCapture.refreshLimits()
        }
    }

    private fun maybeAttach() {
        if (attached) return
        if (penSurface.width <= 0 || penSurface.height <= 0) {
            penSurface.removeCallbacks(attachRetry)
            penSurface.postDelayed(attachRetry, ATTACH_RETRY_MS)
            return
        }
        inkCapture.attach()
        if (inkCapture.isRawDrawingActive()) {
            attached = true
        }
    }

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
        } else if (onboardingStarted) {
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

    override fun onPause() {
        if (BuildConfig.DEBUG) unregisterReceiver(demoWriteReceiver)
        penSurface.removeCallbacks(attachRetry)
        if (onboardingSeen) stateMachine.stop() else if (onboardingStarted) onboardingController?.stop()
        // Losing focus is temporary when Settings, Agents, or History is on
        // top. Disable input without closing the raw session so returning does
        // not recreate the TouchHelper and trigger avoidable E-Ink refreshes.
        inkCapture.setInputEnabled(false)
        super.onPause()
    }

    override fun onDestroy() {
        // The Activity is no longer coming back, so this is the point where
        // the raw session can be closed without penalizing ordinary navigation.
        penSurface.removeCallbacks(attachRetry)
        inkCapture.detach()
        attached = false
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_SETTINGS = 1
        private const val REQUEST_HISTORY = 2
        private const val REQUEST_AGENTS = 3

        /** How long to wait before looking again for a surface with a size. */
        private const val ATTACH_RETRY_MS = 100L

        private const val TAG = "MainActivity"
        private const val ACTION_DEMO_WRITE = "com.riddleboox.app.DEMO_WRITE"
        private const val EXTRA_DEMO_TEXT = "text"
    }
}
