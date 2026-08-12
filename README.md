# OBD2 Dashboard

Android app for reading diagnostic trouble codes and monitoring live engine
parameters over a Bluetooth LE OBD2 adapter (built for the vgate iCar2 BLE,
works with ELM327-compatible clones). Inspired by Car Scanner ELM OBD2.

## Features (MVP)

- **DTC diagnostics** — read stored / pending / permanent trouble codes
  (modes 03 / 07 / 0A) with descriptions, clear codes (mode 04). Each code opens
  onto its own screen: when the app first and last saw it, which codes appeared
  near it and in what order, the ECU freeze frame, and — when the app was
  watching at the time — the ±30 s of engine data around the moment it appeared
- **Live dashboard** — one hero card for revs, road speed and the estimated gear,
  then a list of values with the range each one is normal in; tap any row for its
  last minute plotted against that range
- **Charts** — any parameter from the catalogue, up to six at once, in three axis
  modes: a strip each, one shared 0–100 % axis, or one shared axis in units
- **Trips** — every recording read back into distance, consumption, maxima and
  the events worth a badge (oil over its limit, the limiter reached), each with
  the second it happened; export as CSV
- **UI in English and Polish**

Planned later: Android Auto screen with live engine parameters
(via `androidx.car.app` — see `docs/research-android-stack.md` for the
distribution constraints that apply to sideloaded builds).

## Tech

Kotlin + Jetpack Compose, platform BLE API, coroutines/Flow.
minSdk 26, targetSdk 35.

```
app/src/main/java/com/miskibin/obd2dashboard/
├── ble/    BLE transport: scanner, GATT auto-probe, ELM327 byte stream
├── obd/    pure-JVM protocol layer: ELM327 session, PIDs, DTC, scheduler
├── data/   persistence: tile config, trip logs, per-vehicle cache
└── ui/     Compose screens: dashboard, DTC, charts, settings
```

## Building

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Every push builds an APK in GitHub Actions (artifact `obd2-dashboard-apk`);
tags matching `v*` publish a GitHub Release with a direct APK download link.
All builds share one checked-in debug signing key, so a newer APK installs
over an older one without uninstalling.

## Research notes

- `docs/research-obd2-protocol.md` — ELM327 AT layer, iCar2 BLE GATT
  profiles, mode 01 PID formulas, DTC decoding, throughput engineering
- `docs/research-vehicle-metadata.md` — what the app can know about the car
  beyond the OBD port: VIN decoding offline vs. online, the CEPiK and EEA open
  datasets, and the per-car constants that make the live numbers mean something
- `docs/research-android-stack.md` — stack choice rationale, Android Auto
  reality check, CI/signing setup, prior art (ObdGraphs, AndrOBD, …)
