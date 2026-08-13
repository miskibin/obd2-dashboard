# Play Store listing — ready to paste

Character limits are Play Console limits. Counts for the texts below are noted
next to each item (verified; re-count if you edit).

## English (default listing, en-US)

### App title (max 30 chars)

```
OBD2 Dashboard
```
(14 chars)

### Short description (max 80 chars)

```
Live engine data, fault codes and trip logs from your car over Bluetooth OBD2.
```
(78 chars)

### Full description (max 4000 chars)

```
OBD2 Dashboard turns your phone into a live gauge cluster and a fault-code reader for your car.

IMPORTANT: you need a Bluetooth ELM327-compatible OBD2 adapter (sold separately, from about 10 EUR). The app was built for the vgate iCar2 Bluetooth LE adapter and works with common ELM327 clones, over both Bluetooth LE and classic Bluetooth (SPP). Without an adapter plugged into your car's OBD2 port, the app can only run in demo mode.

LIVE DASHBOARD
A hero card with revs, road speed and the estimated gear, followed by a list of live parameters, each shown against the range it is normally in. Tap any row to see its last minute plotted against that range.

CHARTS
Plot any parameter from the catalogue, up to six at once: a strip per parameter, one shared 0-100% axis, or one shared axis in real units.

FAULT CODES
Read stored, pending and permanent diagnostic trouble codes with descriptions, and clear them. Each code gets its own screen: when it was first and last seen, which codes appeared around it, the ECU freeze frame, and - if the app was connected at the time - the 30 seconds of engine data either side of the moment it appeared.

TRIPS
Record a drive and get it back as distance, fuel consumption, maxima and notable events (oil temperature over its limit, rev limiter reached), each with the second it happened. Export any trip as a CSV file.

VEHICLE PROFILE
Profiles are keyed by the car's VIN, not the adapter, so one adapter moved between two cars means two separate profiles. The VIN is decoded offline from a bundled table - nothing is looked up online.

HONEST NUMBERS
Every value the app computed rather than read carries a "~" mark, and the detail sheet names the assumption behind it (for example the assumed fuel type behind a consumption figure). Values whose source has gone quiet dim instead of pretending to be fresh.

PRIVACY
The app has no internet permission - it is technically unable to send data anywhere. Trips, fault-code history and settings stay on your phone; data leaves the device only if you export a file yourself.

Interface in English and Polish.

Works with cars supporting the OBD2 standard (petrol cars roughly since 2001, diesels since 2004 in the EU). Which parameters are available depends on your car. Not affiliated with any vehicle or adapter manufacturer.
```
(2315 chars)

## Polish (pl-PL)

### Tytuł aplikacji (max 30 znaków)

```
OBD2 Dashboard
```
(14 znaków — ta sama nazwa co w aplikacji, `values-pl` nie tłumaczy jej)

### Krótki opis (max 80 znaków)

```
Parametry silnika na żywo, kody błędów i zapisy tras przez Bluetooth OBD2.
```
(74 znaki)

### Pełny opis (max 4000 znaków)

```
OBD2 Dashboard zamienia telefon w zestaw zegarów na żywo i czytnik kodów błędów Twojego samochodu.

WAŻNE: potrzebny jest adapter OBD2 Bluetooth zgodny z ELM327 (kupowany osobno, od ok. 40 zł). Aplikacja powstała pod adapter vgate iCar2 Bluetooth LE i działa z popularnymi klonami ELM327, zarówno przez Bluetooth LE, jak i klasyczny Bluetooth (SPP). Bez adaptera wpiętego w złącze OBD2 auta aplikacja działa tylko w trybie demo.

PULPIT NA ŻYWO
Główna karta z obrotami, prędkością i szacowanym biegiem, a pod nią lista parametrów, każdy na tle zakresu, w którym normalnie się mieści. Dotknięcie wiersza pokazuje ostatnią minutę przebiegu na tle tego zakresu.

WYKRESY
Dowolny parametr z katalogu, do sześciu naraz: osobny pasek na parametr, wspólna oś 0-100% albo wspólna oś w jednostkach.

KODY BŁĘDÓW
Odczyt zapisanych, oczekujących i trwałych kodów usterek z opisami oraz ich kasowanie. Każdy kod ma własny ekran: kiedy pojawił się pierwszy i ostatni raz, jakie kody wystąpiły obok niego, freeze frame z ECU, a jeśli aplikacja była wtedy połączona - 30 sekund danych silnika wokół momentu wystąpienia.

TRASY
Nagraj przejazd i zobacz go jako dystans, spalanie, wartości maksymalne i zdarzenia warte odznaki (olej powyżej limitu, odcięcie obrotów), każde z sekundą wystąpienia. Każdą trasę można wyeksportować jako plik CSV.

PROFIL POJAZDU
Profile są przypisane do numeru VIN auta, nie do adaptera - jeden adapter przekładany między dwoma autami to dwa osobne profile. VIN jest dekodowany offline z wbudowanej tabeli - nic nie jest sprawdzane w internecie.

UCZCIWE LICZBY
Każda wartość wyliczona, a nie odczytana, nosi znak "~", a arkusz szczegółów podaje założenie, które za nią stoi (np. przyjęty rodzaj paliwa przy spalaniu). Wartości, których źródło zamilkło, przygasają, zamiast udawać świeże.

PRYWATNOŚĆ
Aplikacja nie ma uprawnienia dostępu do internetu - technicznie nie jest w stanie niczego nigdzie wysłać. Trasy, historia kodów i ustawienia zostają w telefonie; dane opuszczają urządzenie tylko wtedy, gdy sam(a) wyeksportujesz plik.

Interfejs po polsku i po angielsku.

Działa z autami obsługującymi standard OBD2 (benzynowe mniej więcej od 2001 r., diesle od 2004 r. w UE). Dostępne parametry zależą od auta. Aplikacja nie jest powiązana z żadnym producentem pojazdów ani adapterów.
```
(2300 znaków)

## Graphic assets to prepare

Take screenshots in **demo mode** (Demo button on the connection screen) — no
car needed, and the simulated data animates all screens.

Screens worth capturing (in this order of priority):
1. Live dashboard — hero card with revs/speed/gear plus the parameter list
2. Charts — several parameters at once (shows the three axis modes best)
3. Fault codes list, and one code's detail screen (freeze frame + timeline)
4. Trip detail — distance/consumption summary with event badges
5. Vehicle profile screen
6. Parameter detail sheet (the "~" honesty marker with its explanation)

Play Console requirements:

| Asset | Requirement |
|---|---|
| App icon | 512 x 512 px, 32-bit PNG, max 1 MB (Play uses this, not the APK icon) |
| Feature graphic | 1024 x 500 px, PNG or JPEG — required |
| Phone screenshots | 2-8 images, 16:9 or 9:16, each side 320-3840 px |
| 7" tablet screenshots | up to 8; required only if you want tablet featuring/quality badge |
| 10" tablet screenshots | up to 8; same note |

Take one screenshot set per language (EN and PL) if you localize screenshots;
otherwise one neutral set is acceptable. Landscape dashboard shots show the
app well since the activity supports full sensor rotation.
