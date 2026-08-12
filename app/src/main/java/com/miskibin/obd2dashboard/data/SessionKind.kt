package com.miskibin.obd2dashboard.data

/**
 * Which car a session is talking to: the one in the driveway, or the simulation.
 *
 * Demo mode exists so the app can be looked at without a dongle, and everything it
 * produces is fiction — trips that were never driven, codes no ECU ever set. That fiction
 * is useful *inside* demo mode and is a lie everywhere else, so anything a session leaves
 * behind is filed under the kind of session that made it: demo recordings go to their own
 * directory, demo code sightings to their own log. Nothing is deleted and nothing is
 * hidden from the mode that made it; it simply stops being visible from the other side.
 */
enum class SessionKind {
    Real,
    Demo,
    ;

    val demo: Boolean get() = this == Demo

    companion object {
        fun of(demo: Boolean): SessionKind = if (demo) Demo else Real
    }
}
