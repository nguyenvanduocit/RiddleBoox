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
import android.widget.Toast
import androidx.core.content.FileProvider
import com.riddleboox.app.BuildConfig
import com.riddleboox.app.agent.AgentStore
import com.riddleboox.app.backup.wholeDiaryBackup
import com.riddleboox.app.history.ConversationStore
import com.riddleboox.app.library.BookAccessCheck
import com.riddleboox.app.library.canOpenBooks
import com.riddleboox.app.library.checkBookAccess
import com.riddleboox.app.library.persistStorageGrant
import com.riddleboox.app.library.requestStorageAccessIntent
import com.riddleboox.app.reply.fetchModelIds
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
 * Every row on the page is one of three shapes: a plain text field (api key),
 * a choice picked from a list — base url and model both offer the same
 * two-named-choices-plus-"type it in…" shape, see [pickBaseUrl]/[pickModel] —
 * plus everything wrapped in [EnumSettingRow], or a self-contained toggle that
 * writes through its own store immediately ([PinField]) rather than waiting
 * for "save". [dirty] and [writeAndFinish] only ever need to know about the
 * first two.
 */
class SettingsActivity : Activity() {

    private lateinit var store: SettingsStore
    private lateinit var defaults: ReplySettings

    /** What was on the page when it opened — the thing [dirty] compares against. */
    private lateinit var loaded: ReplySettings
    private lateinit var baseUrlChooser: TextView
    private lateinit var apiKeyField: EditText
    private lateinit var modelField: TextView
    private lateinit var libraryField: TextView

    /** The named server picked on the base-url row; null means "other", typed via [promptCustomBaseUrl]. */
    private var chosenProvider: Provider? = null

    /** What "other" resolves to — set only by [promptCustomBaseUrl], the same way [chosenModel] backs [modelField]. */
    private var customBaseUrl: String = ""
    private var chosenModel: String = ""

    /** reply font size, send mode, pen style, stroke width — see [EnumSettingRow]. */
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

        chosenProvider = providerFor(current.baseUrl)
        customBaseUrl = current.baseUrl
        baseUrlChooser = chooserField("") { pickBaseUrl() }
        // Masked: the key is a bearer token to a billed account, and settings
        // is the one screen that gets opened while someone else is helping
        // with the setup. A doubted character is cheaper re-pasted than shown.
        apiKeyField = valueField(
            current.apiKey,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
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
        val sendModeStore = SendModeStore(this)
        val sendModeRow = EnumSettingRow(
            activity = this,
            entries = SendMode.entries,
            labelOf = SendMode::label,
            dialogTitle = "send mode",
            read = sendModeStore::read,
            write = sendModeStore::write,
        )
        val penStyleStore = PenStyleStore(this)
        val penStyleRow = EnumSettingRow(
            activity = this,
            entries = PenStyle.entries,
            labelOf = PenStyle::label,
            dialogTitle = "pen style",
            read = penStyleStore::read,
            write = penStyleStore::write,
        )
        val penWidthStore = PenStrokeWidthStore(this)
        val penWidthRow = EnumSettingRow(
            activity = this,
            entries = PenStrokeWidth.entries,
            labelOf = PenStrokeWidth::label,
            dialogTitle = "stroke width",
            read = penWidthStore::read,
            write = penWidthStore::write,
        )
        enumRows = listOf(fontSizeRow, sendModeRow, penStyleRow, penWidthRow)

        val onboardingStore = OnboardingStore(this)
        val pinField = PinField(this)

        libraryField = statusField()
        showBaseUrl()
        val column = textBlock().apply {
            // Both header actions act on the section as a whole rather than
            // any one field, but not on the same fields: "restore defaults"
            // touches base url and model only — never the api key, see
            // resetConnectionDefaults() — while "set up from phone" (QR
            // pairing) overwrites all three, api key included, see
            // onActivityResult().
            addView(
                sectionHeader(
                    "AI model",
                    "restore defaults" to { resetConnectionDefaults() },
                    "set up from phone" to { openPairing() },
                ),
            )
            addView(field("base url", baseUrlChooser))
            addView(field("api key", apiKeyField))
            addView(field("model", modelField))

            addView(sectionHeader("reading & writing"))
            addView(field("reply font size", fontSizeRow.field))
            addView(field("send mode", sendModeRow.field))
            addView(field("pen style", penStyleRow.field))
            addView(field("stroke width", penWidthRow.field))

            addView(sectionHeader("permissions", "check" to { checkPermissions() }))
            addView(field("books on this device", libraryField))

            addView(sectionHeader("security & info"))
            // Không tự finish() ở đây — gọi lại save() để không đánh mất các
            // field khác đang sửa dở trên cùng màn hình. save() lưu luôn mọi
            // field khác trên màn hình này (base url, api key, model, reply
            // font size, send mode), không chỉ riêng cờ onboarding — có chủ
            // đích, không phải side effect ngoài ý muốn.
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
        // "save" rides in the running head rather than under the last field:
        // the head is pinned above the scrolling body, so it stays in view
        // however long the page grows and whatever the keyboard takes from the
        // foot of it — see paperPage and holdAboveKeyboard.
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
        effectiveBaseUrl() != loaded.baseUrl ||
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
        refreshPermissionsRow()
    }

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

    /**
     * The "permissions" section's own action, not tied to any one row under
     * it: re-reads the grant (in case it changed since [onResume] last ran)
     * and, when it's held, actually opens a book rather than trusting the
     * grant alone — the same check the onboarding permission step runs, see
     * [checkBookAccess].
     */
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

    /**
     * Applies [PairActivity]'s result to this form the way a hand-typed edit
     * would land: nothing is saved here, "save" still does that. See
     * [pairingPayloadFrom] for why the actual extraction is a plain function
     * rather than logic inline in this override. The folder-grant picker
     * shares this same override, since both are ordinary `startActivityForResult`
     * round trips — [REQUEST_STORAGE_GRANT] hands the granted Uri straight to
     * [persistStorageGrant] and refreshes the row, [REQUEST_PAIR] fills the form.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_STORAGE_GRANT) {
            data?.data?.let { persistStorageGrant(this, it) }
            refreshPermissionsRow()
            return
        }
        if (requestCode != REQUEST_PAIR || resultCode != RESULT_OK || data == null) return
        val payload = pairingPayloadFrom(data) ?: return
        chosenProvider = providerFor(payload.baseUrl)
        customBaseUrl = payload.baseUrl
        showBaseUrl()
        apiKeyField.setText(payload.apiKey)
        if (payload.model.isNotEmpty()) choose(payload.model)
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
        if (looksLikeItHasTrailingV1(effectiveBaseUrl())) {
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
                baseUrl = effectiveBaseUrl(),
                apiKey = apiKeyField.text.toString(),
                model = chosenModel,
            ).sanitized(defaults),
        )
        enumRows.forEach { it.save() }
        setResult(RESULT_OK)
        finish()
    }

    /**
     * The named servers, same shape as [pickModelFrom]: a single-choice list
     * for the two well-known ones, plus a neutral "type it in…" button that
     * opens [promptCustomBaseUrl] for anything else.
     */
    private fun pickBaseUrl() {
        val labels = PROVIDERS.map { it.label + "\n" + it.baseUrl }.toTypedArray()
        val checked = PROVIDERS.indexOf(chosenProvider)
        AlertDialog.Builder(this)
            .setTitle("base url")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                chosenProvider = PROVIDERS[which]
                showBaseUrl()
                dialog.dismiss()
            }
            .setNeutralButton("type it in…") { _, _ -> promptCustomBaseUrl() }
            .show()
    }

    /** Puts [chosenProvider] on the form, or [customBaseUrl] when it's a hand-typed one. */
    private fun showBaseUrl() {
        baseUrlChooser.text = chosenProvider?.label ?: customBaseUrl
    }

    /**
     * The escape hatch the list can't offer: a base url typed by hand, same
     * pattern as [promptCustomModel]. Prefilled with [customBaseUrl] so
     * re-opening this to tweak one character doesn't require retyping the
     * whole thing; an empty submission is treated as "changed my mind" and
     * leaves the form untouched.
     */
    private fun promptCustomBaseUrl() {
        val input = valueField(customBaseUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        AlertDialog.Builder(this)
            .setTitle("base url")
            .setView(input)
            .setNegativeButton("cancel", null)
            .setPositiveButton("use") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    chosenProvider = null
                    customBaseUrl = url
                    showBaseUrl()
                }
            }
            .show()
    }

    /** The base url the settings on this form add up to. */
    private fun effectiveBaseUrl(): String = chosenProvider?.baseUrl ?: customBaseUrl

    /**
     * Opens [PairActivity]. Its result — base url, api key, model — lands in
     * [onActivityResult] and is applied to this form exactly the way a
     * hand-typed edit would be: nothing reaches [SettingsStore] until "save"
     * is tapped, same as every other field here.
     */
    private fun openPairing() {
        startActivityForResult(PairActivity.intent(this), REQUEST_PAIR)
    }

    /**
     * Asks the configured server which models it actually serves — the base
     * url and key on the form right now, not the saved ones, so a writer
     * midway through pointing the diary somewhere new sees that server's
     * catalogue. The wait dialog's "cancel" is the abort: once it is gone,
     * a late answer shows nothing. When the server cannot be asked — offline,
     * wrong key, empty catalogue — the curated shortlist steps in, so the
     * picker still works on a beach.
     */
    private fun pickModel() {
        val baseUrl = effectiveBaseUrl()
        val apiKey = apiKeyField.text.toString().trim()
        val waiting = AlertDialog.Builder(this)
            .setMessage("asking the server for its models…")
            .setNegativeButton("cancel", null)
            .show()
        Thread {
            val fetched = runCatching { fetchModelIds(baseUrl, apiKey) }.getOrNull()
            runOnUiThread {
                // A backgrounded activity the OS reclaimed still runs this
                // callback, and a forced window teardown leaves the dialog's
                // own isShowing stale-true — showing the next dialog on a dead
                // activity would BadTokenException.
                if (isFinishing || isDestroyed || !waiting.isShowing) return@runOnUiThread
                waiting.dismiss()
                val ids = fetched.orEmpty()
                if (ids.isEmpty()) pickModelFromShortlist() else pickModelFrom(ids)
            }
        }.apply { isDaemon = true; start() }
    }

    /**
     * The server's own list. Tapping a row chooses it; what is picked is
     * remembered in [chosenModel] because a TextView has no text to read back
     * on save the way an EditText does. The model in use leads the list even
     * when the server no longer carries it: opening this dialog must not be
     * able to silently rewrite a working setup. The neutral button opens
     * [promptCustomModel] for an id the server misreports or hides.
     */
    private fun pickModelFrom(ids: List<String>) {
        val choices = if (chosenModel.isBlank() || chosenModel in ids) ids else listOf(chosenModel) + ids
        AlertDialog.Builder(this)
            .setTitle("model")
            .setSingleChoiceItems(choices.toTypedArray(), choices.indexOf(chosenModel)) { dialog, which ->
                choose(choices[which])
                dialog.dismiss()
            }
            .setNeutralButton("type it in…") { _, _ -> promptCustomModel() }
            .show()
    }

    /**
     * The offline fallback, and the one place the measured notes still show:
     * the fetched list is bare ids, but these few have been read against the
     * same handwritten page — see [modelChoices].
     */
    private fun pickModelFromShortlist() {
        val choices = modelChoices(chosenModel)
        val labels = choices.map { it.label + "\n" + it.note }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("model — couldn't reach the server, showing the shortlist")
            .setSingleChoiceItems(labels, choices.indexOfFirst { it.id == chosenModel }) { dialog, which ->
                choose(choices[which].id)
                dialog.dismiss()
            }
            .setNeutralButton("type it in…") { _, _ -> promptCustomModel() }
            .show()
    }

    private fun choose(modelId: String) {
        chosenModel = modelId
        modelField.text = modelId
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
        chosenProvider = providerFor(defaults.baseUrl)
        customBaseUrl = defaults.baseUrl
        showBaseUrl()
        choose(defaults.model)
    }

    /**
     * The escape hatch the lists can't offer: a model id typed by hand for
     * whatever the server misreports or hides. Prefilled with the current id
     * so re-opening this to tweak one character doesn't require retyping the
     * whole thing; an empty submission is treated as "changed my mind" and
     * leaves [chosenModel] untouched.
     */
    private fun promptCustomModel() {
        val input = valueField(chosenModel, InputType.TYPE_CLASS_TEXT)
        AlertDialog.Builder(this)
            .setTitle("model")
            .setView(input)
            .setNegativeButton("cancel", null)
            .setPositiveButton("use") { _, _ ->
                val id = input.text.toString().trim()
                if (id.isNotEmpty()) choose(id)
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
        private const val REQUEST_STORAGE_GRANT = 1
        private const val REQUEST_PAIR = 2

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
