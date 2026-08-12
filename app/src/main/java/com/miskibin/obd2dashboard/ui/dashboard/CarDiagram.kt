package com.miskibin.obd2dashboard.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miskibin.obd2dashboard.R
import com.miskibin.obd2dashboard.data.AlertRule
import com.miskibin.obd2dashboard.data.Metric
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.MetricStatus
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.statusOf
import com.miskibin.obd2dashboard.data.valueOf
import com.miskibin.obd2dashboard.obd.Pids
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import com.miskibin.obd2dashboard.ui.AppIcons
import com.miskibin.obd2dashboard.ui.components.NO_VALUE
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.label
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.Graphite
import com.miskibin.obd2dashboard.ui.theme.LocalSkin
import com.miskibin.obd2dashboard.ui.theme.SlateEdge
import com.miskibin.obd2dashboard.ui.theme.SlateTrack
import com.miskibin.obd2dashboard.ui.theme.Smoke
import kotlin.math.cos
import kotlin.math.sin

/**
 * The front quarter of the car, drawn close, with the readings standing where they are taken.
 *
 * A grid of tiles says what the numbers are; it cannot say where they come from, and to
 * anybody who has not spent time under a bonnet "intake air temperature" and "coolant
 * temperature" are two temperatures with no place attached. Here they are the grille, the
 * lamp, the wing and the arch — and the one that has gone amber is a lit spot on a drawing
 * of the car rather than a word in a list.
 *
 * The drawing is a crop, not a diagram: the hood, shoulder and pillar lines run off the top
 * of it and the wheel off the bottom, which is what makes it read as a car seen from a step
 * away rather than as clip art parked in a box. The zones on it are areas rather than dots —
 * each is a target a thumb can hit in a moving car, carries its own reading, and opens the
 * same sheet its tile would.
 */
@Composable
fun CarDiagram(
    snapshot: VehicleSnapshot,
    alertRules: List<AlertRule>,
    onOpenMetric: (MetricId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zones = carZones(snapshot, alertRules)
    if (zones.isEmpty()) return

    val skin = LocalSkin.current
    val palette = CarColors(
        line = skin.title,
        muted = Smoke,
        faint = Graphite,
        track = SlateTrack,
        ghost = SlateEdge,
    )
    val description = stringResource(R.string.dashboard_car_description)
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // The artwork is drawn in the box it was designed in and scaled to whatever width
        // the screen gives us, so every line and every zone keeps its place on the car.
        // Only the geometry is scaled: the strokes are stated in points, because a sketch
        // that reads as line art on a tablet would be a grey haze on a phone.
        val widthPx = with(density) { maxWidth.toPx() }
        val art = remember(widthPx) { CarArtwork(widthPx / DESIGN_WIDTH) }
        val strokes = remember(density) { CarStrokes(density) }
        val scale = maxWidth.value / DESIGN_WIDTH
        val zoneSide = (ZONE_SIDE * scale).dp.coerceAtLeast(ZONE_MIN_SIDE.dp)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(maxWidth * DESIGN_HEIGHT / DESIGN_WIDTH)
                .clearAndSetSemantics { contentDescription = description },
        ) {
            drawCar(art, strokes, palette)
        }

        zones.forEach { zone ->
            CarZoneArea(
                zone = zone,
                side = zoneSide,
                modifier = Modifier.offset(
                    x = (zone.at.x * scale).dp - zoneSide / 2,
                    y = (zone.at.y * scale).dp - zoneSide / 2,
                ),
                onClick = { onOpenMetric(zone.id) },
            )
        }
    }
}

/** Whether the car has reported anything the drawing can stand a zone on. */
fun hasCarZones(snapshot: VehicleSnapshot): Boolean =
    ANCHORS.any { anchor -> anchor.candidates.any { snapshot.valueOf(it) != null } }

/** One place on the car: the metric measured there, and how that metric is doing. */
private data class CarZoneState(
    val id: MetricId,
    val metric: Metric,
    val value: Double?,
    val status: MetricStatus,
    val glyph: ImageVector,
    val at: Offset,
)

/**
 * A place on the car as a target: a chip on the drawing carrying the glyph, the reading and
 * the state as a tint.
 *
 * The chip has a surface of its own rather than being a hole cut in the line art, because a
 * reading laid straight onto a sketch is a reading with a bonnet line through it. It is
 * sized against the artwork so it keeps its place on the car, with a floor under it so it
 * never falls below what a thumb can find on the narrowest phone.
 */
@Composable
private fun CarZoneArea(
    zone: CarZoneState,
    side: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(
        targetValue = statusColor(zone.status),
        animationSpec = tween(durationMillis = ZONE_ANIMATION_MILLIS),
        label = "zone-status",
    )
    val skin = LocalSkin.current
    val breached = zone.status.breached
    val reading = formatReading(zone.value, zone.metric.decimals)
    val label = zone.metric.label()
    val description = stringResource(R.string.dashboard_car_zone, label, reading, zone.metric.unit)

    // Two edges, as the sketch draws them: a soft one that lifts the chip off the line art,
    // and a crisp one inside it that states the state.
    Box(
        modifier = modifier
            .size(side)
            .clip(ZoneCorner)
            .background(skin.card)
            .border(ZONE_HALO_STROKE.dp, tint.copy(alpha = ZONE_HALO), ZoneCorner)
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .size(side - ZONE_INSET.dp * 2)
                .clip(ZoneInnerCorner)
                .background(tint.copy(alpha = if (breached) ZONE_FILL_ON else ZONE_FILL))
                .border(
                    width = 1.dp,
                    color = tint.copy(alpha = if (breached) ZONE_EDGE_ON else ZONE_EDGE),
                    shape = ZoneInnerCorner,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = zone.glyph,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(ZONE_GLYPH.dp),
            )
            Text(
                text = if (zone.value == null) NO_VALUE else reading,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = ZONE_TEXT.sp),
                color = if (breached) tint else Chalk,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** Which metric each place on the car speaks for, and where that place is on the drawing. */
private data class CarAnchor(
    val at: Offset,
    val glyph: ImageVector,
    val candidates: List<MetricId>,
)

/**
 * The four places, and what to read at each when the first choice is not reported.
 *
 * A generic OBD2 car answers a different subset of the standard from the next one, so each
 * place names the reading that belongs there and then the nearest thing physically beside
 * it: the airflow sensor sits in the same pipe as the intake temperature sensor, the ECU's
 * own supply is measured on the same battery the adapter measures, and either answers the
 * question the place is asking. A place whose car reports none of them is simply not drawn,
 * and every reading has its tile in the grid below whether or not it has a place up here.
 */
private val ANCHORS: List<CarAnchor> by lazy {
    listOf(
        // The radiator, behind the grille the air goes into.
        CarAnchor(
            at = Offset(820f, 600f),
            glyph = AppIcons.Thermometer,
            candidates = listOf(Metrics.CoolantTemp, MetricId.Sensor(0x67)),
        ),
        // The battery, beside the lamp at the front of the bay.
        CarAnchor(
            at = Offset(1230f, 300f),
            glyph = AppIcons.Battery,
            candidates = listOf(Metrics.Battery, MetricId.Sensor(Pids.CONTROL_MODULE_VOLTAGE)),
        ),
        // The intake, drawing its air in past the arch.
        CarAnchor(
            at = Offset(1140f, 690f),
            glyph = AppIcons.Airflow,
            candidates = listOf(Metrics.IntakeAirTemp, Metrics.Maf, MetricId.Sensor(0x66)),
        ),
        // The engine itself, under the wing.
        CarAnchor(
            at = Offset(1620f, 330f),
            glyph = AppIcons.Droplet,
            candidates = listOf(Metrics.OilTemp, MetricId.Sensor(Pids.COMMANDED_EQUIV_RATIO)),
        ),
    )
}

/** The zones the current snapshot can fill, each already judged against its band. */
@Composable
private fun carZones(
    snapshot: VehicleSnapshot,
    alertRules: List<AlertRule>,
): List<CarZoneState> = remember(snapshot, alertRules) {
    ANCHORS.mapNotNull { anchor ->
        val id = anchor.candidates.firstOrNull { snapshot.valueOf(it) != null }
            ?: return@mapNotNull null
        val metric = Metrics[id] ?: return@mapNotNull null
        val value = snapshot.valueOf(id)
        CarZoneState(
            id = id,
            metric = metric,
            value = value,
            status = Metrics.bandFor(id, alertRules).statusOf(value) ?: MetricStatus.Normal,
            glyph = anchor.glyph,
            at = anchor.at,
        )
    }
}

/** The roles the sketch is drawn in, so it reads on either ground. */
private data class CarColors(
    val line: Color,
    val muted: Color,
    val faint: Color,
    val track: Color,
    val ghost: Color,
)

/**
 * The sketch, scaled once into device pixels.
 *
 * Held as built paths rather than re-parsed every frame, and scaled by transforming the
 * geometry rather than the canvas, so the stroke widths stay the ones [CarStrokes] states.
 */
private class CarArtwork(val scale: Float) {
    val backdrop: Path = path(BACKDROP)
    val grille: Path = path(GRILLE)
    val grilleTop: Path = path(GRILLE_TOP)
    val wing: Path = path(WING)
    val wingInner: Path = path(WING_INNER)
    val lamp: Path = path(LAMP)
    val lampStrips: Path = path(LAMP_STRIPS)
    val lampFlicks: Path = path(LAMP_FLICKS)
    val bumper: Path = path(BUMPER)
    val bumperInner: Path = path(BUMPER_INNER)
    val bumperGhost: Path = path(BUMPER_GHOST)
    val hood: Path = path(HOOD)
    val hoodInner: Path = path(HOOD_INNER)
    val shoulder: Path = path(SHOULDER)
    val pillars: Path = path(PILLARS)
    val arch: Path = path(ARCH)
    val archInner: Path = path(ARCH_INNER)
    val caliper: Path = path(CALIPER)

    /** A point of the design, in pixels. */
    fun at(x: Float, y: Float) = Offset(x * scale, y * scale)

    /** A length of the design, in pixels. */
    fun len(value: Float) = value * scale

    private fun path(data: String): Path =
        PathParser().parsePathString(data).toPath().apply {
            transform(Matrix().apply { scale(this@CarArtwork.scale, this@CarArtwork.scale) })
        }
}

/** The weights the sketch is drawn at, stated in points and kept in pixels. */
private class CarStrokes(density: Density) {
    val hair = with(density) { 0.7.dp.toPx() }
    val thin = with(density) { 0.9.dp.toPx() }
    val line = with(density) { 1.2.dp.toPx() }
    val bold = with(density) { 1.7.dp.toPx() }
    val heavy = with(density) { 2.4.dp.toPx() }
    val underlay = with(density) { 4.2.dp.toPx() }
    val mesh = with(density) { 0.6.dp.toPx() }
}

/**
 * The whole sketch, back to front.
 *
 * The order is the order a pen would take it: the faint arcs behind everything, then the
 * grille and its mesh, then the panels over them, and the wheel last, so its arch cuts the
 * lines that run behind it.
 */
private fun DrawScope.drawCar(art: CarArtwork, strokes: CarStrokes, colors: CarColors) {
    drawPath(art.backdrop, color = colors.ghost, style = Stroke(strokes.thin))

    drawGrilleMesh(art, strokes, colors.faint)
    drawPath(art.grilleTop, color = colors.line, style = Stroke(strokes.line))
    drawPath(art.wing, color = colors.line, style = Stroke(strokes.heavy))
    drawPath(art.wingInner, color = colors.muted, style = Stroke(strokes.thin))

    drawEmblem(art, strokes, colors)

    drawPath(art.lamp, color = colors.line, style = Stroke(strokes.line))
    drawLampLens(art, strokes, colors)
    drawPath(art.lampStrips, color = colors.muted, style = Stroke(strokes.thin))
    drawPath(art.lampFlicks, color = colors.faint, style = Stroke(strokes.hair))

    drawPath(art.bumperGhost, color = colors.track, style = Stroke(strokes.underlay))
    drawPath(art.bumper, color = colors.line, style = Stroke(strokes.bold))
    drawPath(art.bumperInner, color = colors.line, style = Stroke(strokes.thin))

    drawPath(art.hood, color = colors.line, style = Stroke(strokes.thin))
    drawPath(art.hoodInner, color = colors.muted, style = Stroke(strokes.hair))
    drawPath(art.shoulder, color = colors.line, style = Stroke(strokes.hair))
    drawPath(art.pillars, color = colors.line, style = Stroke(strokes.thin))

    drawPath(art.arch, color = colors.track, style = Stroke(strokes.underlay))
    drawPath(art.arch, color = colors.line, style = Stroke(strokes.bold))
    drawPath(art.archInner, color = colors.line, style = Stroke(strokes.thin))

    drawWheel(art, strokes, colors.line)
    drawPath(art.caliper, color = colors.faint, style = Stroke(strokes.thin))
}

/**
 * The grille's diamond mesh, as a lattice clipped to the grille.
 *
 * Two families of parallel lines rather than a tiled diamond: at this size the mesh is a
 * texture, and a texture drawn as five hundred little closed paths costs five hundred paths
 * to say what a hundred lines say.
 */
private fun DrawScope.drawGrilleMesh(art: CarArtwork, strokes: CarStrokes, color: Color) {
    val mesh = color.copy(alpha = MESH_ALPHA)
    val step = art.len(MESH_STEP)
    val slope = MESH_RISE / MESH_RUN
    val from = art.at(MESH_FROM_X, 0f).x
    val to = art.at(MESH_TO_X, 0f).x

    clipPath(art.grille) {
        rotate(degrees = MESH_ANGLE, pivot = art.at(MESH_PIVOT_X, MESH_PIVOT_Y)) {
            var offset = art.len(MESH_FIRST)
            val last = art.len(MESH_LAST)
            while (offset <= last) {
                drawLine(
                    color = mesh,
                    start = Offset(from, offset + slope * from),
                    end = Offset(to, offset + slope * to),
                    strokeWidth = strokes.mesh,
                )
                drawLine(
                    color = mesh,
                    start = Offset(from, offset - slope * from),
                    end = Offset(to, offset - slope * to),
                    strokeWidth = strokes.mesh,
                )
                offset += step
            }
        }
    }
}

/** The badge on the grille: two ovals, and the owner's own zigzag inside them. */
private fun DrawScope.drawEmblem(art: CarArtwork, strokes: CarStrokes, colors: CarColors) {
    val centre = art.at(EMBLEM_X, EMBLEM_Y)
    rotate(degrees = EMBLEM_ANGLE, pivot = centre) {
        drawOval(
            color = colors.line,
            topLeft = centre - Offset(art.len(EMBLEM_RX), art.len(EMBLEM_RY)),
            size = Size(art.len(EMBLEM_RX * 2), art.len(EMBLEM_RY * 2)),
            style = Stroke(strokes.bold),
        )
        drawOval(
            color = colors.muted,
            topLeft = centre - Offset(art.len(EMBLEM_INNER_RX), art.len(EMBLEM_INNER_RY)),
            size = Size(art.len(EMBLEM_INNER_RX * 2), art.len(EMBLEM_INNER_RY * 2)),
            style = Stroke(strokes.hair),
        )
        val mark = Path()
        EMBLEM_MARK.forEachIndexed { index, point ->
            val at = centre + Offset(art.len(point.first), art.len(point.second))
            if (index == 0) mark.moveTo(at.x, at.y) else mark.lineTo(at.x, at.y)
        }
        drawPath(mark, color = colors.line, style = Stroke(strokes.bold))
    }
}

/** The projector inside the lamp: two ovals on the same rake. */
private fun DrawScope.drawLampLens(art: CarArtwork, strokes: CarStrokes, colors: CarColors) {
    val centre = art.at(LENS_X, LENS_Y)
    rotate(degrees = LENS_ANGLE, pivot = centre) {
        drawOval(
            color = colors.line,
            topLeft = centre - Offset(art.len(LENS_RX), art.len(LENS_RY)),
            size = Size(art.len(LENS_RX * 2), art.len(LENS_RY * 2)),
            style = Stroke(strokes.line),
        )
        drawOval(
            color = colors.muted,
            topLeft = centre - Offset(art.len(LENS_INNER_RX), art.len(LENS_INNER_RY)),
            size = Size(art.len(LENS_INNER_RX * 2), art.len(LENS_INNER_RY * 2)),
            style = Stroke(strokes.hair),
        )
    }
}

/**
 * The wheel, seen at the angle the rest of the sketch is seen at.
 *
 * The foreshortening is baked into the geometry rather than applied as a canvas scale: a
 * squashed canvas squashes the strokes with it, and a rim drawn with an oval pen is the one
 * thing that would give the drawing away as a trick.
 */
private fun DrawScope.drawWheel(art: CarArtwork, strokes: CarStrokes, line: Color) {
    val centre = art.at(WHEEL_X, WHEEL_Y)
    rotate(degrees = WHEEL_ANGLE, pivot = centre) {
        listOf(
            WHEEL_TYRE to strokes.bold,
            WHEEL_RIM to strokes.line,
            WHEEL_HUB to strokes.line,
        ).forEach { (radius, width) ->
            val rx = art.len(radius * WHEEL_SQUASH)
            val ry = art.len(radius)
            drawOval(
                color = line,
                topLeft = centre - Offset(rx, ry),
                size = Size(rx * 2, ry * 2),
                style = Stroke(width),
            )
        }

        repeat(WHEEL_SPOKES) { index ->
            val angle = index * (2.0 * Math.PI / WHEEL_SPOKES)
            val cosine = cos(angle).toFloat()
            val sine = sin(angle).toFloat()
            // Each spoke is a pair of lines, splaying from the hub out to the rim.
            listOf(-1f, 1f).forEach { side ->
                val inner = spoke(art, centre, SPOKE_INNER_X * side, SPOKE_INNER_Y, cosine, sine)
                val outer = spoke(art, centre, SPOKE_OUTER_X * side, SPOKE_OUTER_Y, cosine, sine)
                drawLine(color = line, start = inner, end = outer, strokeWidth = strokes.line)
            }
        }
    }
}

/** One end of a spoke, turned about the hub and then foreshortened. */
private fun spoke(
    art: CarArtwork,
    centre: Offset,
    x: Float,
    y: Float,
    cosine: Float,
    sine: Float,
): Offset = centre + Offset(
    art.len((x * cosine - y * sine) * WHEEL_SQUASH),
    art.len(x * sine + y * cosine),
)

/** The box the sketch is drawn in, and scaled out of. */
private const val DESIGN_WIDTH = 1920f
private const val DESIGN_HEIGHT = 1080f

/* The sketch itself, path by path, in that box. */
private const val BACKDROP =
    "M 400 30 A 120 70 0 0 1 600 30 M 800 10 A 120 70 0 0 1 1000 10 " +
        "M 500 50 C 650 90, 750 90, 900 50"
private const val GRILLE = "M 180 460 Q 500 280 850 430 C 700 700, 350 720, 180 460 Z"
private const val GRILLE_TOP = "M 180 460 Q 500 280 850 430"
private const val WING = "M 180 460 C 250 780, 650 760, 900 480 C 1020 370, 1150 320, 1300 290"
private const val WING_INNER =
    "M 190 455 C 260 760, 640 740, 890 465 C 1010 355, 1145 305, 1300 275"
private const val LAMP = "M 850 430 C 1020 340, 1160 310, 1300 290"
private const val LAMP_STRIPS =
    "M 1000 370 L 1150 325 M 1010 385 L 1140 340 M 1020 400 L 1110 365"
private const val LAMP_FLICKS = "M 865 415 L 920 375 M 880 425 L 935 385"
private const val BUMPER =
    "M 170 780 C 350 950, 700 920, 950 820 C 1050 780, 1120 720, 1180 630"
private const val BUMPER_INNER =
    "M 180 730 C 300 850, 500 870, 700 840 C 850 820, 950 780, 1050 680"
private const val BUMPER_GHOST = "M 680 840 C 850 810, 1000 750, 1080 650"
private const val HOOD = "M 450 360 C 650 200, 950 120, 1300 80"
private const val HOOD_INNER = "M 250 420 C 450 250, 750 160, 1100 120"
private const val SHOULDER = "M 1150 310 C 1350 220, 1550 180, 1850 150"
private const val PILLARS =
    "M 1250 160 C 1450 90, 1650 50, 1900 20 M 1550 210 C 1650 120, 1780 40, 1920 0"
private const val ARCH = "M 1150 580 C 1300 300, 1600 350, 1750 950"
private const val ARCH_INNER = "M 1170 610 C 1310 360, 1550 410, 1680 950"
private const val CALIPER = "M 1240 800 Q 1230 840 1240 880 M 1250 740 Q 1240 760 1250 780"

/** The grille mesh: a diamond lattice on a 24×16 tile, laid over at fifteen degrees. */
private const val MESH_ANGLE = 15f
private const val MESH_RUN = 12f
private const val MESH_RISE = 8f
private const val MESH_STEP = 16f
private const val MESH_FIRST = -800f
private const val MESH_LAST = 1200f
private const val MESH_FROM_X = 40f
private const val MESH_TO_X = 1060f
private const val MESH_PIVOT_X = 500f
private const val MESH_PIVOT_Y = 480f
private const val MESH_ALPHA = 0.55f

private const val EMBLEM_X = 450f
private const val EMBLEM_Y = 480f
private const val EMBLEM_ANGLE = 10f
private const val EMBLEM_RX = 75f
private const val EMBLEM_RY = 95f
private const val EMBLEM_INNER_RX = 60f
private const val EMBLEM_INNER_RY = 80f

/** The owner's own mark inside the badge, kept exactly as they drew it. */
private val EMBLEM_MARK = listOf(
    -55f to 10f,
    -25f to 35f,
    0f to -35f,
    25f to 35f,
    55f to 10f,
)

private const val LENS_X = 950f
private const val LENS_Y = 390f
private const val LENS_ANGLE = 22f
private const val LENS_RX = 22f
private const val LENS_RY = 40f
private const val LENS_INNER_RX = 8f
private const val LENS_INNER_RY = 15f

private const val WHEEL_X = 1420f
private const val WHEEL_Y = 700f
private const val WHEEL_ANGLE = 8f

/** How far the wheel is turned away from us, as the width it keeps. */
private const val WHEEL_SQUASH = 0.65f
private const val WHEEL_TYRE = 280f
private const val WHEEL_RIM = 220f
private const val WHEEL_HUB = 35f
private const val WHEEL_SPOKES = 5
private const val SPOKE_INNER_X = 15f
private const val SPOKE_INNER_Y = -35f
private const val SPOKE_OUTER_X = 25f
private const val SPOKE_OUTER_Y = -220f

/** A zone, in design units, and the floor under it in points. */
private const val ZONE_SIDE = 260f
private const val ZONE_MIN_SIDE = 46
private const val ZONE_INSET = 3
private const val ZONE_GLYPH = 17
private const val ZONE_TEXT = 10f
private val ZoneCorner = RoundedCornerShape(percent = 24)
private val ZoneInnerCorner = RoundedCornerShape(percent = 22)

private const val ZONE_FILL = 0.10f
private const val ZONE_FILL_ON = 0.22f
private const val ZONE_EDGE = 0.5f
private const val ZONE_EDGE_ON = 0.95f
private const val ZONE_HALO = 0.3f
private const val ZONE_HALO_STROKE = 2.5f
private const val ZONE_ANIMATION_MILLIS = 400
