package com.miskibin.obd2dashboard.obd

/**
 * What is in the tank, in the numbers the fuel maths needs.
 *
 * This exists because PID 5E — the one that reports fuel rate directly — is unsupported on
 * most cars old enough to be worth watching, so the app derives the rate from the mass air
 * flow instead. That derivation is only as good as its assumption about what the air is
 * being burnt with, and it used to assume petrol at λ = 1 for every car.
 *
 * The two constants barely differ between petrol and diesel (14.7 × 820 = 12 054 g of air
 * per litre against 14.5 × 835 = 12 107, a 0.4 % gap). [nominalLambda] is where the real
 * difference lives: a spark-ignition engine is throttled and burns close to stoichiometric,
 * while a diesel is qualitatively governed and always lean, so dividing its air flow by the
 * stoichiometric ratio overstates its fuel flow by half again at cruise and several times
 * over at idle.
 */
enum class FuelType(
    val storageKey: String,
    val stoichiometricAfr: Double,
    val densityGramsPerLitre: Double,
    val nominalLambda: Double,
) {
    Petrol("petrol", 14.7, 820.0, 1.0),
    Diesel("diesel", 14.5, 835.0, DIESEL_CRUISE_LAMBDA),
    Lpg("lpg", 15.6, 540.0, 1.0),
    Ethanol("e85", 9.8, 781.0, 1.0),
    ;

    /** Grams of air consumed per litre of fuel at λ = 1. */
    val airMassPerLitre: Double get() = stoichiometricAfr * densityGramsPerLitre

    companion object {
        /**
         * What the maths assumes when the driver has not said.
         *
         * Petrol, because it is both the commonest case and the one the app assumed
         * unconditionally before this existed — an unfilled profile therefore reads exactly
         * as it did before rather than changing under somebody who never opened the screen.
         */
        val Default = Petrol

        fun fromKey(key: String?): FuelType? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * A diesel's air-fuel ratio away from idle and away from full load.
 *
 * Only a fallback: [Pids.COMMANDED_EQUIV_RATIO] is the real answer and is used whenever the
 * car reports it. Diesels run anywhere from λ ≈ 1.3 at full load to λ > 5 at idle, so no
 * single number is right everywhere; this one is the middle of the range a car spends most
 * of its running in, and is far closer than the 1.0 the app assumed before.
 */
private const val DIESEL_CRUISE_LAMBDA = 1.8
