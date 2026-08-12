package com.miskibin.obd2dashboard.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
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
import com.miskibin.obd2dashboard.data.CarZone
import com.miskibin.obd2dashboard.data.CarZoneBinding
import com.miskibin.obd2dashboard.data.MetricId
import com.miskibin.obd2dashboard.data.MetricStatus
import com.miskibin.obd2dashboard.data.Metrics
import com.miskibin.obd2dashboard.data.MisfireReading
import com.miskibin.obd2dashboard.data.ZoneSource
import com.miskibin.obd2dashboard.data.statusOf
import com.miskibin.obd2dashboard.obd.VehicleSnapshot
import com.miskibin.obd2dashboard.ui.AppIcons
import com.miskibin.obd2dashboard.ui.components.NO_VALUE
import com.miskibin.obd2dashboard.ui.components.formatReading
import com.miskibin.obd2dashboard.ui.label
import com.miskibin.obd2dashboard.ui.theme.Chalk
import com.miskibin.obd2dashboard.ui.theme.LocalPalette
import com.miskibin.obd2dashboard.ui.theme.LocalSkin
import com.miskibin.obd2dashboard.ui.theme.Palette
import com.miskibin.obd2dashboard.ui.theme.Smoke

/**
 * The car seen from above, drawn as a blueprint, with the readings standing on the parts
 * they are taken from.
 *
 * A grid of tiles says what the numbers are; it cannot say where they come from, and to
 * anybody who has not spent time under a bonnet "intake air temperature" and "coolant
 * temperature" are two temperatures with no place attached. From above every part has a
 * place of its own — the radiator across the nose, the coil pack behind the block, a tyre
 * at each corner — which a side view cannot give it: half the car is hidden behind the
 * other half, and all four wheels are never in it at once.
 *
 * The parts are drawn rather than dotted: the block with its ribs, the rail with an
 * injector under each dot, the muffler with the probe hanging off it. A zone that is only
 * a chip on a silhouette says "a number belongs here"; a zone that is a drawing of the
 * component says which one.
 *
 * Only the zones the driver has ticked are drawn, and only where the connected car can
 * actually fill them — see [com.miskibin.obd2dashboard.data.carZoneBindings].
 */
@Composable
fun CarDiagram(
    bindings: List<CarZoneBinding>,
    snapshot: VehicleSnapshot,
    alertRules: List<AlertRule>,
    misfire: MisfireReading?,
    onOpenMetric: (MetricId) -> Unit,
    onOpenMonitors: () -> Unit,
    onEditZones: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zones = carZones(bindings, snapshot, alertRules, misfire)
    if (zones.isEmpty()) return

    val palette = LocalPalette.current
    val colors = remember(palette) { carColors(palette) }
    val accents = remember(palette) { carAccents(palette.dark) }
    val description = stringResource(R.string.dashboard_car_description)
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // The artwork is drawn in the box it was designed in and scaled to whatever width
        // the screen gives us, so every line and every part keeps its place on the car.
        // Only the geometry is scaled: the strokes are stated in points, because a sketch
        // that reads as line art on a tablet would be a grey haze on a phone.
        val widthPx = with(density) { maxWidth.toPx() }
        val art = remember(widthPx) { CarArtwork(widthPx / DESIGN_WIDTH) }
        val strokes = remember(density) { CarStrokes(density) }
        val scale = maxWidth.value / DESIGN_WIDTH
        val chipSide = (CHIP_SIDE * scale).dp.coerceAtLeast(CHIP_MIN_SIDE.dp)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(maxWidth * DESIGN_HEIGHT / DESIGN_WIDTH)
                // The drawing is the affordance: a long press on the car is how the driver
                // says which parts of it they want to read, the same gesture that turns the
                // grid of tiles below into something they can prune.
                .pointerInput(onEditZones) {
                    detectTapGestures(onLongPress = { onEditZones() })
                }
                .clearAndSetSemantics { contentDescription = description },
        ) {
            drawCar(art, strokes, colors)
            zones.forEach { zone ->
                drawComponent(zone.zone, art, strokes, colors, accents.getValue(zone.zone))
            }
        }

        zones.forEach { zone ->
            CarZoneChip(
                zone = zone,
                accent = accents.getValue(zone.zone),
                side = chipSide,
                modifier = Modifier.offset(
                    x = ((zone.at.x - DESIGN_LEFT) * scale).dp - chipSide / 2,
                    y = ((zone.at.y - DESIGN_TOP) * scale).dp - chipSide / 2,
                ),
                onClick = {
                    val metric = zone.opens
                    if (metric != null) onOpenMetric(metric) else onOpenMonitors()
                },
            )
        }

        // The way to change what is on the drawing, for the driver who never discovers a
        // long press. It sits over the empty corner behind the car rather than on it.
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.car_zones_title),
            tint = Smoke,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(EDIT_BUTTON.dp)
                .clip(CircleShape)
                .clickable(onClick = onEditZones)
                .padding(EDIT_PADDING.dp),
        )
    }
}

/** One place on the car: what it is showing, how that reading is doing, and where it sits. */
private data class CarZoneState(
    val zone: CarZone,
    val opens: MetricId?,
    val glyph: ImageVector,
    val value: Double?,
    val unit: String,
    val decimals: Int,
    val label: String,
    val status: MetricStatus,
    val at: Offset,
)

/**
 * A place on the car as a target: a chip on the drawing carrying the glyph, the reading and
 * the state as a tint.
 *
 * The chip has a surface of its own rather than being a hole cut in the line art, because a
 * reading laid straight onto a sketch is a reading with a panel line through it. It is
 * sized against the artwork so it keeps its place on the car, with a floor under it so it
 * never falls below what a thumb can find on the narrowest phone.
 */
@Composable
private fun CarZoneChip(
    zone: CarZoneState,
    accent: Color,
    side: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The part's own colour while the reading is where it should be, and the app's own
    // amber or red the moment it is not: a zone that stayed its family colour through a
    // breach would be a warning nobody sees.
    val target = if (zone.status.breached) statusColor(zone.status) else accent
    val tint by animateColorAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = ZONE_ANIMATION_MILLIS),
        label = "zone-status",
    )
    val skin = LocalSkin.current
    val breached = zone.status.breached
    val reading = formatReading(zone.value, zone.decimals)
    val description = stringResource(R.string.dashboard_car_zone, zone.label, reading, zone.unit)

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

/** Each bound zone as the drawing needs it, already judged against its band. */
@Composable
private fun carZones(
    bindings: List<CarZoneBinding>,
    snapshot: VehicleSnapshot,
    alertRules: List<AlertRule>,
    misfire: MisfireReading?,
): List<CarZoneState> {
    // Not cached: the name has to be resolved in composition — a metric that comes in a
    // family is named from a template and the numbers that place it in that family — and
    // there are at most fourteen of these, which is less work than the keys of a remember
    // that a freshly built list of names would invalidate on every frame anyway.
    return bindings.map { binding ->
        val value = binding.valueIn(snapshot, misfire)
        CarZoneState(
            zone = binding.zone,
            opens = binding.opens,
            glyph = zoneGlyph(binding.zone),
            value = value,
            unit = binding.unit,
            decimals = binding.decimals,
            label = binding.metric?.label() ?: stringResource(R.string.car_zone_misfires),
            status = zoneStatus(binding, value, alertRules, misfire),
            at = ANCHORS.getValue(binding.zone),
        )
    }
}

/**
 * How a zone's reading is doing.
 *
 * Everything out of the catalogue is judged against its normal band, as it is on its tile.
 * The misfire count has no band anybody could publish — a handful over ten drive cycles is
 * a cold morning — so it is judged by the pass mark the ECU itself shipped with it.
 */
private fun zoneStatus(
    binding: CarZoneBinding,
    value: Double?,
    alertRules: List<AlertRule>,
    misfire: MisfireReading?,
): MetricStatus = when (binding.source) {
    ZoneSource.Misfires ->
        if (misfire?.passed == false) MetricStatus.Above else MetricStatus.Normal

    is ZoneSource.Reading ->
        Metrics.bandFor(binding.source.id, alertRules).statusOf(value) ?: MetricStatus.Normal
}

/** One glyph per part, at the size a chip can carry. */
private fun zoneGlyph(zone: CarZone): ImageVector = when (zone) {
    CarZone.Engine -> AppIcons.Gauge
    CarZone.Throttle -> AppIcons.Valve
    CarZone.Fuel -> AppIcons.Droplet
    CarZone.Ignition -> AppIcons.Spark
    CarZone.Intake -> AppIcons.Airflow
    CarZone.Coolant -> AppIcons.Thermometer
    CarZone.Battery -> AppIcons.Battery
    CarZone.Oil -> AppIcons.Droplet
    CarZone.Transmission -> AppIcons.Cog
    CarZone.Exhaust -> AppIcons.Airflow
    CarZone.TyreFrontLeft, CarZone.TyreFrontRight,
    CarZone.TyreRearLeft, CarZone.TyreRearRight,
    -> AppIcons.Tyre
}

/** Where each part sits on the drawing, in design units. */
private val ANCHORS: Map<CarZone, Offset> = mapOf(
    CarZone.Engine to Offset(1130f, 500f),
    CarZone.Throttle to Offset(1270f, 500f),
    CarZone.Fuel to Offset(1130f, 360f),
    CarZone.Ignition to Offset(1130f, 640f),
    CarZone.Intake to Offset(1320f, 340f),
    CarZone.Coolant to Offset(1410f, 500f),
    CarZone.Battery to Offset(1310f, 660f),
    CarZone.Oil to Offset(985f, 630f),
    CarZone.Transmission to Offset(880f, 500f),
    CarZone.Exhaust to Offset(650f, 500f),
    CarZone.TyreFrontLeft to Offset(1180f, 220f),
    CarZone.TyreFrontRight to Offset(1180f, 780f),
    CarZone.TyreRearLeft to Offset(620f, 220f),
    CarZone.TyreRearRight to Offset(620f, 780f),
)

// -------------------------------------------------------------------------------------
// Colour
// -------------------------------------------------------------------------------------

/**
 * The roles the blueprint is drawn in, so it reads on either ground.
 *
 * The artwork was drawn on paper: slate strokes, a white body, near-black glass. Only the
 * *relationships* survive the move to the instrument ground — the body is the lighter thing
 * and the glass the darker — so on the night ground they swap: light strokes over dark
 * fills, exactly as the sketch this replaced handled it.
 */
private data class CarColors(
    val grid: Color,
    val bodyFill: Color,
    val bodyStroke: Color,
    val panelLine: Color,
    val glassFill: Color,
    val glassDeep: Color,
    val glassStroke: Color,
    val tyreFill: Color,
    val lampFill: Color,
    val drl: Color,
    val componentFill: Color,
)

private fun carColors(palette: Palette) = CarColors(
    grid = palette.slateFaint,
    // The body is the ground's own card, one step off the shell, so the car reads as a
    // panel of the app rather than as a picture pasted onto it.
    bodyFill = palette.slate,
    // The outline carries the whole shape, so it is the mid grey of either ground rather
    // than a hairline role: on paper that is the artwork's own slate over a white body, and
    // at night it is the same value seen the other way up — a light stroke over a dark one.
    bodyStroke = palette.smoke,
    panelLine = palette.graphite,
    // Glass, tyres and the splitter are the darkest thing on paper and the darkest thing
    // at night too: the one part of the drawing that does not invert, because a windscreen
    // drawn lighter than the roof around it stops reading as a window.
    glassFill = if (palette.dark) palette.inkRaised else palette.chalkDim,
    glassDeep = if (palette.dark) palette.ink else palette.chalk,
    glassStroke = palette.graphite,
    tyreFill = if (palette.dark) palette.ink else palette.chalkDim,
    lampFill = if (palette.dark) palette.chalkDim else palette.inkRaised,
    drl = palette.steel,
    componentFill = if (palette.dark) palette.ink else palette.inkRaised,
)

/**
 * The colour each part carries while its reading is where it should be.
 *
 * A table rather than one accent for all of them, because the whole point of a drawing is
 * that the eye finds the radiator without reading the word "radiator", and thirteen chips
 * in one blue would undo that. The hues are the artwork's own on the night ground; on
 * paper each is taken down until it holds the 3:1 a graphic needs against a white card —
 * neon cyan on white is a chip nobody can read.
 */
private fun carAccents(dark: Boolean): Map<CarZone, Color> = if (dark) DARK_ACCENTS else LIGHT_ACCENTS

private val DARK_ACCENTS: Map<CarZone, Color> = mapOf(
    CarZone.Engine to Color(0xFFFF6B6B),
    CarZone.Throttle to Color(0xFFFF9800),
    CarZone.Fuel to Color(0xFFFFC107),
    CarZone.Ignition to Color(0xFFE879F9),
    CarZone.Intake to Color(0xFF64B5F6),
    CarZone.Coolant to Color(0xFF00E5FF),
    CarZone.Battery to Color(0xFF00E676),
    CarZone.Oil to Color(0xFFBCAAA4),
    CarZone.Transmission to Color(0xFF8C9EFF),
    CarZone.Exhaust to Color(0xFFE2E8F0),
    CarZone.TyreFrontLeft to Color(0xFFFF7BA6),
    CarZone.TyreFrontRight to Color(0xFFFF7BA6),
    CarZone.TyreRearLeft to Color(0xFFFF7BA6),
    CarZone.TyreRearRight to Color(0xFFFF7BA6),
)

private val LIGHT_ACCENTS: Map<CarZone, Color> = mapOf(
    CarZone.Engine to Color(0xFFC62828),
    CarZone.Throttle to Color(0xFFB35C00),
    CarZone.Fuel to Color(0xFF8D6A00),
    CarZone.Ignition to Color(0xFF8E24AA),
    CarZone.Intake to Color(0xFF1565C0),
    CarZone.Coolant to Color(0xFF00707F),
    CarZone.Battery to Color(0xFF1B7F3B),
    CarZone.Oil to Color(0xFF6D4C41),
    CarZone.Transmission to Color(0xFF3F51B5),
    CarZone.Exhaust to Color(0xFF546E7A),
    CarZone.TyreFrontLeft to Color(0xFFC2185B),
    CarZone.TyreFrontRight to Color(0xFFC2185B),
    CarZone.TyreRearLeft to Color(0xFFC2185B),
    CarZone.TyreRearRight to Color(0xFFC2185B),
)

// -------------------------------------------------------------------------------------
// The drawing
// -------------------------------------------------------------------------------------

/**
 * The blueprint, scaled once into device pixels.
 *
 * Held as built paths rather than re-parsed every frame, and scaled by transforming the
 * geometry rather than the canvas, so the stroke widths stay the ones [CarStrokes] states.
 */
private class CarArtwork(val scale: Float) {
    val splitter: Path = path(SPLITTER)
    val body: Path = path(BODY)
    val panels: Path = path(PANELS)
    val windscreen: Path = path(WINDSCREEN)
    val roof: Path = path(ROOF)
    val sideGlass: Path = path(SIDE_GLASS)
    val mirrors: Path = path(MIRRORS)
    val lamps: Path = path(LAMPS)
    val drl: Path = path(DRL)
    val airbox: Path = path(AIRBOX)
    val gearbox: Path = path(GEARBOX)
    val oilPan: Path = path(OIL_PAN)

    /** A point of the design, in pixels. */
    fun at(x: Float, y: Float) = Offset((x - DESIGN_LEFT) * scale, (y - DESIGN_TOP) * scale)

    /** A length of the design, in pixels. */
    fun len(value: Float) = value * scale

    private fun path(data: String): Path =
        PathParser().parsePathString(data).toPath().apply {
            transform(
                Matrix().apply {
                    scale(this@CarArtwork.scale, this@CarArtwork.scale)
                    translate(-DESIGN_LEFT, -DESIGN_TOP)
                },
            )
        }
}

/** The weights the blueprint is drawn at, stated in points and kept in pixels. */
private class CarStrokes(density: Density) {
    val hair = with(density) { 0.6.dp.toPx() }
    val thin = with(density) { 0.9.dp.toPx() }
    val line = with(density) { 1.1.dp.toPx() }
    val bold = with(density) { 1.4.dp.toPx() }
    val heavy = with(density) { 2.1.dp.toPx() }
}

/**
 * The car itself, back to front: the drawing lattice, the wheels, the splitter around the
 * nose, the body over both, then the glass, the mirrors and the lamps.
 */
private fun DrawScope.drawCar(art: CarArtwork, strokes: CarStrokes, colors: CarColors) {
    drawGrid(art, strokes, colors.grid)

    TYRES.forEach { tyre ->
        drawTyre(art, strokes, tyre, colors.tyreFill, colors.panelLine)
    }

    drawPath(art.splitter, color = colors.glassFill)
    drawPath(art.splitter, color = colors.bodyStroke, style = Stroke(strokes.thin))

    drawPath(art.body, color = colors.bodyFill)
    drawPath(art.body, color = colors.bodyStroke, style = Stroke(strokes.heavy))
    drawPath(art.panels, color = colors.panelLine, style = Stroke(strokes.line))

    drawPath(art.roof, color = colors.glassFill)
    drawPath(art.roof, color = colors.glassStroke, style = Stroke(strokes.thin))
    drawPath(art.sideGlass, color = colors.glassFill)
    drawPath(art.sideGlass, color = colors.glassStroke, style = Stroke(strokes.thin))
    drawPath(art.windscreen, color = colors.glassDeep)
    drawPath(art.windscreen, color = colors.glassStroke, style = Stroke(strokes.thin))

    drawPath(art.mirrors, color = colors.bodyFill)
    drawPath(art.mirrors, color = colors.bodyStroke, style = Stroke(strokes.line))

    drawPath(art.lamps, color = colors.lampFill)
    drawPath(art.lamps, color = colors.bodyStroke, style = Stroke(strokes.line))
    drawPath(art.drl, color = colors.drl, style = Stroke(strokes.bold))
}

/**
 * The lattice the car is drawn on.
 *
 * A blueprint without its paper is a car floating in a card. It is drawn at the faintest
 * neutral the ground has, which on either ground is a shade off the card behind it.
 */
private fun DrawScope.drawGrid(art: CarArtwork, strokes: CarStrokes, color: Color) {
    var x = DESIGN_LEFT
    while (x <= DESIGN_LEFT + DESIGN_WIDTH) {
        drawLine(
            color = color,
            start = art.at(x, DESIGN_TOP),
            end = art.at(x, DESIGN_TOP + DESIGN_HEIGHT),
            strokeWidth = strokes.hair,
        )
        x += GRID_STEP
    }
    var y = DESIGN_TOP
    while (y <= DESIGN_TOP + DESIGN_HEIGHT) {
        drawLine(
            color = color,
            start = art.at(DESIGN_LEFT, y),
            end = art.at(DESIGN_LEFT + DESIGN_WIDTH, y),
            strokeWidth = strokes.hair,
        )
        y += GRID_STEP
    }
}

/** One wheel: staggered outside the silhouette, as a car seen from above shows them. */
private fun DrawScope.drawTyre(
    art: CarArtwork,
    strokes: CarStrokes,
    tyre: Wheel,
    fill: Color,
    stroke: Color,
) {
    drawRoundRect(
        color = fill,
        topLeft = art.at(tyre.x, tyre.y),
        size = Size(art.len(tyre.width), art.len(tyre.height)),
        cornerRadius = CornerRadius(art.len(TYRE_RADIUS)),
    )
    drawRoundRect(
        color = stroke,
        topLeft = art.at(tyre.x, tyre.y),
        size = Size(art.len(tyre.width), art.len(tyre.height)),
        cornerRadius = CornerRadius(art.len(TYRE_RADIUS)),
        style = Stroke(strokes.bold),
    )
}

/**
 * The part behind one zone, in that zone's own colour.
 *
 * Drawn only for the zones that are on: a bay full of components with no readings on them
 * would be a diagram of an engine rather than of this car's engine, and the parts a driver
 * has not asked about are exactly the ones they do not want in the way.
 */
private fun DrawScope.drawComponent(
    zone: CarZone,
    art: CarArtwork,
    strokes: CarStrokes,
    colors: CarColors,
    accent: Color,
) {
    val fill = colors.componentFill.copy(alpha = COMPONENT_FILL)
    when (zone) {
        // The block, with the ribs down its side.
        CarZone.Engine -> {
            box(art, strokes, 1030f, 420f, 190f, 160f, 14f, fill, accent)
            listOf(460f, 500f, 540f).forEach { y ->
                rule(art, strokes, 1058f, y, 1192f, y, accent)
            }
        }
        // The throttle body, and the butterfly plate inside it.
        CarZone.Throttle -> {
            box(art, strokes, 1230f, 425f, 80f, 150f, 10f, fill, accent)
            ring(art, strokes, 1270f, 500f, 18f, accent)
        }
        // The rail, with an injector under each dot.
        CarZone.Fuel -> {
            box(art, strokes, 1050f, 345f, 160f, 30f, 15f, fill, accent)
            listOf(1085f, 1130f, 1175f).forEach { x -> dot(art, x, 360f, 6f, accent) }
        }
        // Three coils, side by side over the plugs.
        CarZone.Ignition -> listOf(1055f, 1110f, 1165f).forEach { x ->
            box(art, strokes, x, 615f, 40f, 50f, 6f, fill, accent)
        }
        // The airbox, and the duct out of it towards the block.
        CarZone.Intake -> {
            drawPath(art.airbox, color = fill)
            drawPath(art.airbox, color = accent, style = Stroke(strokes.line))
            box(art, strokes, 1225f, 332f, 45f, 16f, 6f, fill, accent)
        }
        // The radiator across the nose, with the ticks of its core.
        CarZone.Coolant -> {
            box(art, strokes, 1395f, 360f, 30f, 280f, 6f, fill, accent)
            listOf(430f, 500f, 570f).forEach { y ->
                rule(art, strokes, 1395f, y, 1425f, y, accent)
            }
        }
        // The battery, and its two posts: the filled one is the positive, as the
        // artwork's own plus and minus would be were they not under the chip.
        CarZone.Battery -> {
            box(art, strokes, 1215f, 605f, 180f, 110f, 10f, fill, accent)
            dot(art, 1231f, 633f, 8f, accent)
            ring(art, strokes, 1231f, 681f, 8f, accent)
        }
        // The sump under the block: the level across it and the plug at its foot.
        CarZone.Oil -> {
            drawPath(art.oilPan, color = fill)
            drawPath(art.oilPan, color = accent, style = Stroke(strokes.line))
            rule(art, strokes, 928f, 630f, 1042f, 630f, accent)
            dot(art, 985f, 662f, 7f, accent)
        }
        // The gearbox behind the engine, and the shafts through it.
        CarZone.Transmission -> {
            drawPath(art.gearbox, color = fill)
            drawPath(art.gearbox, color = accent, style = Stroke(strokes.line))
            rule(art, strokes, 855f, 458f, 855f, 542f, accent)
            rule(art, strokes, 905f, 462f, 905f, 538f, accent)
        }
        // The muffler, with the probe hanging off the pipe in front of it.
        CarZone.Exhaust -> {
            box(art, strokes, 550f, 480f, 200f, 40f, 20f, fill, accent)
            rule(art, strokes, 610f, 520f, 610f, 556f, accent)
            ring(art, strokes, 610f, 564f, 8f, accent)
        }
        // A wheel is its own part: the tyre is restruck in the zone's colour.
        CarZone.TyreFrontLeft -> drawTyre(art, strokes, TYRE_FRONT_LEFT, fill, accent)
        CarZone.TyreFrontRight -> drawTyre(art, strokes, TYRE_FRONT_RIGHT, fill, accent)
        CarZone.TyreRearLeft -> drawTyre(art, strokes, TYRE_REAR_LEFT, fill, accent)
        CarZone.TyreRearRight -> drawTyre(art, strokes, TYRE_REAR_RIGHT, fill, accent)
    }
}

/** A rounded box of the design, filled and then outlined. */
private fun DrawScope.box(
    art: CarArtwork,
    strokes: CarStrokes,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    radius: Float,
    fill: Color,
    stroke: Color,
) {
    val topLeft = art.at(x, y)
    val size = Size(art.len(width), art.len(height))
    val corner = CornerRadius(art.len(radius))
    drawRoundRect(color = fill, topLeft = topLeft, size = size, cornerRadius = corner)
    drawRoundRect(
        color = stroke,
        topLeft = topLeft,
        size = size,
        cornerRadius = corner,
        style = Stroke(strokes.line),
    )
}

/** A detail line of the design: a rib, a tick, a terminal mark. */
private fun DrawScope.rule(
    art: CarArtwork,
    strokes: CarStrokes,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    color: Color,
) = drawLine(
    color = color,
    start = art.at(x1, y1),
    end = art.at(x2, y2),
    strokeWidth = strokes.thin,
)

private fun DrawScope.dot(art: CarArtwork, x: Float, y: Float, radius: Float, color: Color) =
    drawCircle(color = color, radius = art.len(radius), center = art.at(x, y))

private fun DrawScope.ring(
    art: CarArtwork,
    strokes: CarStrokes,
    x: Float,
    y: Float,
    radius: Float,
    color: Color,
) = drawCircle(
    color = color,
    radius = art.len(radius),
    center = art.at(x, y),
    style = Stroke(strokes.thin),
)

/** A rectangle of the design, in design units. */
private data class Wheel(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * The box the blueprint is drawn in, and scaled out of.
 *
 * The artwork's own frame is 500,100 1050×800; it is cropped a little top and bottom, to
 * where the mirrors and the wheels actually reach, so the card is not two bands of empty
 * paper around a car. The rear of the car runs off the left edge, which is what keeps it
 * reading as a car rather than as clip art parked in a box.
 */
private const val DESIGN_LEFT = 500f
private const val DESIGN_TOP = 140f
private const val DESIGN_WIDTH = 1050f
private const val DESIGN_HEIGHT = 720f

/** The lattice under the car, as the artwork rules it. */
private const val GRID_STEP = 40f

/* The car itself, path by path, in that frame. Front of the car is to the right. */
private const val SPLITTER =
    "M 1450 500 C 1450 350, 1400 260, 1250 260 L 1250 240 C 1420 240, 1500 340, 1500 500 " +
        "C 1500 660, 1420 760, 1250 760 L 1250 740 C 1400 740, 1450 650, 1450 500 Z"

private const val BODY =
    "M 1450 500 C 1450 350, 1400 260, 1250 260 C 1150 260, 1120 220, 1050 250 " +
        "C 850 310, 600 310, 450 260 L 450 740 C 600 690, 850 690, 1050 750 " +
        "C 1120 780, 1150 740, 1250 740 C 1400 740, 1450 650, 1450 500 Z"

/** The panel gaps of the bonnet: two shut lines and the seam across the nose. */
private const val PANELS =
    "M 1300 320 C 1150 300, 1100 320, 1050 340 " +
        "M 1300 680 C 1150 700, 1100 680, 1050 660 " +
        "M 1300 320 Q 1350 500 1300 680"

private const val WINDSCREEN = "M 1050 320 Q 1150 500 1050 680 L 950 640 Q 1000 500 950 360 Z"
private const val ROOF = "M 950 360 L 500 370 Q 450 500 500 630 L 950 640 Z"
private const val SIDE_GLASS =
    "M 1020 315 L 940 355 L 510 365 L 430 350 Q 700 280 1020 315 Z " +
        "M 1020 685 L 940 645 L 510 635 L 430 650 Q 700 720 1020 685 Z"

private const val MIRRORS =
    "M 1030 250 L 1000 180 L 980 180 L 990 250 Z " +
        "M 1030 750 L 1000 820 L 980 820 L 990 750 Z"

private const val LAMPS =
    "M 1340 285 L 1400 275 L 1420 315 L 1340 310 Z " +
        "M 1340 715 L 1400 725 L 1420 685 L 1340 690 Z"

/** The daytime strip inside each lamp — the one part of the car drawn in the app's own blue. */
private const val DRL = "M 1350 305 L 1410 305 M 1350 695 L 1410 695"

/* The three parts whose outline is not a rounded box. */
private const val AIRBOX = "M 1270 305 L 1370 315 L 1370 365 L 1270 375 Z"
private const val GEARBOX = "M 800 450 L 960 465 L 960 535 L 800 550 Z"
private const val OIL_PAN = "M 910 587 L 1060 587 L 1042 673 L 928 673 Z"

/** The four wheels, staggered outside the body as the artwork places them. */
private val TYRE_FRONT_LEFT = Wheel(1100f, 190f, 160f, 60f)
private val TYRE_FRONT_RIGHT = Wheel(1100f, 750f, 160f, 60f)
private val TYRE_REAR_LEFT = Wheel(540f, 190f, 160f, 60f)
private val TYRE_REAR_RIGHT = Wheel(540f, 750f, 160f, 60f)
private val TYRES =
    listOf(TYRE_FRONT_LEFT, TYRE_FRONT_RIGHT, TYRE_REAR_LEFT, TYRE_REAR_RIGHT)
private const val TYRE_RADIUS = 10f

/** How solid a drawn part is over the body under it. */
private const val COMPONENT_FILL = 0.85f

/**
 * A chip, in design units, and the floor under it in points.
 *
 * Small enough that the fourteen places the drawing knows do not collide when a driver
 * ticks every one of them — the anchors are set a chip's width apart — and floored at what
 * a thumb can find in a moving car, which is what decides it on the narrowest phone.
 */
private const val CHIP_SIDE = 130f
private const val CHIP_MIN_SIDE = 44
private const val ZONE_INSET = 3
private const val ZONE_GLYPH = 15
private const val ZONE_TEXT = 9.5f
private val ZoneCorner = RoundedCornerShape(percent = 24)
private val ZoneInnerCorner = RoundedCornerShape(percent = 22)

private const val EDIT_BUTTON = 32
private const val EDIT_PADDING = 8

private const val ZONE_FILL = 0.10f
private const val ZONE_FILL_ON = 0.22f
private const val ZONE_EDGE = 0.5f
private const val ZONE_EDGE_ON = 0.95f
private const val ZONE_HALO = 0.3f
private const val ZONE_HALO_STROKE = 2.5f
private const val ZONE_ANIMATION_MILLIS = 400
