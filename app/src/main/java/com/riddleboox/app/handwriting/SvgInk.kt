package com.riddleboox.app.handwriting

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A vector figure flattened to pen strokes, ready to be laid on the page.
 *
 * The strokes are polylines in the figure's own units, translated so the
 * figure's bounding box starts at (0, 0) — the figure carries no opinion about
 * where on the page it goes or how large it is drawn. [WriteCursor.placeFigure]
 * decides both, the same way it decides where a word lands.
 *
 * Everything is an outline. A pen has no fill; a filled SVG shape arrives here
 * as the stroke of its boundary, which is what a hand sketching the same figure
 * would draw.
 */
data class SvgFigure(
    val strokes: List<WriteStroke>,
    val width: Float,
    val height: Float,
)

/**
 * Parses [svg] markup into an [SvgFigure], or throws [IllegalArgumentException]
 * with a reason a model can act on — the caller puts that reason in the tool
 * answer and the model fixes its own markup.
 *
 * Deliberately a subset, matched to what a pen can draw and what a model
 * actually emits: `path` (every command of the `d` grammar, arcs included),
 * `line`, `rect` (sharp-cornered; `rx`/`ry` are ignored), `circle`, `ellipse`,
 * `polyline` and `polygon`, under `translate`/`scale`/`rotate`/`skewX`/
 * `skewY`/`matrix` transforms on `<g>` or the element itself. `text`, `image`
 * and CSS styling have no pen equivalent and are skipped; `defs` and its kin
 * are never rendered directly and are skipped whole.
 *
 * Curves are flattened generously here (units are still the SVG's own, so the
 * final on-page density is unknowable) and thinned to pen pace later — see
 * [decimated].
 */
fun svgFigure(svg: String): SvgFigure {
    require(svg.isNotBlank()) { "no SVG markup was given" }
    require(svg.length <= MAX_SVG_CHARS) { "the SVG is too long (over $MAX_SVG_CHARS characters)" }
    val polylines = SvgWalker(svg).walk()
    val strokes = polylines
        .map { points -> points.filter { it.x.isFinite() && it.y.isFinite() } }
        .filter { it.size >= 2 }
        .map(::WriteStroke)
    require(strokes.isNotEmpty()) {
        "the SVG contains no drawable geometry (only path, line, rect, circle, " +
            "ellipse, polyline and polygon are drawn; text is not)"
    }
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (stroke in strokes) for (p in stroke.points) {
        if (p.x < minX) minX = p.x
        if (p.y < minY) minY = p.y
        if (p.x > maxX) maxX = p.x
        if (p.y > maxY) maxY = p.y
    }
    val width = maxX - minX
    val height = maxY - minY
    require(width > 0f || height > 0f) { "the SVG's geometry collapses to a single point" }
    return SvgFigure(
        strokes = strokes.map { stroke ->
            WriteStroke(stroke.points.map { WritePoint(it.x - minX, it.y - minY) })
        },
        width = width,
        height = height,
    )
}

/**
 * [strokes] with points closer together than [spacingPx] dropped, endpoints
 * always kept.
 *
 * The reveal pen pays per point ([com.riddleboox.app.riddle.REPLY_POINTS_PER_TICK]),
 * so a figure's density decides how long the writer watches it being drawn.
 * Flattening upstream is deliberately fine — this is where the density is
 * brought down to the same order as handwriting, once the on-page scale is
 * known.
 */
fun decimated(strokes: List<WriteStroke>, spacingPx: Float): List<WriteStroke> = strokes.map { stroke ->
    val points = stroke.points
    val kept = ArrayList<WritePoint>(points.size)
    kept.add(points.first())
    for (i in 1 until points.size - 1) {
        val last = kept.last()
        val p = points[i]
        if (hypot(p.x - last.x, p.y - last.y) >= spacingPx) kept.add(p)
    }
    if (points.size > 1) kept.add(points.last())
    WriteStroke(kept)
}

/** Ceiling on the markup itself — past this it is not a sketch, it is an upload. */
private const val MAX_SVG_CHARS = 100_000

/** Ceiling on flattened points before any thinning — a bomb guard, not a budget. */
private const val MAX_RAW_POINTS = 60_000

/** Line segments a full circle or ellipse is flattened into. */
private const val ELLIPSE_SEGMENTS = 48

/** Line segments one cubic Bézier is flattened into. */
private const val CUBIC_SEGMENTS = 32

/** Line segments one quadratic Bézier is flattened into. */
private const val QUAD_SEGMENTS = 24

/** Line segments a full turn of an arc is flattened into. */
private const val ARC_SEGMENTS_PER_TURN = 64

/**
 * The SVG 2x3 affine `[a b c d e f]`: x' = ax + cy + e, y' = bx + dy + f.
 */
private class SvgMatrix(
    val a: Float, val b: Float, val c: Float,
    val d: Float, val e: Float, val f: Float,
) {
    fun apply(x: Float, y: Float): WritePoint = WritePoint(a * x + c * y + e, b * x + d * y + f)

    /** This transform applied after [inner] — SVG's left-to-right transform list order. */
    fun then(inner: SvgMatrix): SvgMatrix = SvgMatrix(
        a = a * inner.a + c * inner.b,
        b = b * inner.a + d * inner.b,
        c = a * inner.c + c * inner.d,
        d = b * inner.c + d * inner.d,
        e = a * inner.e + c * inner.f + e,
        f = b * inner.e + d * inner.f + f,
    )

    companion object {
        val IDENTITY = SvgMatrix(1f, 0f, 0f, 1f, 0f, 0f)
    }
}

/**
 * Walks the markup tag by tag, keeping the transform stack, and flattens every
 * drawable element into polylines in root coordinates.
 *
 * A hand-rolled scanner rather than an XML parser on purpose: the input is a
 * model's own markup arriving over a tool call, this must run in a plain JVM
 * test, and the only structure that matters is tags, attributes and nesting.
 * Malformed nesting is tolerated (a stray close tag pops nothing below the
 * root) — the failure mode this class owes the caller is a clear message, not
 * a validation report.
 */
private class SvgWalker(svg: String) {

    private val markup = svg
        .replace(COMMENT, " ")
        .replace(CDATA, " ")
        .replace(PROCESSING, " ")
        .replace(DOCTYPE, " ")

    private val polylines = ArrayList<List<WritePoint>>()
    private var rawPoints = 0

    fun walk(): List<List<WritePoint>> {
        val transforms = ArrayDeque<SvgMatrix>().apply { addLast(SvgMatrix.IDENTITY) }
        // Depth inside a subtree that is never rendered directly (defs, text…).
        var skipDepth = 0
        for (tag in TAG.findAll(markup)) {
            val closing = tag.groupValues[1] == "/"
            val name = tag.groupValues[2].lowercase()
            val body = tag.groupValues[3]
            val selfClosing = tag.groupValues[4] == "/"
            when {
                closing -> when {
                    // Symmetric with the opening side below: any NESTABLE tag
                    // opened inside a skipped subtree was counted, so any
                    // NESTABLE close inside one uncounts.
                    skipDepth > 0 -> if (name in NESTABLE) skipDepth--
                    name == "g" || name == "svg" -> if (transforms.size > 1) transforms.removeLast()
                }
                skipDepth > 0 -> if (!selfClosing && name in NESTABLE) skipDepth++
                name in SKIPPED -> if (!selfClosing) skipDepth++
                name == "g" || name == "svg" -> {
                    val local = transforms.last().then(parseTransform(attr(body, "transform")))
                    if (selfClosing) Unit else transforms.addLast(local)
                }
                name in DRAWN -> {
                    val local = transforms.last().then(parseTransform(attr(body, "transform")))
                    for (polyline in flatten(name, body)) {
                        add(polyline.map { local.apply(it.x, it.y) })
                    }
                }
            }
        }
        return polylines
    }

    private fun add(polyline: List<WritePoint>) {
        rawPoints += polyline.size
        require(rawPoints <= MAX_RAW_POINTS) { "the SVG is too complex to draw with a pen" }
        polylines.add(polyline)
    }

    private fun flatten(name: String, body: String): List<List<WritePoint>> = when (name) {
        "path" -> flattenPath(attr(body, "d"))
        "line" -> listOf(
            listOf(
                WritePoint(number(body, "x1"), number(body, "y1")),
                WritePoint(number(body, "x2"), number(body, "y2")),
            ),
        )
        "rect" -> {
            val x = number(body, "x")
            val y = number(body, "y")
            val w = number(body, "width")
            val h = number(body, "height")
            if (w <= 0f || h <= 0f) {
                emptyList()
            } else {
                listOf(
                    listOf(
                        WritePoint(x, y), WritePoint(x + w, y), WritePoint(x + w, y + h),
                        WritePoint(x, y + h), WritePoint(x, y),
                    ),
                )
            }
        }
        "circle" -> ellipse(number(body, "cx"), number(body, "cy"), number(body, "r"), number(body, "r"))
        "ellipse" -> ellipse(number(body, "cx"), number(body, "cy"), number(body, "rx"), number(body, "ry"))
        "polyline" -> listOfNotNull(points(attr(body, "points")))
        "polygon" -> listOfNotNull(
            points(attr(body, "points"))?.let { if (it.size >= 2) it + it.first() else it },
        )
        else -> emptyList()
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): List<List<WritePoint>> {
        if (rx <= 0f || ry <= 0f) return emptyList()
        return listOf(
            (0..ELLIPSE_SEGMENTS).map { i ->
                val angle = 2 * PI.toFloat() * i / ELLIPSE_SEGMENTS
                WritePoint(cx + rx * cos(angle), cy + ry * sin(angle))
            },
        )
    }

    private fun points(spec: String): List<WritePoint>? {
        val values = NUMBER.findAll(spec).map { it.value.toFloat() }.toList()
        if (values.size < 4) return null
        return (0 until values.size / 2).map { WritePoint(values[it * 2], values[it * 2 + 1]) }
    }

    private fun attr(body: String, name: String): String =
        ATTRIBUTE.findAll(body)
            .firstOrNull { it.groupValues[1].equals(name, ignoreCase = true) }
            ?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }
            .orEmpty()

    private fun number(body: String, name: String): Float =
        NUMBER.find(attr(body, name))?.value?.toFloatOrNull() ?: 0f

    private fun parseTransform(spec: String): SvgMatrix {
        var matrix = SvgMatrix.IDENTITY
        for (op in TRANSFORM_OP.findAll(spec)) {
            val args = NUMBER.findAll(op.groupValues[2]).map { it.value.toFloat() }.toList()
            val next = when (op.groupValues[1].lowercase()) {
                "translate" -> SvgMatrix(1f, 0f, 0f, 1f, args.getOrElse(0) { 0f }, args.getOrElse(1) { 0f })
                "scale" -> {
                    val sx = args.getOrElse(0) { 1f }
                    SvgMatrix(sx, 0f, 0f, args.getOrElse(1) { sx }, 0f, 0f)
                }
                "rotate" -> {
                    val radians = Math.toRadians(args.getOrElse(0) { 0f }.toDouble())
                    val rotation = SvgMatrix(
                        cos(radians).toFloat(), sin(radians).toFloat(),
                        -sin(radians).toFloat(), cos(radians).toFloat(), 0f, 0f,
                    )
                    if (args.size >= 3) {
                        val cx = args[1]
                        val cy = args[2]
                        SvgMatrix(1f, 0f, 0f, 1f, cx, cy)
                            .then(rotation)
                            .then(SvgMatrix(1f, 0f, 0f, 1f, -cx, -cy))
                    } else {
                        rotation
                    }
                }
                "skewx" -> SvgMatrix(1f, 0f, tan(Math.toRadians(args.getOrElse(0) { 0f }.toDouble())).toFloat(), 1f, 0f, 0f)
                "skewy" -> SvgMatrix(1f, tan(Math.toRadians(args.getOrElse(0) { 0f }.toDouble())).toFloat(), 0f, 1f, 0f, 0f)
                "matrix" -> if (args.size >= 6) SvgMatrix(args[0], args[1], args[2], args[3], args[4], args[5]) else SvgMatrix.IDENTITY
                else -> SvgMatrix.IDENTITY
            }
            matrix = matrix.then(next)
        }
        return matrix
    }

    // ---- The `d` grammar ----

    private fun flattenPath(d: String): List<List<WritePoint>> {
        if (d.isBlank()) return emptyList()
        val out = ArrayList<List<WritePoint>>()
        var current = ArrayList<WritePoint>()
        val scan = PathScanner(d)

        var x = 0f
        var y = 0f
        var startX = 0f
        var startY = 0f
        // Reflection anchors for S/T — the previous curve's last control point.
        var lastCubicX = 0f
        var lastCubicY = 0f
        var lastQuadX = 0f
        var lastQuadY = 0f
        var previous = ' '

        fun close() {
            if (current.size >= 2) out.add(current)
            current = ArrayList()
        }

        fun moveTo(nx: Float, ny: Float) {
            close()
            x = nx
            y = ny
            startX = nx
            startY = ny
            current.add(WritePoint(x, y))
        }

        fun lineTo(nx: Float, ny: Float) {
            if (current.isEmpty()) current.add(WritePoint(x, y))
            x = nx
            y = ny
            current.add(WritePoint(x, y))
        }

        fun cubicTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, nx: Float, ny: Float) {
            if (current.isEmpty()) current.add(WritePoint(x, y))
            val (fromX, fromY) = x to y
            for (i in 1..CUBIC_SEGMENTS) {
                val t = i.toFloat() / CUBIC_SEGMENTS
                val u = 1 - t
                current.add(
                    WritePoint(
                        u * u * u * fromX + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * nx,
                        u * u * u * fromY + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * ny,
                    ),
                )
            }
            lastCubicX = c2x
            lastCubicY = c2y
            x = nx
            y = ny
        }

        fun quadTo(cx: Float, cy: Float, nx: Float, ny: Float) {
            if (current.isEmpty()) current.add(WritePoint(x, y))
            val (fromX, fromY) = x to y
            for (i in 1..QUAD_SEGMENTS) {
                val t = i.toFloat() / QUAD_SEGMENTS
                val u = 1 - t
                current.add(
                    WritePoint(
                        u * u * fromX + 2 * u * t * cx + t * t * nx,
                        u * u * fromY + 2 * u * t * cy + t * t * ny,
                    ),
                )
            }
            lastQuadX = cx
            lastQuadY = cy
            x = nx
            y = ny
        }

        fun arcTo(rx: Float, ry: Float, rotationDeg: Float, largeArc: Boolean, sweep: Boolean, nx: Float, ny: Float) {
            if (current.isEmpty()) current.add(WritePoint(x, y))
            for (p in flattenArc(x, y, rx, ry, rotationDeg, largeArc, sweep, nx, ny)) current.add(p)
            x = nx
            y = ny
        }

        while (scan.hasMore()) {
            val command = scan.command() ?: previous.takeIf { it != ' ' }
                ?: throw IllegalArgumentException("the path data does not start with a command")
            val relative = command.isLowerCase()
            val dx = if (relative) x else 0f
            val dy = if (relative) y else 0f
            when (command.uppercaseChar()) {
                'M' -> {
                    moveTo(dx + scan.number(), dy + scan.number())
                    // Extra coordinate pairs after a move are implicit linetos.
                    previous = if (relative) 'l' else 'L'
                    continue
                }
                'L' -> lineTo(dx + scan.number(), dy + scan.number())
                'H' -> lineTo(dx + scan.number(), y)
                'V' -> lineTo(x, dy + scan.number())
                'C' -> cubicTo(
                    dx + scan.number(), dy + scan.number(),
                    dx + scan.number(), dy + scan.number(),
                    dx + scan.number(), dy + scan.number(),
                )
                'S' -> {
                    val reflectedX = if (previous.uppercaseChar() in "CS") 2 * x - lastCubicX else x
                    val reflectedY = if (previous.uppercaseChar() in "CS") 2 * y - lastCubicY else y
                    cubicTo(
                        reflectedX, reflectedY,
                        dx + scan.number(), dy + scan.number(),
                        dx + scan.number(), dy + scan.number(),
                    )
                }
                'Q' -> quadTo(
                    dx + scan.number(), dy + scan.number(),
                    dx + scan.number(), dy + scan.number(),
                )
                'T' -> {
                    val reflectedX = if (previous.uppercaseChar() in "QT") 2 * x - lastQuadX else x
                    val reflectedY = if (previous.uppercaseChar() in "QT") 2 * y - lastQuadY else y
                    quadTo(reflectedX, reflectedY, dx + scan.number(), dy + scan.number())
                }
                'A' -> arcTo(
                    scan.number(), scan.number(), scan.number(),
                    scan.flag(), scan.flag(),
                    dx + scan.number(), dy + scan.number(),
                )
                'Z' -> {
                    lineTo(startX, startY)
                    close()
                }
                else -> throw IllegalArgumentException("unknown path command '$command'")
            }
            previous = command
        }
        close()
        return out
    }

    /**
     * One elliptical-arc segment, flattened — the endpoint-to-centre
     * conversion of the SVG spec's implementation notes (B.2.4), including its
     * radius correction for endpoints the given radii cannot reach.
     */
    private fun flattenArc(
        x1: Float, y1: Float,
        radiusX: Float, radiusY: Float,
        rotationDeg: Float,
        largeArc: Boolean, sweep: Boolean,
        x2: Float, y2: Float,
    ): List<WritePoint> {
        var rx = abs(radiusX)
        var ry = abs(radiusY)
        if (rx == 0f || ry == 0f || (x1 == x2 && y1 == y2)) return listOf(WritePoint(x2, y2))
        val phi = Math.toRadians(rotationDeg.toDouble())
        val cosPhi = cos(phi).toFloat()
        val sinPhi = sin(phi).toFloat()
        val halfDx = (x1 - x2) / 2
        val halfDy = (y1 - y2) / 2
        val px = cosPhi * halfDx + sinPhi * halfDy
        val py = -sinPhi * halfDx + cosPhi * halfDy
        val lambda = px * px / (rx * rx) + py * py / (ry * ry)
        if (lambda > 1) {
            val grow = sqrt(lambda)
            rx *= grow
            ry *= grow
        }
        val numerator = rx * rx * ry * ry - rx * rx * py * py - ry * ry * px * px
        val denominator = rx * rx * py * py + ry * ry * px * px
        val factor = sqrt(max(0f, numerator / denominator)) * if (largeArc != sweep) 1 else -1
        val centreXPrime = factor * rx * py / ry
        val centreYPrime = -factor * ry * px / rx
        val centreX = cosPhi * centreXPrime - sinPhi * centreYPrime + (x1 + x2) / 2
        val centreY = sinPhi * centreXPrime + cosPhi * centreYPrime + (y1 + y2) / 2

        fun angleBetween(ux: Float, uy: Float, vx: Float, vy: Float): Float {
            val dot = ux * vx + uy * vy
            val magnitude = hypot(ux, uy) * hypot(vx, vy)
            if (magnitude == 0f) return 0f
            val angle = acos((dot / magnitude).coerceIn(-1f, 1f))
            return if (ux * vy - uy * vx < 0) -angle else angle
        }

        val startVectorX = (px - centreXPrime) / rx
        val startVectorY = (py - centreYPrime) / ry
        val theta = angleBetween(1f, 0f, startVectorX, startVectorY)
        var sweepAngle = angleBetween(startVectorX, startVectorY, (-px - centreXPrime) / rx, (-py - centreYPrime) / ry)
        if (!sweep && sweepAngle > 0) sweepAngle -= 2 * PI.toFloat()
        if (sweep && sweepAngle < 0) sweepAngle += 2 * PI.toFloat()

        val segments = (abs(sweepAngle) / (2 * PI.toFloat()) * ARC_SEGMENTS_PER_TURN)
            .toInt().coerceIn(4, ARC_SEGMENTS_PER_TURN)
        return (1..segments).map { i ->
            val angle = theta + sweepAngle * i / segments
            WritePoint(
                centreX + rx * cos(angle) * cosPhi - ry * sin(angle) * sinPhi,
                centreY + rx * cos(angle) * sinPhi + ry * sin(angle) * cosPhi,
            )
        }
    }

    private companion object {
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val CDATA = Regex("<!\\[CDATA\\[.*?]]>", RegexOption.DOT_MATCHES_ALL)
        val PROCESSING = Regex("<\\?.*?\\?>", RegexOption.DOT_MATCHES_ALL)
        val DOCTYPE = Regex("<![^>]*>")

        /** One tag: closing slash, name, attribute body (quote-aware), self-closing slash. */
        val TAG = Regex("<(/?)([a-zA-Z][\\w:-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)(/?)>")

        val ATTRIBUTE = Regex("([a-zA-Z_:][\\w:-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
        val NUMBER = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
        val TRANSFORM_OP = Regex("([a-zA-Z]+)\\s*\\(([^)]*)\\)")

        /** Subtrees never rendered directly, or with nothing a pen can draw. */
        val SKIPPED = setOf("defs", "symbol", "clippath", "mask", "marker", "pattern", "style", "text", "metadata", "title", "desc")

        /** Skipped subtrees count their own nesting so the walk knows where they end. */
        val NESTABLE = SKIPPED + setOf("g", "svg")

        val DRAWN = setOf("path", "line", "rect", "circle", "ellipse", "polyline", "polygon")
    }
}

/**
 * A cursor over path data: commands, numbers and the arc's single-digit flags,
 * with SVG's own permissive separators (whitespace, commas, and a `-` or `.`
 * that simply begins the next number).
 */
private class PathScanner(private val data: String) {
    private var index = 0

    private fun skipSeparators() {
        while (index < data.length && (data[index].isWhitespace() || data[index] == ',')) index++
    }

    fun hasMore(): Boolean {
        skipSeparators()
        return index < data.length
    }

    /** The next command letter, consumed — or null when a number is next (a repeated command). */
    fun command(): Char? {
        skipSeparators()
        val c = data.getOrNull(index) ?: return null
        if (c.isLetter() && c.uppercaseChar() != 'E') {
            index++
            return c
        }
        return null
    }

    fun number(): Float {
        skipSeparators()
        val start = index
        if (index < data.length && (data[index] == '+' || data[index] == '-')) index++
        var dotSeen = false
        while (index < data.length) {
            val c = data[index]
            when {
                c.isDigit() -> index++
                c == '.' && !dotSeen -> {
                    dotSeen = true
                    index++
                }
                (c == 'e' || c == 'E') && index > start -> {
                    index++
                    if (index < data.length && (data[index] == '+' || data[index] == '-')) index++
                }
                else -> break
            }
        }
        val text = data.substring(start, index)
        return text.toFloatOrNull()
            ?: throw IllegalArgumentException("expected a number in path data at position $start")
    }

    /** An arc flag is one character, `0` or `1`, even when run together as `11`. */
    fun flag(): Boolean {
        skipSeparators()
        val c = data.getOrNull(index)
            ?: throw IllegalArgumentException("path data ends where an arc flag was expected")
        require(c == '0' || c == '1') { "expected an arc flag (0 or 1), found '$c'" }
        index++
        return c == '1'
    }
}
