package com.riddleboox.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.graphics.Rect
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.riddleboox.app.agent.AgentDefinition
import com.riddleboox.app.agent.AgentCapability
import com.riddleboox.app.agent.AgentManagerTools
import com.riddleboox.app.agent.AgentSelfTools
import com.riddleboox.app.agent.AgentSelectionStore
import com.riddleboox.app.agent.AgentStore
import com.riddleboox.app.agent.CompositeToolbox
import com.riddleboox.app.agent.WorkspaceTools
import com.riddleboox.app.handwriting.HandwritingPlanner
import com.riddleboox.app.handwriting.PaintTextRaster
import com.riddleboox.app.handwriting.ScriptAwareTextRaster
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
import com.riddleboox.app.settings.PenStrokeWidthStore
import com.riddleboox.app.settings.PenStyleStore
import com.riddleboox.app.settings.ReplyFontSizeStore
import com.riddleboox.app.settings.ReplySettings
import com.riddleboox.app.settings.SettingsActivity
import com.riddleboox.app.library.Book
import com.riddleboox.app.library.BookAccessCheck
import com.riddleboox.app.library.OnyxLibrary
import com.riddleboox.app.library.allFilesAccess
import com.riddleboox.app.library.canOpenBooks
import com.riddleboox.app.library.checkBookAccess
import com.riddleboox.app.onboarding.permissionOverlay
import com.riddleboox.app.settings.SendMode
import com.riddleboox.app.settings.SendModeStore
import com.riddleboox.app.settings.SettingsStore
import com.riddleboox.app.tools.DilibTools
import com.riddleboox.app.tools.DiaryTools
import com.riddleboox.app.tools.BooxNotesTools
import com.riddleboox.app.tools.BooxNote
import com.riddleboox.app.tools.BooxStateTools
import com.riddleboox.app.tools.MemoryTools
import com.riddleboox.app.tools.OnyxBooxNotes
import com.riddleboox.app.tools.OpenAiBooxNotesVisionReader
import com.riddleboox.app.tools.StoredMemory
import com.riddleboox.app.reply.Toolbox
import com.riddleboox.app.reply.reasoningFor
import com.riddleboox.app.reply.replyClient
import com.riddleboox.app.reply.replyModel
import com.riddleboox.app.ui.OfflineWatcher
import com.riddleboox.app.ui.RegionView
import com.riddleboox.app.ui.RegionViewPanel
import com.riddleboox.app.ui.caption
import com.riddleboox.app.ui.chromeTopInset
import com.riddleboox.app.ui.dp
import com.riddleboox.app.ui.offlineBanner
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
    /** Whether the "begin" button on the welcome screen has been tapped yet. */
    private var onboardingStarted = false
    /** The screen's whole view tree — kept as a field so the onboarding permission overlay can attach to it after onCreate returns. */
    private lateinit var root: FrameLayout
    /** Non-null only while [showOnboardingPermissionOverlay]'s overlay is on screen. */
    private var onboardingPermissionOverlay: View? = null
    /**
     * Non-null only when a diary is configured to be asked — a keyless,
     * deliberately offline diary (see [SettingsStore]) has no use for a
     * "no internet" banner. Started/stopped with the debug receivers in
     * [onResume]/[onPause].
     */
    private var offlineWatcher: OfflineWatcher? = null
    /** Non-null exactly when [offlineWatcher] is — [drawingRect] keeps the pen out of the strip while it is visible. */
    private var offlineBanner: View? = null

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

    /**
     * Debug/dev control channel, debug builds only, for driving and reading
     * back the diary over adb where the e-ink panel is slow to read and hard
     * to interact with by hand:
     * ```
     * adb shell "am broadcast -a com.riddleboox.app.DEBUG_CONTROL --es cmd 'memorize'"
     * adb shell "am broadcast -a com.riddleboox.app.DEBUG_CONTROL --es cmd 'state'"
     * adb shell "am broadcast -a com.riddleboox.app.DEBUG_CONTROL --es cmd 'position'"
     * adb shell "am broadcast -a com.riddleboox.app.DEBUG_CONTROL --es cmd 'write some text'"
     * adb shell "am broadcast -a com.riddleboox.app.DEBUG_CONTROL --es cmd 'bookcheck'"
     * ```
     * `am broadcast` sends an ordered broadcast and blocks to print the
     * receiver's `setResultData`, so the outcome comes back in the same adb
     * call — no separate logcat/screenshot round trip needed to see whether a
     * tap was accepted or why it failed.
     */
    private val debugControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cmd = intent.getStringExtra(EXTRA_DEBUG_CMD)
            Log.i(TAG, "debug control received: $cmd")
            if (cmd == null) {
                resultData = "rejected: no cmd"
                return
            }
            resultData = when {
                cmd == "memorize" -> stateMachine.memorize()
                cmd == "state" -> stateMachine.debugStateSummary()
                cmd == "position" -> stateMachine.debugReplyPosition()
                cmd.startsWith("write ") -> stateMachine.debugWriteReplyInk(cmd.removePrefix("write "))
                cmd == "bookcheck" -> when (val result = checkBookAccess(contentResolver)) {
                    BookAccessCheck.PermissionMissing -> "permission missing"
                    BookAccessCheck.LibraryEmpty -> "granted, library empty"
                    BookAccessCheck.Readable -> "granted, book opened fine"
                    is BookAccessCheck.Unreadable -> "granted, unreadable: ${result.reason}"
                }
                else -> "rejected: unknown cmd '$cmd'"
            }
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
                "The last page may have been cut short when the app closed — what was written may not have been saved.",
                Toast.LENGTH_LONG,
            ).show()
        }

        agentStore = AgentStore(this)
        agentStore.ensureDefaults()
        val selectedId = AgentSelectionStore(this).read()
        val defaultAgent = agentStore.load(selectedId) ?: checkNotNull(agentStore.load("chat")) {
            "ensureDefaults() just created \"chat\" but its manifest is unreadable"
        }
        selectedAgent = resolveLaunchAgent(defaultAgent)

        penSurface = SurfaceView(this)
        regionView = RegionView(this)
        val replyFontSize = ReplyFontSizeStore(this).read()
        regionView.replyFontSizePx = replyFontSize.px
        val penStyle = PenStyleStore(this).read()
        val penWidth = PenStrokeWidthStore(this).read()
        regionView.penStyle = penStyle
        regionView.penWidthScale = penWidth.scale

        val sendMode = SendModeStore(this).read()

        // An imprint line, not a status bar: quiet caption type, low contrast,
        // so it reads like the tiny print at the foot of a book page rather
        // than app chrome competing with the ink.
        //
        // Up only while something is actually happening — see [onBusyChanged].
        // A waiting page has nothing to report, and a caption standing there
        // saying so is one more thing on a line the ink already shares.
        // INVISIBLE rather than GONE: this caption carries the row's whole
        // slack (weight 1), so removing it would slide every control on the
        // far end across the paper each time a turn begins.
        statusView = caption(idleStatus(sendMode)).apply {
            visibility = View.INVISIBLE
            setPadding(dp(6), 0, dp(8), 0)
            // A lookup note can run long — it is cut rather than allowed to
            // push the controls off the far end. The stop label sits directly
            // after these words, inside the room this caption is given.
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        // Stands alone at the far end, in the room the menu has just given up:
        // while a turn runs the line holds exactly two things, what is
        // happening and the word that ends it, one at each margin. Far from the
        // status rather than beside it, because a tap that lands one word off
        // should not be able to end a reply the writer wanted. Same plain
        // caption style as every other control on this line — it is only ever
        // up while a turn is in flight, so there is never another label beside
        // it to confuse it with.
        stopLabel = caption("stop", R.drawable.ic_chrome_stop).apply {
            setPadding(dp(6), 0, dp(6), 0)
            visibility = View.GONE
            setOnClickListener { stateMachine.stopNow() }
        }
        val sendLabel = caption("send", R.drawable.ic_chrome_send).apply {
            setPadding(dp(6), 0, dp(6), 0)
            visibility = sendVisibility(sendMode)
        }
        val newConversationLabel = caption("new conversation", R.drawable.ic_chrome_new_conversation).apply {
            setPadding(dp(6), 0, dp(6), 0)
        }
        val memorizeLabel = caption("memorize", R.drawable.ic_chrome_memorize).apply {
            setPadding(dp(6), 0, dp(6), 0)
        }
        val agentLabel = caption(selectedAgent.name.lowercase(), R.drawable.ic_chrome_agent).apply {
            setPadding(dp(6), 0, dp(6), 0)
        }
        val historyLabel = caption("history", R.drawable.ic_chrome_history).apply {
            setPadding(dp(6), 0, dp(6), 0)
        }
        val settingsLabel = caption("settings", R.drawable.ic_chrome_settings).apply {
            setPadding(dp(6), 0, dp(6), 0)
        }
        // Everything on this line that is not the status and not [stopLabel]:
        // the whole of it goes away while a turn is in flight — see
        // [onBusyChanged]. [sendLabel] is left out because it has a second
        // reason to be hidden ([sendVisibility]) and is restored through that.
        val menuLabels = listOf(
            newConversationLabel,
            memorizeLabel,
            agentLabel,
            historyLabel,
            settingsLabel,
        )

        chromeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // The line's vertical rhythm is set once, here, so every label on
            // it sits on the same centre line instead of each padding itself
            // down by a different amount.
            gravity = Gravity.CENTER_VERTICAL
            // The margin lives on the row, not on the labels at either end of
            // it: [sendLabel] comes and goes with the send mode and the whole
            // menu goes away mid-turn, so whichever label happens to be first
            // or last still opens and closes the line in the same place. It is
            // the column's margin rather than a number of its own — the first
            // label stands over the first letter of the hand written below it,
            // the way a running head sits over its column — less the padding
            // that label carries itself.
            val sideInset = HandwritingPlanner.DEFAULT_MARGIN_X_PX.toInt() - dp(6)
            setPadding(sideInset, chromeTopInset(), sideInset, dp(6))
            // What splits the line in two: what this page does, at the margin
            // the ink below it starts from, and where else the writer can go,
            // at the far end. The gap between them is [statusView], which
            // carries the row's whole slack (weight 1) — so the empty middle of
            // the line *is* the caption, and it gives that room up first when
            // a long lookup note needs it. A caption measured at WRAP_CONTENT
            // instead takes what its words need and pushes the last control off
            // the paper — the one thing its ellipsize is there to prevent.
            listOf(sendLabel, newConversationLabel, memorizeLabel).forEach {
                addView(it, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            }
            addView(
                statusView,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(stopLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            listOf(agentLabel, historyLabel, settingsLabel).forEach {
                addView(it, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            }
        }

        root = FrameLayout(this).apply {
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

        inkCapture = InkCaptureController(
            this,
            penSurface,
            strokeStore,
            inkCallbacks,
            ::drawingRect,
            penStyle = penStyle,
            penWidthScale = penWidth.scale,
        )
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
        val handwritingPlanner = HandwritingPlanner(
            ScriptAwareTextRaster(ShapeAwareTextRaster(PaintTextRaster.fromAsset(this))),
        )
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
            penStyle = penStyle,
            penWidthScale = penWidth.scale,
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
                // The line has one job at a time: while a turn runs it reports
                // that turn and offers the one word that ends it, and the rest
                // of the chrome goes away. Every label on it acts on the page —
                // starting a new conversation, memorizing, leaving for another
                // screen — and none of them are things to do to a page that is
                // mid-answer; hidden is clearer than present-and-ignored on a
                // panel that has no press state to say a tap did nothing.
                statusView.visibility = if (busy) View.VISIBLE else View.INVISIBLE
                stopLabel.visibility = if (busy) View.VISIBLE else View.GONE
                sendLabel.visibility =
                    if (busy) View.GONE else sendVisibility(stateMachine.sendMode)
                menuLabels.forEach { it.visibility = if (busy) View.GONE else View.VISIBLE }
            },
        )

        if (replySettings != null) {
            // Added after the chrome but before the onboarding overlays, so a
            // full-screen intro still covers the strip.
            val banner = offlineBanner(this) {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            banner.visibility = View.GONE
            root.addView(banner, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ))
            offlineBanner = banner
            offlineWatcher = OfflineWatcher(this) { offline ->
                banner.visibility = if (offline) View.VISIBLE else View.GONE
                // A GONE view gets no layout pass of its own, so the hide
                // path re-limits the pen here; the show path is finished by
                // the relimit listener once the strip has a height.
                maybeAttach()
                refreshRegion()
            }
        }

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
                // Right after the diary explains it can read the writer's
                // books (ONBOARDING_SEGMENTS[4]) is where asking for the
                // all-files-access permission actually makes sense to the
                // writer, instead of it appearing unexplained in Settings.
                permissionCheckpointAfter = ONBOARDING_PERMISSION_CHECKPOINT,
                onPermissionCheckpoint = { showOnboardingPermissionOverlay() },
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
        offlineBanner?.addOnLayoutChangeListener(relimit)

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
        newConversationLabel.setOnClickListener { stateMachine.newConversation() }
        memorizeLabel.setOnClickListener { stateMachine.memorize() }
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
     * Keeps a book entry-point local to this Activity run. The saved agent
     * choice remains the normal fallback and is never changed by a book
     * request.
     */
    private fun resolveLaunchAgent(defaultAgent: AgentDefinition): AgentDefinition {
        if (!intent.hasExtra(EXTRA_BOOK_ID)) return defaultAgent
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        if (bookId.isNullOrBlank()) return requestedBookCouldNotBeOpened(defaultAgent)

        val book = runCatching {
            OnyxLibrary(contentResolver).books().firstOrNull { it.id == bookId }
        }.onFailure {
            Log.w(TAG, "requested book lookup failed", it)
        }.getOrNull() ?: return requestedBookCouldNotBeOpened(defaultAgent)

        return runCatching { agentStore.resolveOrCreateBookAgent(book) }
            .onFailure { Log.w(TAG, "requested book agent could not be opened", it) }
            .getOrElse { requestedBookCouldNotBeOpened(defaultAgent) }
    }

    private fun requestedBookCouldNotBeOpened(defaultAgent: AgentDefinition): AgentDefinition {
        Toast.makeText(
            this,
            "The requested book could not be opened. Using your selected agent instead.",
            Toast.LENGTH_LONG,
        ).show()
        return defaultAgent
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
        if (requestCode == REQUEST_AGENTS && resultCode == RESULT_OK) {
            // A book-id launch is one session only. If the writer explicitly
            // chooses an agent, let that saved choice win after recreation.
            if (data?.hasExtra(AgentsActivity.EXTRA_SELECTED_AGENT_ID) == true) {
                intent.removeExtra(EXTRA_BOOK_ID)
            }
            recreate()
        }
        if (requestCode == REQUEST_HISTORY && resultCode == RESULT_OK) {
            pendingResumeId = data?.getStringExtra(HistoryActivity.EXTRA_RESUME_ID)
        }
        // ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION always comes back with
        // RESULT_CANCELED regardless of what the writer actually did on that
        // screen — canOpenBooks() is the only honest way to tell.
        if (requestCode == REQUEST_ONBOARDING_PERMISSION) testBookAccessThenResumeOnboarding()
    }

    /**
     * Called once the writer returns from the all-files-access screen (or
     * taps "not now" without ever leaving). If the switch is now on, this
     * opens one real book to confirm reading actually works end to end — a
     * true permission with a library the diary still can't read from would
     * otherwise surface only much later, mid-conversation. Either way,
     * onboarding always resumes: declining here just means the "books on
     * this device" row in Settings does the asking later instead.
     */
    private fun testBookAccessThenResumeOnboarding() {
        onboardingPermissionOverlay?.let { root.removeView(it) }
        onboardingPermissionOverlay = null
        if (canOpenBooks()) Log.i(TAG, "onboarding permission check: ${checkBookAccess(contentResolver)}")
        onboardingController?.proceedFromCheckpoint()
    }

    /**
     * The one interactive stop in an otherwise non-interactive onboarding —
     * see [OnboardingController]'s permissionCheckpointAfter. Skipped
     * entirely (resumed with nothing shown) when the permission is already
     * granted or this device has nowhere to grant it.
     */
    private fun showOnboardingPermissionOverlay() {
        val grantScreen = allFilesAccess(this)
        if (canOpenBooks() || grantScreen == null) {
            onboardingController?.proceedFromCheckpoint()
            return
        }
        val overlay = permissionOverlay(
            this,
            onAllow = {
                // All-files access alone was found not to be enough on at
                // least one Android 11 device — the switch reads as on, yet
                // every book still throws a permission denial until both
                // legacy storage permissions are also granted. See
                // AndroidManifest.xml's note on READ/WRITE_EXTERNAL_STORAGE.
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_READ_STORAGE,
                )
                startActivityForResult(grantScreen, REQUEST_ONBOARDING_PERMISSION)
            },
            onSkip = { testBookAccessThenResumeOnboarding() },
        )
        onboardingPermissionOverlay = overlay
        root.addView(
            overlay,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
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
     * read_book refuses — an answer that names the Settings switch as the
     * reason, so the refusal sends the writer to the switch instead of reading
     * as a mood. The state is logged here as well, for the bug report that
     * arrives without the page.
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
            // Extracted chapter text survives between turns and app runs, so a
            // second search through the same large book is a file read, not
            // another full pass over the zip. Android may sweep cacheDir when
            // storage runs low, which just means the next search re-extracts.
            textCacheDir = File(cacheDir, "booktext"),
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

    /**
     * Every agent's toolbox: a default set carried by all of them, plus the
     * selected capabilities. Built-ins always use their factory capability
     * set; custom agents use the choices stored in their manifest.
     *
     * The defaults — its own workspace files and its own long-term memory —
     * are senses of the diary itself, not capabilities: they are added here
     * unconditionally, never consulted in a manifest, so a new default tool
     * reaches every agent without any migration of agent.json files. Drawing
     * needs no tool at all — a reply carries its figures as inline SVG, which
     * the reply stream renders itself (see the drawing paragraph of
     * [com.riddleboox.app.reply.Conversation]'s turn protocol).
     */
    private fun agentToolbox(replySettings: ReplySettings?): Toolbox {
        // Built-in capabilities are factory-owned. Keep this guard here so an
        // old or hand-edited manifest cannot narrow the tools exposed at runtime.
        val capabilities = if (selectedAgent.builtin) {
            AgentCapability.defaultsForBuiltin(selectedAgent.id)
        } else {
            selectedAgent.toolIds
        }
        val boxes = buildList {
            add(WorkspaceTools(selectedAgent.workspace))
            // A fresh id per toolbox build, not per turn: this is rebuilt
            // exactly when a new evening starts (app open, agent switch),
            // the same boundary RiddleStateMachine.conversationId uses.
            add(MemoryTools(selectedAgent.workspace, conversationId = UUID.randomUUID().toString()))
            // Every agent may inspect its own definition, while only custom
            // agents may rewrite it. This is part of the default set rather
            // than a capability. The id is bound here and never passed as an
            // argument: an agent reaches itself only.
            // Whatever it writes is read back at the next toolbox build, which
            // is why the tool's answers promise "next time you are opened".
            add(AgentSelfTools(agentStore, selectedAgent.id))
            if (AgentCapability.LIBRARY in capabilities) {
                add(diaryTools())
            }
            if (AgentCapability.DILIB in capabilities) {
                add(DilibTools(this@MainActivity))
            }
            if (AgentCapability.BOOX_NOTES in capabilities) {
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
            // Neither capability alone is enough: this tool reads both the
            // shelf and Notebook in one call, so it only appears once an
            // agent can see both on their own.
            if (AgentCapability.LIBRARY in capabilities && AgentCapability.BOOX_NOTES in capabilities) {
                add(BooxStateTools(OnyxLibrary(contentResolver), OnyxBooxNotes(contentResolver)))
            }
            // This check is intentionally stricter than the manifest alone:
            // no custom agent can gain awareness of the agent-management API.
            if (selectedAgent.builtin && selectedAgent.id == "agent-manager" &&
                AgentCapability.AGENT_MANAGEMENT in capabilities
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

    /**
     * Where the pen is allowed to write: the page below the chrome.
     *
     * The status line and the settings/reset labels sit across the top, and a
     * hand resting there while writing would otherwise land strokes on them —
     * or press them. Handing the SDK a limit rect that stops at the chrome's
     * bottom edge means the pen simply is not read up there, which is a
     * stronger guarantee than hoping nobody touches it.
     *
     * The offline banner borrows the bottom edge the same way while it is
     * visible: its "wi-fi settings" tap must not double as an ink dot, and
     * raw pen capture ignores ordinary view hit-testing entirely.
     */
    private fun drawingRect(): Rect {
        val w = penSurface.width
        val h = penSurface.height
        if (w <= 0 || h <= 0) return Rect(0, 0, 0, 0)
        val top = (if (::chromeRow.isInitialized) chromeRow.height else 0).coerceAtMost(h)
        val bannerHeight = offlineBanner?.takeIf { it.visibility == View.VISIBLE }?.height ?: 0
        return Rect(0, top, w, (h - bannerHeight).coerceAtLeast(top))
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
        offlineWatcher?.start()
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this,
                demoWriteReceiver,
                IntentFilter(ACTION_DEMO_WRITE),
                ContextCompat.RECEIVER_EXPORTED,
            )
            ContextCompat.registerReceiver(
                this,
                debugControlReceiver,
                IntentFilter(ACTION_DEBUG_CONTROL),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }

    override fun onPause() {
        offlineWatcher?.stop()
        if (BuildConfig.DEBUG) {
            unregisterReceiver(demoWriteReceiver)
            unregisterReceiver(debugControlReceiver)
        }
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
        if (::stateMachine.isInitialized) stateMachine.close()
        super.onDestroy()
    }

    companion object {
        /** Exact NeoReader [Book.id] supplied by a trusted, explicit caller. */
        const val EXTRA_BOOK_ID = "com.riddleboox.app.MAIN_BOOK_ID"

        /** Opens a session with the existing-or-new normal agent for [bookId]. */
        fun intent(context: Context, bookId: String): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_BOOK_ID, bookId)

        private const val REQUEST_SETTINGS = 1
        private const val REQUEST_HISTORY = 2
        private const val REQUEST_AGENTS = 3
        private const val REQUEST_ONBOARDING_PERMISSION = 4
        private const val REQUEST_READ_STORAGE = 5

        /** Index into ONBOARDING_SEGMENTS after which the all-files-access ask happens — see [showOnboardingPermissionOverlay]. */
        private const val ONBOARDING_PERMISSION_CHECKPOINT = 4

        /** How long to wait before looking again for a surface with a size. */
        private const val ATTACH_RETRY_MS = 100L

        private const val TAG = "MainActivity"
        private const val ACTION_DEMO_WRITE = "com.riddleboox.app.DEMO_WRITE"
        private const val EXTRA_DEMO_TEXT = "text"
        private const val ACTION_DEBUG_CONTROL = "com.riddleboox.app.DEBUG_CONTROL"
        private const val EXTRA_DEBUG_CMD = "cmd"
    }
}
