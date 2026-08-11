# Tech-stack research: custom Android OBD2 dashboard with Android Auto

**Scope.** A personal, sideloadable Android app that (a) ships as an APK the user installs by hand,
(b) talks to a Bluetooth Classic (SPP/RFCOMM) or BLE ELM327 adapter, and (c) *later* renders live
engine parameters on the car head unit via Android Auto.

**Research date:** August 2026. Versions/policies below were current at that point.

---

## 0. TL;DR — the decisions

| Question | Answer |
|---|---|
| Framework | **Native Kotlin + Jetpack Compose.** Not Flutter, not React Native. |
| Why | Android Auto is *only* reachable through `androidx.car.app` (`CarAppService`) — native Kotlin/Java code. Every cross-platform "Android Auto" package is a thin bridge over that same native layer, and Bluetooth **Classic RFCOMM** (which most cheap ELM327 dongles use) is the weakest, least-maintained part of both Flutter and RN ecosystems. |
| Car surface | `CarAppService` + **`NavigationTemplate` + `SurfaceCallback`** (custom canvas gauges), with `IOT` category + `GridTemplate`/`PaneTemplate` as the low-effort fallback. |
| Hard blocker to plan for | **A sideloaded APK using the Car App Library will NOT appear on a real head unit.** Android Auto's "Unknown sources" developer toggle explicitly *does not* cover Car App Library apps. Real-car use requires distribution through **Play Internal App Sharing** or the **Internal testing track** (no Play review needed, but a $25 Play Console account is). Local dev on the **Desktop Head Unit (DHU)** works fine from a plain `adb install`. |
| Phone-only APK | Fully sideloadable; build + sign in GitHub Actions, download the artifact / GitHub Release asset on the phone. |
| Connection ownership | One **foreground service** (`foregroundServiceType="connectedDevice"`) owns the single ELM327 socket; phone UI and car screen are both read-only observers of a shared `StateFlow`. |

---

## 1. Stack comparison for this use case

### 1.1 The decisive factor: Android Auto

Android Auto "projected" apps are not normal Android UIs. The head unit renders a **template**
described by your app; your process never draws View/Compose hierarchies on the car screen (with one
exception, see §2.4). The contract is `androidx.car.app.CarAppService` → `Session` → `Screen` →
`onGetTemplate()`, all in Kotlin/Java.
([Set up your project](https://developer.android.com/training/cars/apps/library/set-up-project),
[CarAppService](https://developer.android.com/reference/androidx/car/app/CarAppService))

Consequences per stack:

- **Kotlin/Compose** — direct. The car module is a normal Gradle module in the same app; it can call
  straight into your service/repository singletons with zero bridging.
- **Flutter** — *possible but always hybrid*. Google's own official example, the Flutter Navigation
  SDK, tells you to install the Android for Cars App Library, edit the manifest, declare the min car
  API level, and **write a `CarAppService` and `Screen` subclass in Kotlin**, then bridge back to
  Dart with a controller object.
  ([flutter-navigation-sdk/doc/android-auto.md](https://github.com/googlemaps/flutter-navigation-sdk/blob/main/doc/android-auto.md))
  You cannot drive the car UI from Dart. So you write the same native code as option 1 *plus* a
  platform channel, *plus* you must keep the Dart isolate alive and reachable from the car session.
  `flutter_carplay` is primarily an Apple CarPlay package despite its marketing copy.
  ([pub.dev/packages/flutter_carplay](https://pub.dev/packages/flutter_carplay))
- **React Native** — same story, with a maturity discount. `react-native-carplay` added Android Auto
  in 2.4.0 and its own docs still label it **beta**; it exposes an imperative JS wrapper over the
  template list (List/Grid/Map/Navigation/Pane/Search/Tab/Message).
  ([AndroidAuto.md](https://github.com/birkir/react-native-carplay/blob/master/AndroidAuto.md)).
  Shopify's `react-native-android-auto` is an abandoned proof of concept that runs a React instance
  inside a `CarAppService`.
  ([CarService.java](https://github.com/Shopify/react-native-android-auto/blob/master/android/src/main/java/com/shopify/rnandroidauto/CarService.java))
  `@iternio/react-native-auto-play` is the more actively maintained option.
  ([npm](https://www.npmjs.com/package/@iternio/react-native-auto-play))

**None of these bridges expose `SurfaceCallback` canvas rendering** — the mechanism you actually
need for a smooth gauge cluster (§2.4). That alone rules out RN/Flutter for the interesting version
of this app.

### 1.2 Bluetooth quality

Two transports matter, and most people own the cheap one:

- **Bluetooth Classic SPP/RFCOMM** (`00001101-0000-1000-8000-00805F9B34FB`) — the classic ELM327
  clone. Android API: `BluetoothSocket` + blocking `InputStream`/`OutputStream`. Trivially
  wrappable in a coroutine on `Dispatchers.IO`.
- **BLE** — newer/"4.0" dongles (Veepeak BLE, LELink, OBDLink CX, Kiwi 3, most iOS-compatible ones).
  There is **no standard BLE serial profile**, so every vendor invents a GATT service. Common
  variants: service `FFF0` with notify `FFF1` / write `FFF2`, or service `FFE0` with a single
  read+write+notify `FFE1`; Nordic UART (`6E400001-…`) also appears.
  ([variant survey](https://github.com/rubenmuehlhans/esphome-obd2-ble),
  [obd-ble-serial](https://github.com/vdvornichenko/obd-ble-serial))
  You must auto-detect: scan services, pick the characteristic with `NOTIFY` as RX and the one with
  `WRITE`/`WRITE_NO_RESPONSE` as TX, and reassemble the `>`-terminated ELM327 responses across
  20-byte (or MTU-sized) fragments.

| | Kotlin native | Flutter | React Native |
|---|---|---|---|
| Classic RFCOMM | First-party `BluetoothSocket`; complete control of timeouts, reconnection, MTU-free streaming | `flutter_bluetooth_serial` is effectively unmaintained; a scatter of newer forks/plugins (`flutter_classic_bluetooth`, `bluetooth_rfcomm`, `bluetooth_classic`, `flutter_bluetooth_serial_ble`) with small user bases ([pub.dev search](https://pub.dev/packages?q=bluetooth+classic)) | `react-native-bluetooth-classic` (kenjdavidson) is the only real option; low release cadence ([GitHub](https://github.com/kenjdavidson/react-native-bluetooth-classic)) |
| BLE | Platform API, or Nordic **Android-BLE-Library** / **Kotlin-BLE-Library** (queueing, MTU, reconnect, coroutines+Flow since 2.3) ([Android-BLE-Library](https://github.com/NordicSemiconductor/Android-BLE-Library)) | `flutter_blue_plus` / `flutter_reactive_ble` — decent | `react-native-ble-plx` — healthy, ~86k downloads/wk ([npm](https://www.npmjs.com/package/react-native-ble-plx)) |
| Verdict | ✅ | ⚠️ Classic is the weak link | ⚠️ Classic is the weak link |

### 1.3 Background / long-lived connection

You need the ELM327 link alive while the phone screen is off and while the car screen is in front.
Android 14+ requires a typed foreground service; the correct type is `connectedDevice`.
([Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[Android 14 FGS changes](https://developer.android.com/about/versions/14/changes/fgs-types-required))

- **Kotlin**: a `LifecycleService` with a coroutine scope, one `Flow` per PID. Native, boring, works.
- **Flutter**: needs `flutter_foreground_task` + a background isolate, and the community has
  documented failures combining it with BLE plugins when the app is backgrounded
  ([flutter_blue_plus#924](https://github.com/chipweinberger/flutter_blue_plus/issues/924),
  [flutter_reactive_ble#901](https://github.com/PhilipsHue/flutter_reactive_ble/issues/901)) plus
  OEM task-killer issues ([flutter_foreground_task#343](https://github.com/Dev-hwang/flutter_foreground_task/issues/343)).
  A second isolate is also an awkward place to own a socket that a native `CarAppService` must read.
- **React Native**: same class of problem, headless JS task.

### 1.4 CI / APK build simplicity

- **Kotlin**: `actions/setup-java` + `gradle/actions/setup-gradle` + `./gradlew assembleRelease`.
  No extra toolchain. Signing is a `signingConfigs` block reading env vars.
- **Flutter**: add `subosito/flutter-action`, `flutter build apk --release`; still Gradle underneath,
  plus a Flutter SDK download each run. Fine, just slower and one more version axis.
- **React Native**: Node + Yarn + Gradle + (Hermes) — the most moving parts and the most frequent
  breakage on RN upgrades.

### 1.5 Recommendation

> **Native Kotlin + Jetpack Compose, multi-module Gradle.**
>
> The app is (1) a hardware-protocol client, (2) a real-time gauge renderer, (3) an Android Auto
> template/surface client. All three are areas where cross-platform frameworks add a bridge and
> subtract capability. There is no iOS requirement, so the only argument for Flutter/RN — code
> sharing — does not apply. Choosing Flutter here means writing the Kotlin anyway.

---

## 2. Android Auto for a car-data dashboard

### 2.1 Allowed categories

A templated Android Auto app declares one or more categories in the `CarAppService` intent filter
([set-up-project](https://developer.android.com/training/cars/apps/library/set-up-project)):

| Category constant | Purpose |
|---|---|
| `androidx.car.app.category.NAVIGATION` | Turn-by-turn navigation. Only category allowed to use `NavigationTemplate`. |
| `androidx.car.app.category.POI` | Points of interest — parking, charging, fuel. |
| `androidx.car.app.category.IOT` | Act on connected devices from the car. |
| `androidx.car.app.category.WEATHER` | Weather at location / along route. |
| `androidx.car.app.category.MEDIA` | Templated media. |
| `androidx.car.app.category.MESSAGING`, `.CALLING` | Communication. |

**There is no "vehicle data" / "dashboard" / "diagnostics" category.** Google will not approve a
gauge-cluster app for *production* Play distribution under a category it does not fit, and the IoT
quality guidelines explicitly forbid setup-style tasks while driving
([Build an IoT app](https://developer.android.com/training/cars/apps/iot),
[Car app quality](https://developer.android.com/docs/quality-guidelines/car-app-quality)).

For a **personal** app this matters much less: internal-testing distribution is not form-factor
reviewed (§2.6). Practically, real OBD apps declare `IOT` and/or `NAVIGATION`.

### 2.2 How existing OBD2 apps get on the car screen

| App | Mechanism | Notes |
|---|---|---|
| **ObdGraphs** (open source, Apache-2.0) | Modern `CarAppService`; its `automotive/` manifest declares `IOT` **and** `NAVIGATION` **and** `FEATURE_CLUSTER`, requests `androidx.car.app.ACCESS_SURFACE` + `androidx.car.app.NAVIGATION_TEMPLATES`, and renders custom gauges onto the map `Surface` inside a `NavigationTemplate` | **This is the reference implementation.** Distributed via Play Store. [repo](https://github.com/tzebrowski/ObdGraphs) · [automotive manifest](https://raw.githubusercontent.com/tzebrowski/ObdGraphs/master/automotive/src/main/AndroidManifest.xml) · [NavTemplateCarScreen.kt](https://raw.githubusercontent.com/tzebrowski/ObdGraphs/master/automotive/src/main/java/org/obd/graphs/aa/screen/nav/NavTemplateCarScreen.kt) |
| **OBD2 Plugin for Android Auto / "OBD2AA" / AA Torque** (borconi) | *Legacy* path: the pre-2020 **unofficial gearhead API**. Users had to tap "About Android Auto" 10×, enable developer mode, tick **Unknown sources**, and start a headunit server. Reads data from Torque Pro via its plugin API | EOL 30 Nov 2022, source published "personal use only" (not an OSS licence). [GitHub](https://github.com/borconi/obd2aa) · [XDA thread](https://xdaforums.com/t/app-5-0-obd2-plugin-for-android-auto-use-torque-with-androidauto.3657805/) · [autoevolution](https://www.autoevolution.com/news/the-world-s-number-one-obd2-app-for-android-now-available-on-android-auto-with-a-catch-222189.html) |
| **Torque Pro** | Has no first-party AA UI; users pair it with the plugin above | [torque-bhp.com](https://torque-bhp.com/software/torque-android-obd2-adapters/) |
| **Car Scanner ELM OBD2** | No official Android Auto template app; phone-only | [Play](https://play.google.com/store/apps/details?id=com.ovz.carscanner) |
| **AAAD (Android Auto Apps Downloader)** | Not an app-rendering mechanism at all — an *installer* that side-installs curated APKs (Fermata, CarStream, Screen2Auto) and sets the installer package so Android Auto accepts them. Those apps are **media/parked/mirroring** apps, not Car App Library apps | [GitHub](https://github.com/shmykelsa/AAAD) |

### 2.3 The sideloading blocker (read this twice)

Android Auto has a developer setting **Unknown sources** (Settings → Connected devices → Connection
preferences → Android Auto → ⋮ → Developer settings → Unknown sources). Google's own testing doc
states:

> "This setting applies to media, messaging notifications, and parked apps but **doesn't apply to
> apps built using the Android for Cars App Library**."
> — [Test Android apps for cars](https://developer.android.com/training/cars/testing)

and

> "To test your app in real vehicles, you must install it from a trusted source such as Google Play…"

Consequences:

- Your `CarAppService` dashboard **will not appear in the head-unit launcher** if you install the
  APK by hand — no matter how the manifest is written. This is exactly the long-standing OsmAnd
  complaint ([OsmAnd#15400](https://github.com/osmandapp/OsmAnd/issues/15400),
  [F-Droid forum](https://forum.f-droid.org/t/osmand-and-android-auto/22590),
  [Google support thread](https://support.google.com/androidauto/thread/444013881/sideloaded-navigation-app-not-showing-in-android-auto-launcher-with-unknown-sources-enabled)).
- The old community workaround — install with the Play Store as the recorded installer,
  `adb shell pm install -i "com.android.vending" -r /data/local/tmp/app.apk` — **worked around
  Android 7 and is reported not to help on Android 11+**; the OsmAnd thread concludes "apparently,
  the installer name does not matter anymore". Try it, but do not plan around it.
- **The DHU is unaffected.** For development you enable developer mode on the phone, `adb install`
  your debug APK, `adb forward tcp:5277 tcp:5277`, and run `desktop-head-unit`. That is your daily
  loop and it costs nothing.
  ([DHU testing](https://developer.android.com/training/cars/testing/dhu))

### 2.4 What the car screen can actually show

Templates and their live-data properties
([Template restrictions](https://developer.android.com/training/cars/apps/library/template-restrictions),
[Refresh a template](https://developer.android.com/training/cars/apps/library/refresh-template)):

- **Task step limit: 5 templates per task.** Blowing the quota makes the host show an error and
  **close your app**.
- The final template of a task must be `NavigationTemplate`, `PaneTemplate`, `MessageTemplate`,
  `MediaPlaybackTemplate`, `SignInTemplate` or `LongMessageTemplate`.
- A *refresh* (same `Screen`, same template type, only content changed) does **not** consume quota;
  a *new* template does. You trigger refreshes with `Screen.invalidate()`, which makes the host call
  `onGetTemplate()` again.
- **`NavigationTemplate` resets the quota to maximum** — it is designed for a persistent, constantly
  updating surface. This is why it is the right host for a gauge cluster.
- Hosts throttle refreshes during driving ("adaptive task limits"): you may call `invalidate()`
  freely, but intermediate frames are coalesced and only the last one in each throttle window is
  drawn. Design for ~1–2 template refreshes/sec of *text*, not 30.
- Debug the step counter: AA developer mode → "Enable debug overlay"; AAOS →
  `adb shell setprop log.tag.CarApp.H.Dis VERBOSE`.

**The escape hatch — draw your own pixels.** With `androidx.car.app.category.NAVIGATION` +
`<uses-permission android:name="androidx.car.app.ACCESS_SURFACE"/>`, you implement `SurfaceCallback`
and register it via `AppManager.setSurfaceCallback()`. The host hands you a real `Surface` for the
map area and you `lockCanvas()` / draw / `unlockCanvasAndPost()` at your own frame rate — bypassing
the template refresh throttle entirely for that region.
([Draw maps](https://developer.android.com/training/cars/apps/library/draw-maps))
This is precisely what ObdGraphs' `SurfaceRendererScreen.renderFrame()` does, wrapped in a
`NavigationTemplate`.

If you want Compose instead of raw `Canvas`, **ComposableScreen** (MPL-2.0) hosts the car surface on
a virtual display and presents a `ComposeView` into it.
([Rallista/ComposableScreen](https://github.com/Rallista/ComposableScreen))
Otherwise, Compose is *not* supported on car templates.

**Low-effort fallback (category `IOT`)**: `GridTemplate` (Google's recommendation for IoT) of 4–8
tiles, or `PaneTemplate` with 4 rows of `title`/`text`, refreshed via `invalidate()` about once a
second. No surface permission, no navigation category, no custom drawing — good enough for
coolant/RPM/speed/voltage readouts and a fine first milestone.

### 2.5 Minimal car-module manifest

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:tools="http://schemas.android.com/tools">

    <!-- only needed for the custom-canvas gauge path -->
    <uses-permission android:name="androidx.car.app.ACCESS_SURFACE" android:required="false"/>
    <uses-permission android:name="androidx.car.app.NAVIGATION_TEMPLATES" android:required="false"/>

    <application>
        <!-- tells Android Auto this app has a car experience -->
        <meta-data android:name="com.google.android.gms.car.application"
                   android:resource="@xml/automotive_app_desc"/>

        <meta-data android:name="androidx.car.app.minCarApiLevel"
                   android:value="1"
                   tools:ignore="MetadataTagInsideApplicationTag"/>

        <service android:name=".car.ObdCarAppService" android:exported="true">
            <intent-filter>
                <action android:name="androidx.car.app.CarAppService"/>
                <category android:name="androidx.car.app.category.IOT"/>
                <category android:name="androidx.car.app.category.NAVIGATION"/>
            </intent-filter>
        </service>
    </application>
</manifest>
```

`res/xml/automotive_app_desc.xml`:

```xml
<automotiveApp>
    <uses name="template"/>
</automotiveApp>
```

([Add Android Auto support](https://developer.android.com/training/cars/apps/auto))

Gradle:

```kotlin
implementation("androidx.car.app:app:1.7.0")           // Car App API level 8, stable Jul 2025
implementation("androidx.car.app:app-projected:1.7.0") // Android Auto host glue
testImplementation("androidx.car.app:app-testing:1.7.0")
```

Avoid alpha/beta `car-app` artifacts in a build you install in a car — hosts update independently.
Note the CVE-2024-10382 fix landed in 1.7.0-beta03; do not pin anything older.
([Car App release notes](https://developer.android.com/jetpack/androidx/releases/car-app))

Also override `CarAppService.createHostValidator()`; the common pattern (from ObdGraphs) is
`ALLOW_ALL_HOSTS_VALIDATOR` in debug builds and the packaged host allowlist in release.

### 2.6 Realistic distribution plan for a personal Android Auto app

1. **Phase 1 (phone only).** Signed release APK from CI, install by hand. No Play account needed.
2. **Phase 2 (car UI development).** DHU + `adb install`. Free, fast, complete template fidelity.
3. **Phase 3 (real head unit).** Play Console account ($25 one-off) →
   Advanced settings → Form factors → **Add form factor → Android Auto** → upload to
   **Internal app sharing** (up to 100 testers, link valid 60 days, *no review*) or the
   **Internal testing track** (*no form-factor review*). Closed testing = non-blocking review;
   open testing/production = blocking review, which a diagnostics dashboard would likely fail on
   category grounds.
   ([Distribute to cars](https://developer.android.com/training/cars/distribute),
   [Internal app sharing](https://play.google.com/console/about/internalappsharing/))

Internal app sharing is genuinely the intended answer here: upload the APK, get a link, open it on
the phone, and Android Auto treats it as trusted.

**Sideloading policy heads-up (unrelated to Auto, but relevant to "ship an APK").**
Google is rolling out mandatory **Android developer verification** for apps installed on certified
devices — first countries September 2026, global from 2027. Carve-outs announced in Nov 2025: a free
"limited distribution" tier (no government ID, unlimited apps, **up to 20 devices**), an "experienced
user" advanced install flow, and an **exemption for installs via ADB**.
([Android Developers Blog, Nov 2025](https://android-developers.googleblog.com/2025/11/android-developer-verification-early.html),
[Console help](https://support.google.com/android-developer-console/answer/16561738),
[9to5Google](https://9to5google.com/2025/08/25/android-apps-developer-verification/))
For a one-person, one-phone project this is a non-issue, but factor it in before handing APKs to
friends in 2027.

---

## 3. Bluetooth on modern Android (API 31+)

### 3.1 Permissions

```xml
<!-- legacy, pre-Android 12 only -->
<uses-permission android:name="android.permission.BLUETOOTH"       android:maxSdkVersion="30"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30"/>

<!-- Android 12+ runtime permissions -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
                 android:usesPermissionFlags="neverForLocation"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>

<!-- only if you scan pre-31 devices; drop entirely if you never derive location -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
                 android:maxSdkVersion="30"/>

<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
```

- `BLUETOOTH_SCAN` → discovering adapters. `BLUETOOTH_CONNECT` → talking to already-paired devices
  *and* reading `BluetoothDevice.getName()` / the bonded-device list.
- `neverForLocation` lets you skip `ACCESS_FINE_LOCATION` on 12+ (it filters some beacon payloads —
  irrelevant for ELM327).
- Both are **runtime** permissions: request with
  `ActivityResultContracts.RequestMultiplePermissions` before touching the adapter.
- ([Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions))

### 3.2 Foreground service

```xml
<service
    android:name=".obd.ObdConnectionService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice"/>
```

- Android 14+ (`targetSdk 34`) requires the type **and** the matching
  `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission (auto-granted, normal-level). `BLUETOOTH_CONNECT`
  must already be granted at `startForeground()` time or the system throws.
  ([FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types),
  [Android 14 changes](https://developer.android.com/about/versions/14/changes/fgs-types-required))
- Start it from the Activity when the user hits "Connect"; keep a low-priority notification showing
  connection state + a couple of live values.
- Expect OEM battery managers (Xiaomi/Huawei/Samsung) to kill it anyway; offer a
  "disable battery optimisation" prompt (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) as ObdGraphs does.

### 3.3 Libraries

| Need | Pick | Licence |
|---|---|---|
| BLE with queueing, MTU, reconnect, retries | [NordicSemiconductor/Android-BLE-Library](https://github.com/NordicSemiconductor/Android-BLE-Library) (`no.nordicsemi.android:ble-ktx` for coroutines/Flow, ≥2.3) | BSD-3-Clause |
| BLE, newer coroutine-first API | [NordicSemiconductor/Kotlin-BLE-Library](https://github.com/NordicSemiconductor/Kotlin-BLE-Library) | BSD-3-Clause |
| BLE, lighter alternative | [weliem/blessed-android-coroutines](https://github.com/weliem/blessed-android-coroutines) | MIT |
| Classic RFCOMM | Platform `BluetoothSocket` — no library needed | — |

### 3.4 Transport abstraction (the one interface that matters)

Keep the OBD layer transport-agnostic. Every serious Kotlin/Java OBD library already assumes this
(`ObdDeviceConnection(inputStream, outputStream)` in kotlin-obd-api).

```kotlin
interface ObdTransport {
    suspend fun connect()
    suspend fun send(command: String)          // appends "\r"
    fun incoming(): Flow<String>               // one ELM327 response per emission, '>' stripped
    suspend fun close()
}
```

- **`RfcommTransport`** — `device.createRfcommSocketToServiceRecord(SPP_UUID)`, always
  `adapter.cancelDiscovery()` first, then blocking reads on `Dispatchers.IO`. Keep the well-known
  fallback `createRfcommSocket(int)` reflection hack for stubborn clones.
- **`BleTransport`** — scan → connect → discover services → pick notify/write characteristics
  (`FFF0/FFF1/FFF2`, `FFE0/FFE1`, Nordic UART), request MTU 247, then **reassemble** chunks until
  you see `>`. BLE writes may need splitting at MTU-3.

### 3.5 ELM327 session bring-up and throughput

Init sequence (send, wait for `>` between each):

```
ATZ      reset (slow, ~1s; some clones need 2)
ATE0     echo off      ← biggest single latency win
ATL0     linefeeds off
ATS0     spaces off    ← fewer bytes to parse
ATH0     headers off (ATH1 only if you decode multi-ECU replies)
ATSP0    auto protocol
ATAT2    adaptive timing, aggressive
0100     probe: triggers protocol detection, returns supported PIDs 01–20
```

Throughput reality check — this constrains the whole UI design:

- Genuine ELM327 silicon streams ~50–100 messages/s; **clones commonly manage 3–10 PIDs/s**, good
  adapters ~15–20 PIDs/s.
  ([Car Scanner: optimizing connection speed](https://www.carscanner.info/optimizing-connection-speed/),
  [RealDash forum](https://forum.realdash.net/t/elm327-data-refresh-rate/6172),
  [ELM327 datasheet](https://www.elmelectronics.com/wp-content/uploads/2016/07//ELM327DS.pdf))
- On CAN (ISO 15765-4) you may request **up to 6 PIDs in a single mode-01 message**
  (`01 0C 0D 05 0F 11 04`) — the single biggest throughput multiplier. Detect support by trying it
  and falling back on `NO DATA`/`?`.
- Append the expected frame count (`0100 1`) so the adapter stops waiting for more replies.
- Querying a *different* ECU forces re-addressing and costs extra round trips — group by ECU.

**Design implication:** a priority scheduler, not a fixed loop. Tier A (RPM, speed, throttle) at
~5 Hz, tier B (coolant, IAT, MAP, load) at ~1 Hz, tier C (fuel level, voltage, DTC count) at
0.1 Hz — all sharing one budget-limited round-robin so the queue never backs up.

---

## 4. Recommended architecture

```
:core-obd          pure Kotlin/JVM. PID definitions, encoders, response parsers,
                   DTC decoding. No Android imports → unit-testable on the JVM.
                   (either your own, or wrap com.github.eltonvs:kotlin-obd-api)

:transport         ObdTransport interface + RfcommTransport + BleTransport.
                   Android-only. Emits Flow<String>.

:data              ObdConnectionService (FGS, connectedDevice) owns:
                     - the single transport instance
                     - the priority PID scheduler (coroutine + Channel)
                     - VehicleStateRepository: MutableStateFlow<VehicleSnapshot>
                   Exposed as a @Singleton (Hilt) so *both* UIs read the same object.

:app (phone)       Jetpack Compose. collectAsStateWithLifecycle() on the repository.
                   Canvas-drawn gauges, adapter picker, PID selection, DTC screen,
                   trip logging.

:car               ObdCarAppService : CarAppService
                     └ ObdSession : Session
                         └ DashboardScreen : Screen
                             ├ IOT path:  GridTemplate/PaneTemplate + invalidate() @1Hz
                             └ NAV path:  NavigationTemplate + SurfaceCallback,
                                          renderFrame() @5-10 fps on the raw Canvas
```

Key points:

1. **`CarAppService` runs in your app's process.** No IPC, no bridging — inject the same
   `VehicleStateRepository` singleton into the car `Screen`. This is the structural advantage that
   Flutter/RN throw away.
2. **The service, not the UI, owns the connection.** Both the phone Activity and the car Session are
   observers with independent lifecycles; either can be gone while the other keeps the link.
3. **Bind, don't restart.** The car Session should `bindService()`/observe and start the FGS if it
   isn't running (e.g. AA connected while the phone app was never opened).
4. **Two render paths behind one flag.** Ship the `IOT`/`GridTemplate` path first (a day's work),
   add the `NavigationTemplate` + surface renderer once the data pipeline is proven. Pick at runtime
   from `CarContext.getCarAppApiLevel()` and whether `ACCESS_SURFACE` was granted.
5. **Feature-flag the car module** so a pure-sideload build (which cannot show on a head unit
   anyway, §2.3) stays lean.
6. Prototype offline with a **replay transport** that feeds recorded ELM327 traces — AndrOBD ships a
   demo mode for exactly this reason, and it removes the car from the dev loop.

---

## 5. Signed APK from GitHub Actions

### 5.1 Create the keystore once (locally, never in the repo)

```bash
keytool -genkeypair -v \
  -keystore release.jks -alias obd2dash \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass '<store-pass>' -keypass '<key-pass>' \
  -dname "CN=OBD2 Dashboard, O=Personal, C=PL"

openssl base64 -A < release.jks > release.jks.b64   # -A = no newlines
```

Store as **repository secrets**: `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
**Back up `release.jks` off-machine** — losing it means you can never update an installed app
(Android refuses upgrades across signing keys), and it permanently blocks a future Play upload.

### 5.2 `app/build.gradle.kts`

```kotlin
android {
    signingConfigs {
        create("release") {
            val ks = System.getenv("RELEASE_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias      = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword   = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { applicationIdSuffix = ".debug" }   // lets debug + release coexist on the phone
    }
}
```

Add `-keep class androidx.car.app.** { *; }` style rules only if R8 strips template classes; the
library ships consumer rules, but verify a minified build against the DHU before trusting it.

### 5.3 `.github/workflows/android.yml`

```yaml
name: Android build

on:
  push:
    branches: [main]
    tags: ['v*']
  pull_request:
  workflow_dispatch:

permissions:
  contents: write     # needed only by the release step

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - uses: gradle/actions/setup-gradle@v4     # caching + dependency graph

      - name: Unit tests
        run: ./gradlew test --stacktrace

      # ---- debug APK on every push: instantly installable, no secrets required ----
      - name: Build debug APK
        run: ./gradlew :app:assembleDebug

      - uses: actions/upload-artifact@v4
        with:
          name: obd2dash-debug
          path: app/build/outputs/apk/debug/*.apk
          retention-days: 14

      # ---- signed release APK ----
      - name: Decode keystore
        if: ${{ env.HAS_KEYSTORE == 'true' }}
        env:
          HAS_KEYSTORE: ${{ secrets.KEYSTORE_B64 != '' }}
          KEYSTORE_B64: ${{ secrets.KEYSTORE_B64 }}
        run: echo "$KEYSTORE_B64" | base64 -d > "$RUNNER_TEMP/release.jks"

      - name: Build release APK
        env:
          RELEASE_KEYSTORE:       ${{ runner.temp }}/release.jks
          RELEASE_STORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          RELEASE_KEY_ALIAS:      ${{ secrets.KEY_ALIAS }}
          RELEASE_KEY_PASSWORD:   ${{ secrets.KEY_PASSWORD }}
        run: ./gradlew :app:assembleRelease

      - uses: actions/upload-artifact@v4
        with:
          name: obd2dash-release
          path: app/build/outputs/apk/release/*.apk

      # ---- tagged builds become a GitHub Release: a stable URL you open on the phone ----
      - name: Publish release
        if: startsWith(github.ref, 'refs/tags/v')
        uses: softprops/action-gh-release@v2
        with:
          files: app/build/outputs/apk/release/*.apk
```

Notes:

- **Debug vs release.** The debug APK is signed with the auto-generated debug key: fine for `adb`
  and DHU work, but it has a different signature (so it cannot upgrade a release install) and cannot
  be uploaded to Play. Keep both, with `applicationIdSuffix`.
- **`upload-artifact` produces a ZIP**, and downloading it requires being logged into GitHub — mildly
  annoying on a phone. The **GitHub Release asset** path gives a direct `.apk` URL: tag, wait, tap.
- Gradle already runs `zipalign` + `apksigner` (v2/v3 scheme) for a configured `signingConfig`; the
  manual `jarsigner`/`zipalign` dance in older blog posts is unnecessary.
- If you prefer signing outside Gradle, [`r0adkll/sign-android-release`](https://github.com/marketplace/actions/sign-android-release)
  takes `signingKeyBase64`/`alias`/`keyStorePassword` and outputs `signedReleaseFile`.
- For Phase 3, add a Play upload step (`r0adkll/upload-google-play` with `track: internal`) using a
  service-account JSON secret; that is the only route to the head unit.

---

## 6. Open-source projects worth mining

| Project | What to take | Licence |
|---|---|---|
| [tzebrowski/ObdGraphs](https://github.com/tzebrowski/ObdGraphs) | **The closest thing to this project that exists.** Kotlin, modular (`datalogger`, `screen_renderer`, `automotive`), real `CarAppService` with `NavigationTemplate` + surface gauge rendering, host validator pattern, FGS + battery-optimisation handling | Apache-2.0 |
| [tzebrowski/ObdMetrics](https://github.com/tzebrowski/ObdMetrics) | Java OBD2 framework under ObdGraphs; PID registry as JSON, adaptive polling, ELM327/STNxxxx quirks. Maven `io.github.tzebrowski:obd-metrics:9.1.0` | Apache-2.0 |
| [eltonvs/kotlin-obd-api](https://github.com/eltonvs/kotlin-obd-api) | Pure-Kotlin, transport-agnostic (`ObdDeviceConnection(input, output)`), clean command/response model. `com.github.eltonvs:kotlin-obd-api:1.4.1`. Best starting point for `:core-obd` | Apache-2.0 |
| [fr3ts0n/AndrOBD](https://github.com/fr3ts0n/AndrOBD) | Mature, actively maintained, BT/USB/Wi-Fi transports, huge PID/DTC database, **demo/replay mode**, CSV export, [plugin API](https://github.com/fr3ts0n/AndrOBD-Plugin). Note: GPL-3.0 — copying code makes your app GPL; reading it for protocol quirks does not | GPL-3.0 |
| [barnhill/AndroidOBD](https://github.com/barnhill/AndroidOBD) | Small Kotlin/Java ELM327-over-Bluetooth library, permissive licence | MIT |
| [pires/obd-java-api](https://github.com/pires/obd-java-api) | The ancestor of most OBD-on-Android code. **Archived Nov 2017** — reference only. Companion app: [android-obd-reader](https://github.com/pires/android-obd-reader) | Apache-2.0 |
| [android/car-samples](https://github.com/android/car-samples) | Google's official Car App Library showcase — canonical template usage, host validator, surface rendering | Apache-2.0 |
| [Rallista/ComposableScreen](https://github.com/Rallista/ComposableScreen) | Render Compose onto the car surface via a virtual display — write car gauges as `@Composable`s | MPL-2.0 |
| [NordicSemiconductor/Android-BLE-Library](https://github.com/NordicSemiconductor/Android-BLE-Library) / [Kotlin-BLE-Library](https://github.com/NordicSemiconductor/Kotlin-BLE-Library) | Battle-tested BLE state machine, coroutines/Flow API | BSD-3-Clause |
| [weliem/blessed-android-coroutines](https://github.com/weliem/blessed-android-coroutines) | Lighter coroutine BLE alternative | MIT |
| [evrenonur/obd2-elm327-pid-reference](https://github.com/evrenonur/obd2-elm327-pid-reference) | Complete mode-01 PID table with formulas — saves days of transcription | (check repo) |
| [borconi/obd2aa](https://github.com/borconi/obd2aa) | Historical: how OBD data reached AA before the Car App Library. **"Personal use only" — not an OSS licence**, do not copy code | Source-available |
| [marouenklai-android/smart-obd-android](https://github.com/marouenklai-android/smart-obd-android) | Small modern reference: Kotlin + Compose + ELM327 | (check repo) |

---

## 7. Risks and open questions

1. **Head-unit access is gated by Play, not by code.** Budget the $25 Play Console fee and the
   internal-app-sharing workflow, or accept DHU-only. This is the single most likely thing to derail
   the plan; validate it early with a hello-world `PaneTemplate` before building the real dashboard.
2. **Category fit.** `IOT` is a stretch for a gauge cluster and `NAVIGATION` is a fiction. Fine for
   internal testing; a blocker for public Play release. Do not build a business case on it.
3. **Refresh throttling.** Templates are throttled while driving. If the gauge feel matters, the
   `NavigationTemplate` + `SurfaceCallback` path is mandatory, not optional.
4. **Adapter lottery.** ELM327 clones vary wildly in firmware, throughput and BLE GATT layout. Build
   the adapter-profile abstraction (init string overrides, PID batch size, timeouts) from day one.
5. **`car-app` host compatibility.** Templates are rendered by the *Android Auto app on the phone*,
   whose version you don't control. Guard new APIs with `@RequiresCarApi` and
   `CarContext.getCarAppApiLevel()`.
6. **Developer verification (2026–2027).** Free "limited distribution" tier caps you at 20 devices;
   ADB installs are exempt. Not a problem for personal use, worth watching.

---

## Sources

Android for Cars / Android Auto
- https://developer.android.com/training/cars/apps
- https://developer.android.com/training/cars/apps/library/set-up-project
- https://developer.android.com/training/cars/apps/library/template-restrictions
- https://developer.android.com/training/cars/apps/library/refresh-template
- https://developer.android.com/training/cars/apps/library/draw-maps
- https://developer.android.com/training/cars/apps/iot
- https://developer.android.com/training/cars/apps/navigation
- https://developer.android.com/training/cars/apps/auto
- https://developer.android.com/training/cars/testing
- https://developer.android.com/training/cars/testing/dhu
- https://developer.android.com/training/cars/distribute
- https://developer.android.com/docs/quality-guidelines/car-app-quality
- https://developer.android.com/jetpack/androidx/releases/car-app
- https://developer.android.com/reference/androidx/car/app/CarAppService
- https://play.google.com/console/about/internalappsharing/
- https://android-developers.googleblog.com/2025/05/android-for-cars-google-io-2025.html

Bluetooth / background work
- https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/about/versions/14/changes/fgs-types-required
- https://github.com/NordicSemiconductor/Android-BLE-Library
- https://github.com/NordicSemiconductor/Kotlin-BLE-Library
- https://github.com/weliem/blessed-android-coroutines

Cross-platform reality checks
- https://github.com/googlemaps/flutter-navigation-sdk/blob/main/doc/android-auto.md
- https://github.com/birkir/react-native-carplay/blob/master/AndroidAuto.md
- https://github.com/Shopify/react-native-android-auto/blob/master/android/src/main/java/com/shopify/rnandroidauto/CarService.java
- https://www.npmjs.com/package/@iternio/react-native-auto-play
- https://github.com/kenjdavidson/react-native-bluetooth-classic
- https://www.npmjs.com/package/react-native-ble-plx
- https://github.com/chipweinberger/flutter_blue_plus/issues/924
- https://github.com/PhilipsHue/flutter_reactive_ble/issues/901
- https://github.com/Dev-hwang/flutter_foreground_task/issues/343

Sideloading & existing OBD/AA apps
- https://support.google.com/androidauto/thread/444013881/sideloaded-navigation-app-not-showing-in-android-auto-launcher-with-unknown-sources-enabled
- https://github.com/osmandapp/OsmAnd/issues/15400
- https://forum.f-droid.org/t/osmand-and-android-auto/22590
- https://github.com/shmykelsa/AAAD
- https://github.com/borconi/obd2aa
- https://xdaforums.com/t/app-5-0-obd2-plugin-for-android-auto-use-torque-with-androidauto.3657805/
- https://www.autoevolution.com/news/the-world-s-number-one-obd2-app-for-android-now-available-on-android-auto-with-a-catch-222189.html
- https://www.xda-developers.com/useful-sideloaded-android-auto-apps-you-wont-find-in-the-play-store/
- https://android-developers.googleblog.com/2025/11/android-developer-verification-early.html
- https://support.google.com/android-developer-console/answer/16561738

ELM327 / OBD2
- https://www.elmelectronics.com/wp-content/uploads/2016/07//ELM327DS.pdf
- https://www.carscanner.info/optimizing-connection-speed/
- https://forum.realdash.net/t/elm327-data-refresh-rate/6172
- https://github.com/evrenonur/obd2-elm327-pid-reference
- https://github.com/rubenmuehlhans/esphome-obd2-ble
- https://github.com/vdvornichenko/obd-ble-serial

Reference implementations
- https://github.com/tzebrowski/ObdGraphs
- https://raw.githubusercontent.com/tzebrowski/ObdGraphs/master/automotive/src/main/AndroidManifest.xml
- https://github.com/tzebrowski/ObdMetrics
- https://github.com/eltonvs/kotlin-obd-api
- https://github.com/fr3ts0n/AndrOBD
- https://github.com/barnhill/AndroidOBD
- https://github.com/pires/obd-java-api
- https://github.com/android/car-samples
- https://github.com/Rallista/ComposableScreen

CI / signing
- https://github.com/marketplace/actions/sign-android-release
- https://github.com/gradle/actions
