package com.miskibin.obd2dashboard.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Everything on the car screen that the app draws for itself.
 *
 * Android Auto does not hand a projected app a canvas — the host lays out templates and
 * draws them in its own type and its own colours, which is what makes a template legal to
 * look at while driving. The one exception is an image: a template can carry a bitmap the
 * app produced, and that bitmap is the only place the dashboard's own design survives the
 * trip onto the car screen. So the readings that need words go in rows the host draws, and
 * the one thing worth a glance rather than a read — road speed inside a rev counter, with
 * the gear under it — is drawn here.
 *
 * Laid out in the design's own units and scaled up on the way to pixels, so the numbers in
 * this file are the numbers in the mock-up rather than a set derived from them. [SCALE] is
 * the only knob: the head units this is aimed at are 800 × 480, and the pane's image slot
 * on one of those is a few hundred pixels wide.
 */
object CarGauge {

    /** The design's card, in the design's units. Everything below is measured in these. */
    private const val W = 318f
    private const val H = 373f

    /**
     * Rendered a little larger than the design so the arc's edges do not crawl on a screen
     * that scales it up. Larger would be waste: the bitmap crosses a binder to the host
     * every time a reading changes, and nothing on an 800 × 480 dash can show more.
     */
    private const val SCALE = 1.2f

    /* The palette, straight off the design. */
    private const val CARD = 0xFF1E2022.toInt()
    private const val CARD_EDGE = 0xFF2A2C2F.toInt()
    private const val TEXT = 0xFFEDECEA.toInt()
    private const val MUTED = 0xFF8E9092.toInt()
    private const val DIM = 0xFF75777A.toInt()
    private const val FAINT = 0xFF6E7275.toInt()
    private const val FAINTER = 0xFF5E6265.toInt()
    private const val ACCENT = 0xFF8FB4C9.toInt()
    private const val DANGER = 0xFFC4574D.toInt()
    private const val TRACK = 0xFF26282B.toInt()
    private const val TICK_OFF = 0xFF33363A.toInt()
    private const val TICK_OFF_RED = 0xFF43302D.toInt()
    private const val GEAR_ON = 0xFF33454E.toInt()
    private const val GEAR_ON_EDGE = 0xFF3E545E.toInt()
    private const val GEAR_ON_TEXT = 0xFFDDEAF2.toInt()
    private const val GEAR_OFF = 0xFF1A1C1E.toInt()
    private const val ALERT_TEXT = 0xFFB7B9BA.toInt()

    /* The rev counter: a 240° arc with its gap at the bottom, as in the design. */
    private const val CX = 159f
    private const val CY = 177f
    private const val R = 132f
    private const val ARC_START = -210f
    private const val ARC_SWEEP = 240f

    /** Where the scale turns red, as a fraction of the redline. */
    private const val RED_ZONE = 0.92f

    private const val TICK_STEP = 250

    private const val ELLIPSIS = "…"

    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /**
     * The design's card as a bitmap.
     *
     * A fresh bitmap every time rather than one redrawn in place: the previous one has been
     * handed to the host, which may still be reading it.
     */
    fun render(face: GaugeFace): Bitmap {
        val bitmap = Bitmap.createBitmap(
            (W * SCALE).roundToInt(),
            (H * SCALE).roundToInt(),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.scale(bitmap.width / W, bitmap.height / H)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        card(canvas, paint)
        dial(canvas, paint, face)
        readout(canvas, paint, face)
        gears(canvas, paint, face)
        footer(canvas, paint, face)
        face.alert?.let { banner(canvas, paint, it) }
        return bitmap
    }

    private fun card(canvas: Canvas, paint: Paint) {
        val rect = RectF(0.5f, 0.5f, W - 0.5f, H - 0.5f)
        paint.reset()
        paint.isAntiAlias = true
        paint.color = CARD
        canvas.drawRoundRect(rect, 16f, 16f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = CARD_EDGE
        canvas.drawRoundRect(rect, 16f, 16f, paint)
    }

    /** The arc, the part of it the engine has used, and the scale inside it. */
    private fun dial(canvas: Canvas, paint: Paint, face: GaugeFace) {
        val bounds = RectF(CX - R, CY - R, CX + R, CY + R)
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 10.5f
        paint.color = TRACK
        canvas.drawArc(bounds, ARC_START, ARC_SWEEP, false, paint)

        val fraction = face.revFraction
        if (fraction > 0.01f) {
            paint.color = if (face.overRevving) DANGER else ACCENT
            canvas.drawArc(bounds, ARC_START, ARC_SWEEP * fraction, false, paint)
        }

        // Every 250 rpm, long every 1 000: the same scale the phone's rev bar draws, bent
        // round the arc. Ticks the engine has passed are lit, which is what makes the
        // number in the middle readable as a position and not just a value.
        paint.strokeCap = Paint.Cap.ROUND
        val lit = face.rpm ?: 0
        for (value in 0..face.redline step TICK_STEP) {
            val major = value % 1_000 == 0
            val degrees = ARC_START + ARC_SWEEP * value / face.redline
            val red = value >= face.redline * RED_ZONE
            paint.color = when {
                red -> if (value <= lit) DANGER else TICK_OFF_RED
                else -> if (value <= lit) ACCENT else TICK_OFF
            }
            paint.strokeWidth = if (major) 3.1f else 1.7f
            val inner = R * if (major) 0.752f else 0.812f
            val outer = R * 0.911f
            val radians = Math.toRadians(degrees.toDouble())
            val dx = cos(radians).toFloat()
            val dy = sin(radians).toFloat()
            canvas.drawLine(
                CX + inner * dx,
                CY + inner * dy,
                CX + outer * dx,
                CY + outer * dy,
                paint,
            )
        }
    }

    /** Road speed inside the dial, with the revs that produced it underneath. */
    private fun readout(canvas: Canvas, paint: Paint, face: GaugeFace) {
        text(paint, size = 92f, color = TEXT, spacing = -0.055f)
        canvas.drawText(face.speed, CX - paint.measureText(face.speed) / 2f, 207f, paint)

        text(paint, size = 12f, color = MUTED, spacing = 0.16f)
        canvas.drawText(face.speedUnit, CX - paint.measureText(face.speedUnit) / 2f, 227f, paint)

        // Value and unit are centred as one line, so the pair stays put as the number
        // gains and loses a digit rather than sliding about under the speed.
        text(paint, size = 23f, color = if (face.overRevving) DANGER else TEXT, spacing = -0.02f)
        val revWidth = paint.measureText(face.revs)
        text(paint, size = 12.5f, color = DIM, spacing = 0f)
        val unitWidth = paint.measureText(face.revsUnit)
        val start = CX - (revWidth + 5f + unitWidth) / 2f

        text(paint, size = 23f, color = if (face.overRevving) DANGER else TEXT, spacing = -0.02f)
        canvas.drawText(face.revs, start, 257f, paint)
        text(paint, size = 12.5f, color = DIM, spacing = 0f)
        canvas.drawText(face.revsUnit, start + revWidth + 5f, 257f, paint)
    }

    /** The gear strip: one chip per forward gear, the engaged one lit. */
    private fun gears(canvas: Canvas, paint: Paint, face: GaugeFace) {
        text(paint, size = 10f, color = DIM, spacing = 0.16f)
        canvas.drawText(face.gearLabel, 12f, 309f, paint)

        val count = face.gearCount
        val total = count * 36f + (count - 1) * 5f
        var x = 306f - total
        repeat(count) { index ->
            val number = index + 1
            val on = face.gear == number
            val chip = RectF(x, 287f, x + 36f, 323f)
            paint.reset()
            paint.isAntiAlias = true
            paint.color = if (on) GEAR_ON else GEAR_OFF
            canvas.drawRoundRect(chip, 10f, 10f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = if (on) GEAR_ON_EDGE else CARD_EDGE
            canvas.drawRoundRect(chip, 10f, 10f, paint)

            val label = number.toString()
            text(paint, size = 17f, color = if (on) GEAR_ON_TEXT else FAINTER, spacing = 0f)
            canvas.drawText(label, chip.centerX() - paint.measureText(label) / 2f, 315f, paint)
            x += 41f
        }
    }

    /** The line under the strip: what the revs have been doing, and where they stop. */
    private fun footer(canvas: Canvas, paint: Paint, face: GaugeFace) {
        paint.reset()
        paint.isAntiAlias = true
        paint.strokeWidth = 1f
        paint.color = TRACK
        canvas.drawLine(12f, 332f, 306f, 332f, paint)

        // The redline is fixed and short, so it keeps its width and the note gives way:
        // the driver can read where the engine stops off the dial, but not what it did.
        text(paint, size = 12.5f, color = FAINTER, spacing = 0f)
        val redlineWidth = paint.measureText(face.redlineNote)
        canvas.drawText(face.redlineNote, 306f - redlineWidth, 352f, paint)
        text(paint, size = 12.5f, color = FAINT, spacing = 0f)
        canvas.drawText(elide(paint, face.note, 294f - redlineWidth - 10f), 12f, 352f, paint)
    }

    /**
     * A tripped alert, over the foot of the card.
     *
     * The design puts this over the dashboard as a card of its own. A template cannot be
     * overlaid, so it lands on the one surface the app owns — which also means it cannot
     * steal a tap, and the driver dismisses it by the reading going back where it belongs.
     */
    private fun banner(canvas: Canvas, paint: Paint, alert: GaugeFace.Alert) {
        val accent = if (alert.severe) 0xFFD9776B.toInt() else 0xFFD7B570.toInt()
        val background = if (alert.severe) 0xFF2B1F1E.toInt() else 0xFF2A2419.toInt()
        val edge = if (alert.severe) 0xFF4A2D2A.toInt() else 0xFF3E3623.toInt()
        val iconBackground = if (alert.severe) 0xFF3A2320.toInt() else 0xFF33291A.toInt()

        val rect = RectF(8f, 321f, W - 8f, H - 8f)
        paint.reset()
        paint.isAntiAlias = true
        paint.color = background
        canvas.drawRoundRect(rect, 14f, 14f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = edge
        canvas.drawRoundRect(rect, 14f, 14f, paint)

        paint.reset()
        paint.isAntiAlias = true
        paint.color = iconBackground
        canvas.drawCircle(34f, rect.centerY(), 15f, paint)
        paint.color = accent
        canvas.drawPath(warningGlyph(34f, rect.centerY()), paint)

        // A metric's name is whatever its translation is, and the longest of them is twice
        // the width of this banner, so both lines are cut to fit rather than drawn off it.
        val room = rect.right - 58f - 12f
        text(paint, size = 15f, color = accent, spacing = -0.01f)
        canvas.drawText(elide(paint, alert.title, room), 58f, rect.centerY() - 2f, paint)
        text(paint, size = 12f, color = ALERT_TEXT, spacing = 0f)
        canvas.drawText(elide(paint, alert.body, room), 58f, rect.centerY() + 14f, paint)
    }

    /** As much of [value] as fits [maxWidth] in the paint's current type, with an ellipsis. */
    private fun elide(paint: Paint, value: String, maxWidth: Float): String {
        if (maxWidth <= 0f || paint.measureText(value) <= maxWidth) return value
        val kept = paint.breakText(value, true, maxWidth - paint.measureText(ELLIPSIS), null)
        return value.take(kept).trimEnd() + ELLIPSIS
    }

    /** The warning triangle, centred on a point and sized to sit in the icon circle. */
    private fun warningGlyph(cx: Float, cy: Float): Path = Path().apply {
        val half = 9f
        moveTo(cx, cy - half)
        lineTo(cx + half, cy + half * 0.8f)
        lineTo(cx - half, cy + half * 0.8f)
        close()
    }

    /** One place that sets every text property, so no paint carries a leftover into the next. */
    private fun text(paint: Paint, size: Float, color: Int, spacing: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = medium
        paint.textSize = size
        paint.color = color
        paint.letterSpacing = spacing
        // Digits that change ten times a second must not shuffle the ones beside them.
        paint.fontFeatureSettings = "tnum"
    }
}

/**
 * Everything the drawn card says, already turned into the strings it will show.
 *
 * A data class on purpose: the card is redrawn only when this changes, which is what keeps
 * a bitmap off the binder on the many ticks where the engine is doing the same thing it was
 * doing a second ago.
 */
data class GaugeFace(
    val speed: String,
    val speedUnit: String,
    val revs: String,
    val revsUnit: String,
    val rpm: Int?,
    val redline: Int,
    val gear: Int?,
    val gearCount: Int,
    val gearLabel: String,
    val note: String,
    val redlineNote: String,
    val alert: Alert? = null,
) {
    val revFraction: Float get() = ((rpm ?: 0).toFloat() / redline).coerceIn(0f, 1f)

    val overRevving: Boolean get() = revFraction >= 0.92f

    /** A rule the driver set that the car is currently on the wrong side of. */
    data class Alert(val title: String, val body: String, val severe: Boolean)
}
