<!--
Google Play requires the privacy policy to be reachable at a public URL
(entered in Play Console under App content -> Privacy policy).
Hosting options that work:
  - GitHub Pages for this repo (Settings -> Pages), then link the rendered page
  - a raw link to this file, e.g.
    https://raw.githubusercontent.com/<owner>/obd2-dashboard/master/docs/play/privacy-policy.md
The URL must be public (no login), and must stay live for as long as the app is on Play.
-->

# Privacy Policy — OBD2 Dashboard

**Effective date:** 2026-08-13
**App:** OBD2 Dashboard (`com.miskibin.obd2dashboard`)
**Contact:** skibinek109@gmail.com

## The short version

OBD2 Dashboard has **no internet access at all**. The app does not declare the
Android `INTERNET` permission, so it is technically incapable of sending
anything to us or to anyone else. Everything the app records stays on your
phone unless you yourself share a file.

## What the app stores, and where

All of the following is stored only in the app's private storage on your device:

- **Trip recordings** — CSV files with the engine parameters logged during a
  recording (RPM, speed, temperatures, and so on).
- **Fault-code history** — diagnostic trouble codes read from your car, with
  when they were first and last seen and the freeze-frame data the ECU reports.
- **Vehicle profile** — the vehicle's VIN (read from the car over the OBD2
  port) and the details you enter yourself (fuel type, displacement, mass,
  etc.). The VIN is decoded entirely offline using a table bundled with the
  app; it is never looked up online.
- **Settings** — your dashboard layout and app preferences.

None of this is transmitted anywhere. There is no server side to this app.

## Bluetooth

Bluetooth is used for exactly one thing: finding and talking to your OBD2
adapter (an ELM327-compatible dongle plugged into the car).

- On Android 12 and newer, the app declares `BLUETOOTH_SCAN` with the
  `neverForLocation` flag, which tells Android the scan may not be used to
  derive your location — the system strips location-relevant results
  accordingly. The app never receives or infers location from Bluetooth.
- On Android 11 and older only, Android itself requires the location
  permission (`ACCESS_FINE_LOCATION`) to perform a Bluetooth scan. The app
  requests it there solely because the OS demands it for scanning; it does not
  read GPS, does not compute a location, and the permission is declared with
  `maxSdkVersion="30"` so it does not even exist on modern Android versions.

## When data leaves your device

Only when you explicitly make it leave. You can export a trip as a CSV file
through the standard Android share sheet; the app grants the receiving app you
choose read access to that single file and nothing else. Where it goes from
there (email, messenger, cloud drive) is your choice and is governed by the
app you send it to.

Android's own system backup may include the app's settings (preferences) in
your device backup, handled and encrypted by Android under your Google
account; trip files are not included in that backup.

## What the app does not do

- No user accounts, no sign-in.
- No analytics, no crash reporting, no telemetry.
- No advertising.
- No third-party SDKs that communicate over the network. The app's only
  dependencies are the standard Android (AndroidX/Jetpack) and Kotlin
  libraries, which run locally.
- No collection of personal data of any kind.

## Deleting your data

Everything the app knows lives in its app storage. Individual trip recordings
can be deleted inside the app (open a trip → Delete). Uninstalling the app, or
using Android's *Settings → Apps → OBD2 Dashboard → Clear storage*, removes
all of it permanently. There is nothing to delete on any server, because
nothing was ever sent to one.

## Children

The app is a vehicle diagnostic utility and is not directed at children.

## Changes

If a future version ever changes any of the above (for example, an online
feature), this policy will be updated and the effective date changed before
that version ships.

---
---

# Polityka prywatności — OBD2 Dashboard

**Data obowiązywania:** 2026-08-13
**Aplikacja:** OBD2 Dashboard (`com.miskibin.obd2dashboard`)
**Kontakt:** skibinek109@gmail.com

## W skrócie

OBD2 Dashboard **w ogóle nie ma dostępu do internetu**. Aplikacja nie
deklaruje uprawnienia `INTERNET`, więc technicznie nie jest w stanie niczego
nikomu wysłać. Wszystko, co aplikacja zapisuje, zostaje na Twoim telefonie —
chyba że sam(a) udostępnisz plik.

## Co aplikacja przechowuje i gdzie

Wszystko poniższe jest zapisywane wyłącznie w prywatnej pamięci aplikacji na
Twoim urządzeniu:

- **Zapisy tras** — pliki CSV z parametrami silnika zarejestrowanymi podczas
  nagrywania (obroty, prędkość, temperatury itd.).
- **Historia kodów błędów** — kody usterek odczytane z samochodu, wraz z datą
  pierwszego i ostatniego wystąpienia oraz danymi freeze frame z ECU.
- **Profil pojazdu** — numer VIN (odczytany z samochodu przez złącze OBD2)
  oraz dane wpisane przez Ciebie (rodzaj paliwa, pojemność, masa itd.). VIN
  jest dekodowany całkowicie offline, na podstawie tabeli wbudowanej w
  aplikację; nigdy nie jest sprawdzany w internecie.
- **Ustawienia** — układ pulpitu i preferencje aplikacji.

Nic z tego nie jest nigdzie przesyłane. Ta aplikacja nie ma żadnego serwera.

## Bluetooth

Bluetooth służy dokładnie do jednego: znalezienia adaptera OBD2 (dongla
zgodnego z ELM327 wpiętego w samochód) i komunikacji z nim.

- Na Androidzie 12 i nowszych aplikacja deklaruje `BLUETOOTH_SCAN` z flagą
  `neverForLocation`, która mówi systemowi, że skanowanie nie może służyć do
  ustalania lokalizacji. Aplikacja nigdy nie odczytuje ani nie wylicza
  lokalizacji na podstawie Bluetooth.
- Wyłącznie na Androidzie 11 i starszych sam system wymaga uprawnienia
  lokalizacji (`ACCESS_FINE_LOCATION`) do skanowania Bluetooth. Aplikacja
  prosi tam o nie tylko dlatego, że system tego żąda; nie odczytuje GPS, nie
  wyznacza pozycji, a uprawnienie jest zadeklarowane z `maxSdkVersion="30"`,
  więc na nowszych Androidach w ogóle nie istnieje.

## Kiedy dane opuszczają urządzenie

Tylko wtedy, gdy wyraźnie je udostępnisz. Trasę można wyeksportować jako plik
CSV przez standardowy systemowy arkusz udostępniania; wybrana przez Ciebie
aplikacja dostaje dostęp tylko do tego jednego pliku (tylko do odczytu).
Dalszy los pliku (e-mail, komunikator, dysk w chmurze) zależy od Ciebie i od
regulaminu aplikacji, do której go wyślesz.

Systemowa kopia zapasowa Androida może objąć ustawienia aplikacji
(preferencje) — obsługuje ją i szyfruje sam Android w ramach Twojego konta
Google; pliki tras nie wchodzą w skład tej kopii.

## Czego aplikacja nie robi

- Brak kont użytkowników, brak logowania.
- Brak analityki, raportowania błędów i telemetrii.
- Brak reklam.
- Brak zewnętrznych SDK komunikujących się przez sieć. Jedyne zależności to
  standardowe biblioteki Androida (AndroidX/Jetpack) i Kotlina, działające
  lokalnie.
- Brak zbierania jakichkolwiek danych osobowych.

## Usuwanie danych

Wszystko, co aplikacja wie, znajduje się w jej pamięci na urządzeniu.
Pojedyncze nagrania tras można usuwać w samej aplikacji (otwórz trasę →
Usuń). Odinstalowanie aplikacji lub użycie *Ustawienia → Aplikacje → OBD2
Dashboard → Wyczyść dane* usuwa wszystko trwale. Na żadnym serwerze nie ma
nic do usunięcia, bo nic nigdy nie zostało wysłane.

## Dzieci

Aplikacja jest narzędziem diagnostycznym pojazdu i nie jest skierowana do
dzieci.

## Zmiany

Jeżeli przyszła wersja miałaby zmienić cokolwiek z powyższych (np. funkcję
online), polityka zostanie zaktualizowana, a data obowiązywania zmieniona,
zanim taka wersja zostanie wydana.
