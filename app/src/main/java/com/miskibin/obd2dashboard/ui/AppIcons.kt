package com.miskibin.obd2dashboard.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The handful of glyphs Material's icon *core* artifact does not carry.
 *
 * Declaring them here keeps `material-icons-extended` — a multi-megabyte artifact of
 * which the app would use four drawings — out of the APK.
 */
object AppIcons {

    val Gauge: ImageVector by lazy {
        icon("Gauge") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.NonZero) {
                moveTo(20.38f, 8.57f)
                lineToRelative(-1.23f, 1.85f)
                arcToRelative(8f, 8f, 0f, false, true, -0.22f, 7.58f)
                horizontalLineTo(5.07f)
                arcToRelative(8f, 8f, 0f, false, true, 10.51f, -11.15f)
                lineToRelative(1.85f, -1.23f)
                arcTo(10f, 10f, 0f, false, false, 3.35f, 19f)
                arcToRelative(2f, 2f, 0f, false, false, 1.72f, 1f)
                horizontalLineToRelative(13.85f)
                arcToRelative(2f, 2f, 0f, false, false, 1.74f, -1f)
                arcToRelative(10f, 10f, 0f, false, false, -0.27f, -10.44f)
                close()
                moveTo(10.59f, 15.41f)
                arcToRelative(2f, 2f, 0f, false, false, 2.83f, 0f)
                lineToRelative(5.66f, -8.49f)
                lineToRelative(-8.49f, 5.66f)
                arcToRelative(2f, 2f, 0f, false, false, 0f, 2.83f)
                close()
            }
        }
    }

    val Timeline: ImageVector by lazy {
        icon("Timeline") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.NonZero) {
                moveTo(23f, 8f)
                curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f)
                curveToRelative(-0.18f, 0f, -0.35f, -0.02f, -0.51f, -0.07f)
                lineToRelative(-3.56f, 3.55f)
                curveToRelative(0.05f, 0.16f, 0.07f, 0.34f, 0.07f, 0.52f)
                curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f)
                reflectiveCurveToRelative(-2f, -0.9f, -2f, -2f)
                curveToRelative(0f, -0.18f, 0.02f, -0.36f, 0.07f, -0.52f)
                lineToRelative(-2.55f, -2.55f)
                curveToRelative(-0.16f, 0.05f, -0.34f, 0.07f, -0.52f, 0.07f)
                reflectiveCurveToRelative(-0.36f, -0.02f, -0.52f, -0.07f)
                lineToRelative(-4.55f, 4.56f)
                curveToRelative(0.05f, 0.16f, 0.07f, 0.33f, 0.07f, 0.51f)
                curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f)
                reflectiveCurveToRelative(-2f, -0.9f, -2f, -2f)
                reflectiveCurveToRelative(0.9f, -2f, 2f, -2f)
                curveToRelative(0.18f, 0f, 0.35f, 0.02f, 0.51f, 0.07f)
                lineToRelative(4.56f, -4.55f)
                curveTo(8.02f, 9.36f, 8f, 9.18f, 8f, 9f)
                curveToRelative(0f, -1.1f, 0.9f, -2f, 2f, -2f)
                reflectiveCurveToRelative(2f, 0.9f, 2f, 2f)
                curveToRelative(0f, 0.18f, -0.02f, 0.36f, -0.07f, 0.52f)
                lineToRelative(2.55f, 2.55f)
                curveToRelative(0.16f, -0.05f, 0.34f, -0.07f, 0.52f, -0.07f)
                reflectiveCurveToRelative(0.36f, 0.02f, 0.52f, 0.07f)
                lineToRelative(3.55f, -3.56f)
                curveTo(19.02f, 8.35f, 19f, 8.18f, 19f, 8f)
                curveToRelative(0f, -1.1f, 0.9f, -2f, 2f, -2f)
                reflectiveCurveToRelative(2f, 0.9f, 2f, 2f)
                close()
            }
        }
    }

    val Bluetooth: ImageVector by lazy {
        icon("Bluetooth") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.NonZero) {
                moveTo(17.71f, 7.71f)
                lineTo(12f, 2f)
                horizontalLineToRelative(-1f)
                verticalLineToRelative(7.59f)
                lineTo(6.41f, 5f)
                lineTo(5f, 6.41f)
                lineTo(10.59f, 12f)
                lineTo(5f, 17.59f)
                lineTo(6.41f, 19f)
                lineTo(11f, 14.41f)
                verticalLineTo(22f)
                horizontalLineToRelative(1f)
                lineToRelative(5.71f, -5.71f)
                lineToRelative(-4.3f, -4.29f)
                close()
                moveTo(13f, 5.83f)
                lineToRelative(1.88f, 1.88f)
                lineTo(13f, 9.59f)
                close()
                moveTo(14.88f, 16.29f)
                lineTo(13f, 18.17f)
                verticalLineToRelative(-3.76f)
                close()
            }
        }
    }

    val DragHandle: ImageVector by lazy {
        icon("DragHandle") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.NonZero) {
                moveTo(20f, 9f)
                horizontalLineTo(4f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(16f)
                close()
                moveTo(4f, 15f)
                horizontalLineToRelative(16f)
                verticalLineToRelative(-2f)
                horizontalLineTo(4f)
                close()
            }
        }
    }

    val StopSquare: ImageVector by lazy {
        icon("StopSquare") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.NonZero) {
                moveTo(6f, 6f)
                horizontalLineToRelative(12f)
                verticalLineToRelative(12f)
                horizontalLineTo(6f)
                close()
            }
        }
    }

    val RecordDot: ImageVector by lazy {
        icon("RecordDot") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.NonZero) {
                moveTo(12f, 5f)
                arcToRelative(7f, 7f, 0f, true, true, 0f, 14f)
                arcToRelative(7f, 7f, 0f, true, true, 0f, -14f)
                close()
            }
        }
    }

    /**
     * The warning triangle, drawn to the same weight as the rest of the set.
     *
     * Material's own triangle is rounder and reads as a notice; this one has the flat
     * corners of the lamp on the dashboard it stands for.
     */
    val Alert: ImageVector by lazy {
        icon("Alert") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.NonZero) {
                moveTo(12f, 3.5f)
                lineTo(22.5f, 21f)
                horizontalLineToRelative(-21f)
                close()
                moveTo(11f, 10f)
                verticalLineToRelative(5f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-5f)
                close()
                moveTo(11f, 16.5f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                close()
            }
        }
    }

    /** The trip log: a page with a pencil at its corner. */
    val Trips: ImageVector by lazy {
        icon("Trips") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.NonZero) {
                moveTo(4f, 4f)
                horizontalLineToRelative(10f)
                verticalLineToRelative(2f)
                horizontalLineTo(6f)
                verticalLineToRelative(12f)
                horizontalLineToRelative(12f)
                verticalLineToRelative(-8f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(10f)
                horizontalLineTo(4f)
                close()
                moveTo(16.6f, 5f)
                lineTo(20f, 8.4f)
                lineToRelative(-7.3f, 7.3f)
                lineToRelative(-3.4f, 0.1f)
                lineToRelative(0.1f, -3.4f)
                close()
            }
        }
    }

    /**
     * One glyph per family of readings, drawn as a line rather than a solid.
     *
     * A dashboard tile carries an icon at seventeen points inside a twenty-six point
     * circle, which is far too small for a literal drawing of a sensor: what survives at
     * that size is a silhouette — a bulb with a stem, a cell with a terminal, a drop — and
     * anything more detailed turns into a smudge. They are strokes so they read as
     * markings on the card rather than as buttons on it.
     */
    val Thermometer: ImageVector by lazy {
        lineIcon("Thermometer") {
            moveTo(12f, 3.6f)
            arcToRelative(2.4f, 2.4f, 0f, false, true, 2.4f, 2.4f)
            verticalLineToRelative(7f)
            arcToRelative(4.4f, 4.4f, 0f, true, true, -4.8f, 0f)
            verticalLineToRelative(-7f)
            arcTo(2.4f, 2.4f, 0f, false, true, 12f, 3.6f)
            close()
        }
    }

    /** The battery: a cell, its terminal, and the plus that says which way round it goes. */
    val Battery: ImageVector by lazy {
        lineIcon("Battery") {
            moveTo(3.5f, 8.5f)
            horizontalLineToRelative(14f)
            verticalLineToRelative(7f)
            horizontalLineToRelative(-14f)
            close()
            moveTo(17.5f, 10.5f)
            horizontalLineToRelative(2.5f)
            verticalLineToRelative(3f)
            horizontalLineToRelative(-2.5f)
            close()
            moveTo(7.5f, 12f)
            horizontalLineToRelative(4f)
            moveTo(9.5f, 10f)
            verticalLineToRelative(4f)
        }
    }

    /** Anything that is a fluid or is measured against one: fuel, mixture, air flow. */
    val Droplet: ImageVector by lazy {
        lineIcon("Droplet") {
            moveTo(12f, 3.8f)
            curveToRelative(3.4f, 4f, 5.3f, 6.7f, 5.3f, 9f)
            arcToRelative(5.3f, 5.3f, 0f, true, true, -10.6f, 0f)
            curveToRelative(0f, -2.3f, 1.9f, -5f, 5.3f, -9f)
            close()
        }
    }

    /** Moving air: what the intake side of the engine measures. */
    val Airflow: ImageVector by lazy {
        lineIcon("Airflow") {
            moveTo(3f, 8.5f)
            horizontalLineToRelative(10.5f)
            arcTo(3f, 3f, 0f, true, false, 10.5f, 5f)
            moveTo(3f, 13f)
            horizontalLineToRelative(8f)
            arcTo(2.6f, 2.6f, 0f, true, true, 8.4f, 15.6f)
            moveTo(3f, 17.5f)
            horizontalLineToRelative(5.5f)
        }
    }

    /** A butterfly valve seen edge on: the throttle, and anything that opens like it. */
    val Valve: ImageVector by lazy {
        lineIcon("Valve") {
            moveTo(12f, 3.6f)
            arcToRelative(8.4f, 8.4f, 0f, true, false, 0f, 16.8f)
            arcToRelative(8.4f, 8.4f, 0f, false, false, 0f, -16.8f)
            close()
            moveTo(6f, 18f)
            lineTo(18f, 6f)
        }
    }

    /** A spark: the ignition side of the engine, and the misfires counted there. */
    val Spark: ImageVector by lazy {
        lineIcon("Spark") {
            moveTo(13.6f, 2.8f)
            lineTo(6.4f, 13.2f)
            horizontalLineToRelative(4.4f)
            lineToRelative(-1.4f, 8f)
            lineToRelative(7.2f, -10.4f)
            horizontalLineToRelative(-4.4f)
            close()
        }
    }

    /** A cog: the gearbox, and anything geared. */
    val Cog: ImageVector by lazy {
        lineIcon("Cog") {
            moveTo(12f, 5.4f)
            arcToRelative(6.6f, 6.6f, 0f, true, false, 0f, 13.2f)
            arcToRelative(6.6f, 6.6f, 0f, false, false, 0f, -13.2f)
            close()
            moveTo(12f, 9.4f)
            arcToRelative(2.6f, 2.6f, 0f, true, false, 0f, 5.2f)
            arcToRelative(2.6f, 2.6f, 0f, false, false, 0f, -5.2f)
            close()
            moveTo(12f, 5.4f)
            verticalLineTo(2.6f)
            moveTo(12f, 18.6f)
            verticalLineTo(21.4f)
            moveTo(5.4f, 12f)
            horizontalLineTo(2.6f)
            moveTo(18.6f, 12f)
            horizontalLineTo(21.4f)
        }
    }

    /** A tyre seen from the side: the wheel positions a pressure belongs to. */
    val Tyre: ImageVector by lazy {
        lineIcon("Tyre") {
            moveTo(12f, 3.6f)
            arcToRelative(8.4f, 8.4f, 0f, true, false, 0f, 16.8f)
            arcToRelative(8.4f, 8.4f, 0f, false, false, 0f, -16.8f)
            close()
            moveTo(12f, 8.6f)
            arcToRelative(3.4f, 3.4f, 0f, true, false, 0f, 6.8f)
            arcToRelative(3.4f, 3.4f, 0f, false, false, 0f, -6.8f)
            close()
        }
    }

    /** A chevron for rows that lead somewhere. */
    val ChevronRight: ImageVector by lazy {
        icon("ChevronRight") {
            path(fill = SolidColor(Color.White), pathFillType = PathFillType.NonZero) {
                moveTo(9.3f, 6.3f)
                lineTo(14.99f, 12f)
                lineTo(9.3f, 17.7f)
                lineTo(7.9f, 16.3f)
                lineTo(12.2f, 12f)
                lineTo(7.9f, 7.7f)
                close()
            }
        }
    }

    /**
     * A stroked glyph: one path, no fill, round joins.
     *
     * [androidx.compose.material3.Icon] tints the whole vector, so the white here is only
     * the colour the tint replaces — the same convention the filled glyphs above use.
     */
    private inline fun lineIcon(
        name: String,
        crossinline block: PathBuilder.() -> Unit,
    ): ImageVector = icon(name) {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = LINE_STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            block()
        }
    }

    private inline fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    /** Thin enough to stay a line at seventeen points, heavy enough not to disappear. */
    const val LINE_STROKE = 1.6f
}
