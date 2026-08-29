package com.riddleboox.app.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.FileProvider
import com.riddleboox.app.BuildConfig
import com.riddleboox.app.agent.AgentStore
import com.riddleboox.app.backup.wholeDiaryBackup
import com.riddleboox.app.history.ConversationStore
import com.riddleboox.app.library.allFilesAccess
import com.riddleboox.app.library.canOpenBooks
import com.riddleboox.app.reply.VisionModel
import com.riddleboox.app.reply.modelChoices
import com.riddleboox.app.tools.readMemories
import com.riddleboox.app.ui.openPaperWindow
import com.riddleboox.app.ui.paperPage
import com.riddleboox.app.ui.runningHead
import com.riddleboox.app.ui.textBlock
import java.io.File

/**
 * The one screen where the diary is configured rather than written in. Same
 * sheet of paper as everywhere else — see `ui/Paper.kt` — so the settings read
 * as the endpapers of the book rather than an Android dialog bolted on.
 *
 * Holds no state: it prefills from [SettingsStore], and on save writes back
 * and leaves. Defaults arrive as intent extras rather than reading
 * `BuildConfig` directly — see [intent] — except for the version line at the
 * bottom of the page, which is read-only display with no default to override.
 *
 * Every row on the page is one of three shapes: a plain text field (base
 * url, api key — read straight off the [EditText] in [writeAndFinish]), a
 * preset picked from a short list (model, plus everything wrapped in
 * [EnumSettingRow]), or a self-contained toggle that writes through its own
 * store immediately ([PinField]) rather than waiting for "save". [dirty] and
 * [writeAndFinish] only ever need to know about the first two.
 */
class SettingsActivity : Activity() {

    private lateinit var store: SettingsStore
    private lateinit var defaults: ReplySettings

    /** What was on the page when it opened — the thing [dirty] compares against. */
    private lateinit var loaded: ReplySettings
    private lateinit var baseUrlField: EditText
    private lateinit var apiKeyField: EditText
    private lateinit var modelField: TextView
    private lateinit var libraryField: TextView
    private var chosenModel: String = ""

    /** reply font size, transcript font size, send mode — see [EnumSettingRow]. */
    private lateinit var enumRows: List<EnumSettingRow<*>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPaperWindow()

        store = SettingsStore(this)
        defaults = ReplySettings(
            baseUrl = intent.getStringExtra(EXTRA_DEFAULT_BASE_URL).orEmpty(),
            apiKey = intent.getStringExtra(EXTRA_DEFAULT_API_KEY).orEmpty(),
            model = intent.getStringExtra(EXTRA_DEFAULT_MODEL).orEmpty(),
        )
        val current = store.readOrDefault(defaults.baseUrl, defaults.apiKey, defaults.model)
        loaded = current

        baseUrlField = valueField(
            current.baseUrl,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        // Visible on purpose: the key is pasted or hand-typed once on a device
        // with no physical keyboard, and a masked field makes a single wrong
        // character indistinguishable from a wrong account.
        apiKeyField = valueField(
            current.apiKey,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )
        chosenModel = current.model
        modelField = chooserField(current.model) { pickModel() }

        val fontSizeStore = ReplyFontSizeStore(this)
        val fontSizeRow = EnumSettingRow(
            activity = this,
            entries = ReplyFontSize.entries,
            labelOf = ReplyFontSize::label,
            dialogTitle = "font size",
            read = fontSizeStore::read,
            write = fontSizeStore::write,
        )
        val transcriptFontSizeStore = TranscriptFontSizeStore(this)
        val transcriptFontSizeRow = EnumSettingRow(
            activity = this,
            entries = TranscriptFontSize.entries,
            labelOf = TranscriptFontSize::label,
            dialogTitle = "transcript font size",
            read = transcriptFontSizeStore::read,
            write = transcriptFontSizeStore::write,
        )
        val sendModeStore = SendModeStore(this)
        val sendModeRow = EnumSettingRow(
            activity = this,
            entries = SendMode.entries,
            labelOf = SendMode::label,
            dialogTitle = "send mode",
            read = sendModeStore::read,
            write = sendModeStore::write,
        )
        enumRows = listOf(fontSizeRow, transcriptFontSizeRow, sendModeRow)

        val onboardingStore = OnboardingStore(this)
        val pinField = PinField(this)

        libraryField = statusField()
        val column = textBlock().apply {
            addView(sectionHeader("connection"))
            addView(field("base url", baseUrlField))
            addView(field("api key", apiKeyField))
            addView(field("model", modelField))
            // Ngay dưới "model" vì đây là field cấu hình kết nối cuối cùng —
            // một nút gộp chung, khôi phục cả "base url" lẫn "model" về giá
            // trị mặc định lúc build; "api key" cố ý bị loại, xem
            // resetConnectionDefaults().
            addView(
                field(
                    "restore defaults (base url + model)",
                    chooserField("tap to restore") { resetConnectionDefaults() },
                ),
            )

            addView(sectionHeader("reading & writing"))
            addView(field("reply font size", fontSizeRow.field))
            addView(field("transcript font size", transcriptFontSizeRow.field))
            addView(field("send mode", sendModeRow.field))
            addView(field("books on this device", libraryField))

            addView(sectionHeader("security & info"))
            // Không tự finish() ở đây — gọi lại save() để không đánh mất các
            // field khác đang sửa dở trên cùng màn hình. save() lưu luôn mọi
            // field khác trên màn hình này (base url, api key, model, reply
            // font size, transcript font size, send mode), không chỉ riêng cờ
            // onboarding — có chủ đích, không phải side effect ngoài ý muốn.
            addView(field("introduction", chooserField("tap to replay") {
                onboardingStore.write(false)
                save()
            }))
            // Không đưa vào dirty()/save(): PinField ghi thẳng qua PinStore
            // ngay khi chạm, không phải state chờ nút "save" ở đầu trang — một
            // PIN vừa đặt mà "Discard unsaved changes?" xoá mất là khoá giả.
            addView(field("PIN lock", pinField.field))
            // Đọc, ghi ra một file rồi mở share sheet ngay khi chạm — không có
            // gì để "save" hay "discard", nên cũng đứng ngoài dirty()/save()
            // như "introduction" và "PIN lock" ở trên.
            addView(field("back up all data", chooserField("tap to export") { backupAll() }))
            // Đọc-chỉ-đọc, không thuộc dirty()/save(): chỉ để nhận dạng bản
            // build khi cần hỗ trợ ("bạn đang dùng bản nào?"), không phải một
            // setting có thể đổi.
            addView(field("version", statusField().apply {
                text = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
            }))
        }
        // "save" rides in the running head rather than under the last field: the
        // soft keyboard eats the lower half of the screen while a field is
        // focused, and the head is the one strip of page it never covers.
        setContentView(paperPage(runningHead("settings", "save", onAction = { save() }) { leave() }, column))
    }

    /**
     * The way off this screen that does not write anything.
     *
     * It asks first, and only when there is something to lose: an api key is
     * pasted or hand-typed once on a device with no keyboard, and a stylus tap
     * on the wrong word at the top of the page should not be able to throw it
     * away silently. With nothing edited there is nothing to ask about, and
     * the question would just be a second tap between the writer and the page.
     */
    private fun leave() {
        if (!dirty()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Discard unsaved changes?")
            .setNegativeButton("cancel", null)
            .setPositiveButton("discard") { _, _ -> finish() }
            .show()
    }

    /**
     * Compares raw text, not [ReplySettings.sanitized] values: a field the
     * writer emptied reads back as the default it falls to, and calling that
     * "unchanged" would drop the edit without asking.
     */
    private fun dirty(): Boolean =
        baseUrlField.text.toString() != loaded.baseUrl ||
            apiKeyField.text.toString() != loaded.apiKey ||
            chosenModel != loaded.model ||
            enumRows.any { it.dirty }

    /**
     * Whatever produced the back gesture, leaving costs the same question:
     * the navigation bar this page hides can still be swiped back into view.
     *
     * Deprecated in favour of `OnBackPressedDispatcher`, which belongs to
     * `ComponentActivity`; these screens are plain `android.app.Activity` and
     * the framework still routes the key here for them.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = leave()

    /**
     * The one row on this screen that is not a setting.
     *
     * Whether the diary can read the words inside the writer's books is a
     * system permission, granted in Android's own Settings and never here —
     * which is exactly why it is read in `onResume` and not once in
     * [onCreate]: the writer leaves this screen to turn it on and comes back,
     * and a line that still said "no" would read as the switch not having
     * worked.
     *
     * The row exists at all because the alternative is a command on a computer.
     * The shelf, the reading progress and every marked passage work without it;
     * only opening a book does, so the wording says which half is missing
     * rather than calling it an error.
     */
    override fun onResume() {
        super.onResume()
        val canOpen = canOpenBooks()
        val where = if (canOpen) null else allFilesAccess(this)
        libraryField.text = when {
            canOpen -> "can read the words inside your books"
            where != null -> "can only see titles, progress and notes — tap to allow reading whole books"
            else -> "this device has no switch for reading book files"
        }
        libraryField.isClickable = where != null
        libraryField.setOnClickListener(where?.let { screen -> View.OnClickListener { startActivity(screen) } })
    }

    /**
     * Gate before [writeAndFinish], not a correction of it: a base URL that
     * already ends in `/v1` makes koog double it up into
     * `.../v1/v1/chat/completions`, a 404 with no body and no other symptom —
     * see README's "Base URL là gốc server, không kèm `/v1`". Some self-hosted
     * proxies genuinely want `/v1` at the root, so this only asks; it never
     * rewrites the field.
     */
    private fun save() {
        if (looksLikeItHasTrailingV1(baseUrlField.text.toString())) {
            AlertDialog.Builder(this)
                .setMessage(
                    "This base URL seems to end in \"/v1\" — every API request " +
                        "may silently fail with a 404 (see README). Save anyway?",
                )
                .setNegativeButton("edit it", null)
                .setPositiveButton("save anyway") { _, _ -> writeAndFinish() }
                .show()
            return
        }
        writeAndFinish()
    }

    /** True for `.../v1` and `.../v1/`, case-insensitively — see [save]. */
    private fun looksLikeItHasTrailingV1(url: String): Boolean =
        url.trim().trimEnd('/').endsWith("/v1", ignoreCase = true)

    private fun writeAndFinish() {
        store.write(
            ReplySettings(
                baseUrl = baseUrlField.text.toString(),
                apiKey = apiKeyField.text.toString(),
                model = chosenModel,
            ).sanitized(defaults),
        )
        enumRows.forEach { it.save() }
        setResult(RESULT_OK)
        finish()
    }

    /**
     * The shortlist is the fast path: these are provider-qualified ids like
     * `openai/gpt-5.6-luna`, and hand-typing one on a stylus tablet is how a
     * working setup turns into a 404 over a single character. Tapping a row
     * chooses it; what is picked is remembered in [chosenModel] because a
     * TextView has no text to read back on save the way an EditText does.
     * The neutral button opens [promptCustomModel] for the one case the
     * shortlist can't cover — a model too new to have been added yet, or a
     * self-hosted one.
     *
     * Not an [EnumSettingRow]: the shortlist's labels are composed from two
     * fields (`label` + `note`) rather than one, matching is by id rather
     * than equality, and the neutral "type it in…" escape hatch has no
     * equivalent in a preset list — three differences, not a preset picker
     * with different words.
     */
    private fun pickModel() {
        val choices = modelChoices(chosenModel)
        val labels = choices.map { it.label + "\n" + it.note }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("model")
            .setSingleChoiceItems(labels, choices.indexOfFirst { it.id == chosenModel }) { dialog, which ->
                choose(choices[which])
                dialog.dismiss()
            }
            .setNeutralButton("type it in…") { _, _ -> promptCustomModel() }
            .show()
    }

    private fun choose(model: VisionModel) {
        chosenModel = model.id
        modelField.text = model.id
    }

    /**
     * Puts [defaults] — the build-time values from `local.properties`, read
     * once in [onCreate] — back onto the form for base url and model only.
     * Only the form changes here, same as every other field on this screen:
     * nothing reaches [SettingsStore] until "save" is tapped, so a mis-tap
     * just needs "Discard unsaved changes?" in [leave], not an undo.
     *
     * Deliberately leaves [apiKeyField] alone. The key on the form right now
     * may be the writer's own, pasted in by hand — not the build's default —
     * and swapping it for the build default here would silently switch
     * which account every request bills to, with no typo to notice. Base
     * url and model carry no such risk: a wrong one only 404s.
     */
    private fun resetConnectionDefaults() {
        baseUrlField.setText(defaults.baseUrl)
        chosenModel = defaults.model
        modelField.text = defaults.model
    }

    /**
     * The escape hatch [pickModel] can't offer: a model id typed by hand for
     * whatever isn't on the shortlist yet. No [VisionModel] backs it — there
     * is no label or note to show, only the id the writer typed — so it
     * writes straight into [chosenModel] the way [choose] writes `model.id`.
     * Prefilled with the current id so re-opening this to tweak one character
     * doesn't require retyping the whole thing; an empty submission is
     * treated as "changed my mind" and leaves [chosenModel] untouched.
     */
    private fun promptCustomModel() {
        val input = valueField(chosenModel, InputType.TYPE_CLASS_TEXT)
        AlertDialog.Builder(this)
            .setTitle("model")
            .setView(input)
            .setNegativeButton("cancel", null)
            .setPositiveButton("use") { _, _ ->
                val id = input.text.toString().trim()
                if (id.isNotEmpty()) {
                    chosenModel = id
                    modelField.text = id
                }
            }
            .show()
    }

    /**
     * The one export that reaches every agent at once — see
     * [wholeDiaryBackup]. Re-reads every store fresh rather than anything
     * cached on this screen, the same reason
     * [com.riddleboox.app.history.HistoryActivity.exportAll] does: a backup
     * is meant to be what's on disk right now.
     */
    private fun backupAll() {
        val agents = AgentStore(this).list()
        val conversations = ConversationStore(this).list()
        val memories = agents.associate { it.id to readMemories(it.workspace) }
        val text = wholeDiaryBackup(agents, conversations, memories, exportedAtMs = System.currentTimeMillis())

        val exportsDir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, "riddlebox-backup.txt")
        file.writeText(text)

        val uri = FileProvider.getUriForFile(this, "$packageName.onyx.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share the whole backup"))
    }

    companion object {
        private const val EXTRA_DEFAULT_BASE_URL = "com.riddleboox.app.settings.DEFAULT_BASE_URL"
        private const val EXTRA_DEFAULT_API_KEY = "com.riddleboox.app.settings.DEFAULT_API_KEY"
        private const val EXTRA_DEFAULT_MODEL = "com.riddleboox.app.settings.DEFAULT_MODEL"
        /**
         * Opens the screen with the build-time values as its factory defaults:
         * what a field falls back to when it has never been saved, or when the
         * user empties it.
         */
        fun intent(context: Context, defaults: ReplySettings): Intent =
            Intent(context, SettingsActivity::class.java)
                .putExtra(EXTRA_DEFAULT_BASE_URL, defaults.baseUrl)
                .putExtra(EXTRA_DEFAULT_API_KEY, defaults.apiKey)
                .putExtra(EXTRA_DEFAULT_MODEL, defaults.model)
    }
}
