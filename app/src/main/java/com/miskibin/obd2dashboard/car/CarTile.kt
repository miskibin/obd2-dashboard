package com.miskibin.obd2dashboard.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.roundToInt

/** How far a reading is from where the driver said they want it. */
enum class TileLevel { Normal, Warn, Bad }

/**
 * One grid tile's worth of drawing: a reading, and where it has been.
 *
 * A data class because the bitmap it produces crosses a binder to the host, so the screen
 * only redraws a tile whose face differs from the one it sent last.
 */
data class TileFace(
    val value: String,
    val unit: String,
    /** Oldest sample first. Fewer than two draws no trace at all rather than a flat line. */
    val trace: List<Float>,
    /** The driver's threshold in the reading's own units, drawn as the dashed line. */
    val mark: Float?,
    val level: TileLevel,
    val dark: Boolean,
)

/**
 * The one thing on the car screen the app draws for itself.
 *
 * Android Auto hands a projected app no canvas: the host lays out templates and draws them
 * in its own type and its own colours, which is what makes a template legal to look at
 * while driving. The single exception is an image, and a [androidx.car.app.model.GridItem]
 * can carry one the app produced. So each tile's picture — the reading, its last half
 * minute, and the line it must not cross — is drawn here, and the label under it belongs
 * to the host.
 *
 * Written in the design's own units (a 136 × 104 tile) and scaled on the way to pixels, so
 * every number below is a number from the mock-up rather than one derived from it.
 */
object CarTile {

    /** The design's tile, in the design's units. Everything else here is measured in these. */
    private const val W = 136f
    private const val H = 104f

    /**
     * Oversampled a little so the trace's corners do not crawl when the host scales the
     * tile up. Larger would be waste: this bitmap goes to the host every time the reading
     * moves, and no 800 × 480 dash can show more detail than this.
     */
    private const val SCALE = 1.5f

    /* Where the trace lives, straight off the design. */
    private const val TRACE_LEFT = 8f
    private const val TRACE_RIGHT = 128f
    private const val TRACE_FLOOR = 100f
    private const val TRACE_BASE = 98f
    private const val TRACE_SPAN = 42f
    private const val MARK_LEFT = 6f
    private const val MARK_RIGHT = 130f
    private const val DOT_RADIUS = 2.6f

    /** Head-room above and below the samples, so a steady reading is not a jagged line. */
    private const val PAD_FRACTION = 0.18f

    /** The band a perfectly flat trace gets, so it sits mid-tile instead of on the floor. */
    private const val FLAT_BAND = 1f

    /* The reading itself. */
    private const val VALUE_SIZE = 40f
    private const val VALUE_SPACING = -0.035f
    private const val VALUE_BASELINE = 39.7f
    private const val UNIT_SIZE = 14f
    private const val UNIT_GAP = 5f

    private const val TRACE_WIDTH = 2f
    private const val MARK_WIDTH = 1f
    private val MARK_DASH = floatArrayOf(3f, 4f)

    /** How opaque the fill under the trace is; the design's rgba(…, .1). */
    private const val FILL_ALPHA = 28

    private val LIGHT_TYPE = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val PLAIN_TYPE = Typeface.create("sans-serif", Typeface.NORMAL)

    fun render(face: TileFace): Bitmap {
        val bitmap = Bitmap.createBitmap(
            (W * SCALE).roundToInt(),
            (H * SCALE).roundToInt(),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.scale(bitmap.width / W, bitmap.height / H)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val ink = if (face.dark) DARK else LIGHT

        trace(canvas, paint, face, ink)
        reading(canvas, paint, face, ink)
        return bitmap
    }

    /**
     * The threshold line, the last thirty samples, and a dot on the newest one.
     *
     * The scale comes from the samples themselves rather than from the metric's range: half
     * a minute of coolant temperature moves by two degrees, and a trace drawn against 40–120
     * would be a straight line saying nothing. The threshold is folded into the scale so a
     * reading that crosses it is seen crossing it.
     */
    private fun trace(canvas: Canvas, paint: Paint, face: TileFace, ink: Ink) {
        val samples = face.trace
        val mark = face.mark
        if (samples.size < 2 && mark == null) return

        var low = samples.minOrNull() ?: mark!!
        var high = samples.maxOrNull() ?: mark!!
        if (mark != null) {
            low = minOf(low, mark)
            high = maxOf(high, mark)
        }
        val pad = ((high - low) * PAD_FRACTION).takeIf { it > 0f } ?: FLAT_BAND
        low -= pad
        high += pad
        val y = { value: Float -> TRACE_BASE - TRACE_SPAN * ((value - low) / (high - low)) }

        if (mark != null) {
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.color = ink.mark
            paint.strokeWidth = MARK_WIDTH
            paint.pathEffect = DashPathEffect(MARK_DASH, 0f)
            canvas.drawLine(MARK_LEFT, y(mark), MARK_RIGHT, y(mark), paint)
            paint.pathEffect = null
        }
        if (samples.size < 2) return

        val colour = ink.of(face.level)
        val step = (TRACE_RIGHT - TRACE_LEFT) / (samples.size - 1)
        val line = Path()
        samples.forEachIndexed { index, value ->
            val x = TRACE_LEFT + step * index
            if (index == 0) line.moveTo(x, y(value)) else line.lineTo(x, y(value))
        }

        val area = Path(line)
        area.lineTo(TRACE_RIGHT, TRACE_FLOOR)
        area.lineTo(TRACE_LEFT, TRACE_FLOOR)
        area.close()
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = colour
        paint.alpha = FILL_ALPHA
        canvas.drawPath(area, paint)

        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.color = colour
        paint.strokeWidth = TRACE_WIDTH
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(line, paint)

        paint.style = Paint.Style.FILL
        canvas.drawCircle(TRACE_RIGHT, y(samples.last()), DOT_RADIUS, paint)
    }

    /** The number and its unit, centred as one baseline-aligned run. */
    private fun reading(canvas: Canvas, paint: Paint, face: TileFace, ink: Ink) {
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = LIGHT_TYPE
        paint.textSize = VALUE_SIZE
        paint.letterSpacing = VALUE_SPACING
        val valueWidth = paint.measureText(face.value)

        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        unitPaint.typeface = PLAIN_TYPE
        unitPaint.textSize = UNIT_SIZE
        val unitWidth = if (face.unit.isEmpty()) 0f else unitPaint.measureText(face.unit)
        val gap = if (face.unit.isEmpty()) 0f else UNIT_GAP

        var x = (W - (valueWidth + gap + unitWidth)) / 2f
        paint.color = if (face.level == TileLevel.Normal) ink.value else ink.of(face.level)
        canvas.drawText(face.value, x, VALUE_BASELINE, paint)
        if (face.unit.isNotEmpty()) {
            x += valueWidth + gap
            unitPaint.color = ink.unit
            canvas.drawText(face.unit, x, VALUE_BASELINE, unitPaint)
        }
    }

    /**
     * One palette per host theme.
     *
     * A tile is a transparent bitmap dropped onto whatever the host painted behind it, and
     * Android Auto swaps between a day and a night theme on its own. Ink chosen for the
     * night theme would be invisible on the day one, so the screen asks
     * [androidx.car.app.CarContext.isDarkMode] and the answer travels in the face.
     */
    private class Ink(
        val value: Int,
        val unit: Int,
        val mark: Int,
        private val normal: Int,
        private val warn: Int,
        private val bad: Int,
    ) {
        fun of(level: TileLevel): Int = when (level) {
            TileLevel.Normal -> normal
            TileLevel.Warn -> warn
            TileLevel.Bad -> bad
        }
    }

    private val DARK = Ink(
        value = 0xFFF1F3F4.toInt(),
        unit = 0xFF8A8F93.toInt(),
        mark = 0xFF4A4E51.toInt(),
        normal = 0xFF8FB4C9.toInt(),
        warn = 0xFFE5A45C.toInt(),
        bad = 0xFFE08C7D.toInt(),
    )

    private val LIGHT = Ink(
        value = 0xFF1A1B1C.toInt(),
        unit = 0xFF62666A.toInt(),
        mark = 0xFFC0C4C7.toInt(),
        normal = 0xFF3E6D89.toInt(),
        warn = 0xFF9C6620.toInt(),
        bad = 0xFF9E4035.toInt(),
    )
}
