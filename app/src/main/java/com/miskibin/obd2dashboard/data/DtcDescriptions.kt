package com.miskibin.obd2dashboard.data

import java.util.Locale

/** A trouble-code description in both languages the app ships. */
typealias DtcText = LocalizedText

/**
 * Generic (SAE J2012) descriptions for the codes an owner actually meets.
 *
 * Manufacturer-specific codes cannot be resolved without the make's own tables, so
 * anything not listed falls back to a category description that is at least honest
 * about what part of the car is complaining.
 */
object DtcDescriptions {

    /** Exact match, or null when the code is not in the generic table. */
    fun lookup(code: String): DtcText? = table[code.trim().uppercase(Locale.ROOT)]

    /** Description of [code], falling back to its category when it is not known. */
    fun describe(code: String): DtcText = lookup(code) ?: fallback(code)

    /**
     * Category-level description derived from the first two characters: the system
     * letter and the SAE/manufacturer digit.
     */
    fun fallback(code: String): DtcText {
        val normalised = code.trim().uppercase(Locale.ROOT)
        if (normalised.length < 2) return UNKNOWN
        val system = normalised[0]
        val digit = normalised[1]
        val generic = digit == '0' || digit == '2'
        return when (system) {
            'P' -> if (generic) POWERTRAIN_GENERIC else POWERTRAIN_MANUFACTURER
            'C' -> if (generic) CHASSIS_GENERIC else CHASSIS_MANUFACTURER
            'B' -> if (generic) BODY_GENERIC else BODY_MANUFACTURER
            'U' -> if (generic) NETWORK_GENERIC else NETWORK_MANUFACTURER
            else -> UNKNOWN
        }
    }

    val POWERTRAIN_GENERIC = DtcText(
        "Powertrain — generic fault",
        "Układ napędowy — usterka standardowa",
    )
    val POWERTRAIN_MANUFACTURER = DtcText(
        "Powertrain — manufacturer specific",
        "Układ napędowy — kod producenta",
    )
    val CHASSIS_GENERIC = DtcText(
        "Chassis — generic fault",
        "Podwozie — usterka standardowa",
    )
    val CHASSIS_MANUFACTURER = DtcText(
        "Chassis — manufacturer specific",
        "Podwozie — kod producenta",
    )
    val BODY_GENERIC = DtcText(
        "Body — generic fault",
        "Nadwozie — usterka standardowa",
    )
    val BODY_MANUFACTURER = DtcText(
        "Body — manufacturer specific",
        "Nadwozie — kod producenta",
    )
    val NETWORK_GENERIC = DtcText(
        "Network / communication — generic fault",
        "Sieć / komunikacja — usterka standardowa",
    )
    val NETWORK_MANUFACTURER = DtcText(
        "Network / communication — manufacturer specific",
        "Sieć / komunikacja — kod producenta",
    )
    val UNKNOWN = DtcText("Unknown code", "Nieznany kod")

    private infix fun String.means(text: Pair<String, String>) = this to DtcText(text.first, text.second)

    private val table: Map<String, DtcText> = mapOf(
        // Variable valve timing and position sensors
        "P0011" means ("Camshaft position A — timing over-advanced, bank 1"
            to "Wałek rozrządu A — zbyt wczesne ustawienie faz, rząd 1"),
        "P0014" means ("Camshaft position B — timing over-advanced, bank 1"
            to "Wałek rozrządu B — zbyt wczesne ustawienie faz, rząd 1"),
        "P0016" means ("Crankshaft / camshaft position correlation, bank 1 sensor A"
            to "Niezgodność położenia wału korbowego i wałka rozrządu, rząd 1 czujnik A"),
        "P0087" means ("Fuel rail / system pressure too low"
            to "Zbyt niskie ciśnienie w listwie paliwowej"),
        "P0088" means ("Fuel rail / system pressure too high"
            to "Zbyt wysokie ciśnienie w listwie paliwowej"),

        // Air metering
        "P0100" means ("Mass air flow sensor circuit malfunction"
            to "Usterka obwodu przepływomierza powietrza"),
        "P0101" means ("Mass air flow sensor — range / performance"
            to "Przepływomierz powietrza — nieprawidłowy zakres lub działanie"),
        "P0102" means ("Mass air flow sensor — low input"
            to "Przepływomierz powietrza — zbyt niski sygnał"),
        "P0103" means ("Mass air flow sensor — high input"
            to "Przepływomierz powietrza — zbyt wysoki sygnał"),
        "P0106" means ("Manifold pressure sensor — range / performance"
            to "Czujnik ciśnienia w kolektorze — nieprawidłowy zakres lub działanie"),
        "P0107" means ("Manifold pressure sensor — low input"
            to "Czujnik ciśnienia w kolektorze — zbyt niski sygnał"),
        "P0108" means ("Manifold pressure sensor — high input"
            to "Czujnik ciśnienia w kolektorze — zbyt wysoki sygnał"),
        "P0110" means ("Intake air temperature sensor circuit"
            to "Obwód czujnika temperatury powietrza dolotowego"),
        "P0111" means ("Intake air temperature sensor — range / performance"
            to "Czujnik temperatury powietrza dolotowego — nieprawidłowy zakres"),
        "P0112" means ("Intake air temperature sensor — low input"
            to "Czujnik temperatury powietrza dolotowego — zbyt niski sygnał"),
        "P0113" means ("Intake air temperature sensor — high input"
            to "Czujnik temperatury powietrza dolotowego — zbyt wysoki sygnał"),
        "P0115" means ("Engine coolant temperature sensor circuit"
            to "Obwód czujnika temperatury płynu chłodzącego"),
        "P0116" means ("Coolant temperature sensor — range / performance"
            to "Czujnik temperatury płynu chłodzącego — nieprawidłowy zakres"),
        "P0117" means ("Coolant temperature sensor — low input"
            to "Czujnik temperatury płynu chłodzącego — zbyt niski sygnał"),
        "P0118" means ("Coolant temperature sensor — high input"
            to "Czujnik temperatury płynu chłodzącego — zbyt wysoki sygnał"),
        "P0120" means ("Throttle position sensor A circuit"
            to "Obwód czujnika położenia przepustnicy A"),
        "P0121" means ("Throttle position sensor A — range / performance"
            to "Czujnik położenia przepustnicy A — nieprawidłowy zakres"),
        "P0122" means ("Throttle position sensor A — low input"
            to "Czujnik położenia przepustnicy A — zbyt niski sygnał"),
        "P0123" means ("Throttle position sensor A — high input"
            to "Czujnik położenia przepustnicy A — zbyt wysoki sygnał"),
        "P0128" means ("Coolant thermostat below regulating temperature"
            to "Termostat — silnik nie osiąga temperatury roboczej"),

        // Oxygen sensors
        "P0130" means ("Oxygen sensor circuit, bank 1 sensor 1"
            to "Obwód sondy lambda, rząd 1 czujnik 1"),
        "P0131" means ("Oxygen sensor low voltage, bank 1 sensor 1"
            to "Zbyt niskie napięcie sondy lambda, rząd 1 czujnik 1"),
        "P0132" means ("Oxygen sensor high voltage, bank 1 sensor 1"
            to "Zbyt wysokie napięcie sondy lambda, rząd 1 czujnik 1"),
        "P0133" means ("Oxygen sensor slow response, bank 1 sensor 1"
            to "Wolna reakcja sondy lambda, rząd 1 czujnik 1"),
        "P0134" means ("Oxygen sensor no activity detected, bank 1 sensor 1"
            to "Brak sygnału sondy lambda, rząd 1 czujnik 1"),
        "P0135" means ("Oxygen sensor heater circuit, bank 1 sensor 1"
            to "Obwód podgrzewacza sondy lambda, rząd 1 czujnik 1"),
        "P0136" means ("Oxygen sensor circuit, bank 1 sensor 2"
            to "Obwód sondy lambda, rząd 1 czujnik 2"),
        "P0137" means ("Oxygen sensor low voltage, bank 1 sensor 2"
            to "Zbyt niskie napięcie sondy lambda, rząd 1 czujnik 2"),
        "P0138" means ("Oxygen sensor high voltage, bank 1 sensor 2"
            to "Zbyt wysokie napięcie sondy lambda, rząd 1 czujnik 2"),
        "P0139" means ("Oxygen sensor slow response, bank 1 sensor 2"
            to "Wolna reakcja sondy lambda, rząd 1 czujnik 2"),
        "P0140" means ("Oxygen sensor no activity detected, bank 1 sensor 2"
            to "Brak sygnału sondy lambda, rząd 1 czujnik 2"),
        "P0141" means ("Oxygen sensor heater circuit, bank 1 sensor 2"
            to "Obwód podgrzewacza sondy lambda, rząd 1 czujnik 2"),
        "P0150" means ("Oxygen sensor circuit, bank 2 sensor 1"
            to "Obwód sondy lambda, rząd 2 czujnik 1"),
        "P0155" means ("Oxygen sensor heater circuit, bank 2 sensor 1"
            to "Obwód podgrzewacza sondy lambda, rząd 2 czujnik 1"),

        // Mixture
        "P0171" means ("System too lean, bank 1"
            to "Mieszanka zbyt uboga, rząd 1"),
        "P0172" means ("System too rich, bank 1"
            to "Mieszanka zbyt bogata, rząd 1"),
        "P0174" means ("System too lean, bank 2"
            to "Mieszanka zbyt uboga, rząd 2"),
        "P0175" means ("System too rich, bank 2"
            to "Mieszanka zbyt bogata, rząd 2"),
        "P0180" means ("Fuel temperature sensor A circuit"
            to "Obwód czujnika temperatury paliwa A"),
        "P0190" means ("Fuel rail pressure sensor circuit"
            to "Obwód czujnika ciśnienia w listwie paliwowej"),
        "P0191" means ("Fuel rail pressure sensor — range / performance"
            to "Czujnik ciśnienia listwy paliwowej — nieprawidłowy zakres"),
        "P0193" means ("Fuel rail pressure sensor — high input"
            to "Czujnik ciśnienia listwy paliwowej — zbyt wysoki sygnał"),

        // Injectors and boost
        "P0201" means ("Injector circuit, cylinder 1" to "Obwód wtryskiwacza, cylinder 1"),
        "P0202" means ("Injector circuit, cylinder 2" to "Obwód wtryskiwacza, cylinder 2"),
        "P0203" means ("Injector circuit, cylinder 3" to "Obwód wtryskiwacza, cylinder 3"),
        "P0204" means ("Injector circuit, cylinder 4" to "Obwód wtryskiwacza, cylinder 4"),
        "P0217" means ("Engine over temperature condition" to "Przegrzanie silnika"),
        "P0221" means ("Throttle position sensor B — range / performance"
            to "Czujnik położenia przepustnicy B — nieprawidłowy zakres"),
        "P0222" means ("Throttle position sensor B — low input"
            to "Czujnik położenia przepustnicy B — zbyt niski sygnał"),
        "P0223" means ("Throttle position sensor B — high input"
            to "Czujnik położenia przepustnicy B — zbyt wysoki sygnał"),
        "P0230" means ("Fuel pump primary circuit" to "Obwód główny pompy paliwa"),
        "P0234" means ("Turbocharger / supercharger overboost"
            to "Zbyt wysokie ciśnienie doładowania"),
        "P0235" means ("Turbocharger boost sensor A circuit"
            to "Obwód czujnika doładowania A"),
        "P0236" means ("Turbocharger boost sensor A — range / performance"
            to "Czujnik doładowania A — nieprawidłowy zakres"),
        "P0243" means ("Turbocharger wastegate solenoid A"
            to "Elektrozawór zaworu upustowego turbosprężarki A"),
        "P0245" means ("Turbocharger wastegate solenoid A — low"
            to "Elektrozawór zaworu upustowego A — zbyt niski sygnał"),
        "P0246" means ("Turbocharger wastegate solenoid A — high"
            to "Elektrozawór zaworu upustowego A — zbyt wysoki sygnał"),
        "P0251" means ("Injection pump fuel metering control A"
            to "Sterowanie dawkowaniem pompy wtryskowej A"),
        "P0261" means ("Cylinder 1 injector circuit — low"
            to "Obwód wtryskiwacza cylindra 1 — zwarcie do masy"),
        "P0262" means ("Cylinder 1 injector circuit — high"
            to "Obwód wtryskiwacza cylindra 1 — zwarcie do plusa"),
        "P0299" means ("Turbocharger / supercharger underboost"
            to "Zbyt niskie ciśnienie doładowania"),

        // Misfires
        "P0300" means ("Random or multiple cylinder misfire detected"
            to "Wykryto wypadanie zapłonów w losowych cylindrach"),
        "P0301" means ("Cylinder 1 misfire detected" to "Wypadanie zapłonu w cylindrze 1"),
        "P0302" means ("Cylinder 2 misfire detected" to "Wypadanie zapłonu w cylindrze 2"),
        "P0303" means ("Cylinder 3 misfire detected" to "Wypadanie zapłonu w cylindrze 3"),
        "P0304" means ("Cylinder 4 misfire detected" to "Wypadanie zapłonu w cylindrze 4"),
        "P0305" means ("Cylinder 5 misfire detected" to "Wypadanie zapłonu w cylindrze 5"),
        "P0306" means ("Cylinder 6 misfire detected" to "Wypadanie zapłonu w cylindrze 6"),
        "P0307" means ("Cylinder 7 misfire detected" to "Wypadanie zapłonu w cylindrze 7"),
        "P0308" means ("Cylinder 8 misfire detected" to "Wypadanie zapłonu w cylindrze 8"),
        "P0313" means ("Misfire detected with low fuel level"
            to "Wypadanie zapłonów przy niskim poziomie paliwa"),
        "P0316" means ("Misfire detected on startup, first 1000 revolutions"
            to "Wypadanie zapłonów zaraz po uruchomieniu silnika"),

        // Ignition and position sensors
        "P0335" means ("Crankshaft position sensor A circuit"
            to "Obwód czujnika położenia wału korbowego A"),
        "P0336" means ("Crankshaft position sensor A — range / performance"
            to "Czujnik położenia wału korbowego A — nieprawidłowy zakres"),
        "P0340" means ("Camshaft position sensor A circuit"
            to "Obwód czujnika położenia wałka rozrządu A"),
        "P0341" means ("Camshaft position sensor A — range / performance"
            to "Czujnik położenia wałka rozrządu A — nieprawidłowy zakres"),
        "P0351" means ("Ignition coil A primary / secondary circuit"
            to "Obwód pierwotny lub wtórny cewki zapłonowej A"),
        "P0352" means ("Ignition coil B primary / secondary circuit"
            to "Obwód pierwotny lub wtórny cewki zapłonowej B"),
        "P0353" means ("Ignition coil C primary / secondary circuit"
            to "Obwód pierwotny lub wtórny cewki zapłonowej C"),
        "P0354" means ("Ignition coil D primary / secondary circuit"
            to "Obwód pierwotny lub wtórny cewki zapłonowej D"),

        // EGR, secondary air, catalyst
        "P0400" means ("Exhaust gas recirculation flow malfunction"
            to "Usterka przepływu recyrkulacji spalin (EGR)"),
        "P0401" means ("Exhaust gas recirculation flow insufficient"
            to "Zbyt mały przepływ recyrkulacji spalin (EGR)"),
        "P0402" means ("Exhaust gas recirculation flow excessive"
            to "Zbyt duży przepływ recyrkulacji spalin (EGR)"),
        "P0403" means ("Exhaust gas recirculation control circuit"
            to "Obwód sterowania zaworem EGR"),
        "P0404" means ("EGR control circuit — range / performance"
            to "Sterowanie EGR — nieprawidłowy zakres lub działanie"),
        "P0405" means ("EGR sensor A — low input" to "Czujnik EGR A — zbyt niski sygnał"),
        "P0406" means ("EGR sensor A — high input" to "Czujnik EGR A — zbyt wysoki sygnał"),
        "P0410" means ("Secondary air injection system"
            to "Układ wtórnego wtrysku powietrza"),
        "P0411" means ("Secondary air injection system — incorrect flow"
            to "Nieprawidłowy przepływ w układzie wtórnego powietrza"),
        "P0420" means ("Catalyst system efficiency below threshold, bank 1"
            to "Sprawność katalizatora poniżej progu, rząd 1"),
        "P0421" means ("Warm-up catalyst efficiency below threshold, bank 1"
            to "Sprawność katalizatora rozruchowego poniżej progu, rząd 1"),
        "P0430" means ("Catalyst system efficiency below threshold, bank 2"
            to "Sprawność katalizatora poniżej progu, rząd 2"),
        "P0431" means ("Warm-up catalyst efficiency below threshold, bank 2"
            to "Sprawność katalizatora rozruchowego poniżej progu, rząd 2"),

        // Evaporative emissions
        "P0440" means ("Evaporative emission control system"
            to "Układ odzysku par paliwa (EVAP)"),
        "P0441" means ("Evaporative emission system — incorrect purge flow"
            to "Nieprawidłowe przepłukiwanie układu EVAP"),
        "P0442" means ("Evaporative emission system leak detected (small leak)"
            to "Wykryto małą nieszczelność układu EVAP"),
        "P0443" means ("Evaporative emission purge control valve circuit"
            to "Obwód zaworu przepłukiwania EVAP"),
        "P0446" means ("Evaporative emission vent control circuit"
            to "Obwód zaworu odpowietrzania EVAP"),
        "P0447" means ("Evaporative emission vent control circuit open"
            to "Przerwa w obwodzie zaworu odpowietrzania EVAP"),
        "P0448" means ("Evaporative emission vent control circuit shorted"
            to "Zwarcie w obwodzie zaworu odpowietrzania EVAP"),
        "P0449" means ("Evaporative emission vent valve / solenoid circuit"
            to "Obwód elektrozaworu odpowietrzania EVAP"),
        "P0451" means ("Evaporative emission pressure sensor — range / performance"
            to "Czujnik ciśnienia EVAP — nieprawidłowy zakres"),
        "P0452" means ("Evaporative emission pressure sensor — low input"
            to "Czujnik ciśnienia EVAP — zbyt niski sygnał"),
        "P0453" means ("Evaporative emission pressure sensor — high input"
            to "Czujnik ciśnienia EVAP — zbyt wysoki sygnał"),
        "P0455" means ("Evaporative emission system leak detected (large leak)"
            to "Wykryto dużą nieszczelność układu EVAP — sprawdź korek wlewu paliwa"),
        "P0456" means ("Evaporative emission system leak detected (very small leak)"
            to "Wykryto bardzo małą nieszczelność układu EVAP"),
        "P0457" means ("Evaporative emission system leak — fuel cap loose or missing"
            to "Nieszczelność EVAP — poluzowany lub brakujący korek wlewu paliwa"),

        // Fuel level, speed, idle
        "P0460" means ("Fuel level sensor circuit" to "Obwód czujnika poziomu paliwa"),
        "P0461" means ("Fuel level sensor — range / performance"
            to "Czujnik poziomu paliwa — nieprawidłowy zakres"),
        "P0462" means ("Fuel level sensor — low input"
            to "Czujnik poziomu paliwa — zbyt niski sygnał"),
        "P0463" means ("Fuel level sensor — high input"
            to "Czujnik poziomu paliwa — zbyt wysoki sygnał"),
        "P0500" means ("Vehicle speed sensor A" to "Czujnik prędkości pojazdu A"),
        "P0501" means ("Vehicle speed sensor A — range / performance"
            to "Czujnik prędkości pojazdu A — nieprawidłowy zakres"),
        "P0503" means ("Vehicle speed sensor — intermittent or erratic signal"
            to "Czujnik prędkości pojazdu — sygnał przerywany lub nieregularny"),
        "P0505" means ("Idle air control system" to "Układ regulacji biegu jałowego"),
        "P0506" means ("Idle control system — RPM lower than expected"
            to "Obroty biegu jałowego niższe niż oczekiwane"),
        "P0507" means ("Idle control system — RPM higher than expected"
            to "Obroty biegu jałowego wyższe niż oczekiwane"),
        "P0520" means ("Engine oil pressure sensor circuit"
            to "Obwód czujnika ciśnienia oleju"),
        "P0521" means ("Engine oil pressure sensor — range / performance"
            to "Czujnik ciśnienia oleju — nieprawidłowy zakres"),
        "P0522" means ("Engine oil pressure sensor — low input"
            to "Czujnik ciśnienia oleju — zbyt niski sygnał"),
        "P0523" means ("Engine oil pressure sensor — high input"
            to "Czujnik ciśnienia oleju — zbyt wysoki sygnał"),
        "P0532" means ("Air conditioning pressure sensor — low input"
            to "Czujnik ciśnienia klimatyzacji — zbyt niski sygnał"),

        // Electrical and control module
        "P0562" means ("System voltage low" to "Zbyt niskie napięcie zasilania"),
        "P0563" means ("System voltage high" to "Zbyt wysokie napięcie zasilania"),
        "P0571" means ("Brake switch A circuit" to "Obwód wyłącznika świateł stopu A"),
        "P0600" means ("Serial communication link" to "Usterka magistrali komunikacyjnej"),
        "P0601" means ("Internal control module memory checksum error"
            to "Błąd sumy kontrolnej pamięci sterownika"),
        "P0602" means ("Control module programming error"
            to "Błąd programowania sterownika"),
        "P0603" means ("Internal control module keep-alive memory error"
            to "Błąd pamięci podtrzymywanej sterownika"),
        "P0606" means ("Control module processor fault" to "Usterka procesora sterownika"),
        "P0620" means ("Generator control circuit" to "Obwód sterowania alternatorem"),
        "P0627" means ("Fuel pump control circuit open"
            to "Przerwa w obwodzie sterowania pompą paliwa"),
        "P0645" means ("Air conditioning clutch relay circuit"
            to "Obwód przekaźnika sprzęgła klimatyzacji"),

        // Transmission
        "P0700" means ("Transmission control system malfunction"
            to "Usterka układu sterowania skrzynią biegów"),
        "P0701" means ("Transmission control system — range / performance"
            to "Układ sterowania skrzynią biegów — nieprawidłowy zakres"),
        "P0702" means ("Transmission control system electrical"
            to "Usterka elektryczna układu sterowania skrzynią biegów"),
        "P0703" means ("Torque converter / brake switch B circuit"
            to "Obwód wyłącznika hamulca / przemiennika momentu B"),
        "P0705" means ("Transmission range sensor circuit"
            to "Obwód czujnika położenia dźwigni skrzyni biegów"),
        "P0706" means ("Transmission range sensor — range / performance"
            to "Czujnik położenia dźwigni skrzyni biegów — nieprawidłowy zakres"),
        "P0710" means ("Transmission fluid temperature sensor circuit"
            to "Obwód czujnika temperatury oleju skrzyni biegów"),
        "P0711" means ("Transmission fluid temperature sensor — range / performance"
            to "Czujnik temperatury oleju skrzyni biegów — nieprawidłowy zakres"),
        "P0715" means ("Input / turbine speed sensor circuit"
            to "Obwód czujnika prędkości wejściowej (turbiny)"),
        "P0716" means ("Input / turbine speed sensor — range / performance"
            to "Czujnik prędkości wejściowej — nieprawidłowy zakres"),
        "P0717" means ("Input / turbine speed sensor — no signal"
            to "Brak sygnału czujnika prędkości wejściowej"),
        "P0720" means ("Output speed sensor circuit"
            to "Obwód czujnika prędkości wyjściowej"),
        "P0721" means ("Output speed sensor — range / performance"
            to "Czujnik prędkości wyjściowej — nieprawidłowy zakres"),
        "P0730" means ("Incorrect gear ratio" to "Nieprawidłowe przełożenie skrzyni biegów"),
        "P0731" means ("Gear 1 incorrect ratio" to "Nieprawidłowe przełożenie 1. biegu"),
        "P0732" means ("Gear 2 incorrect ratio" to "Nieprawidłowe przełożenie 2. biegu"),
        "P0733" means ("Gear 3 incorrect ratio" to "Nieprawidłowe przełożenie 3. biegu"),
        "P0734" means ("Gear 4 incorrect ratio" to "Nieprawidłowe przełożenie 4. biegu"),
        "P0735" means ("Gear 5 incorrect ratio" to "Nieprawidłowe przełożenie 5. biegu"),
        "P0740" means ("Torque converter clutch circuit"
            to "Obwód sprzęgła przemiennika momentu"),
        "P0741" means ("Torque converter clutch — stuck off or performance"
            to "Sprzęgło przemiennika momentu — nie blokuje się"),
        "P0743" means ("Torque converter clutch circuit electrical"
            to "Usterka elektryczna obwodu sprzęgła przemiennika momentu"),
        "P0750" means ("Shift solenoid A" to "Elektrozawór zmiany biegów A"),
        "P0751" means ("Shift solenoid A — stuck off or performance"
            to "Elektrozawór zmiany biegów A — zablokowany lub nieskuteczny"),
        "P0753" means ("Shift solenoid A electrical"
            to "Usterka elektryczna elektrozaworu zmiany biegów A"),
        "P0755" means ("Shift solenoid B" to "Elektrozawór zmiany biegów B"),
        "P0756" means ("Shift solenoid B — stuck off or performance"
            to "Elektrozawór zmiany biegów B — zablokowany lub nieskuteczny"),
        "P0758" means ("Shift solenoid B electrical"
            to "Usterka elektryczna elektrozaworu zmiany biegów B"),

        // Diesel aftertreatment and post-catalyst trims
        "P2002" means ("Diesel particulate filter efficiency below threshold, bank 1"
            to "Sprawność filtra cząstek stałych (DPF) poniżej progu, rząd 1"),
        "P2096" means ("Post-catalyst fuel trim system too lean, bank 1"
            to "Korekta paliwowa za katalizatorem zbyt uboga, rząd 1"),
        "P2097" means ("Post-catalyst fuel trim system too rich, bank 1"
            to "Korekta paliwowa za katalizatorem zbyt bogata, rząd 1"),
        "P2187" means ("System too lean at idle, bank 1"
            to "Mieszanka zbyt uboga na biegu jałowym, rząd 1"),
        "P2195" means ("Oxygen sensor signal stuck lean, bank 1 sensor 1"
            to "Sygnał sondy lambda zablokowany na ubogim, rząd 1 czujnik 1"),
        "P2270" means ("Oxygen sensor signal stuck lean, bank 1 sensor 2"
            to "Sygnał sondy lambda zablokowany na ubogim, rząd 1 czujnik 2"),
        "P2279" means ("Intake air system leak" to "Nieszczelność układu dolotowego"),
        "P2463" means ("Diesel particulate filter — soot accumulation"
            to "Filtr cząstek stałych (DPF) — nadmierne nagromadzenie sadzy"),
        "P244A" means ("Diesel particulate filter differential pressure too low"
            to "Zbyt niska różnica ciśnień na filtrze cząstek stałych (DPF)"),

        // Chassis
        "C0035" means ("Left front wheel speed sensor circuit"
            to "Obwód czujnika prędkości lewego przedniego koła"),
        "C0040" means ("Right front wheel speed sensor circuit"
            to "Obwód czujnika prędkości prawego przedniego koła"),
        "C0045" means ("Left rear wheel speed sensor circuit"
            to "Obwód czujnika prędkości lewego tylnego koła"),
        "C0050" means ("Right rear wheel speed sensor circuit"
            to "Obwód czujnika prędkości prawego tylnego koła"),

        // Body
        "B0001" means ("Driver airbag deployment control"
            to "Sterowanie wyzwalaniem poduszki kierowcy"),
        "B0010" means ("Passenger airbag deployment control"
            to "Sterowanie wyzwalaniem poduszki pasażera"),

        // Network
        "U0100" means ("Lost communication with engine control module"
            to "Brak komunikacji ze sterownikiem silnika"),
        "U0101" means ("Lost communication with transmission control module"
            to "Brak komunikacji ze sterownikiem skrzyni biegów"),
        "U0121" means ("Lost communication with ABS control module"
            to "Brak komunikacji ze sterownikiem ABS"),
        "U0140" means ("Lost communication with body control module"
            to "Brak komunikacji ze sterownikiem nadwozia"),
        "U0155" means ("Lost communication with instrument panel cluster"
            to "Brak komunikacji z zestawem wskaźników"),
        "U0164" means ("Lost communication with climate control module"
            to "Brak komunikacji ze sterownikiem klimatyzacji"),
    )

    /** Exposed for tests and for the "N generic codes known" line in About. */
    val knownCodeCount: Int get() = table.size
}
