package com.miskibin.obd2dashboard.obd

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * One Mode 06 record the simulation answers with, before it is put on the wire.
 *
 * The same six numbers a real record carries, kept in a table rather than as raw bytes so
 * that what the demo car is claiming about itself can be read at a glance.
 */
data class DemoMonitorTest(
    val mid: Int,
    val tid: Int,
    val uasid: Int,
    val value: Int,
    val min: Int,
    val max: Int,
) {
    fun bytes(): List<Int> = listOf(mid, tid, uasid) +
        listOf(value, min, max).flatMap { listOf(it shr Byte.SIZE_BITS, it and 0xFF) }
}

/** One instant of the simulated drive cycle, in engineering units. */
data class DemoVehicleState(
    val runTimeSeconds: Int,
    val rpm: Double,
    val speedKph: Double,
    val throttlePercent: Double,
    val engineLoadPercent: Double,
    val coolantC: Double,
    val intakeC: Double,
    val ambientC: Double,
    val oilC: Double,
    val manifoldKpa: Double,
    val barometricKpa: Double,
    val mafGramsPerSecond: Double,
    val timingAdvanceDegrees: Double,
    val shortTrimPercent: Double,
    val longTrimPercent: Double,
    val fuelLevelPercent: Double,
    val fuelRateLitersPerHour: Double,
    val batteryVolts: Double,
)

/**
 * The synthetic car behind demo mode.
 *
 * One speed curve — idle, five upshifts to ~120 km/h, cruise, coast back down, repeat —
 * drives everything else, so RPM saws down at each shift while speed stays continuous and
 * load, manifold pressure and air flow move together the way an engine's do. Coolant and
 * oil warm up once from the moment the demo starts and then stay warm, because the
 * warm-up is the only part of a drive that never repeats.
 */
class DemoVehicle(seed: Long = DEFAULT_SEED) {

    private val random = Random(seed)

    fun sampleAt(elapsedMillis: Long): DemoVehicleState {
        val seconds = elapsedMillis / MILLIS_PER_SECOND
        val cruising = speedAt(seconds)
        // Standing still is exact; only a moving wheel sensor jitters.
        val speed = if (cruising <= 0.0) 0.0 else (cruising + noise(SPEED_NOISE)).coerceAtLeast(0.0)
        val ratio = GEAR_KPH_PER_RPM.last { speed >= it.first }.second
        val idling = speed < CREEP_KPH

        val rpm = if (idling) {
            IDLE_RPM + IDLE_HUNT_RPM + wobble(seconds, IDLE_HUNT_RPM, IDLE_HUNT_SECONDS)
        } else {
            speed / ratio
        }.let { (it + noise(RPM_NOISE)).coerceIn(IDLE_RPM, MAX_RPM) }

        val acceleration = speedAt(seconds + HALF_SECOND) - speedAt(seconds - HALF_SECOND)
        val throttle = when {
            idling -> IDLE_THROTTLE
            acceleration > COASTING_KPH_PER_SECOND -> ACCEL_THROTTLE +
                acceleration * ACCEL_THROTTLE_GAIN + speed * ACCEL_THROTTLE_SPEED_GAIN

            acceleration < -COASTING_KPH_PER_SECOND -> OVERRUN_THROTTLE
            else -> CRUISE_THROTTLE + speed * CRUISE_THROTTLE_SPEED_GAIN
        }.let { (it + noise(THROTTLE_NOISE)).coerceIn(0.0, 100.0) }

        val load = (LOAD_OFFSET + throttle).coerceIn(0.0, MAX_LOAD)
        val manifold = (MANIFOLD_VACUUM_KPA + throttle * MANIFOLD_GAIN)
            .coerceIn(MIN_MANIFOLD_KPA, MAX_MANIFOLD_KPA)
        val maf = rpm / RPM_PER_THOUSAND * manifold / KPA_PER_HUNDRED * MAF_GAIN
        val warmed = min(1.0, seconds / COOLANT_WARM_SECONDS)

        return DemoVehicleState(
            runTimeSeconds = seconds.toInt(),
            rpm = rpm,
            speedKph = speed,
            throttlePercent = throttle,
            engineLoadPercent = load,
            coolantC = if (warmed < 1.0) {
                COLD_C + (HOT_COOLANT_C - COLD_C) * warmed
            } else {
                HOT_COOLANT_C + wobble(seconds, THERMOSTAT_SWING_C, THERMOSTAT_SECONDS)
            } + heatSoak(seconds),
            intakeC = INTAKE_COLD_C + INTAKE_RISE_C * warmed - INTAKE_RAM_COOLING_C * (speed / TOP_KPH),
            ambientC = AMBIENT_C + wobble(seconds, AMBIENT_SWING_C, AMBIENT_SECONDS),
            oilC = COLD_C + (HOT_OIL_C - COLD_C) * min(1.0, seconds / OIL_WARM_SECONDS),
            manifoldKpa = manifold,
            barometricKpa = BAROMETRIC_KPA,
            mafGramsPerSecond = maf,
            timingAdvanceDegrees = MIN_ADVANCE_DEG + ADVANCE_RANGE_DEG * (1.0 - load / 100.0),
            shortTrimPercent = wobble(seconds, SHORT_TRIM_SWING, SHORT_TRIM_SECONDS) +
                noise(SHORT_TRIM_NOISE),
            longTrimPercent = LONG_TRIM_BIAS + wobble(seconds, LONG_TRIM_SWING, LONG_TRIM_SECONDS),
            fuelLevelPercent = (FUEL_START_PERCENT - seconds / SECONDS_PER_MINUTE * FUEL_PER_MINUTE)
                .coerceAtLeast(FUEL_RESERVE_PERCENT),
            fuelRateLitersPerHour = maf * LITRES_PER_HOUR_PER_GRAM_PER_SECOND,
            batteryVolts = ALTERNATOR_VOLTS + wobble(seconds, VOLTAGE_SWING, VOLTAGE_SECONDS) -
                load / 100.0 * VOLTAGE_LOAD_DROP,
        )
    }

    /** The drive cycle, in km/h, as a function of seconds since the demo started. */
    private fun speedAt(seconds: Double): Double {
        val phase = seconds.mod(CYCLE_SECONDS)
        return when {
            phase < IDLE_UNTIL -> 0.0
            phase < ACCELERATE_UNTIL ->
                TOP_KPH * (phase - IDLE_UNTIL) / (ACCELERATE_UNTIL - IDLE_UNTIL)

            phase < CRUISE_UNTIL -> TOP_KPH
            phase < COAST_UNTIL -> TOP_KPH * (COAST_UNTIL - phase) / (COAST_UNTIL - CRUISE_UNTIL)
            else -> 0.0
        }
    }

    /**
     * The long motorway pull at the top of each cycle, as degrees above the thermostat.
     *
     * It is what makes the coolant threshold alert demonstrable without a real car: the
     * temperature climbs past 105 °C towards the end of the cruise and drops back on the
     * coast, so an alert fires, re-arms and can fire again on the next lap.
     */
    private fun heatSoak(seconds: Double): Double {
        val phase = seconds.mod(CYCLE_SECONDS)
        return when {
            phase < HEAT_SOAK_FROM -> 0.0
            phase < CRUISE_UNTIL ->
                HEAT_SOAK_PEAK_C * (phase - HEAT_SOAK_FROM) / (CRUISE_UNTIL - HEAT_SOAK_FROM)

            phase < COAST_UNTIL -> HEAT_SOAK_PEAK_C * (COAST_UNTIL - phase) / (COAST_UNTIL - CRUISE_UNTIL)
            else -> 0.0
        }
    }

    private fun wobble(seconds: Double, amplitude: Double, periodSeconds: Double): Double =
        amplitude * sin(TAU * seconds / periodSeconds)

    private fun noise(amplitude: Double): Double = random.nextDouble(-amplitude, amplitude)

    private companion object {
        const val DEFAULT_SEED = 0x0BD2L
        const val TAU = 2.0 * PI
        const val MILLIS_PER_SECOND = 1_000.0
        const val SECONDS_PER_MINUTE = 60.0
        const val HALF_SECOND = 0.5

        // Drive cycle
        const val CYCLE_SECONDS = 180.0
        const val IDLE_UNTIL = 20.0
        const val ACCELERATE_UNTIL = 120.0
        const val CRUISE_UNTIL = 150.0
        const val COAST_UNTIL = 175.0
        const val TOP_KPH = 120.0
        const val CREEP_KPH = 1.5
        const val SPEED_NOISE = 0.4
        const val COASTING_KPH_PER_SECOND = 0.2

        /** Which gear the box would be in at a given speed, as km/h per rpm. */
        val GEAR_KPH_PER_RPM = listOf(
            0.0 to 0.0075,
            25.0 to 0.0135,
            45.0 to 0.0205,
            70.0 to 0.0280,
            95.0 to 0.0360,
        )

        // Engine
        const val IDLE_RPM = 800.0
        const val MAX_RPM = 3_500.0
        const val IDLE_HUNT_RPM = 25.0
        const val IDLE_HUNT_SECONDS = 11.0
        const val RPM_NOISE = 20.0
        const val RPM_PER_THOUSAND = 1_000.0

        const val IDLE_THROTTLE = 7.0
        const val OVERRUN_THROTTLE = 2.0
        const val CRUISE_THROTTLE = 14.0
        const val CRUISE_THROTTLE_SPEED_GAIN = 0.08
        const val ACCEL_THROTTLE = 24.0
        const val ACCEL_THROTTLE_GAIN = 12.0
        const val ACCEL_THROTTLE_SPEED_GAIN = 0.12
        const val THROTTLE_NOISE = 0.8

        const val LOAD_OFFSET = 12.0
        const val MAX_LOAD = 96.0

        const val MANIFOLD_VACUUM_KPA = 26.0
        const val MANIFOLD_GAIN = 1.85
        const val MIN_MANIFOLD_KPA = 25.0
        const val MAX_MANIFOLD_KPA = 140.0
        const val BAROMETRIC_KPA = 101.0
        const val KPA_PER_HUNDRED = 100.0
        const val MAF_GAIN = 6.5

        /** Stoichiometric petrol: 3600 s ÷ (14.7 × 820 g/l). */
        const val LITRES_PER_HOUR_PER_GRAM_PER_SECOND = 0.2987

        const val MIN_ADVANCE_DEG = 6.0
        const val ADVANCE_RANGE_DEG = 24.0

        // Temperatures
        const val COLD_C = 20.0
        const val HOT_COOLANT_C = 90.0
        const val HOT_OIL_C = 95.0
        const val COOLANT_WARM_SECONDS = 100.0

        /** Starts where the warm-up ends, so the two never overlap. */
        const val HEAT_SOAK_FROM = 100.0
        const val HEAT_SOAK_PEAK_C = 18.0
        const val OIL_WARM_SECONDS = 300.0
        const val THERMOSTAT_SWING_C = 1.5
        const val THERMOSTAT_SECONDS = 30.0
        const val INTAKE_COLD_C = 25.0
        const val INTAKE_RISE_C = 15.0
        const val INTAKE_RAM_COOLING_C = 6.0
        const val AMBIENT_C = 21.0
        const val AMBIENT_SWING_C = 0.4
        const val AMBIENT_SECONDS = 90.0

        // Fuel and electrics
        const val FUEL_START_PERCENT = 68.0
        const val FUEL_PER_MINUTE = 0.4
        const val FUEL_RESERVE_PERCENT = 4.0
        const val SHORT_TRIM_SWING = 2.6
        const val SHORT_TRIM_SECONDS = 23.0
        const val SHORT_TRIM_NOISE = 1.4
        const val LONG_TRIM_BIAS = -2.4
        const val LONG_TRIM_SWING = 1.2
        const val LONG_TRIM_SECONDS = 140.0
        const val ALTERNATOR_VOLTS = 14.2
        const val VOLTAGE_SWING = 0.12
        const val VOLTAGE_SECONDS = 17.0
        const val VOLTAGE_LOAD_DROP = 0.25
    }
}

/**
 * An ELM327 v2.1 sitting on a CAN 11-bit / 500 kbaud petrol car that only exists in
 * software.
 *
 * It is a transport rather than a stub higher up on purpose: demo mode then goes through
 * the same `>`-framing, echo stripping, init sequence, ISO-TP reassembly, response parser
 * and scheduler as a real dongle, so the whole pipeline is what gets demonstrated — and
 * what gets exercised by the tests. The AT settings that matter to the framing (`ATE0`,
 * `ATH1`, `ATS0`) are tracked and honoured, and everything else answers `OK` or `?` the
 * way the chip does.
 */
class DemoElmTransport(
    private val vehicle: DemoVehicle = DemoVehicle(),
    private val latencyMillis: IntRange = DEFAULT_LATENCY_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : ElmTransport {

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    private val random = Random(LATENCY_SEED)

    private var startedAtMillis = 0L
    private var closed = false

    private var echo = true
    private var headers = false
    private var spaces = true

    /**
     * The address requests are being sent to, and the one answers are filtered to.
     *
     * Tracked because the extended parameters are the whole reason they exist: a request
     * for a tyre pressure only reaches the body module if the app set `ATSH 726` first, and
     * its answer only gets past the adapter if it set `ATCRA 72E` too. A simulation that
     * ignored both would let a bug in either sail through.
     */
    private var txHeader = Obd2Client.FUNCTIONAL_HEADER
    private var rxFilter: String? = null

    /** The `SEARCHING...` banner only ever precedes the first request that hits the bus. */
    private var searched = false

    private var stored: List<String> = STORED_CODES
    private var pending: List<String> = PENDING_CODES

    override suspend fun open() {
        startedAtMillis = clock()
        resetSettings()
    }

    override suspend fun write(command: String) {
        if (closed) return
        val normalized = command.filterNot(Char::isWhitespace).uppercase()
        val reply = render(command.trim(), respond(normalized))
        latency()
        reply.toByteArray(Charsets.ISO_8859_1).toList().chunked(NOTIFICATION_CHUNK).forEach {
            if (!closed) channel.send(it.toByteArray())
        }
    }

    override fun incoming(): Flow<ByteArray> = channel.receiveAsFlow()

    override suspend fun close() {
        closed = true
        channel.close()
    }

    // ---- ELM327 ---------------------------------------------------------------------

    private fun respond(command: String): List<String> =
        if (command.startsWith(AT_PREFIX)) {
            atCommand(command.removePrefix(AT_PREFIX))
        } else {
            obdCommand(command)
        }

    private fun atCommand(argument: String): List<String> = when {
        argument == "Z" || argument == "WS" || argument == "D" -> {
            resetSettings()
            listOf(IDENTIFIER)
        }

        argument == "I" -> listOf(IDENTIFIER)
        argument == "RV" -> listOf(VOLTAGE_FORMAT.format(Locale.ROOT, state().batteryVolts))
        argument == "DPN" -> listOf(PROTOCOL_NUMBER)
        argument == "DP" -> listOf(PROTOCOL_NAME)
        argument == "E0" -> ok { echo = false }
        argument == "E1" -> ok { echo = true }
        argument == "H0" -> ok { headers = false }
        argument == "H1" -> ok { headers = true }
        argument == "S0" -> ok { spaces = false }
        argument == "S1" -> ok { spaces = true }
        argument == "AL" || argument == "NL" -> ok {}
        argument.startsWith("SH") -> ok { txHeader = argument.removePrefix("SH") }
        // `ATCRA` with an address narrows the receive filter; bare `ATCRA` clears it.
        argument == "CRA" -> ok { rxFilter = null }
        argument.startsWith("CRA") -> ok { rxFilter = argument.removePrefix("CRA") }
        argument.startsWith("AT") || argument.startsWith("ST") -> ok {}
        argument.startsWith("SP") || argument.startsWith("L") -> ok {}
        argument.startsWith("CAF") || argument.startsWith("CFC") -> ok {}
        else -> listOf(UNKNOWN_COMMAND)
    }

    private fun obdCommand(command: String): List<String> {
        // Obd2Client appends the expected response-line count (`010C1`); it is not a byte.
        val request = if (command.length % 2 == 1) command.dropLast(1) else command
        val bytes = hexBytes(request) ?: return listOf(UNKNOWN_COMMAND)
        val banner = if (searched) emptyList() else listOf(SEARCHING).also { searched = true }
        val engine = txHeader == Obd2Client.FUNCTIONAL_HEADER || txHeader == ENGINE_HEADER
        val payload = when (bytes.firstOrNull()) {
            // The legislated services live on the engine ECU. Asking a body module for
            // `010C` gets silence on a real car, and gets it here too — which is what makes
            // a forgotten `ATSH` restore visible in demo mode instead of only on the road.
            MODE_CURRENT_DATA -> if (engine) currentData(bytes.drop(1)) else null
            MODE_FREEZE_FRAME -> if (engine) freezeFrame(bytes.drop(1)) else null
            MODE_STORED_DTC -> if (engine) troubleCodes(MODE_STORED_DTC, stored) else null
            MODE_PENDING_DTC -> if (engine) troubleCodes(MODE_PENDING_DTC, pending) else null
            MODE_PERMANENT_DTC -> null
            MODE_CLEAR_DTC -> if (engine) clearCodes() else null
            Mode06.MODE -> if (engine) monitorTests(bytes.drop(1)) else null
            MODE_VEHICLE_INFO -> if (engine) vehicleInfo(bytes.drop(1)) else null
            MODE_READ_DATA_BY_ID -> extendedData(bytes.drop(1))
            else -> null
        }
        if (payload == null) return banner + listOf(NO_DATA)
        // An answer from an address the adapter was told to filter out never reaches it.
        val filter = rxFilter
        if (filter != null && !filter.equals(responseHeader(), ignoreCase = true)) {
            return banner + listOf(NO_DATA)
        }
        return banner + canFrames(payload)
    }

    private fun currentData(requested: List<Int>): List<Int>? {
        val snapshot = state()
        val payload = mutableListOf(MODE_CURRENT_DATA + ObdResponseParser.RESPONSE_OFFSET)
        for (pid in requested) {
            val data = dataFor(pid, snapshot) ?: continue
            payload += pid
            payload += data
        }
        return payload.takeIf { it.size > 1 }
    }

    /**
     * The frame the ECU kept from the moment P0420 was set: a steady motorway cruise with
     * the mixture already leaning on a long-term correction. Clearing the codes takes the
     * frame with it, exactly as Mode 04 does on a real car.
     */
    private fun freezeFrame(request: List<Int>): List<Int>? {
        val pid = request.getOrNull(0) ?: return null
        val frame = request.getOrNull(1) ?: FreezeFrames.FIRST_FRAME
        if (stored.isEmpty() || frame != FreezeFrames.FIRST_FRAME) return null
        val data = frozenData(pid) ?: return null
        return listOf(MODE_FREEZE_FRAME + ObdResponseParser.RESPONSE_OFFSET, pid, frame) + data
    }

    private fun frozenData(pid: Int): List<Int>? = when (pid) {
        FreezeFrames.DTC_PID -> DtcDecoder.encode(FREEZE_FRAME_CODE)
        Pids.ENGINE_LOAD -> listOf(ratio(FROZEN_LOAD_PERCENT))
        Pids.COOLANT_TEMP -> listOf(temperature(FROZEN_COOLANT_C))
        Pids.SHORT_FUEL_TRIM_1 -> listOf(fuelTrim(FROZEN_SHORT_TRIM))
        Pids.LONG_FUEL_TRIM_1 -> listOf(fuelTrim(FROZEN_LONG_TRIM))
        Pids.INTAKE_MAP -> listOf(byte(FROZEN_MANIFOLD_KPA))
        Pids.ENGINE_RPM -> word(FROZEN_RPM * RPM_QUARTERS)
        Pids.VEHICLE_SPEED -> listOf(byte(FROZEN_SPEED_KPH))
        Pids.INTAKE_AIR_TEMP -> listOf(temperature(FROZEN_INTAKE_C))
        else -> null
    }

    private fun troubleCodes(mode: Int, codes: List<String>): List<Int> =
        listOf(mode + ObdResponseParser.RESPONSE_OFFSET, codes.size) +
            codes.flatMap { DtcDecoder.encode(it).orEmpty() }

    private fun clearCodes(): List<Int> {
        stored = emptyList()
        pending = emptyList()
        return listOf(MODE_CLEAR_DTC + ObdResponseParser.RESPONSE_OFFSET)
    }

    private fun vehicleInfo(requested: List<Int>): List<Int>? = when (requested.firstOrNull()) {
        VIN_PID -> listOf(
            MODE_VEHICLE_INFO + ObdResponseParser.RESPONSE_OFFSET,
            VIN_PID,
            VIN_MESSAGES,
        ) + VIN.map(Char::code)

        PerformanceTrackingDecoder.PID -> listOf(
            MODE_VEHICLE_INFO + ObdResponseParser.RESPONSE_OFFSET,
            PerformanceTrackingDecoder.PID,
            IPT_COUNTERS.size,
        ) + IPT_COUNTERS.flatMap { listOf(it shr Byte.SIZE_BITS, it and 0xFF) }

        else -> null
    }

    // ---- mode 06 --------------------------------------------------------------------

    /**
     * `0600` and `06<MID>`: the monitor bitmask chain, then the test records themselves.
     *
     * Two of the four cylinders' worth of misfire records carry counts and one of them is
     * over its limit, so the screen has something to say; the catalyst is healthy and its
     * limits are the real shape of the thing — a value well above a minimum that P0420
     * would be set below.
     */
    private fun monitorTests(requested: List<Int>): List<Int>? {
        val mid = requested.firstOrNull() ?: return null
        val marker = Mode06.MODE + ObdResponseParser.RESPONSE_OFFSET
        if (mid % ObdResponseParser.SUPPORT_BLOCK_SIZE == 0) {
            val mask = monitorMask(mid) ?: return null
            return listOf(marker, mid) + mask
        }
        val records = MONITOR_TESTS[mid] ?: return null
        return listOf(marker) + records.flatMap { it.bytes() }
    }

    /** The `0600`/`0620`/… masks, in the same bit order the PID support blocks use. */
    private fun monitorMask(base: Int): List<Int>? {
        var bits = 0L
        for (mid in SUPPORTED_MONITORS) {
            val offset = mid - base
            if (offset in 1..Int.SIZE_BITS) bits = bits or (1L shl (Int.SIZE_BITS - offset))
        }
        if (bits == 0L) return null
        return (3 downTo 0).map { ((bits shr (it * Byte.SIZE_BITS)) and 0xFF).toInt() }
    }

    // ---- manufacturer-specific reads ------------------------------------------------

    /**
     * `22 xxxx` on whichever module `ATSH` currently points at.
     *
     * The tyre *temperature* identifiers are refused with `requestOutOfRange` rather than
     * answered. Plenty of cars are like that — the pressures are published and the
     * temperatures are not — and it is the only way the probe's give-up path gets exercised
     * without a car in the driveway.
     */
    private fun extendedData(requested: List<Int>): List<Int>? {
        if (requested.size < DID_BYTES) return null
        val did = requested[0] * 256 + requested[1]
        if (did in REFUSED_DIDS) {
            return listOf(
                NegativeResponse.MARKER,
                MODE_READ_DATA_BY_ID,
                NegativeResponse.REQUEST_OUT_OF_RANGE,
            )
        }
        val data = extendedValue(txHeader.uppercase(), did) ?: return null
        return listOf(MODE_READ_DATA_BY_ID + ObdResponseParser.RESPONSE_OFFSET) + requested.take(DID_BYTES) + data
    }

    private fun extendedValue(header: String, did: Int): List<Int>? {
        val state = state()
        return when {
            header == ENGINE_HEADER && did == DID_OIL_PRESSURE ->
                word(OIL_PRESSURE_IDLE_KPA + state.rpm * OIL_PRESSURE_PER_RPM)

            header == ENGINE_HEADER && did == DID_OIL_TEMPERATURE ->
                word((state.oilC + TEMPERATURE_OFFSET) * OIL_TEMPERATURE_HUNDREDTHS)

            // The gearbox runs a little behind the engine oil and settles a little cooler.
            header == TRANSMISSION_HEADER && did == DID_FLUID_TEMPERATURE ->
                word((state.oilC - FLUID_TEMPERATURE_LAG_C) * FLUID_TEMPERATURE_COUNTS)

            // The gear the box is actually in, on the ladder of sixteens Mazda reports it
            // on. Answered so the measured-gear path is exercised by something other than
            // a real car: the estimator it replaces is the one thing on the dashboard that
            // cannot be checked against a simulation of itself.
            header == TRANSMISSION_HEADER && did == DID_GEAR ->
                listOf(recommendedGear(state) * GEAR_STEP)

            header == BODY_HEADER && did in TYRE_PRESSURE_DIDS ->
                listOf(TYRE_PRESSURE_COUNTS[did - TYRE_PRESSURE_DIDS.first])

            else -> null
        }
    }

    /** `7E8` for the engine, `7E9` for the gearbox, `72E` for the body module. */
    private fun responseHeader(): String = when (txHeader.uppercase()) {
        TRANSMISSION_HEADER -> TRANSMISSION_RESPONSE
        BODY_HEADER -> BODY_RESPONSE
        else -> ECU_HEADER
    }

    /** Live data, support bitmasks and the lamp status, as raw Mode 01 data bytes. */
    private fun dataFor(pid: Int, state: DemoVehicleState): List<Int>? = when (pid) {
        in SUPPORT_BLOCK_PIDS -> supportMask(pid)
        0x01 -> listOf(lampByte()) + READINESS_BYTES
        0x03 -> listOf(0x02, 0x00)
        Pids.ENGINE_LOAD -> listOf(ratio(state.engineLoadPercent))
        Pids.COOLANT_TEMP -> listOf(temperature(state.coolantC))
        Pids.SHORT_FUEL_TRIM_1 -> listOf(fuelTrim(state.shortTrimPercent))
        Pids.LONG_FUEL_TRIM_1 -> listOf(fuelTrim(state.longTrimPercent))
        Pids.INTAKE_MAP -> listOf(byte(state.manifoldKpa))
        Pids.ENGINE_RPM -> word(state.rpm * RPM_QUARTERS)
        Pids.VEHICLE_SPEED -> listOf(byte(state.speedKph))
        Pids.TIMING_ADVANCE -> listOf(byte((state.timingAdvanceDegrees + ADVANCE_OFFSET) * 2.0))
        Pids.INTAKE_AIR_TEMP -> listOf(temperature(state.intakeC))
        Pids.MAF_RATE -> word(state.mafGramsPerSecond * MAF_HUNDREDTHS)
        Pids.THROTTLE_POSITION -> listOf(ratio(state.throttlePercent))
        0x13 -> listOf(0x03)
        // Sensor 1 tracks the short term trim across the switching band, sensor 2 sits
        // where a healthy catalyst holds it; both report the trim byte alongside.
        0x14 -> listOf(o2Volts(O2_SWITCH_CENTRE + state.shortTrimPercent / O2_SWING_DIVISOR),
            fuelTrim(state.shortTrimPercent))
        0x15 -> listOf(o2Volts(O2_SENSOR_2_VOLTS), NOT_USED_TRIM_BYTE)
        0x1C -> listOf(OBD_STANDARD_EOBD)
        Pids.RUN_TIME -> word(state.runTimeSeconds.toDouble())
        Pids.DISTANCE_WITH_MIL -> word(DISTANCE_WITH_MIL_KM)
        Pids.FUEL_LEVEL -> listOf(ratio(state.fuelLevelPercent))
        0x30 -> listOf(WARM_UPS_SINCE_CLEARED)
        Pids.DISTANCE_SINCE_CLEARED -> word(DISTANCE_SINCE_CLEARED_KM)
        Pids.BAROMETRIC_PRESSURE -> listOf(byte(state.barometricKpa))
        Pids.CONTROL_MODULE_VOLTAGE -> word(state.batteryVolts * MILLIVOLTS_PER_VOLT)
        0x43 -> word(state.engineLoadPercent * FULL_SCALE / 100.0)
        0x45 -> listOf(ratio(state.throttlePercent - CLOSED_THROTTLE_OFFSET))
        Pids.AMBIENT_AIR_TEMP -> listOf(temperature(state.ambientC))
        0x49 -> listOf(ratio(state.throttlePercent + PEDAL_OFFSET))
        0x4A -> listOf(ratio(state.throttlePercent))
        0x4C -> listOf(ratio(state.throttlePercent))
        Pids.FUEL_TYPE -> listOf(FUEL_TYPE_PETROL)
        Pids.OIL_TEMP -> listOf(temperature(state.oilC))
        Pids.FUEL_RATE -> word(state.fuelRateLitersPerHour * FUEL_RATE_TWENTIETHS)
        // Byte A says only the recommended gear is implemented; byte B carries it in its
        // top nibble, which is where a shift indicator lives.
        Pids.AUXILIARY_IO -> listOf(AUX_GEAR_SUPPORTED, recommendedGear(state) shl NIBBLE)
        // Friction falls as the oil thins: highest on a cold engine, least once warm.
        Pids.FRICTION_TORQUE -> listOf(
            byte(
                TORQUE_ZERO + FRICTION_COLD_PERCENT -
                    (state.oilC - COLD_OIL_C).coerceAtLeast(0.0) * FRICTION_PER_DEGREE,
            ),
        )
        // Stoichiometric petrol: the fuel mass is the air mass over 14.7. The vehicle
        // figure matches, because nothing else on this car burns anything.
        Pids.FUEL_RATE_MASS -> (mass(state) + mass(state))
        else -> null
    }

    /** Grams of fuel a second, as the two identical halves of `019D`. */
    private fun mass(state: DemoVehicleState): List<Int> =
        word(state.mafGramsPerSecond / STOICH_AIR_FUEL * FUEL_MASS_FIFTIETHS)

    /** The gear the shift indicator would be asking for, from road speed. */
    private fun recommendedGear(state: DemoVehicleState): Int =
        RECOMMENDED_GEAR_FROM_KPH.count { state.speedKph >= it }.coerceAtLeast(1)

    private fun lampByte(): Int =
        if (stored.isEmpty()) 0 else MIL_BIT or stored.size

    /** `0100`/`0120`/`0140`: bit A7 is `base + 1`, down to bit D0 = `base + 0x20`. */
    private fun supportMask(base: Int): List<Int> {
        var bits = 0L
        for (pid in SUPPORTED_PIDS) {
            val offset = pid - base
            if (offset in 1..Int.SIZE_BITS) bits = bits or (1L shl (Int.SIZE_BITS - offset))
        }
        return (3 downTo 0).map { ((bits shr (it * Byte.SIZE_BITS)) and 0xFF).toInt() }
    }

    // ---- framing --------------------------------------------------------------------

    /** Wraps [payload] in single or ISO-TP multi-frame CAN messages, as text lines. */
    private fun canFrames(payload: List<Int>): List<String> {
        if (payload.size <= SINGLE_FRAME_BYTES) {
            return listOf(line(listOf(payload.size) + payload))
        }
        val lines = mutableListOf(
            line(
                listOf(FIRST_FRAME_PCI or (payload.size shr Byte.SIZE_BITS), payload.size and 0xFF) +
                    payload.take(FIRST_FRAME_BYTES),
            ),
        )
        var index = FIRST_FRAME_BYTES
        var sequence = 1
        while (index < payload.size) {
            val end = min(index + CONSECUTIVE_FRAME_BYTES, payload.size)
            lines += line(
                listOf(CONSECUTIVE_FRAME_PCI or (sequence and 0x0F)) + payload.subList(index, end),
            )
            index = end
            sequence++
        }
        return lines
    }

    private fun line(bytes: List<Int>): String {
        val separator = if (spaces) " " else ""
        val body = bytes.joinToString(separator) { "%02X".format(it) }
        return if (headers) responseHeader() + separator + body else body
    }

    private fun render(command: String, lines: List<String>): String = buildString {
        if (echo) append(command).append(LINE_END)
        lines.forEach { append(it).append(LINE_END) }
        append(PROMPT)
    }

    private suspend fun latency() {
        if (latencyMillis.isEmpty()) return
        delay(random.nextInt(latencyMillis.first, latencyMillis.last + 1).toLong())
    }

    private fun resetSettings() {
        echo = true
        headers = false
        spaces = true
        searched = false
        txHeader = Obd2Client.FUNCTIONAL_HEADER
        rxFilter = null
    }

    private fun state(): DemoVehicleState = vehicle.sampleAt(clock() - startedAtMillis)

    private fun ok(apply: () -> Unit): List<String> {
        apply()
        return listOf(OK)
    }

    private fun byte(value: Double): Int = value.roundToInt().coerceIn(0, 0xFF)

    private fun ratio(percent: Double): Int = byte(percent * FULL_SCALE / 100.0)

    private fun temperature(celsius: Double): Int = byte(celsius + TEMPERATURE_OFFSET)

    private fun fuelTrim(percent: Double): Int = byte((percent + 100.0) * TRIM_SCALE / 100.0)

    /** `0114`-style voltage: 0-1.275 V in 5 mV steps. */
    private fun o2Volts(volts: Double): Int = byte(volts * O2_VOLTS_PER_STEP)

    private fun word(value: Double): List<Int> {
        val raw = value.roundToInt().coerceIn(0, 0xFFFF)
        return listOf(raw shr Byte.SIZE_BITS, raw and 0xFF)
    }

    private fun hexBytes(text: String): List<Int>? {
        if (text.isEmpty() || text.length % 2 != 0) return null
        return (text.indices step 2).map {
            text.substring(it, it + 2).toIntOrNull(16) ?: return null
        }
    }

    companion object {
        /** A real dongle answers in tens of milliseconds; so does this one. */
        val DEFAULT_LATENCY_MILLIS = 20..50

        const val IDENTIFIER = "ELM327 v2.1"

        /**
         * A 2019 Mazda 3, because the extended parameters are gated on the VIN.
         *
         * `JM1` is Mazda's world manufacturer identifier and `K` in position ten is the
         * 2019 model year, which puts the car in the BP generation and therefore on the
         * body module that carries tyre pressures at `726`. A demo car that failed that
         * gate would exercise nothing: the app would decide it had nothing to ask for and
         * the whole extended path would go untested.
         */
        const val VIN = "JM1BPBLM7K1234567"

        /**
         * Realistic petrol-car support: the ~35 PIDs a modern CAN car answers for.
         *
         * The block markers (`20`, `40`, `60`, `80`, `A0`) are what chain one support
         * query into the next, so a PID in the `80` block is unreachable without every
         * marker below it.
         */
        val SUPPORTED_PIDS = sortedSetOf(
            0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11,
            0x13, 0x1C, 0x1F, 0x20,
            // The pre- and post-catalyst probes of a two-sensor petrol car, which is what
            // makes the oxygen-sensor rows demoable without a car in the driveway.
            0x14, 0x15,
            0x21, 0x2F, 0x30, 0x31, 0x33, 0x40,
            0x42, 0x43, 0x45, 0x46, 0x49, 0x4A, 0x4C, 0x51, 0x5C, 0x5E, 0x60,
            0x65, 0x80, 0x8E, 0x9D,
        )

        /** The `01<base>` queries that answer with a bitmask rather than with a reading. */
        val SUPPORT_BLOCK_PIDS = setOf(0x00, 0x20, 0x40, 0x60, 0x80)

        /** Byte A of `0165`: only the recommended gear is implemented on this car. */
        const val AUX_GEAR_SUPPORTED = 0x10

        /** The speeds at which the shift indicator would ask for the next gear up. */
        val RECOMMENDED_GEAR_FROM_KPH = listOf(0.0, 25.0, 45.0, 70.0, 95.0)

        const val STOICH_AIR_FUEL = 14.7
        const val FUEL_MASS_FIFTIETHS = 50.0
        const val TORQUE_ZERO = 125.0
        const val FRICTION_COLD_PERCENT = 12.0
        const val FRICTION_PER_DEGREE = 0.09
        const val COLD_OIL_C = 20.0
        const val NIBBLE = 4

        /**
         * The monitors this ECU implements, plus the block markers that chain `0600` on.
         *
         * Two oxygen sensors, one catalyst bank, two heaters, the general misfire counter
         * and four cylinders — which is what a four-cylinder petrol car with one bank
         * actually reports.
         */
        val SUPPORTED_MONITORS = sortedSetOf(
            0x01, 0x02, 0x20, 0x21, 0x40, 0x41, 0x42, 0x60, 0x80, 0xA0,
            0xA1, 0xA2, 0xA3, 0xA4, 0xA5,
        )

        /**
         * The test records each monitor answers with.
         *
         * Cylinder 2 — monitor `A3`, since `A2` is cylinder 1 — is over its misfire limit
         * and everything else is inside its own, so demo mode has one honest failure to
         * show and a healthy catalyst to compare it against. The catalyst's oxygen storage
         * sits well clear of the minimum below which the ECU would set P0420.
         */
        val MONITOR_TESTS: Map<Int, List<DemoMonitorTest>> = mapOf(
            0x01 to listOf(
                DemoMonitorTest(0x01, 0x05, UASID_SECONDS, value = 48, min = 0, max = 100),
                DemoMonitorTest(0x01, 0x06, UASID_SECONDS, value = 55, min = 0, max = 120),
            ),
            0x02 to listOf(
                DemoMonitorTest(0x02, 0x05, UASID_SECONDS, value = 71, min = 0, max = 150),
                DemoMonitorTest(0x02, 0x06, UASID_SECONDS, value = 88, min = 0, max = 180),
            ),
            0x21 to listOf(
                DemoMonitorTest(0x21, 0x82, UASID_GRAMS, value = 85, min = 30, max = 250),
            ),
            0x41 to listOf(
                DemoMonitorTest(0x41, 0x80, UASID_RAW, value = 62, min = 20, max = 120),
            ),
            0x42 to listOf(
                DemoMonitorTest(0x42, 0x80, UASID_RAW, value = 58, min = 20, max = 120),
            ),
            0xA1 to listOf(
                DemoMonitorTest(0xA1, 0x0B, Mode06.UASID_COUNTS, value = 12, min = 0, max = 40),
            ),
            0xA2 to misfire(0xA2, average = 0, current = 0),
            0xA3 to misfire(0xA3, average = 12, current = 3),
            0xA4 to misfire(0xA4, average = 1, current = 0),
            0xA5 to misfire(0xA5, average = 0, current = 0),
        )

        private fun misfire(mid: Int, average: Int, current: Int) = listOf(
            DemoMonitorTest(mid, Mode06.TID_MISFIRE_AVERAGE, Mode06.UASID_COUNTS, average, 0, MISFIRE_LIMIT),
            DemoMonitorTest(mid, Mode06.TID_MISFIRE_CURRENT, Mode06.UASID_COUNTS, current, 0, MISFIRE_LIMIT),
        )

        /** How many misfires per cylinder the ECU tolerates before the test fails. */
        const val MISFIRE_LIMIT = 10

        private const val UASID_SECONDS = 0x04
        private const val UASID_GRAMS = 0x1E
        private const val UASID_RAW = Mode06.UASID_RAW

        /**
         * `0908`, in the standard's fixed order: two engine-wide counters and then a
         * completion and a conditions count per monitor.
         *
         * The evaporative monitor has run six times in thirty-eight opportunities and the
         * second bank's counters are all zero, which is what a single-bank car reports —
         * both are worth being able to see on the screen.
         */
        val IPT_COUNTERS = listOf(
            210, 250,
            30, 45,
            0, 0,
            40, 44,
            0, 0,
            25, 41,
            0, 0,
            6, 38,
            12, 44,
            0, 0,
        )

        const val ENGINE_HEADER = "7E0"
        const val TRANSMISSION_HEADER = "7E1"
        const val TRANSMISSION_RESPONSE = "7E9"
        const val BODY_HEADER = "726"
        const val BODY_RESPONSE = "72E"

        const val DID_OIL_PRESSURE = 0x0415
        const val DID_OIL_TEMPERATURE = 0x1310
        const val DID_FLUID_TEMPERATURE = 0x1E1C
        const val DID_GEAR = 0x1E12

        /** Mazda numbers the forward gears in steps of sixteen: `0x10` is first. */
        const val GEAR_STEP = 0x10

        /** `D922`-`D925`: the four tyre pressures on the BP-generation body module. */
        val TYRE_PRESSURE_DIDS = 0xD922..0xD925

        /** Front left to rear right, in fifths of a psi: 2.30 to 2.42 bar. */
        val TYRE_PRESSURE_COUNTS = listOf(167, 170, 173, 176)

        /** `D926`-`D929`: the tyre temperatures, which this car declines to report. */
        val REFUSED_DIDS = 0xD926..0xD929

        const val OIL_PRESSURE_IDLE_KPA = 120.0
        const val OIL_PRESSURE_PER_RPM = 0.105
        const val OIL_TEMPERATURE_HUNDREDTHS = 100.0
        const val FLUID_TEMPERATURE_LAG_C = 8.0
        const val FLUID_TEMPERATURE_COUNTS = 80.0
        const val DID_BYTES = 2

        /** Sensor 1 swings with the closed-loop correction; sensor 2 sits flat behind the cat. */
        const val O2_SENSOR_2_VOLTS = 0.72

        /** The voltage a switching probe oscillates about, and how far the trim moves it. */
        const val O2_SWITCH_CENTRE = 0.45
        const val O2_SWING_DIVISOR = 20.0

        /** `0114` reports volts in 5 mV steps, so a volt is 200 counts. */
        const val O2_VOLTS_PER_STEP = 200.0

        /** `0xFF` in the trim byte: this sensor is not used in the trim calculation. */
        const val NOT_USED_TRIM_BYTE = 0xFF

        /** `0151` code 1: petrol, which is what the simulated car burns. */
        const val FUEL_TYPE_PETROL = 1

        val STORED_CODES = listOf("P0420", "P0301")
        val PENDING_CODES = listOf("P0171")

        /**
         * Bytes B, C and D of `0101` for a petrol car that was driven since the last
         * clear but has not finished every self-test: B `07` supports all three
         * continuous monitors and marks them complete on a spark-ignition engine, C `65`
         * supports catalyst, evap and both oxygen-sensor monitors, and D `05` says the
         * catalyst and evap tests are still pending.
         */
        val READINESS_BYTES = listOf(0x07, 0x65, 0x05)

        /** The code the stored freeze frame belongs to; the first of [STORED_CODES]. */
        const val FREEZE_FRAME_CODE = "P0420"

        private const val LATENCY_SEED = 0x1701L
        private const val NOTIFICATION_CHUNK = 20
        private const val AT_PREFIX = "AT"
        private const val OK = "OK"
        private const val UNKNOWN_COMMAND = "?"
        private const val NO_DATA = "NO DATA"
        private const val SEARCHING = "SEARCHING..."
        private const val LINE_END = "\r"
        private const val PROMPT = "\r>"
        private const val ECU_HEADER = "7E8"
        private const val PROTOCOL_NUMBER = "A6"
        private const val PROTOCOL_NAME = "AUTO, ISO 15765-4 (CAN 11/500)"
        private const val VOLTAGE_FORMAT = "%.1fV"

        private const val SINGLE_FRAME_BYTES = 7
        private const val FIRST_FRAME_BYTES = 6
        private const val CONSECUTIVE_FRAME_BYTES = 7
        private const val FIRST_FRAME_PCI = 0x10
        private const val CONSECUTIVE_FRAME_PCI = 0x20

        private const val VIN_PID = 0x02
        private const val VIN_MESSAGES = 0x01
        private const val MIL_BIT = 0x80
        private const val OBD_STANDARD_EOBD = 0x06
        private const val WARM_UPS_SINCE_CLEARED = 9
        private const val DISTANCE_WITH_MIL_KM = 137.0
        private const val DISTANCE_SINCE_CLEARED_KM = 412.0

        private const val FULL_SCALE = 255.0
        private const val TRIM_SCALE = 128.0
        private const val TEMPERATURE_OFFSET = 40.0
        private const val ADVANCE_OFFSET = 64.0
        private const val RPM_QUARTERS = 4.0
        private const val MAF_HUNDREDTHS = 100.0
        private const val MILLIVOLTS_PER_VOLT = 1_000.0
        private const val FUEL_RATE_TWENTIETHS = 20.0
        private const val CLOSED_THROTTLE_OFFSET = 6.0
        private const val PEDAL_OFFSET = 2.0

        // The frozen cruise, in engineering units.
        private const val FROZEN_RPM = 2_100.0
        private const val FROZEN_SPEED_KPH = 84.0
        private const val FROZEN_LOAD_PERCENT = 44.0
        private const val FROZEN_COOLANT_C = 92.0
        private const val FROZEN_MANIFOLD_KPA = 48.0
        private const val FROZEN_INTAKE_C = 34.0
        private const val FROZEN_SHORT_TRIM = 3.9
        private const val FROZEN_LONG_TRIM = 10.2
    }
}
