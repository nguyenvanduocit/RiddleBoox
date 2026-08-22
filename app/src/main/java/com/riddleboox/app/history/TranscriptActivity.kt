package com.riddleboox.app.history

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.riddleboox.app.ui.caption
import com.riddleboox.app.ui.dp
import com.riddleboox.app.ui.openPaperWindow
import com.riddleboox.app.ui.paperPage
import com.riddleboox.app.ui.runningHead
import com.riddleboox.app.ui.textBlock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One conversation read back as print: the writer's lines as the diary read
 * them off the page, and the diary's answers under each.
 *
 * Print rather than the handwriting it happened in, because the two are for
 * different things. The page is where a conversation is *had* — slow ink,
 * one reply at a time. This is where one is *found* again, and finding wants a
 * whole evening at a glance.
 *
 * From here a conversation is either taken up again ([EXTRA_RESUME_ID] back to
 * [HistoryActivity], which passes it on to the diary) or burnt.
 */
class TranscriptActivity : Activity() {

    private lateinit var store: ConversationStore
    private lateinit var conversationId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openPaperWindow()

        store = ConversationStore(this)
        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        val conversation = store.load(conversationId)
        if (conversation == null) {
            // Burnt or damaged between the index being drawn and this tap.
            finish()
            return
        }

        val column = textBlock().apply {
            for (turn in conversation.turns) {
                addView(written(turn.transcript))
                addView(answered(turn.reply))
            }
            addView(actions())
        }
        val day = DAY_AND_TIME.format(Date(conversation.startedAtMs))
        setContentView(paperPage(runningHead(day), column))
    }

    /** What the writer wrote, upright and flush left, the way it sat on the page. */
    private fun written(transcript: String): TextView = TextView(this).apply {
        text = transcript.ifBlank { "(mực nhoè)" }
        textSize = 19f
        typeface = Typeface.SERIF
        setTextColor(Color.BLACK)
        setPadding(0, dp(28), 0, 0)
        setLineSpacing(dp(4).toFloat(), 1f)
    }

    /** The diary's own hand: italic and indented, so a glance tells the voices apart. */
    private fun answered(reply: String): TextView = TextView(this).apply {
        text = reply
        textSize = 19f
        setTypeface(Typeface.SERIF, Typeface.ITALIC)
        setTextColor(Color.BLACK)
        setPadding(dp(24), dp(10), 0, 0)
        setLineSpacing(dp(4).toFloat(), 1f)
    }

    private fun actions(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(48), 0, 0)
        addView(
            caption("tiếp tục").apply {
                textSize = 16f
                setPadding(0, dp(12), dp(40), dp(12))
                setOnClickListener { resume() }
            },
        )
        addView(
            caption("chia sẻ").apply {
                textSize = 16f
                setPadding(0, dp(12), dp(40), dp(12))
                setOnClickListener { share() }
            },
        )
        addView(
            caption("sao chép").apply {
                textSize = 16f
                setPadding(0, dp(12), dp(40), dp(12))
                setOnClickListener { copy() }
            },
        )
        addView(
            caption("đốt").apply {
                textSize = 16f
                setPadding(dp(40), dp(12), dp(12), dp(12))
                setOnClickListener { confirmBurn() }
            },
        )
    }

    private fun resume() {
        setResult(RESULT_OK, Intent().putExtra(HistoryActivity.EXTRA_RESUME_ID, conversationId))
        finish()
    }

    /**
     * Writes the conversation out as plain text under `cacheDir/exports` and
     * hands the file to whatever the writer picks from the share sheet.
     *
     * Re-read from [store] rather than reusing [onCreate]'s already loaded
     * conversation, so a tap on "chia sẻ" a while after the screen opened
     * still exports what is on disk right now, not a stale snapshot.
     *
     * The export lives under `cacheDir`, not `filesDir` where conversations
     * themselves are kept: it is a disposable rendering for one share, not
     * part of the diary's record, and `cacheDir` is what `res/xml/file_paths.xml`'s
     * `exports` root is declared against for [FileProvider].
     */
    private fun share() {
        val conversation = store.load(conversationId) ?: return
        val exportsDir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, "$conversationId.txt")
        file.writeText(conversation.toPlainText())

        val uri = FileProvider.getUriForFile(this, "$packageName.onyx.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Chia sẻ cuộc trò chuyện"))
    }

    /**
     * Puts the conversation straight on the clipboard for pasting into another
     * app's notes — no share sheet, so no [FileProvider] URI or `cacheDir`
     * export file to stand up for it.
     *
     * Re-read from [store] for the same reason as [share]: a tap a while
     * after the screen opened should copy what is on disk right now, not
     * [onCreate]'s stale snapshot.
     */
    private fun copy() {
        val conversation = store.load(conversationId) ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("cuộc trò chuyện", conversation.toPlainText()))
        Toast.makeText(this, "Đã sao chép", Toast.LENGTH_SHORT).show()
    }

    /**
     * Asked first, because there is no undo: the file is the only copy, and a
     * stylus tap is easy to make by accident while scrolling.
     */
    private fun confirmBurn() {
        AlertDialog.Builder(this)
            .setMessage("Đốt cuộc trò chuyện này? Không lấy lại được.")
            .setNegativeButton("thôi", null)
            .setPositiveButton("đốt") { _, _ ->
                store.delete(conversationId)
                finish()
            }
            .show()
    }

    companion object {
        private const val EXTRA_CONVERSATION_ID = "com.riddleboox.app.history.CONVERSATION_ID"

        private val DAY_AND_TIME = SimpleDateFormat("d/M/yyyy · HH:mm", Locale.getDefault())

        fun intent(context: Context, conversationId: String): Intent =
            Intent(context, TranscriptActivity::class.java)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId)
    }
}
