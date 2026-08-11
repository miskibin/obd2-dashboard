package com.miskibin.obd2dashboard.data

import com.miskibin.obd2dashboard.obd.MonitorId
import java.util.Locale

/**
 * A phrase in both languages the app ships.
 *
 * Text that has to exist in both languages *at once* — the shared mechanic report, the
 * trouble-code table — cannot come from `strings.xml`, which only ever resolves the one
 * locale the activity was created with.
 */
data class LocalizedText(val en: String, val pl: String) {
    fun forLanguage(language: String): String =
        if (language.lowercase(Locale.ROOT) == POLISH) pl else en

    private companion object {
        const val POLISH = "pl"
    }
}

/** Names of the SAE J1979 readiness monitors, as an owner would recognise them. */
object MonitorNames {

    operator fun get(id: MonitorId): LocalizedText = names.getValue(id)

    private val names: Map<MonitorId, LocalizedText> = mapOf(
        MonitorId.Misfire to LocalizedText("Misfire", "Wypadanie zapłonów"),
        MonitorId.FuelSystem to LocalizedText("Fuel system", "Układ paliwowy"),
        MonitorId.Components to LocalizedText("Comprehensive components", "Podzespoły elektroniczne"),
        MonitorId.Catalyst to LocalizedText("Catalyst", "Katalizator"),
        MonitorId.HeatedCatalyst to LocalizedText("Heated catalyst", "Katalizator podgrzewany"),
        MonitorId.EvaporativeSystem to LocalizedText("Evaporative system", "Układ par paliwa (EVAP)"),
        MonitorId.SecondaryAirSystem to LocalizedText("Secondary air system", "Wtórne powietrze"),
        MonitorId.AcRefrigerant to LocalizedText("A/C refrigerant", "Czynnik klimatyzacji"),
        MonitorId.OxygenSensor to LocalizedText("Oxygen sensor", "Sonda lambda"),
        MonitorId.OxygenSensorHeater to LocalizedText("Oxygen sensor heater", "Podgrzewacz sondy lambda"),
        MonitorId.EgrSystem to LocalizedText("EGR system", "Układ EGR"),
        MonitorId.NmhcCatalyst to LocalizedText("NMHC catalyst", "Katalizator NMHC"),
        MonitorId.NoxAftertreatment to LocalizedText("NOx / SCR aftertreatment", "Oczyszczanie NOx / SCR"),
        MonitorId.BoostPressure to LocalizedText("Boost pressure", "Ciśnienie doładowania"),
        MonitorId.ExhaustGasSensor to LocalizedText("Exhaust gas sensor", "Czujnik spalin"),
        MonitorId.ParticulateFilter to LocalizedText("Particulate filter", "Filtr cząstek stałych"),
        MonitorId.EgrVvtSystem to LocalizedText("EGR / VVT system", "Układ EGR / zmiennych faz"),
    )
}
