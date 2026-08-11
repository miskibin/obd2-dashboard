# OBD2 Dashboard

Android app for reading diagnostic trouble codes and monitoring live engine
parameters over a Bluetooth LE OBD2 adapter (built for the vgate iCar2 BLE,
works with ELM327-compatible clones). Inspired by Car Scanner ELM OBD2.

## Features (MVP)

- **DTC diagnostics** — read stored / pending / permanent trouble codes
  (modes 03 / 07 / 0A) with descriptions, clear codes (mode 04)
- **Live dashboard** — configurable tiles: RPM, speed, coolant temp, boost
  (MAP − baro), intake temp, fuel trims, battery voltage and more
- **Charts & trip logging** — parameter history over time, logs saved on device
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
- `docs/research-android-stack.md` — stack choice rationale, Android Auto
  reality check, CI/signing setup, prior art (ObdGraphs, AndrOBD, …)
