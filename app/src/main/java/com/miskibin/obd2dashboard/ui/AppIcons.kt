package com.miskibin.obd2dashboard.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
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

    private inline fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()
}
