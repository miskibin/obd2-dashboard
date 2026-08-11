package com.miskibin.obd2dashboard.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.miskibin.obd2dashboard.R

/**
 * Archivo, bundled as four static instances cut from the variable font.
 *
 * A grotesque with short ascenders and open counters: it holds its shape at 10 sp in a
 * chart axis and still looks like a number, not a headline, at 54 sp.
 */
val Archivo = FontFamily(
    Font(R.font.archivo_regular, FontWeight.Normal),
    Font(R.font.archivo_medium, FontWeight.Medium),
    Font(R.font.archivo_semibold, FontWeight.SemiBold),
    Font(R.font.archivo_bold, FontWeight.Bold),
)

/**
 * Tabular figures.
 *
 * Every live number in the app is redrawn several times a second; proportional digits
 * would make the whole readout shuffle sideways each time a 1 became a 7.
 */
private const val TABULAR = "tnum"

/**
 * The scale.
 *
 * Two sizes do most of the work — 13 sp for anything read as a sentence and 12.5 sp for
 * anything read as a label — with a short ladder of semibold sizes above them for values
 * and titles. Weights stop at semibold: bold at these sizes only smears.
 */
val Obd2Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 54.sp,
        lineHeight = 56.sp,
        letterSpacing = (-1.6).sp,
        fontFeatureSettings = TABULAR,
    ),
    displayMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.6).sp,
        fontFeatureSettings = TABULAR,
    ),
    displaySmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = TABULAR,
    ),
    headlineLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.22).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
        fontFeatureSettings = TABULAR,
    ),
    titleMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Medium,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Archivo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.42.sp,
    ),
)

/** Monospaced-by-feature style for live numeric readouts, so digits do not jitter. */
val ReadoutTextStyle = TextStyle(
    fontFamily = Archivo,
    fontWeight = FontWeight.SemiBold,
    fontSize = 44.sp,
    letterSpacing = (-1.3).sp,
    fontFeatureSettings = TABULAR,
)

/** Row values and anything else that has to line up in a column of numbers. */
val NumberTextStyle = TextStyle(
    fontFamily = Archivo,
    fontWeight = FontWeight.SemiBold,
    fontSize = 19.sp,
    fontFeatureSettings = TABULAR,
)

/** Chart axis ticks and in-plot annotations. */
val TickTextStyle = TextStyle(
    fontFamily = Archivo,
    fontWeight = FontWeight.Medium,
    fontSize = 10.5.sp,
    fontFeatureSettings = TABULAR,
)

/** The label drawn inside a chart band, naming the series it belongs to. */
val BandLabelTextStyle = TextStyle(
    fontFamily = Archivo,
    fontWeight = FontWeight.SemiBold,
    fontSize = 10.sp,
)
