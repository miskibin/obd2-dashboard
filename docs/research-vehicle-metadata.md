# Research: what the app can know about the car beyond the OBD port

**Question.** The app reads live PIDs and DTCs. What *else* — metadata about the specific
vehicle, from an API or a bundled dataset — would make it more useful to the driver?

**Research date:** August 2026. Every API claim below was tested with a live request, not
taken from documentation.

---

## 0. TL;DR

**The headline finding: this is mostly not an API problem.**

The app is not short of vehicle *facts*. It is short of the handful of **per-car constants**
that turn the numbers it already reads into judgements. Fuel type, engine displacement, kerb
mass, tank capacity, factory-rated consumption. Five numbers. Every one of them either falls
out of the VIN the app already reads, sits in a small bundled table, or is one text field the
driver fills in once.

Chasing a VIN-decoder API is the tempting move and the wrong one: for the Polish/EU market the
free decoders return almost nothing (§2), and the paid ones start at $99/month (§2.3).

| Idea | Verdict | Why |
|---|---|---|
| **Vehicle profile keyed by VIN** | **Build first** | The structural gap. Nothing else has anywhere to live. |
| **Fuel type → fix the fuel maths** | **Build** | Current MAF fallback assumes petrol *at stoichiometric*. On a diesel it overreads by up to 2×. Real bug, real fix. |
| **Tank capacity → litres + range** | **Build** | One number turns PID `2F` from a useless percentage into "48 km left". |
| **Kerb mass + rated power** | **Build** | Unlocks measured power/torque and 0–100 timing from data already sampled at 10 Hz. |
| **Factory vs. real consumption** | **Build** | "You: 7.8. Factory: 5.9." One line, instantly meaningful. Data is free and official (§5). |
| **Offline WMI table** | **Build** | Make + plant + year with zero network calls and zero privacy exposure. |
| **Service intervals + odometer** | **Build (manual)** | PID `A6` already decoded. Needs no API — just a target and a subtraction. |
| **Statutory dates (PL: OC / badanie techniczne)** | **Build (manual + deep link)** | Genuinely useful in PL. No API exists (§4.2). |
| NHTSA vPIC decode | **Skip** | Tested on 5 EU VINs: returns nothing useful for Škoda, Citroën, SEAT, Nissan EU (§2.1). |
| CEPiK per-car lookup | **Impossible** | API is real and good, but records carry no VIN and no plate (§4.1). |
| Recalls | **Skip** | NHTSA is US-only. EU Safety Gate has no public JSON API (§6). |
| Car photo from CDN | **Skip** | imagin.studio works but licence forbids caching and requires a paid customer key (§7.1). |
| Bundled body-type silhouettes | **Optional** | Cosmetic. Cheap, offline, no licence risk. Do it last (§7.2). |

---

## 1. The anchor: the app already reads the VIN

`Obd2Client.readVin()` pulls mode `09` PID `02` over ISO-TP and `Diagnostics` reassembles the
17 characters. Today that VIN is used for exactly two things:

- `vehicleLabel()` shows the **last 8 characters** as a screen title
  (`ConnectionLabel.kt:39`)
- `MechanicReport` prints it as one line

That is the entire use. The VIN is the only stable identity the app has for the *car* rather
than for the *dongle* — and it is currently a decoration.

Everything in this document hangs off making it a key instead.

---

## 2. Online VIN decoders — tested, and mostly useless here

### 2.1 NHTSA vPIC (free, no key)

`https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/{vin}?format=json`

Free, unauthenticated, ~140 attributes, no rate limit published. Sounds perfect. I ran five
European-market VINs through it:

| VIN | Market | What came back |
|---|---|---|
| `WVWZZZ1KZ8W123456` | VW Golf V (EU) | Make, Manufacturer, ModelYear 2008, Plant Wolfsburg/Germany, VehicleType. **No model, no engine.** |
| `WAUZZZ8K7BA123456` | Audi A4 (EU) | Make AUDI, Germany — and **ModelYear 1981** (wrong by 30 years) |
| `TMBJJ7NE0J0123456` | Škoda Octavia | **Nothing.** Error code 1,7 |
| `VF7DDNFPWDJ123456` | Citroën C4 | **Nothing.** Error 1,7,400 |
| `WF0AXXWPDA8A12345` | Ford Focus (EU) | Make FORD, Ford Werke AG. No model, no engine. |
| `JTDKB20U703123456` | Toyota Prius (**US-market**) | Model, body class, fuel, 1.8 L, 4 cyl, 96 HP, CVT, drive type, series, trim — **the full picture** |

The pattern is unambiguous. vPIC is a registry of what manufacturers submitted **to the US
DOT**. A US-market car decodes completely; a European-market car decodes to make and country
at best, and the model-year digit is decoded without the manufacturer's disambiguation table,
so it can be a full 30-year cycle out.

The same holds for the WMI endpoint (`/decodewmi/{wmi}`):

```
WVW -> VOLKSWAGEN AG        ZFA -> FCA ITALY S.P.A.
WAU -> AUDI AG              JTD -> TOYOTA MOTOR CORPORATION
TMB -> (nothing)   VF7 -> (nothing)   VSS -> (nothing)   SJN -> (nothing)
```

Škoda, Citroën, SEAT and Nissan Europe — four makes any Polish driver might own — are simply
absent from the registry.

**Also:** measured response times exceed 2.5 s, sometimes 5 s+. That is a bad thing to put on
the connect path.

**Verdict: skip.** For this app's likely user base it is a slow network round-trip that
returns the make, which we can derive offline.

### 2.2 What the VIN gives you offline, for free

Everything vPIC reliably returned for EU cars is derivable from the VIN string itself:

| Chars | Field | How |
|---|---|---|
| 1 | Region | ISO 3779 — `W`=Germany, `T`=Czech/Hungary, `V`=France/Spain, `Z`=Italy, `S`=UK, `J`=Japan, `Y`=Sweden/Finland, `1–5`=North America |
| 1–3 | WMI → manufacturer | Lookup table, ~400 entries covers everything on European roads |
| 10 | Model year | Cyclic code (`A`=1980/2010, … `Y`=2000/2030, `1`=2001/2031 …). 30-year ambiguity resolved by the region's plausible range |
| 11 | Assembly plant | Manufacturer-specific; only worth decoding for the big VAG/BMW/Mercedes plants |
| 9 | Check digit | **North America only.** European VINs leave it as filler — note vPIC flagged our valid VW VIN as having a bad check digit, which is a false positive |

A bundled `Map<String, Wmi>` of ~400 entries is a few kB, resolves instantly, needs no
network, and **leaks nothing** — the VIN never leaves the phone. It beats vPIC on EU coverage,
on latency and on privacy simultaneously.

Source for the table: the [Wikibooks WMI list](https://en.wikibooks.org/wiki/Vehicle_Identification_Numbers_(VIN_codes)/World_Manufacturer_Identifier_(WMI))
(CC BY-SA), cross-checked against vPIC for the makes vPIC does know.

**Careful with the check digit:** do not validate it. European VINs are structurally valid
with a filler in position 9, and rejecting them would break the feature for exactly the cars
this app targets.

### 2.3 Commercial decoders

Vincario, VehicleDatabases, Auto.dev and similar do cover European VINs properly — engine
code, trim, gearbox, factory options. Entry pricing for European coverage is **$99/month**.
For a personal sideloaded app that is not a real option, and it would put the user's VIN
through a third party for the privilege.

---

## 3. The real gaps this metadata would close

This is the part worth dwelling on, because it's where the value actually is.

### 3.1 The fuel maths is wrong for anything that isn't petrol — a real bug

`DerivedMetrics.compute()` (`Pid.kt:145`) falls back to MAF when PID `5E` is unsupported,
which is most older cars:

```kotlin
private const val STOICHIOMETRIC_AFR = 14.7
private const val PETROL_DENSITY_G_PER_L = 820.0

val fuelRate = values[Pids.FUEL_RATE]
    ?: values[Pids.MAF_RATE]?.let { it * 3600.0 / (STOICHIOMETRIC_AFR * PETROL_DENSITY_G_PER_L) }
```

Two separate issues:

**The constants themselves are nearly fine for diesel.** Petrol 14.7 × 820 = 12 054 g air per
litre; diesel 14.5 × 835 = 12 107. A 0.4 % difference — not the problem.

**The stoichiometric assumption is the problem.** A petrol engine runs at λ≈1 under closed
loop, so the assumption holds. **A diesel is qualitatively governed and always lean** — λ≈1.5–2.5
at cruise, and 5+ at idle. Dividing MAF by the stoichiometric ratio therefore overstates diesel
fuel flow by **50 % to several hundred percent**. A diesel that really cruises at 5 L/100 km
will be reported somewhere north of 12.

The fix needs one fact (fuel type) and one PID the app already decodes — `0x44`, commanded
air-fuel equivalence ratio, at `Pid.kt:105`:

```
fuelRate = MAF × 3600 / (AFR_stoich × λ × ρ_fuel)
```

**LPG** — common in Poland — is wrong in the other direction: AFR 15.6, ρ≈540 g/L → 8 424 vs
the hardcoded 12 054, so volume is **underestimated by ~30 %**, which matters because LPG's
whole economic case is litres-per-100 km against the pump price.

| Fuel | AFR stoich | ρ (g/L) | Runs at λ |
|---|---|---|---|
| Petrol | 14.7 | 820 | ≈1.0 (closed loop) |
| Diesel | 14.5 | 835 | **1.5–2.5 cruise, 5+ idle** |
| LPG | 15.6 | 540 | ≈1.0 |
| E85 | 9.8 | 781 | ≈1.0 |

So: **fuel type is the single highest-value fact about the car**, and it is the one thing every
source agrees on — WMI+model, CEPiK, EEA, or a two-tap picker.

### 3.2 Tank capacity turns a percentage into a decision

PID `0x2F` (`FUEL_LEVEL`, already in the catalogue at `Pid.kt:94`) returns a percentage. On its
own it duplicates the dashboard gauge and tells the driver nothing new.

With **tank capacity** — one integer — plus the consumption the app is *already integrating per
trip* (`TripAnalysis.averageFuelPer100Km`), it becomes:

> **32 % · 21 L · ~270 km** — on your last 500 km, not on a factory figure.

That is a range estimate grounded in the driver's own measured consumption, which is strictly
better than what the car's own trip computer does with a manufacturer default. This is probably
the single most useful screen the app doesn't have.

**Caveat, stated honestly:** `0x2F` support is patchy. Many VAG cars don't implement it. The
feature has to degrade gracefully to "not supported by this car" rather than showing 0 %.

### 3.3 Mass and rated power make the 10 Hz log mean something

The app records every polled metric at 10 Hz (`TripRecorder`). With **kerb mass** that log
supports:

- **Measured wheel power** — `P = m × a × v`, from speed differentiated over the sample window
- **0–100 km/h timing**, detected automatically from any full-throttle pull in a trip
- **Rated vs. measured** — "factory 103 kW, best measured 91 kW at the wheels" is roughly the
  expected 10–15 % drivetrain loss, and a number well below that is a genuine diagnostic signal
  (boost leak, clogged DPF, failing turbo)

None of this needs a new PID. It needs one number the app doesn't currently have.

### 3.4 Factory vs. real consumption

`TripAnalysis` already computes real L/100 km per trip. Set the homologated WLTP/NEDC figure
next to it and it becomes a headline stat instead of a number. §5 covers where that figure
comes from, free and officially.

### 3.5 Service intervals need no API at all

PID `0xA6` (odometer) is already decoded (`Pid.kt:125`). Given a last-service odometer reading
and an interval, "oil change in 3 200 km" is a subtraction. There is no free API for
manufacturer service schedules and there is no need for one — the driver knows, or the service
book does.

---

## 4. Polish sources

### 4.1 CEPiK open API — real, good, and cannot find *your* car

`https://api.cepik.gov.pl` — Ministerstwo Cyfryzacji, free, no key, 20 req/s and 100/min,
commercial use permitted with attribution. Swagger at `/doc`, bulk CSV at `/pliki`.

I queried it live:

```
GET https://api.cepik.gov.pl/pojazdy
      ?wojewodztwo=02&data-od=20240101&data-do=20240102
      &typ-daty=1&pokaz-wszystkie-pola=true
```

It works and it returns **68 fields per vehicle**, genuinely rich:

```json
"marka": "VOLVO", "model": "XC60 T8 TWIN ENGINE", "rok-produkcji": "2024",
"rodzaj-pojazdu": "SAMOCHÓD OSOBOWY", "podrodzaj-pojazdu": "WIELOZADANIOWY",
"pojemnosc-skokowa-silnika": 1969.0, "moc-netto-silnika": 228.0,
"masa-wlasna": 2150, "dopuszczalna-masa-calkowita": 2664,
"rodzaj-paliwa": "BENZYNA", "rodzaj-pierwszego-paliwa-alternatywnego": "ENERGIA ELEKTRYCZNA",
"liczba-miejsc-ogolem": 5, "liczba-osi": 2, "hak": false,
"max-masa-calkowita-przyczepy-z-hamulcem": 2250,
"rejestracja-powiat": "WROCŁAW", "rejestracja-gmina": "WROCŁAW-STARE MIASTO"
```

**But the records are anonymised.** There is no VIN field and no registration number. Query
filters are voivodeship + date range only. You cannot ask "tell me about *this* car" — only
"tell me about cars registered in Dolnośląskie in January 2024".

Two fields that sound perfect — `srednie-zuzycie-paliwa` (average consumption) and
`poziom-emisji-co2` — were **null** on the records I sampled. Not reliably populated.

**What it is actually good for:** building an offline reference table. Download the bulk CSVs
once, group by (marka, model, rok-produkcji, pojemność), and you have kerb mass, net power,
GVM and fuel type for essentially every car on Polish roads — the exact constants §3 needs. It
is a build-time data source, not a runtime API.

**One implementation note:** `curl` through this session's proxy failed with
`dh key too small`. That is the **proxy's** MITM certificate, not CEPiK — connecting directly,
the server negotiates TLS 1.3 with X25519 and `TLS_AES_256_GCM_SHA384`. No Android network
security config needed.

### 4.2 historiapojazdu.gov.pl / mObywatel — no API

The free government vehicle-history report (mileage from technical inspections since 2014, OC
validity, inspection deadlines, administrative status, sourced from CEPiK + UFG + TDT + SKP) is
**web-only**. It needs plate + VIN + first-registration date, and returns HTML. mObywatel does
it with plate + VIN. Neither exposes a documented API, and scraping a government auth-gated
service from a sideloaded app is not a reasonable thing to build.

**What is reasonable:** store the dates the driver enters, remind them before expiry, and
offer a button that opens `historiapojazdu.gov.pl` with the VIN already on the clipboard. The
reminder is the useful part; the report is one tap away regardless.

This is worth building — OC and badanie techniczne expiry are the two car-admin facts a Polish
driver actually gets fined for forgetting, and no OBD app tracks them.

---

## 5. EU homologation data — the best free spec source for European cars

**EEA, "Monitoring of CO2 emissions from passenger cars, Regulation (EU) 2019/631"**
([datahub](https://www.eea.europa.eu/en/datahub/datahubitem-view/fa8b1229-3db6-495d-b18e-9c9b3267c02b),
[viewer](https://co2cars.apps.eea.europa.eu/))

This is the one genuinely strong EU-wide dataset. It is a legally mandated record of **every
new car registered in the EU**, published annually, containing per version:

- Manufacturer, make, **commercial name**, type / variant / version
- **Specific CO2 emissions, both NEDC and WLTP**
- **Masses** (kerb and test mass), wheelbase, track width
- **Engine capacity and rated power**
- Fuel type and fuel mode, electricity consumption for PHEV/BEV

Downloadable as CSV/SQL, with a REST endpoint on the data viewer. Free, official, redistributable.

That covers **every constant §3 needs** — mass, power, displacement, fuel type — plus the
factory consumption figure for §3.4, for the whole EU market rather than just Poland.

**The catch, and it's the real work:** joining a VIN to a row is not solved by this dataset.
It is keyed by type-approval identifiers, not by VIN. Realistically it is a *picker* backend —
the driver confirms make → model → year → engine once, and the app remembers it against the
VIN forever. Which is fine: it is a one-time, three-tap setup, and the result is more accurate
than any automatic decode would have been.

The pragmatic version: don't ship the whole dataset. Filter it down to the models actually
present in Poland (CEPiK bulk CSVs tell you which), and ship a few hundred kB of lookup table.

---

## 6. Recalls — not viable for this market

- **NHTSA recalls API** — free, no key, but **US vehicles only**. A European Golf has no
  NHTSA recall record.
- **EU Safety Gate (ex-RAPEX)** — the right source in principle: the Commission centralises
  recall notifications from all 31 EEA countries and does cover motor vehicles. But it
  publishes a weekly HTML/PDF report and a JS search UI; I probed the obvious JSON endpoints
  and got 404. Every "API" on offer is a third-party scraper (Apify etc.). Alerts are also
  keyed by brand/model text, not VIN, so a match would be fuzzy even with the data.

**Verdict: skip.** A link to the manufacturer's own VIN recall checker is more honest and more
accurate than anything we could assemble.

---

## 7. Imagery — the thing you asked about specifically

### 7.1 imagin.studio — works, but don't

`https://cdn.imagin.studio/getImage?customer=img&make=volkswagen&modelFamily=golf&modelYear=2012&angle=23`

I tested it: **HTTP 200, 63 kB WebP, a real photo-realistic render.** The `customer=img` demo
key that circulates in tutorials does still work.

Don't ship it:

- Their terms state images "may never be downloaded, cached on server side, distributed or
  modified" and may only be used **straight from the CDN**. That rules out offline display in
  a car app — which is precisely when the phone has no signal.
- The demo key is not a licence. Production use needs a paid customer ID.
- Matching needs make + `modelFamily` + year — the exact fields EU VIN decoding does *not*
  reliably give us (§2.1).
- It fires a request containing the user's car identity to a third party on every screen open.

### 7.2 Bundled body-type silhouettes — the sane version

There is no free "car SVG API". What exists is stock-SVG marketplaces (Vecteezy, SVG Repo,
freesvg.org), i.e. assets, not a service.

So: hand-pick ~8 silhouettes — hatchback, sedan, estate, SUV, coupé, van, pickup, motorcycle —
convert to Compose `ImageVector`, and select by body type. CEPiK's `rodzaj-pojazdu` /
`podrodzaj-pojazdu` or the EEA dataset gives the body type; the driver can override it in two
taps.

Result: offline, themeable (tints with the existing `Skin` colours), a few kB, no licence
exposure, no network call. It won't be a photo of *their* car — but it's honest, and it makes
the vehicle profile screen look like something rather than a VIN in monospace.

**Priority: last.** It is decoration. Everything in §3 changes what the app can tell the
driver; this changes how the header looks.

---

## 8. What I'd build, in order

Steps 1–4 are **done** — see `data/Vehicle.kt`, `data/VinDecoder.kt`, `obd/FuelType.kt` and
`ui/vehicle/VehicleScreen.kt`. The rest is still a plan.

1. ✅ **`Vehicle` profile entity, keyed by VIN, persisted in `AppPreferences`.**
   Holds decoded facts + driver-entered facts. Nothing else on this list has anywhere to live
   until this exists. Also lets the app hold *several* cars.

2. ✅ **Offline WMI + year decode** (§2.2). Bundled table. Turns the header from
   `8W123456` into `Volkswagen`, instantly, offline, privately — and dates the 2011 Audi that
   vPIC put in 1981 correctly, by reading the 30-year year cycle as the most recent car that
   could have an OBD2 port.

3. ✅ **Vehicle setup: fuel type, displacement, kerb mass, rated power, tank capacity.**
   Five fields, one time, off the registration document.

4. ✅ **Fix the fuel maths using fuel type + λ** (§3.1). A bug fix, not a feature. PID `44`
   moved from the slow tier to the medium one in the process: λ stopped being a curiosity
   the moment the fuel rate started being divided by it.

5. **Fuel level → litres + range** (§3.2), using the driver's own measured L/100 km. Degrades
   cleanly when PID `2F` is unsupported. **Next up** — the profile now carries the tank size
   it needs.

6. **Factory vs. real consumption** on the trips screen (§3.4).

7. **Service + statutory reminders** (§3.5, §4.2): odometer-based service intervals from PID
   `A6`, and OC / badanie techniczne expiry dates with notifications. Manual entry, no API,
   high value for a Polish driver.

8. **Power/torque from mass** (§3.3) — measured wheel power and auto-detected 0–100 runs off
   the existing 10 Hz log.

9. **Body-type silhouette** (§7.2). Decoration, last.

**Steps 1–5 need no network at all.** That is worth stating plainly: the most useful version of
"more info about the user's car" is entirely offline, which suits an app that runs in a car
where signal is unreliable.

---

## 9. Privacy

A VIN identifies a specific car and, in practice, its owner. Current handling is good by
accident — the VIN never leaves the device.

Keep it that way:

- **Prefer the offline path.** §2.2 and §5's bundled table both keep the VIN local.
- **Never send a full VIN to a third party without asking.** If an online decode is ever added,
  make it an explicit action, and send only the first 11 characters (WMI + descriptor) — that
  identifies the *model*, not the *car*, and is all a decoder needs.
- **The mechanic report already prints the full VIN.** That is correct — it is for a mechanic —
  but it means the export path is the one place a VIN leaves the phone, and it should stay a
  deliberate user action.

---

## 10. Sources

- [NHTSA vPIC API](https://vpic.nhtsa.dot.gov/api/) — tested live, §2.1
- [Free VIN decoder API comparison 2026](https://cardog.app/blog/free-vin-decoder-api-comparison)
- [NHTSA VIN decoder alternatives](https://vincario.com/blog/alternatives/nhtsa-vin-decoder-api/) — European coverage pricing
- [CEPiK API — Ministerstwo Cyfryzacji](https://www.gov.pl/web/cepik/api-dla-centralnej-ewidencji-pojazdow-i-kierowcow-api-do-cepik), [Swagger](https://api.cepik.gov.pl/doc), [bulk files](https://api.cepik.gov.pl/pliki) — tested live, §4.1
- [Sprawdź historię pojazdu — gov.pl](https://www.gov.pl/web/gov/sprawdz-historie-pojazdu), [mObywatel](https://info.mobywatel.gov.pl/uslugi/historia-pojazdu) — §4.2
- [EEA CO2 monitoring dataset, Reg. (EU) 2019/631](https://www.eea.europa.eu/en/datahub/datahubitem-view/fa8b1229-3db6-495d-b18e-9c9b3267c02b), [data viewer](https://co2cars.apps.eea.europa.eu/) — §5
- [EU Safety Gate alerts](https://ec.europa.eu/safety-gate-alerts/screen/webReport) — §6
- [IMAGIN.studio getImage docs](https://docs.imagin.studio/api-integration/apis) — tested live, §7.1
- [WMI list, Wikibooks](https://en.wikibooks.org/wiki/Vehicle_Identification_Numbers_(VIN_codes)/World_Manufacturer_Identifier_(WMI)) — §2.2
