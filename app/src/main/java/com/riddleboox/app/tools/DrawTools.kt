package com.riddleboox.app.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import com.riddleboox.app.handwriting.SvgFigure
import com.riddleboox.app.handwriting.svgFigure
import com.riddleboox.app.reply.Toolbox
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.atomic.AtomicReference

private const val DRAW = "draw"

/**
 * The seam a drawing crosses from the tool loop to the page.
 *
 * [DrawTools] runs inside the request's own coroutine and the strokes have to
 * land on the tick thread's reply queue — the same crossing streamed text makes
 * through `replyEvents`, with the same rule: bound to one request at a time, so
 * a figure produced by an abandoned turn cannot land on the page of the next
 * one. [com.riddleboox.app.riddle.RiddleStateMachine] opens the board when it
 * launches a request and closes it when the turn is over; between turns a
 * submitted figure simply has nowhere to go and [submit] says so.
 */
class DrawingBoard {

    private val receiver = AtomicReference<((SvgFigure) -> Unit)?>(null)

    fun open(receive: (SvgFigure) -> Unit) {
        receiver.set(receive)
    }

    fun close() {
        receiver.set(null)
    }

    /** Hands [figure] to the turn in flight; false when no turn is taking drawings. */
    fun submit(figure: SvgFigure): Boolean {
        val receive = receiver.get() ?: return false
        receive(figure)
        return true
    }
}

/**
 * The diary's pen offered to the model as a drawing tool.
 *
 * Part of every agent's default toolset — see `MainActivity.agentToolbox` —
 * because drawing, like the workspace and memory, is a sense of the diary
 * itself rather than a capability an agent opts into. The figure is placed and
 * sized by the page ([com.riddleboox.app.handwriting.WriteCursor.placeFigure]),
 * never by the model: coordinates in the SVG only need to agree with each
 * other.
 *
 * A parse failure is an answer, not an exception — the reason goes back as the
 * tool result, worded so the model can repair its own markup and call again.
 */
class DrawTools(private val board: DrawingBoard) : Toolbox {

    override val tools: List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = DRAW,
            description = "Draw a picture onto the page, in the diary's own hand, below the words " +
                "written so far. Give complete SVG markup using only outline geometry: path, line, " +
                "rect, circle, ellipse, polyline, polygon (fills are drawn as outlines; text, image " +
                "and CSS are not drawn). The page chooses the drawing's position and size itself, so " +
                "the SVG's coordinates only need to be consistent with each other. Call it at the " +
                "point in the answer where the picture belongs, and keep the figure simple enough " +
                "to sketch with a pen.",
            requiredParameters = listOf(
                ToolParameterDescriptor(
                    "svg",
                    "The complete <svg>…</svg> markup of the figure.",
                    ToolParameterType.String,
                ),
            ),
            optionalParameters = emptyList(),
        ),
    )

    override suspend fun call(name: String, arguments: JsonObject): String {
        if (name != DRAW) return "There is nothing called $name to draw with."
        val markup = (arguments["svg"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val figure = try {
            svgFigure(markup)
        } catch (e: IllegalArgumentException) {
            return "The drawing could not be read: ${e.message}. Fix the SVG and call $DRAW again."
        }
        return if (board.submit(figure)) {
            "The figure is being drawn onto the page below your words. Do not describe it; " +
                "continue the answer after it if anything remains to be said."
        } else {
            "The page is not taking drawings right now; answer in words instead."
        }
    }

    override fun note(name: String, arguments: JsonObject): String = "đang vẽ lên trang…"
}
