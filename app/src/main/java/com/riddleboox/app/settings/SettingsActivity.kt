package com.riddleboox.app.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.riddleboox.app.library.allFilesAccess
import com.riddleboox.app.library.canOpenBooks
import com.riddleboox.app.reply.VisionModel
import com.riddleboox.app.reply.modelChoices
import com.riddleboox.app.ui.caption
import com.riddleboox.app.ui.dp
import com.riddleboox.app.ui.openPaperWindow
import com.riddleboox.app.ui.paperPage
import com.riddleboox.app.ui.runningHead
import com.riddleboox.app.ui.textBlock

/**
 * The one screen where the diary is configured rather than written in. Same
 * sheet of paper as everywhere else — see `ui/Paper.kt` — so the settings read
 * as the endpapers of the book rather than an Android dialog bolted on.
 *
 * Holds no state: it prefills from [SettingsStore], and on save writes back
 * and leaves. Defaults arrive as intent extras so this screen never reads
 * `BuildConfig` itself — see [intent].
 */
class SettingsActivity : Activity() {

    private lateinit var store: SettingsStore
    private lateinit var fontSizeStore: ReplyFontSizeStore
    private lateinit var transcriptFontSizeStore: TranscriptFontSizeStore
    private lateinit var sendModeStore: SendModeStore
    private lateinit var defaults: ReplySettings

    /** What was on the page when it opened — the thing [dirty] compares against. */
    private lateinit var loaded: ReplySettings
    private var loadedFontSize: ReplyFontSize = ReplyFontSize.Default
    private var loadedTranscriptFontSize: TranscriptFontSize = TranscriptFontSize.Default
    private var loadedSendMode: SendMode = SendMode.Auto
    private lateinit var baseUrlField: EditText
    private lateinit var apiKeyField: EditText
    private lateinit var modelField: TextView
    private lateinit var fontSizeField: TextView
    private lateinit var transcriptFontSizeField: TextView
    private lateinit var sendModeField: TextView
    private lateinit var libraryField: TextView
    private lateinit var pinStore: PinStore
    private lateinit var pinField: TextView
    private var chosenModel: String = ""
    private var chosenFontSize: ReplyFontSize = ReplyFontSize.Default
    private var chosenTranscriptFontSize: TranscriptFontSize = TranscriptFontSize.Default
    private var chosenSendMode: SendMode = SendMode.Auto

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

        fontSizeStore = ReplyFontSizeStore(this)
        val currentFontSize = fontSizeStore.read()
        loadedFontSize = currentFontSize
        chosenFontSize = currentFontSize
        fontSizeField = chooserField(currentFontSize.label) { pickFontSize() }

        transcriptFontSizeStore = TranscriptFontSizeStore(this)
        val currentTranscriptFontSize = transcriptFontSizeStore.read()
        loadedTranscriptFontSize = currentTranscriptFontSize
        chosenTranscriptFontSize = currentTranscriptFontSize
        transcriptFontSizeField = chooserField(currentTranscriptFontSize.label) { pickTranscriptFontSize() }

        sendModeStore = SendModeStore(this)
        val currentSendMode = sendModeStore.read()
        loadedSendMode = currentSendMode
        chosenSendMode = currentSendMode
        sendModeField = chooserField(currentSendMode.label) { pickSendMode() }

        val onboardingStore = OnboardingStore(this)

        pinStore = PinStore(this)
        pinField = chooserField(pinFieldLabel()) { togglePin() }

        libraryField = statusField()
        val column = textBlock().apply {
            addView(field("base url", baseUrlField))
            addView(field("api key", apiKeyField))
            addView(field("model", modelField))
            addView(field("cỡ chữ trả lời", fontSizeField))
            addView(field("cỡ chữ đọc lại", transcriptFontSizeField))
            addView(field("chế độ gửi", sendModeField))
            addView(field("sách trên máy", libraryField))
            // Không tự finish() ở đây — gọi lại save() để không đánh mất các
            // field khác đang sửa dở trên cùng màn hình. save() lưu luôn mọi
            // field khác trên màn hình này (base url, api key, model, cỡ chữ
            // trả lời, cỡ chữ đọc lại, chế độ gửi), không chỉ riêng cờ
            // onboarding — có chủ đích, không phải side effect ngoài ý muốn.
            addView(field("giới thiệu", chooserField("chạm để xem lại") {
                onboardingStore.write(false)
                save()
            }))
            // Không đưa vào dirty()/save(): bật/tắt PIN ghi thẳng qua PinStore
            // ngay khi chạm, không phải state chờ nút "lưu" ở đầu trang — một
            // PIN vừa đặt mà "bỏ những thay đổi chưa lưu?" xoá mất là khoá giả.
            // Chu kỳ này chỉ dựng chỗ đặt/xoá PIN; màn hình khoá thật đọc
            // PinStore.verify() là việc của chu kỳ sau, chưa nối ở đây.
            addView(field("khoá bằng mã PIN", pinField))
        }
        // "lưu" rides in the running head rather than under the last field: the
        // soft keyboard eats the lower half of the screen while a field is
        // focused, and the head is the one strip of page it never covers.
        setContentView(paperPage(runningHead("cấu hình", "lưu", onAction = { save() }) { leave() }, column))
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
            .setMessage("Bỏ những thay đổi chưa lưu?")
            .setNegativeButton("thôi", null)
            .setPositiveButton("bỏ") { _, _ -> finish() }
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
            chosenFontSize != loadedFontSize ||
            chosenTranscriptFontSize != loadedTranscriptFontSize ||
            chosenSendMode != loadedSendMode

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
            canOpen -> "đọc được cả chữ bên trong sách"
            where != null -> "mới đọc được nhan đề, tiến độ và ghi chú — chạm để cho phép đọc cả sách"
            else -> "máy này không có công tắc cho phép đọc file sách"
        }
        libraryField.isClickable = where != null
        libraryField.setOnClickListener(where?.let { screen -> View.OnClickListener { startActivity(screen) } })
    }

    private fun save() {
        store.write(
            ReplySettings(
                baseUrl = baseUrlField.text.toString(),
                apiKey = apiKeyField.text.toString(),
                model = chosenModel,
            ).sanitized(defaults),
        )
        fontSizeStore.write(chosenFontSize)
        transcriptFontSizeStore.write(chosenTranscriptFontSize)
        sendModeStore.write(chosenSendMode)
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
            .setNeutralButton("nhập tay…") { _, _ -> promptCustomModel() }
            .show()
    }

    private fun choose(model: VisionModel) {
        chosenModel = model.id
        modelField.text = model.id
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
            .setNegativeButton("thôi", null)
            .setPositiveButton("dùng") { _, _ ->
                val id = input.text.toString().trim()
                if (id.isNotEmpty()) {
                    chosenModel = id
                    modelField.text = id
                }
            }
            .show()
    }

    /**
     * Presets, not a raw number: a hand-typed pixel value on a stylus tablet
     * is how a working setup turns into unreadable ink or a page that holds
     * three words.
     */
    private fun pickFontSize() {
        val choices = ReplyFontSize.entries
        val labels = choices.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("cỡ chữ")
            .setSingleChoiceItems(labels, choices.indexOf(chosenFontSize)) { dialog, which ->
                chosenFontSize = choices[which]
                fontSizeField.text = chosenFontSize.label
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Presets for the read-back screen, independent of [pickFontSize]: that
     * one sizes rasterized ink in px, this one sizes a plain `TextView` in sp.
     */
    private fun pickTranscriptFontSize() {
        val choices = TranscriptFontSize.entries
        val labels = choices.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("cỡ chữ đọc lại")
            .setSingleChoiceItems(labels, choices.indexOf(chosenTranscriptFontSize)) { dialog, which ->
                chosenTranscriptFontSize = choices[which]
                transcriptFontSizeField.text = chosenTranscriptFontSize.label
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Who decides a page is finished — see [SendMode]. Auto or manual, not a
     * raw toggle on the page's own chrome: the choice is made rarely, here,
     * and read once when the diary opens rather than tapped mid-write.
     */
    private fun pickSendMode() {
        val choices = SendMode.entries
        val labels = choices.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("chế độ gửi")
            .setSingleChoiceItems(labels, choices.indexOf(chosenSendMode)) { dialog, which ->
                chosenSendMode = choices[which]
                sendModeField.text = chosenSendMode.label
                dialog.dismiss()
            }
            .show()
    }

    private fun pinFieldLabel(): String = if (pinStore.isSet()) "đã bật" else "chưa đặt"

    /** Only two states matter to the tap: nothing set yet, or something to turn back off. */
    private fun togglePin() {
        if (pinStore.isSet()) confirmDisablePin() else promptSetPin()
    }

    private fun promptSetPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("đặt mã PIN")
            .setView(input)
            .setNegativeButton("thôi", null)
            .setPositiveButton("đặt") { _, _ ->
                val pin = input.text.toString()
                if (isValidPin(pin)) {
                    promptConfirmPin(pin)
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("PIN cần ít nhất 4 chữ số")
                        .setPositiveButton("thôi", null)
                        .show()
                }
            }
            .show()
    }

    /**
     * A second, independent entry — not a review of the first — because
     * [TYPE_NUMBER_VARIATION_PASSWORD] masks every digit as it's typed, so a
     * writer who fat-fingers one digit in [promptSetPin] has no way to see
     * and catch it there. Only a matching re-entry reaches [setPin]; a
     * mismatch cancels outright rather than looping back into
     * [promptSetPin], leaving the writer to tap "đặt mã PIN" again if they
     * want another attempt.
     */
    private fun promptConfirmPin(pin: String) {
        val confirmInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("nhập lại mã PIN")
            .setView(confirmInput)
            .setNegativeButton("thôi", null)
            .setPositiveButton("xác nhận") { _, _ ->
                if (confirmInput.text.toString() == pin) {
                    setPin(pin)
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("Hai lần nhập không khớp")
                        .setPositiveButton("thôi", null)
                        .show()
                }
            }
            .show()
    }

    /**
     * Validated here rather than left to [PinStore.set]: a store that can be
     * handed a 2-digit or empty PIN and silently accept it is how a writer
     * ends up locked out by a PIN they mistyped once and never actually set.
     */
    private fun isValidPin(pin: String): Boolean = pin.length >= 4 && pin.all { it.isDigit() }

    private fun setPin(pin: String) {
        pinStore.set(pin)
        pinField.text = pinFieldLabel()
    }

    private fun confirmDisablePin() {
        AlertDialog.Builder(this)
            .setMessage("Tắt khoá PIN?")
            .setNegativeButton("thôi", null)
            .setPositiveButton("tắt") { _, _ ->
                pinStore.clear()
                pinField.text = pinFieldLabel()
            }
            .show()
    }

    /** A label above its input, closed by the hairline the text sits on. */
    private fun field(label: String, input: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(32), 0, 0)
        addView(caption(label))
        addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            View(this@SettingsActivity).apply { setBackgroundColor(Color.BLACK) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)),
        )
    }

    /**
     * A line of prose where the other fields hold a value: it wraps, and it is
     * set in the page's own face rather than the monospace the other three
     * use, because nobody proofreads it for a mistyped character.
     */
    private fun statusField(): TextView = TextView(this).apply {
        textSize = 18f
        setTextColor(Color.BLACK)
        setPadding(0, dp(8), 0, dp(8))
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    /** Reads like the fields around it, but opens a list instead of a keyboard. */
    private fun chooserField(value: String, onTap: () -> Unit): TextView = TextView(this).apply {
        text = value
        setSingleLine()
        textSize = 17f
        typeface = Typeface.MONOSPACE
        setTextColor(Color.BLACK)
        setPadding(0, dp(8), 0, dp(8))
        setOnClickListener { onTap() }
    }

    /**
     * Monospace, because these are the three strings a typo silently turns
     * into a 401: an `l` has to look unlike a `1` while proofreading a pasted
     * key.
     */
    private fun valueField(value: String, inputType: Int): EditText = EditText(this).apply {
        setText(value)
        setSingleLine()
        this.inputType = inputType
        textSize = 17f
        typeface = Typeface.MONOSPACE
        setTextColor(Color.BLACK)
        setBackgroundColor(Color.TRANSPARENT)
        setPadding(0, dp(8), 0, dp(8))
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
