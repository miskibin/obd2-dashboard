# Multi-brand extended PIDs (mode 22 / 21) — EU/PL market

Companion to `research-mazda-extended-pids.md`. Same table shape, one section per
brand. Everything here is **read-only mode 22 / mode 21**, sourced from captured
ECU responses where possible.

**Nothing in this file was invented.** Where a brand has no trustworthy public
source for a priority sensor, it says so in a "Gaps" line instead of guessing.

---

## 0. How to read these tables

### Formula convention

Payload = the bytes **after** the positive-response echo:

| Service | Response prefix | `A` is |
|---|---|---|
| `22 <DIDhi> <DIDlo>` | `62 <DIDhi> <DIDlo>` | 1st byte after the 2-byte DID echo |
| `21 <PID>` | `61 <PID>` | 1st byte after the 1-byte PID echo |
| `01 <PID>` | `41 <PID>` | 1st byte after the PID echo |

Letters run `A…Z` for payload bytes 0…25. Past `Z` the tables use `B[n]` =
payload byte at 0-based index *n* (Toyota/Hyundai mode-21 blocks and Honda
records are 40–170 bytes long, so this happens a lot). `bitN..M` = bit range
counted MSB-first from payload bit 0. `SIGNED(x)` = two's complement over the
extracted width.

All formulas were mechanically derived from OBDb v3 `fmt` objects
(`value = (raw [signed] × mul ÷ div) + add`, raw taken from bits
`[bix, bix+len)`), so they are exact, not paraphrased.
Schema: <https://github.com/OBDb/.schemas/blob/main/signals.json>

### Extra columns you will see in "Verified on"

| Marker | Meaning | ELM327 consequence |
|---|---|---|
| `eax NN` | OBDb *extended address* | prepend byte `NN` to the request data (BMW: `12 22 4506`) |
| `session NN` | ECU must be in diagnostic session `NN` first | requires `10 NN` — **violates our read-only rule, see §14** |
| `fcm1` | flow-control mode 1 | `ATFCSH <hdr>` + `ATFCSD 300000` + `ATFCSM1`, else only the first frame returns |
| `pri NN` | 29-bit CAN priority | header is `NN<hdr>`, e.g. `17FC0076` |
| `{from: Y}` / `{to: Y}` | OBDb model-year ship filter | gate on VIN position 10 |

---

## 1. OBDb coverage map (probed 2026-08-12)

`github.com/OBDb` has **740 repos**. Repo existence ≠ data: a large share ship
`{ "commands": [] }`. Probed `signalsets/v3/default.json` for every EU-relevant
model; bytes = file size, `—` = repo exists but is an empty stub.

| Brand | Non-empty (flagship first) | Empty stubs (repo exists, no signals) |
|---|---|---|
| **VW/Audi/Škoda/SEAT/Cupra** | **Audi-Q3 (155 KB — the single richest VAG set, TDI)**, VW-Tiguan 43 KB, VW-Jetta 36 KB, VW-Golf 24 KB, Audi-A6/A4 8.5 KB, VW-Amarok 8 KB, Audi-RS-3 7.9 KB, VW-Polo 7.7 KB, Audi-TT 5.4 KB, VW-e-Golf 3.7 KB, Seat-Leon 3 KB, VW-ID.4 91 KB (BEV), Seat-Ibiza 1 KB, Audi-A1 0.4 KB | **all Škoda** (Octavia, Fabia, Superb, Kodiaq, Kamiq, Scala, Rapid, Enyaq, Elroq), VW-Passat/-B8/-Variant, Polo-V, T-Roc, T-Cross, Touran, Touareg, Sharan, Arteon, CC, Scirocco, Taigo, Up, Crafter, T5, ID3, ID5, GTE, Audi-A3/A5/A7/Q2/Q5/Q7/Q8/S3/S4/S5/S6/e-tron, Seat-Ateca/Arona/Tarraco/Alhambra/Altea, all Cupra |
| **Toyota/Lexus** | Lexus-RX 85 KB, Toyota-Camry 72 KB, RAV4 50 KB, Hilux 48 KB, **Prius 48 KB**, RAV4-Hybrid 41 KB, Land-Cruiser 21 KB, Lexus-IS 19 KB, 4Runner 19 KB, Corolla 6 KB, Corolla-Hybrid 4.4 KB, Prius-Prime 4.4 KB | Yaris, Yaris-Cross, Aygo, Auris, C-HR, Aqua, Highlander, Prius-v, Camry-Hybrid, Lexus-ES/-ES-300h/-NX-350h/-UX/-CT-200h/-LS/-GX |
| **Ford** | **Focus 37 KB (EU)**, F-150 39 KB, Mustang-Mach-E 30 KB, Edge 22 KB, Explorer 13 KB, Mustang 8.5 KB, Ranger 3.5 KB, Fiesta 2.2 KB | Mondeo, Kuga, Puma, Transit, Ka, EcoSport, Focus-RS |
| **Hyundai/Kia** | Kia-Sportage 48 KB, **Hyundai-Santa-Fe 45 KB (incl. CRDi DPF)**, IONIQ-5 86 KB, Elantra 39 KB, IONIQ-6 41 KB, EV6 40 KB, Sonata 35 KB, Kona-Electric 31 KB, Ioniq 20 KB, Santa-Fe-Hybrid 18 KB, Kia-Forte 18 KB, Kona 6 KB, Kia-Soul 4.5 KB, Tucson-Hybrid 2 KB, Tucson 1.6 KB, Kia-Optima 0.9 KB | i10/i20/i30, Accent, Kia-Ceed/Rio/Picanto/Niro/Sorento/Stonic |
| **Renault/Dacia** | **Renault-Megane 38 KB**, **Dacia-Duster 28 KB**, Kadjar 0.2 KB | Clio, Captur, Zoe, Sandero, Spring |
| **Opel/Vauxhall** | VauxhallOpel-Corsa-e / Opel-Corsa 2.5 KB (**BEV only, PSA platform**) | Vauxhall-Astra; no Astra/Insignia/Zafira/Mokka repos at all |
| **BMW/Mini** | BMW-X5 23 KB, 5-Series 21 KB, i3 14 KB, **3-Series 9.9 KB**, MINI-Cooper 8.4 KB, X3 7.3 KB, X1 5.3 KB, 4-Series 4.5 KB, iX3 2.4 KB | 1-Series, 2-Series, M3, E91, MINI-Countryman |
| **Mercedes** | G-Class 2.6 KB (ESP data only) | C-Class, E-Class, S-Class |
| **Honda** | **Civic 46 KB**, Accord 1.3 KB, CR-V 0.6 KB | Jazz, Fit, HR-V, City, Accord-Hybrid |
| **Nissan** | **Leaf 12 KB**, Altima 6 KB | Qashqai, Juke, Micra, X-Trail, Note, Navara, Kicks |
| **Volvo** | **none** | XC40/XC60/XC90, S60/S90, V60/V70, C30 — all empty |
| **Fiat/PSA (Stellantis)** | **Jeep-Renegade 18 KB (FCA 1.6/2.0 MultiJet — best public FCA diesel set)** | Fiat-500/Panda/Punto, Peugeot-208/308/2008/3008/508/206/207, Citroen-C3/C4/Berlingo, Alfa-Giulia/Giulietta, Jeep-Compass |

**Practical consequence:** for the "empty stub" models you inherit from the
*platform sibling* in the same table (Škoda Octavia ≈ VW Golf/Tiguan MQB;
Peugeot 308 ≈ Jeep Renegade only for the FCA-sourced diesel, **not** for PSA
HDi). Gate those as *unverified* and probe on connect.

---

## 2. Source quality ranking used here

1. **OBDb per-model repos** (`signalsets/v3/default.json`, CC BY-SA 4.0) —
   contributed from real captured ELM327 logs, model-year attached, decoded by a
   validated schema. Treated as *verified*. Entries carrying `"dbg": true` in
   the JSON are the repo's own "not yet trusted" marker and are called out.
2. **Established community lists** that multiple independent threads agree on
   (Torque/ScanGauge tables, FORScan community, brand forums with reproduced
   readings). Treated as *corroborated*.
3. **Single forum post** — marked **unverified**, ship behind a probe.

Cross-validation actually observed: VAG `22 114E` / `22 114F` soot mass appear
*both* in OBDb Audi-Q3 captures and in the VW T6 community DPF list; Honda ATF
temp `22 2201` appears both in OBDb Honda-Civic and in Torque threads;
Ford `22 0415` oil pressure and `22 1E1C` trans temp match the Mazda set exactly
(shared Ford/Mazda PCM heritage — see §5 trap).

---

## 3. Cross-brand addressing models (the thing that actually breaks)

| Model | Brands | Request framing | ELM327 setup | In scope for us? |
|---|---|---|---|---|
| **11-bit ISO 15765-4, standard** | VAG, Toyota, Ford, Hyundai/Kia, Renault, Nissan, Mercedes, Opel(PSA) | `22 xx xx` to `7Ex` or a chassis header (`710`,`713`,`714`,`720`,`726`,`740`,`748`,`750`,`758`,`7A0`,`7D2`…) | `ATSH <hdr>`, `ATCRA <rax>` for non-`7Ex`, reset `ATCRA` after | ✅ yes |
| **11-bit + extended address byte** | **BMW/Mini (all F/G and late E)**, Toyota TPMS + Land Cruiser | `<eax> 22 xx xx`, tester ID `6F1` (BMW) / `750` (Toyota TPMS) | `ATSH 6F1`, `ATCRA 6<eax>`, `ATFCSH 6F1`, `ATFCSD 300000`, `ATFCSM1`; request bytes start with `eax` | ⚠️ yes, but needs flow-control support — cheap clones fail |
| **29-bit normal-fixed (`18DAttF1`)** | **Honda/Acura, FCA (Fiat/Jeep/Alfa)** | `18DA<tt>F1` → `18DAF1<tt>` | `ATSP7`, `ATSH DA<tt>F1` (+`ATCP 18`), `ATCRA 18DAF1<tt>` | ⚠️ yes, but a protocol switch away from the 11-bit session |
| **29-bit VW gateway (`17FC00xx`)** | VW MQB-evo (Tiguan `FC00`/`pri 17`) | `17FC00<xx>` → `17FE00<xx>` | `ATSP7`, `ATSH 17FC0076` | ❌ out of scope for v1 — and OBDb flags these `"dbg": true` |
| **KWP2000 / TP2.0** | **VAG pre-MY2009**, Opel pre-2008 | not UDS at all | needs TP2.0 stack | ❌ **out of scope** — our ELM327 flow cannot do it |

**Session gate (`din`).** A sizeable slice of VAG diesel DPF DIDs and *all* of
the FCA (Jeep Renegade) engine DIDs are marked `din: "03"` — the ECU must be put
into extended diagnostic session `10 03` first. Our Mazda safety rule says
*never send `10`*. Decision needed (see §14); until then those rows are
**shipped disabled**.

---

## 4. Volkswagen Group — VW / Škoda / SEAT / Cupra / Audi

**Gate:** WMI ∈ {`WVW` VW car, `WVG` VW SUV/MPV, `WV1`/`WV2` VW LCV,
`VWV` VW Navarra (Polo), `TMB` Škoda, `VSS` SEAT + Cupra, `WAU` Audi car,
`WA1` Audi SUV, `TRU` Audi Hungary (Győr: TT, A3, Q3), `WUA` Audi Sport}
**× model year ≥ 2009** (MY2008 and older = TP2.0, unsupported)
**× fuel:** the `DPF` block only on diesel; the `Injection amount deviation`
block only on TDI/common-rail.
Sources: WMI — [Wikibooks WMI list](https://en.wikibooks.org/wiki/Vehicle_Identification_Numbers_(VIN_codes)/World_Manufacturer_Identifier_(WMI)),
[WALL-E/vin-decoder wmi-from-wiki.csv](https://github.com/WALL-E/vin-decoder/blob/master/csv/wmi-from-wiki.csv),
[NHTSA vPIC DecodeWMI](https://vpic.nhtsa.dot.gov/api/vehicles/DecodeWMI/WVW?format=json) (WVW/WVG/WV1/WAU/WA1/TRU all resolve).

**Module map:** `7E0`→`7E8` engine · `7E1`→`7E9` DSG/AT · `710`→`77A` battery
monitor (IBS/BEM) · `713`→`77D` ABS/ESP (incl. TPMS-indirect) · `714`→`77E`
instrument cluster · `746`→`7B0` climate · `70B` direct-TPMS · `767`→`7D1` eCall.

### 4.1 Engine — `7E0`→`7E8` (oil, boost, injectors) + cluster `714`→`77E`

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `714`→`77E` | `22 202F` | Engine oil temperature | `A-58` | °C | -58..197 | Audi Q3 (fcm1), Seat Leon, VW Jetta ≤2018, Audi A4/A6/RS3, VW Amarok/Polo, Audi TT |
| `714`→`77E` | `22 224B` | Oil thermal wear ("oil life") | `(A*256+B)` | km | 0..65535 | VW Golf, VW Tiguan, Audi Q3, VW Jetta |
| `714`→`77E` | `22 2211` | Charge air pressure (rel.) | `A*100/255` | % | 0..100 | Audi Q3, Seat Leon |
| `714`→`77E` | `22 2203` | Odometer (cluster) | `(A*65536+B*256+C)` | km | 0..16777215 | Audi A4/A6/RS3, VW Amarok/Polo, Seat Ibiza, VW Jetta |
| `714`→`77E` | `22 2299` | Average fuel consumption | `(A*256+B)/10` | l/100 km | 0..6553.5 | VW Golf |
| `714`→`77E` | `22 1014` | Ambient air temperature | `A*0.5-50` | °C | -50..77.5 | VW Golf |
| `7E0`→`7E8` | `22 11BA` | Oil level | `(A*256+B)/250` | mm | 0..262 | VW Golf ≤2020, VW Tiguan ≤2023, Audi A4/A6/RS3, VW Amarok |
| `7E0`→`7E8` | `22 11BC` | Engine oil level (signed) | `SIGNED(A*256+B)/250` | mm | -131.07..131.07 | Audi Q3 |
| `7E0`→`7E8` | `22 11C4` | Oil level, max mark | `(A*256+B)/250` | cm | 0..262 | VW Golf ≤2020, VW Tiguan ≤2019, Audi A4/A6/RS3 |
| `7E0`→`7E8` | `22 11C5` | Oil level, min mark | `(A*256+B)/250` | cm | 0..262 | same |
| `7E0`→`7E8` | `22 11BD` | Oil level warning threshold | `SIGNED(A*256+B)/250` | mm | -131.07..131.07 | Audi Q3, VW Jetta ≥2015 |
| `7E0`→`7E8` | `22 179C` | Oil temperature (variant 2) | `(A*256+B)/10-273.1` | °C | -273.1..6280.4 | Audi Q3 |
| `7E0`→`7E8` | `22 20A1` | Calculated oil temperature | `A-40` | °C | -40..215 | Audi Q3 |
| `7E0`→`7E8` | `22 1057` | Boost pressure (absolute) | `(A*256+B)/1000` | bar | 0..65.535 | Audi Q3 |
| `7E0`→`7E8` | `22 1149` | Boost pressure commanded | `SIGNED(A*256+B)` | raw | -32768..32767 | Audi Q3 |
| `7E0`→`7E8` | `22 11CC` | Boost regulator feedback | `SIGNED(A*256+B)*0.01` | % | 0..100 | VW Golf, VW Tiguan |
| `7E0`→`7E8` | `22 112C` | Turbine actuator 1 bank 1 activation | `SIGNED(A*256+B)*0.01` | % | 0..100 | VW Golf |
| `7E0`→`7E8` | `22 100D` | Current gear | `SIGNED(A)` | — | -128..127 | Audi Q3 |
| `7E0`→`7E8` | `22 113F` | Fuel consumption | `(A*256+B)/100` | l | 0..655.35 | VW Golf, VW Tiguan |
| `7E0`→`7E8` | `22 1139` | Mean injection quantity | `SIGNED(A*256+B)*0.01` | mg/str | -327.68..327.67 | VW Golf, VW Tiguan |
| `7E0`→`7E8` | `22 295A` | Odometer (ECU) | `(A*65536+B*256+C)` | km | 0..16777215 | VW Golf ≤2020 |

**Per-injector correction (TDI diagnostics gold)** — Audi Q3 captures:

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `7E0`→`7E8` | `22 10FF` | Injection amount deviation cyl 1 | `SIGNED(A*256+B)/100` | mg/str | -327.68..327.67 | Audi Q3 |
| `7E0`→`7E8` | `22 1105` | Injection amount deviation cyl 2 | `SIGNED(A*256+B)/100` | mg/str | -327.68..327.67 | Audi Q3 |
| `7E0`→`7E8` | `22 1100` | Injection amount deviation cyl 3 | `SIGNED(A*256+B)/100` | mg/str | -327.68..327.67 | Audi Q3 |
| `7E0`→`7E8` | `22 1104` | Injection amount deviation cyl 4 | `SIGNED(A*256+B)/100` | mg/str | -327.68..327.67 | Audi Q3 |
| `7E0`→`7E8` | `22 10D0/10D3/10D1/…` | Injector *n* rail-pressure calibration pt 1/2/3 | `SIGNED(A*256+B)*0.05/1000` | ms | ±1.638 | Audi Q3 (`10D0` inj1 pt1, `10D6` inj1 pt2, `10DC` inj1 pt3; `10D3/10D9/10DF` inj2; `10D1/10D2/10DD` inj3; `10D8/10DE` inj4) |

> **Note on cylinder→DID order:** the OBDb capture maps `10FF`→cyl 1, `1100`→cyl 3,
> `1104`→cyl 4, `1105`→cyl 2 — i.e. **firing order 1-3-4-2**, not 1-2-3-4. Do not
> "fix" it.

### 4.2 12 V battery monitor (IBS) — `710`→`77A`

This is the best 12 V battery telemetry of any brand in this document.

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `710`→`77A` | `22 2A07` | Battery voltage | `(A*256+B)/1000+4` | V | 4..69.5 | VW Golf, Tiguan (fcm1), Audi Q3, VW Jetta ≥2017 |
| `710`→`77A` | `22 2A09` | Battery current | `(A*65536+B*256+C)*10/10000-3000` | A | -3000..+13777 | VW Golf, VW Tiguan |
| `710`→`77A` | `22 2A09` | Battery current (32-bit variant) | `SIGNED(A*16777216+B*65536+C*256+D)/1000` | A | ±2.1e6 | Audi Q3, VW Jetta ≥2017 |
| `710`→`77A` | `22 2A0A` | Battery sensor temperature | `A-40` | °C | -40..215 | Audi Q3, VW Jetta ≥2022 |
| `710`→`77A` | `22 2A0B` | Battery temperature | `A-40` | °C | -40..215 | VW Golf, VW Tiguan, Audi Q3 |
| `710`→`77A` | `22 2A0C` | **Battery state of charge** | `A` | % | 0..100 | VW Golf, VW Tiguan, Audi Q3, VW Jetta |
| `710`→`77A` | `22 2A0E` | Battery internal resistance | `A*20/100` | mΩ | 0..51 | VW Golf, VW Tiguan (Audi Q3/Jetta use `SIGNED(A)/5`) |
| `710`→`77A` | `22 2A0F` | Usable battery charge | `A` | Ah | 0..255 | VW Golf, VW Tiguan, VW Jetta |
| `710`→`77A` | `22 2A10` | Battery voltage at rest | `A*54/255+9` | V | 9..63 | VW Golf, VW Tiguan, VW Jetta (Audi Q3: `A/10+9`) |
| `710`→`77A` | `22 2A61` | Battery total charge | `(A*16777216+B*65536+C*256+D)/10` | Ah | 0..4.29e8 | Audi Q3, VW Jetta ≥2017 |
| `710`→`77A` | `22 2A61` | Battery total discharge | `(E*16777216+F*65536+G*256+H)/10` | Ah | 0..4.29e8 | Audi Q3, VW Jetta ≥2017 |
| `710`→`77A` | `22 2A00` | Start/stop target voltage | `A/10+10.6` | V | 10.6..36.1 | VW Jetta ≥2017 |

Trap: two mutually incompatible decodings exist for `2A09` and `2A0E`. Probe
both and pick by plausibility (current must sit within ±250 A, resistance
within 2..30 mΩ).

### 4.3 DSG / automatic — `7E1`→`7E9`

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `7E1`→`7E9` | `22 2104` | **ATF / DSG fluid temperature** | `A-40` | °C | -40..215 | Seat Leon, VW Jetta ≥2015, VW Tiguan (via `FE0077`) |
| `7E1`→`7E9` | `22 704E` | ATF temperature, calculated | `A-50` | °C | -50..205 | VW Tiguan |
| `7E1`→`7E9` | `22 704F` | ATF temperature, lifetime max | `A-50` | °C | -50..205 | VW Jetta, VW Tiguan |
| `7E1`→`7E9` | `22 7051` | ATF temperature, modeled max reached | `A-55` | °C | -55..200 | VW Tiguan |
| `7E1`→`7E9` | `22 18E3` | Clutch temperature, calculated (DSG) | `SIGNED(A*256+B)` | °C | -32768..32767 | Audi Q3 |
| `7E1`→`7E9` | `22 381D` | Oil pressure actual (DSG hydraulic) | `(A*256+B)*100/10000` | bar | 0..655.35 | Audi Q3 |
| `7E1`→`7E9` | `22 381E` | Oil pressure control current | `(A*256+B)/10` | mA | 0..6553.5 | Audi Q3 |
| `7E1`→`7E9` | `22 38B2` | Centrifugal oil temperature | `SIGNED(A)` | °C | -128..127 | Audi Q3 |
| `7E1` | `22 210F` | Gear | `A` | enum | 0..255 | Audi A4/A6/RS3, VW Amarok/Polo |

Trap: `22 2104` has an alternate 16-bit signed decoding on some DSG/S-tronic
(`SIGNED(A*256+B)`, Audi Q3). Sanity-gate to −40..160 °C and cross-check against
coolant at cold start, exactly as we do for Mazda `22 1E1C`.

### 4.4 DPF / diesel — `7E0`→`7E8` (**diesel gate mandatory**)

Rows marked **session 03** need `10 03` first. All from Audi Q3 (EA288 TDI)
captures unless noted; corroborated for VW by the T6 community DPF list
(`22114e` soot measured / `22114f` soot calculated / `22178c` oil ash, header
`7E0` — [t6forum](https://www.t6forum.com/threads/vw-t6-custom-pid-codes-for-dpf.33964/)).

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `7E0`→`7E8` | `22 1ABE` | DPF soot mass, measured | `SIGNED(A*256+B)*100/10000` | g | -327.7..327.7 | Audi Q3 |
| `7E0`→`7E8` | `22 2609` | DPF soot mass, calculated | `SIGNED(A*256+B)*100/10000` | g | -327.7..327.7 | Audi Q3 |
| `7E0` | `22 114E` | Soot mass measured (alt scaling) | `SIGNED(A*256+B)/100` | g | -327.7..327.7 | Audi Q3 — **session 03** |
| `7E0` | `22 114F` | Soot mass calculated (alt scaling) | `(A*256+B)/100` | g | 0..655.35 | Audi Q3 — **session 03** |
| `7E0`→`7E8` | `22 1AD3` | DPF regeneration level, current | `A` | — | 0..255 | Audi Q3 |
| `7E0`→`7E8` | `22 1AD4` | **DPF regeneration status** | `(A*256+B)` | enum | 0..65535 | Audi Q3 |
| `7E0`→`7E8` | `22 1AC2` | DPF field regeneration request | `A` | enum | 0..255 | Audi Q3 |
| `7E0`→`7E8` | `22 11C1` | DPF field regeneration lock status | `A` | enum | 0..255 | Audi Q3 |
| `7E0`→`7E8` | `22 1ABA` | Distance since last regeneration | `(A*256+B)/10` | km | 0..6553.5 | Audi Q3 |
| `7E0`→`7E8` | `22 1156` | Distance since last regeneration (32-bit) | `(A*16777216+B*65536+C*256+D)/1000` | km | 0..4.29e6 | Audi Q3 — **session 03** |
| `7E0`→`7E8` | `22 1AC3` | Time since last regeneration | `(A*256+B)*127.998/65535` | h | 0..128 | Audi Q3 |
| `7E0`→`7E8` | `22 1AC0` | Regeneration time counter | `(A*256+B)*6400/10000` | s | 0..41942 | Audi Q3 |
| `7E0`→`7E8` | `22 1AC4` | Service-regen current duration | `(A*256+B)*6400/10000` | s | 0..41942 | Audi Q3 |
| `7E0`→`7E8` | `22 1AC1` | Fuel used since last regeneration | `(A*256+B)*100/10000` | l | 0..655.35 | Audi Q3 |
| `7E0`→`7E8` | `22 1AC9` | Interrupted regenerations, count | `A` | — | 0..255 | Audi Q3 |
| `7E0`→`7E8` | `22 1AC8` | Regeneration blocked status | `A` | enum | 0..255 | Audi Q3 |
| `7E0`→`7E8` | `22 11B2` | DPF inlet temperature | `(A*256+B)/10-273.1` | °C | -273..6280 | Audi Q3 |
| `7E0`→`7E8` | `22 10F9` | DPF outlet temperature | `(A*256+B)/10-273.1` | °C | -273..6280 | Audi Q3 |
| `7E0` | `22 1044` | DPF surface temperature | `(A*256+B)/10-273.1` | °C | -273..6280 | VW Golf, VW Tiguan, Audi Q3 — **session 03** |
| `7E0`→`7E8` | `22 10F1` | DPF differential-pressure offset | `SIGNED(A*256+B)` | raw | ±32768 | Audi Q3 |
| `7E0` | `22 14F5` | Differential pressure | `SIGNED(A*256+B)` | raw | ±32768 | Audi Q3 — **session 03** |
| `7E0`→`7E8` | `22 1ABD` | DPF oil-ash mass | `(A*16777216+B*65536+C*256+D)*0.0011921/10000` | g | 0..512 | Audi Q3 |
| `7E0` | `22 1153` | DPF oil-ash volume | `SIGNED(A*256+B)/100` | l | ±327.7 | Audi Q3 |
| `7E0`→`7E8` | `22 1ABF` | DPF ash load limit | `(A*256+B)*78.125/10000` | g | 0..512 | Audi Q3 |
| `7E0`→`7E8` | `22 1AD5` | DPF inlet lambda | `SIGNED(A*256+B)*10/10000` | λ | ±32.8 | Audi Q3 |
| `7E0`→`7E8` | `22 11E6` | DPF exhaust gas flow volume | `SIGNED(A*256+B)*1000/10000` | — | ±3276.8 | Audi Q3 |
| `7E0`→`7E8` | `22 115C` | Service regeneration status | `A` | enum | 0..255 | Audi Q3 |
| `7E0`→`7E8` | `22 1354` | Soot index | `SIGNED(A*256+B)` | m | ±32768 | Audi Q3 |
| `7E0`→`7E8` | `22 1ACB` | Route length in charge cycle | `A*800/100` | km | 0..2040 | Audi Q3 |
| `7E0`→`7E8` | `22 1ACD` | Regeneration time (statistics) | `A*6400/100` | s | 0..16320 | Audi Q3 |

### 4.5 TPMS

Two completely different systems — gate them apart:

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `70B` | `22 18A0` | Front right tire pressure | `F/40` | bar | 0..6.375 | Audi A4/A6/RS3, VW Amarok, VW Polo (fcm1) |
| `70B` | `22 18A1` | Front left tire pressure | `F/40` | bar | 0..6.375 | same |
| `70B` | `22 18A2` | Rear left tire pressure | `F/40` | bar | 0..6.375 | same |
| `70B` | `22 18A3` | Rear right tire pressure | `F/40` | bar | 0..6.375 | same |
| `70B` | `22 18A0..18A3` | Tire *n* pressure (Audi TT layout) | `G/10` | bar | 0..25.5 | Audi TT (+ position enum `0=FL,1=FR,2=RL,3=RR` at byte 0) |
| `713`→`77D` | `22 1821` | TPMS available? | `bit7` | bool | 0..1 | VW Golf, Tiguan, Jetta, Audi Q3 |
| `713`→`77D` | `22 1821` | Tire leakage rate FL/FR/RL/RR | `B[33]`/`B[34]`/`B[35]`/`B[36]` | — | 0..255 | Audi Q3, VW Golf/Tiguan/Jetta |
| `713`→`77D` | `22 1821` | Flat tire indicator FL/FR/RL/RR | `B[37]`/`B[38]`/`B[39]`/`B[40]` | — | 0..255 | Audi Q3, VW Golf/Tiguan |
| `713`→`77D` | `22 1821` | Odometer at last TPMS adaptation | `(B[1]*16777216+B[2]*65536+B[3]*256+B[4])` | km | 0..200000 | Audi Q3, VW Golf |

`22 1821` is a ~42-byte multi-frame response — **`fcm1` is mandatory** or you
only get the first 6 bytes.

### 4.6 VAG traps

- **TP2.0 cut-off.** Golf V/Passat B6/Octavia II and older (≈MY2008−) talk
  KWP2000-over-TP2.0. Our ELM327 flow cannot reach them at all. Gate `year ≥ 2009`.
- **`din: "03"`.** Roughly half the DPF DIDs need an extended session. Shipping
  them means sending `10 03` — currently forbidden by our safety rule.
- **Cluster-derived oil temp.** `714 / 22 202F` reads the *cluster's* value, not
  the ECU sensor. On engines without a real oil temperature sensor this is a
  modeled value. `A-58` (not the more common `A-60`) is what the captures show —
  do not "correct" it.
- **`FC00` 29-bit rows.** VW Tiguan's largest block (47 commands) is on
  `17FC0076`/`17FE0076` and is flagged `"dbg": true` upstream. Out of scope, and
  unverified even if we did implement 29-bit.
- **Škoda/Cupra have zero OBDb data.** Inherit VW Golf/Tiguan (MQB) rows,
  gate `TMB`/`VSS` as *probe-required*.

---

## 5. Ford (Europe)

**Gate:** WMI ∈ {`WF0` Ford-Werke Cologne/Saarlouis — the dominant one in PL,
`VS6` Ford Spain (Valencia), `SFA` Ford UK, `NM0` Ford Otosan Turkey (Transit/
Courier)} × year ≥ 2009 × fuel (DPF block diesel-only).
Sources: WMI — [WALL-E CSV](https://github.com/WALL-E/vin-decoder/blob/master/csv/wmi-from-wiki.csv), vPIC (`WF0`→Ford, `NM0`→Ford truck).

**Module map:** `7E0`→`7E8` PCM · `7E6`→`7EE` TCM (some) · `726`→`72E` BCM
(TPMS + 12 V battery monitor) · `720`→`728` IPC · `760`→`768` ABS.

**Big win:** the Ford DID space is *the same space Mazda uses* (`22 0415` oil
pressure, `22 1E1C` trans temp, `22 0301` MAP volt, `22 0307` fuel-pump duty,
`22 F4xx` mode-01 mirror). Anything already implemented for Mazda transfers with
only the VIN gate changed.

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `7E0`→`7E8` | `22 0415` | Engine oil pressure | `SIGNED(A*256+B)` | kPa | 0..1000 sane | Ford Focus, F-150 ≥2009 |
| `7DF` | `22 1310` | Engine oil temperature | `A` | °C (raw) | 0..255 | Ford Edge — **unverified scaling**, and `7DF` is the functional broadcast (see trap) |
| `7E0`→`7E8` | `22 1E1C` | Transmission oil temperature | `SIGNED(A*256+B)/16` | °C | -500..500 | Ford Edge, Explorer; Focus/F-150 use unsigned `/16` |
| `7E6`→`7EE` | `22 1E1C` | Transmission temperature (TCM) | `SIGNED(A*256+B)/16` | °C | -300..300 | Ford Mustang Mach-E, Ford Edge (fcm1) |
| `7E0`→`7E8` | `22 1E12` | Current gear | enum `1..10` | — | 1..10 | Ford Focus, Edge, Explorer, Ranger |
| `7E0`→`7E8` | `22 1E23` | Shift lever position | enum `10=MANUAL,46=DRIVE,50=NEUTRAL,60=REVERSE,70=PARK` | — | — | Ford Focus, F-150 ≥2009 |
| `7E0`→`7E8` | `22 0461` | Charge-air temperature | `SIGNED(A*256+B)/64-40` | °C | -40..189 | Ford Focus, F-150 |
| `7E0`→`7E8` | `22 0462` | Wastegate duty cycle | `(A*256+B)*100/32768` | % | 0..100 | Ford Focus, F-150 |
| `7E0`→`7E8` | `22 033E` | Throttle inlet (boost) pressure | `(A*256+B)*37/33441` | psi | 0..60 | Ford Focus, F-150 |
| `7E0`→`7E8` | `22 0466` | Throttle inlet pressure, desired | `(A*256+B)*37/33441` | psi | 0..72.5 | Ford Focus, F-150 |
| `7E0`→`7E8` | `22 0334` | Cylinder head temperature | `SIGNED(A*256+B)/64` | °C | -512..512 | Ford Focus, F-150 |
| `7E0`→`7E8` | `22 038F` | Coolant temperature (high-res) | `SIGNED(A*256+B)/64` | °C | -512..511 | Ford Focus, F-150 |
| `7E0`→`7E8` | `22 03DC` | Fuel rail pressure, desired | `(A*256+B)*1.45038` | psi | 0..3500 | Ford Focus, F-150 |
| `7E0`→`7E8` | `22 0548` | Fuel pressure, low side actual | `SIGNED(A*256+B)*10000/137892` | psi | 0..200 | Ford Focus, F-150 |
| `7E0`→`7E8` | `22 0345` | Misfire events, latest cycle | `(A*16777216+B*65536+C*256+D)` | count | — | Ford Focus, F-150 |
| `7E0`→`7E8` | `22 0382/0388/038A/038B/038C/0398` | Misfire acceleration cyl 1..6 | `SIGNED(A)/64` | — | -2..2 | Ford Focus, F-150 |
| `726`→`72E` | `22 4028` | 12 V battery state of charge | `A*100/255` | % | 0..100 | Ford F-150 ≥2009 (Mach-E: plain `A`) |
| `726`→`72E` | `22 4029` | 12 V battery temperature | `A-40` | °C | -40..100 | Ford F-150 2009–2020 |
| `726`→`72E` | `22 402A` | 12 V battery voltage | `A/20+6` | V | 6..18.75 | Ford Mustang Mach-E |
| `726`→`72E` | `22 402B` | 12 V battery current | `A-127` | A | -127..128 | Ford Mustang Mach-E |
| `726`→`72E` | `22 2813/2814/2815/2816` | Tire pressure FL/FR/RRo/RLo | `(A*256+B)/20` | psi | 0..80 sane | Ford Focus, Edge, Explorer, Fiesta ≥2018, Ranger, F-150 ≥2009, Mach-E |
| `726`→`72E` | `22 2817/2818` | Tire pressure rear inner R/L (dually) | `(A*256+B)/20` | psi | 0..80 | Ford Focus, Edge, Explorer, F-150 |
| `720`→`728` | `22 61A5` | Tire pressure warning lamp | `bit2` | bool | 0..1 | Ford Focus, Edge, Fiesta, F-150 |

**Ford EU DPF — unverified.** OBDb has *no* DPF signals for any Ford. The
strongest community lead is `22 0579` "DPF Load" and `22 057B` "Soot load",
equation `(A*256)+B`, header `7E0`, reported on facelift 2.0 TDCi
([talkford / FORScan community](https://www.talkford.com/threads/extra-pids-for-2-0-duratorq-inside.234095/)) —
**single-source, ship behind a probe and label unverified.** FORScan exposes
DPF Load / DPF Soot Load / last-completed-regen / failed-regens / average
distance between regens, but the PID↔formula mapping is not published.

**Traps:** `7DF` is the *functional* (broadcast) address — the Ford Edge rows
using it will answer from whichever module replies first. Rewrite them to `7E0`
before shipping. Signed vs unsigned `22 1E1C` differs per model; sanity-gate.

---

## 6. Toyota / Lexus

**Gate:** WMI ∈ {`JT1`–`JT8` Japan, `SB1` Toyota UK Burnaston (Corolla, Auris,
Avensis), `VNK` Toyota France Valenciennes (Yaris), `NMT` Toyota Turkey Sakarya
(C-HR, Corolla), `MR0` Thailand (Hilux), `TW1` Portugal; Lexus `JTH`, `JTJ`,
`JT6`, `JT8`} × year × drivetrain (`hybrid` gates the whole §6.3 block).
All confirmed via [vPIC](https://vpic.nhtsa.dot.gov/api/vehicles/DecodeWMI/SB1?format=json)
(`SB1`,`NMT`,`VNK`,`JTH`,`JTJ` → Toyota/Lexus) + Wikibooks.

**Mode 21 vs mode 22 — the central Toyota decision.**
Denso ECMs answer **both**, with different data:

| | Service 21 | Service 22 |
|---|---|---|
| Header | `7E0` (engine), `7E2`/`7B0` (HV), `750`+eax `2A` (TPMS) | `700`→`708` (engine), `701`→`709` (TCM), `7D2`→`7DA` (HV/inverter), `747`→`74F` (battery ECU) |
| Shape | one PID → one **big fixed block** (40–70 bytes), fields at fixed offsets | one DID → one value |
| Availability | legacy, works back to ~2004 | generally **MY2016+** |
| Cost | 1 request → many signals (cheap!) | 1 request → 1 signal |

Prefer `21` where available: `21 51` alone yields oil temp + VVT oil temp;
`21 82` yields both transmission temperatures. Gate `22 xxxx` on `year ≥ 2016`.

### 6.1 Engine / transmission

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `7E0`→`7E8` | `21 51` | **Engine oil temperature** | `J-40` | °C | -40..215 | Lexus RX, Camry, Corolla, Hilux, Land Cruiser, Prius, 4Runner ≥2007, RAV4 ≤2018 |
| `7E0`→`7E8` | `21 51` | VVT oil temperature | `H-40` | °C | -40..215 | same set |
| `7E0`→`7E8` | `21 51` | Engine oil temperature, previous trip | `K-40` | °C | -40..215 | Lexus RX, Camry, Hilux, RAV4 |
| `7E0`→`7E8` | `21 82` | **Transmission temp, pan** | `(A*256+B)/256-40` | °C | -40..215 | Camry, Corolla, Hilux, Land Cruiser, Prius, 4Runner, RAV4, Lexus IS ≤2020 |
| `7E0`→`7E8` | `21 82` | Transmission temp, torque converter | `(C*256+D)/256-40` | °C | -40..215 | same set |
| `7E0`→`7E8` | `21 85` | Gear | `A` | 0..20 | 0..20 | Corolla, Hilux, Land Cruiser, 4Runner ≥2007, RAV4 ≤2018, Lexus IS ≤2020 |
| `7E0`→`7E8` | `21 DA` | Current gear (alt) | `A` | — | 0..255 | Camry, Hilux, Lexus RX ≤2009, RAV4 ≤2018 |
| `7E0`→`7E8` | `21 25` | Shift switch P/R/N/D + sport up/down | `bit24/25/26/27`, `bit33/34` | bool | 0..1 | Lexus RX, Camry, Hilux, RAV4 |
| `700`→`708` | `22 1074` | Engine oil pressure | `(A*256+B)*10/128` | kPa | 0..1000 | Prius, Lexus RX ≥2016, RAV4-Hybrid ≥2018 (Camry ≥2018 & Hilux: raw `(A*256+B)` kPa) |
| `700`→`708` | `22 107B` | Engine oil temperature | `Y-40` | °C | -40..215 | Lexus IS, Lexus RX ≥2016, Camry ≥2018 |
| `700`→`708` | `22 1F5C` | Engine oil temperature (var 2) | `A-40` | °C | -40..215 | Prius, Lexus RX ≥2016, Camry ≥2018, RAV4-Hybrid ≥2018 |
| `701`→`709` | `22 1627` | Transmission fluid temp, pan | `(A*256+B)/256-40` | °C | -40..215 | Lexus RX ≥2016, Camry ≥2018, Lexus IS ≥2021, Corolla ≥2019, Land Cruiser |
| `701`→`709` | `22 1628` | Transmission fluid temp, post-converter | `(A*256+B)*0.00703125-40` | °F | -40..350 | Land Cruiser |
| `700`→`708` | `22 162B` | ATF thermal degradation estimate | `(A*256+B)` | — | 0..65535 | Camry ≥2016 |
| `700`→`708` | `22 1621` | Gear | `A` | — | 0..255 | Camry, Lexus RX ≥2016 |
| `700`→`708` | `22 1002` | Battery (12 V) voltage | `(A*256+B)*12.207/10000` | V | 0..18 sane | Camry, Hilux, Lexus RX ≥2016 |
| `700`→`708` | `22 106C` | Distance since battery disconnect | `(A*256+B)` | km | 0..65535 | Camry, Hilux, Lexus RX |
| `700`→`708` | `22 1041` | Coolant temp at ignition / initial | `A*62.5/100-40` / `B*62.5/100-40` | °C | -40..119 | Camry, Lexus RX ≥2016 |
| `700`→`708` | `22 1018` | Injector pulse width | `(A*256+B)/1000` | ms | 0..65.5 | Camry, Hilux, Lexus RX |
| `700`→`708` | `22 105C` | Knock learned / feedback value | `(A*256+B)*312.5/10000-1024` / `(C*256+D)*…` | — | ±1024 | Lexus IS/RX, Prius, Camry |
| `700`→`708` | `22 1043` | Electric water pump rpm / target | `B*25` / `A*25` | rpm | 0..6375 | Prius, Camry ≥2018, Lexus RX ≥2023 |
| `7D2`→`7DA` | `22 1040` | Transaxle oil temperature (eCVT) | `C-40` | °C | -40..215 | RAV4-Hybrid ≥2018, Lexus RX ≥2023 |
| `6F1` (eax `12`) | `22 586F` | Engine oil pressure | `A` (raw) | — | 0..255 | Toyota Land Cruiser — **unverified scaling**; note the BMW-style tester ID |
| `6F1` (eax `18`) | `22 DA12` / `22 DA25` | ATF temp / oil temp | `A` (raw) | — | 0..255 | Toyota Land Cruiser — same BMW-style DIDs, unverified scaling |

### 6.2 TPMS — tester `750`, extended address `2A`

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `750`→`758` | `21 30` | Tire pressure FL/FR/RL/RR/spare | `A/58-0.5` … `E/58-0.5` | bar | 0..3.9 | Camry ≥2013, Lexus IS, Prius ≤2023, RAV4, RAV4-Hybrid, 4Runner |
| `750`→`758` | `21 16` | Tire temperature FL/FR/RL/RR/spare | `A-40` … `E-40` | °C | -40..150 | same set |

Needs `eax 2A` **and** `tst 2A` (tester address) **and** `fcm1`.

### 6.3 Hybrid (HSD) — the rich set

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `7D2`→`7DA` | `22 1F9A` | **HV battery voltage** | `(C*256+D)*156.25/10000` | V | 0..1023 | Prius, RAV4-Hybrid ≥2018, Lexus RX ≥2023 |
| `7D2`→`7DA` | `22 1F9A` | **HV battery current** | `SIGNED(E*256+F)/10` | A | ±200 sane | same |
| `7D2`→`7DA` | `22 1F5B` | **HV state of charge** | `A*100/255` | % | 0..100 | Prius, RAV4-Hybrid ≥2018, Corolla-Hybrid, Prius Prime, Lexus RX ≥2023 (fcm1) |
| `7D2`→`7DA` | `22 106C` | HV charge max / min | `B/2` / `C/2` | % | 0..100 | Prius, RAV4-Hybrid, Land Cruiser, Lexus RX (fcm1) |
| `7D2`→`7DA` | `22 1829` / `22 182A` | HV battery max / min temperature | `A-50` | °C | -50..205 | RAV4-Hybrid ≥2018 |
| `7D2`→`7DA` | `22 10A1` | Motor (MG2) rpm | `(A*256+B)-32768` | rpm | ±32768 | Prius, RAV4-Hybrid, Lexus RX |
| `7D2`→`7DA` | `22 10A1` | Motor torque | `(G*256+H)/8-4096` | N·m | ±4096 | same |
| `7D2`→`7DA` | `22 10A2` | Rear motor torque (AWD-e) | `(G*256+H)/8-4096` | N·m | ±4096 | Prius, RAV4-Hybrid, Lexus IS |
| `7D2`→`7DA` | `22 10B2` | Inverter coolant temperature | `A-40` | °C | -40..100 | Prius, RAV4-Hybrid, Lexus RX |
| `7D2`→`7DA` | `22 10A6` | Rear inverter coolant / inverter max temp | `A-40` / `C-40` | °C | -40..215 | Prius, RAV4-Hybrid, Lexus RX |
| `747`→`74F` | `22 182E` | HV battery **cell n** voltage (n = 1…) | `(B[2n-2]*256+B[2n-1])*5/65535` | V | 0..5 | Prius, RAV4-Hybrid ≥2018 (fcm1) |
| `745` | `22 1739` | HV battery charge (displayed) | `A` | % | 0..100 | Land Cruiser (fcm1) |
| `7E2` | `21 C3` | MG2 rpm / torque | `(A*256+B)` / `(C*256+D)` | rpm / N·m | — | Lexus RX |
| `7E2` | `21 C3` | MG1 rpm / torque | `(E*256+F)` / `(G*256+H)` | rpm / N·m | — | Lexus RX |
| `7E2` | `21 C3` | State of charge | `O*0.392` | % | 0..100 | Lexus RX |
| `7E2` | `21 C3` | MG1/MG2 inverter temperature | `U-50` / `V-50` | °C | -50..205 | Lexus RX |
| `7E2` | `21 C3` | MG1/MG2 motor temperature | `W-50` / `X-50` | °C | -50..205 | Lexus RX |
| `7B0`→`7B8` | `21 48` | Regen request / regen operation | `(A*256+B)` / `(C*256+D)` | N·m | 0..65535 | Camry, Lexus RX |

`22 182E` returns a long block: cell *n* voltage sits at payload byte offset
`2(n−1)`. OBDb Prius/RAV4-Hybrid enumerate cells 1..N explicitly; decode
generically instead of hard-coding 20 signals.

**Gap:** Toyota **diesel** (Hilux/Land Cruiser 1GD/2GD DPF) has no public
verified DPF DID set. `6F1`/eax rows above are the only Land-Cruiser-specific
finds, and their scalings are raw.

---

## 7. Hyundai / Kia

**Gate:** WMI ∈ {Hyundai `KMH` (car KR), `KMF`/`KMJ` (van/truck KR), `TMA`
(HMMC Nošovice, CZ — i30/Tucson/Kona), `NLH` (Hyundai Assan, İzmit TR — i10/i20),
`MAL` (India); Kia `KNA`/`KNB`/`KNC`/`KND`/`KNE` (KR), `U5Y`/`U6Y` (Kia Slovakia,
Žilina — Ceed/Sportage/XCeed)} × year × fuel.
`KMH`,`KMF`,`KNA`,`KND`,`U5Y` confirmed via [vPIC](https://vpic.nhtsa.dot.gov/api/vehicles/DecodeWMI/U5Y?format=json);
`TMA`,`NLH`,`U6Y` via the Wikibooks/WALL-E list.

**Module map:** `7E0`→`7E8` ECM · `7E1`→`7E9` TCM · `7E2`→`7EA` HCU/hybrid ·
`7A0`→`7A8` TPMS · `7C6`→`7CE` cluster · `7D4`→`7DC` BMS (Sportage HEV) ·
`7E4`→`7EC` BMS (EV/HEV) · `744`→`74C` VCU (EV).

Hyundai/Kia mirror much of the mode-21 block space into mode-22 `E0xx` DIDs
(`21 01` ≡ `22 E001`, `21 02` ≡ `22 E002`, `21 04` ≡ `22 E004`). Prefer `22 E0xx`
for MY2014+; fall back to `21 xx` on older.

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `7E0`→`7E8` | `22 E001` | **Engine oil temperature** | `B[34]*3/4-48` | °C | -48..143 | Hyundai Elantra, Santa Fe ≥2014, Sonata ≥2015, Kona, Kia Forte, Kia Sportage |
| `7E0`→`7E8` | `21 01` | Engine oil temperature (legacy) | `B[34]*0.75-48` | °C | -48..143 | Hyundai Elantra, Santa Fe ≤2018, Sonata ≤2019 |
| `7E0`→`7E8` | `22 ED02` | Engine oil temperature (Smartstream) | `X-4` | °C | -40..215 | Hyundai Tucson-Hybrid, Kia Sportage — **check the `-4` offset on car**, it looks like a truncated `-40` |
| `7E1`→`7E9` | `21 A0` | **ATF temperature** | `N-40` | °C | -40..215 | Kia Forte, Soul, Sportage, Hyundai Santa Fe ≤2018 |
| `7E1`→`7E9` | `21 A0` | Current gear / commanded gear | `X` / `B[22]` (Santa Fe) or enum `1=N,2=R,3=P,4=M` | — | — | Kia Forte/Soul/Sportage, Hyundai Santa Fe ≤2018 |
| `7E1`→`7E9` | `22 01A4` | Current gear / next gear | `bit188..191` / `bit196..199` | — | 0..15 | Kia Forte, Kia Sportage |
| `7E0`→`7E8` | `22 E021` | **Turbo boost pressure (gauge)** | `(H*256+I)/831.5569-12` | psi | -10..66.8 | Kia Forte, Kia Sportage |
| `7E0`→`7E8` | `22 E021` | Turbo boost pressure (absolute) | `(H*256+I)*0.008291` | kPa | 0..543 | Hyundai Santa Fe ≥2014, Elantra |
| `7E0`→`7E8` | `22 E002` | Injection duration cyl 1..4 | `(W*256+X)/1220.7`, `(Y*256+Z)/1220.7`, `(B[26]*256+B[27])/1220.7`, `(B[28]*256+B[29])/1220.7` | ms | 0..53 | Kia Forte/Sportage, Hyundai Elantra/Santa Fe/Sonata/Kona |
| `7E0`→`7E8` | `22 E004` | Aux (12 V) battery state of charge | `B[40]` | % | 0..100 | Kia Sportage, Hyundai Santa Fe/Sonata/Elantra |
| `7E0`→`7E8` | `22 E004` | Aux battery state of health | `B[41]` | % | 0..100 | same |
| `7E2`→`7EA` | `22 E004` | 12 V battery charge (hybrid) | `T` | % | 0..100 | Hyundai Santa-Fe-Hybrid, Kia Forte, Kia Sportage |
| `7C6`→`7CE` | `22 B002` | 12 V battery voltage (cluster) | `F/12.8` | V | 0..18 | Kia Soul, Kia Sportage (fcm1) |
| `7A0`→`7A8` | `22 C00B` | Tire pressure FL/FR/RL/RR | `E/5`, `J/5`, `O/5`, `T/5` | psi | 0..51 | Kia Sportage/EV6, Hyundai IONIQ-5/6, Tucson, Tucson-Hybrid (fcm1) |
| `7A0`→`7A8` | `22 C00B` | Tire temperature FL/FR/RL/RR | `F-50`, `K-50`, `P-50`, `U-50` | °C | -50..205 | Kia Sportage, Kona-Electric, Kia Forte, IONIQ-5 |
| `7A0`→`7A8` | `22 C00B` | Tire sensor battery level | enum `0=3V+,1=2.7–3V,2=2.4–2.7V,3=<2.4V` | — | — | Kia Sportage |
| `7E4`→`7EC` | `22 0101` | HV battery charge (dash) | `E*0.5` | % | 0..100 | Kia Sportage-HEV, Kia EV6, IONIQ-5/6 (fcm1) |
| `7D4`→`7DC` | `22 0101` | BMS state of charge | `E/2` | % | 0..100 | Kia Sportage HEV |
| `7D4`→`7DC` | `22 0101` | BMS min / module 1..4 temperature | `SIGNED(P)`, `SIGNED(Q..T)` | °C | -40..80 | Kia Sportage HEV |
| `744`→`74C` | `22 E003` | HV pack SoC / voltage / max current | `B[15]/2`, `(B[13]*256+B[14])/10`, `(B[22]*256+B[23])/10` | %, V, A | — | Hyundai IONIQ-5/6, Kia EV6 (fcm1) |

**Careful — the TPMS byte offsets move between platforms.** OBDb records
*two* layouts for `22 C00B`: FL@32/FR@64/RL@128/RR@96 (older: Elantra ≤2020,
Santa Fe, Kona, Optima, Soul, Ioniq, Kona-Electric) vs FL@32/FR@72/RL@112/RR@152
(newer: Elantra ≥2021, Sonata, Tucson, Sportage, EV6, IONIQ-5/6). Gate by
model-year, or probe: the wrong layout returns the temperature byte as pressure.

### Diesel (CRDi) — DPF

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `7E0`→`7E8` | `21 06` | DPF regeneration state | `bit207` | bool | 0..1 | Hyundai Santa Fe (CRDi) |
| `7E0`→`7E8` | `21 1B` | DPF differential pressure | `(A*256+B)/2` | (raw) | — | Hyundai Santa Fe |
| `7E0` | `21 03` | Odometer at last DPF regen | `(B[49]*2^24+B[50]*2^16+B[51]*256+B[52])/1000` | km | 0..4.29e6 | Hyundai Santa Fe |
| `7E0` | `21 03` | Distance since last DPF regen | `(B[53]*2^24+B[54]*2^16+B[55]*256+B[56])/1000` | km | 0..4.29e6 | Hyundai Santa Fe |

**Gap:** no verified soot-mass (g) DID for Hyundai/Kia diesel — only the
pressure and the distance/regen-state pair above. Do not synthesise one.

---

## 8. BMW / MINI

**Gate:** WMI ∈ {`WBA` BMW car, `WBS` BMW M, `WBY` BMW i/BEV, `WBX` BMW SUV
(Spartanburg — X3/X5/X7), `4US` BMW US; MINI `WMW`} × year ≥ 2007 × drivetrain.
All confirmed via [vPIC](https://vpic.nhtsa.dot.gov/api/vehicles/DecodeWMI/WBY?format=json).

**Addressing — read this first.** *Every* BMW extended PID uses tester ID `6F1`
with a **1-byte extended target address prepended to the request**:

```
ATSH 6F1 · ATCRA 6<eax> · ATFCSH 6F1 · ATFCSD 300000 · ATFCSM1
request bytes: <eax> 22 <DIDhi> <DIDlo>      e.g. DME oil temp = "12 22 4506"
response arrives on 6<eax>, e.g. 612
```

`eax` map observed in OBDb: `12`→DME/DDE (engine, resp `612`), `18`→EGS
(transmission, `618`), `19`→turbo/EGT module (`619`), `07`→SME (HV battery,
`607`), `0D`→(`60D`), `29`→(`629`), `56`→(`656`), `60`→KOMBI/cluster (`660`),
`63`→(`663`), `1A`→EME (`61A`).
Contract confirmed by [Pelican's extended-PID docs](https://pelican.clutch.engineering/scanning/extended-pids/)
("extended addressing (`eax`): a 2-hexadecimal-character prefix for protocols
like BMW") and the OBDb example file
[`.schemas/examples/extended-addressing.json`](https://github.com/OBDb/.schemas/blob/main/examples/extended-addressing.json).

**Compatibility verdict:** this *is* reachable from a plain ELM327 — but only
one that honours `ATCRA` + `ATFCSH/ATFCSD/ATFCSM1`. Genuine ELM327 v1.4+/v2.x
and OBDLink are fine; many €10 clones silently drop the flow-control config and
return only the first frame. Ship BMW rows gated on a runtime capability probe
(send `ATFCSM1`, require `OK`).

| Header→resp | eax | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|---|
| `6F1`→`618` | `18` | `22 DA25` | **Engine oil temperature** | `SIGNED(A*256+B)-48` | °C | -50..180 sane | BMW 5-Series ≥2007, 3-Series ≥2012, X3, X5 ≥2014 |
| `6F1`→`612` | `12` | `22 4506` | Engine oil temperature (DME) | `A` | °C | -40..200 | BMW X5 |
| `6F1`→`612` | `12` | `22 57C3` | Engine oil temperature, alternate | `(A*256+B)/10` | °C | -40..200 | BMW X5 |
| `6F1`→`612` | `12` | `22 4402` | Engine oil temperature (MINI) | `A-60` | °C | -60..195 | MINI Cooper |
| `6F1`→`612` | `12` | `22 586F` | **Engine oil pressure** | `(A*256+B)/1000` | bar | 0..10 sane | MINI Cooper (BMW 3/4/5/X5 record raw `A` — unverified scaling) |
| `6F1`→`618` | `18` | `22 DA12` | ATF temperature | `A` (raw) | — | 0..255 | BMW 3-Series ≥2012, 4-Series, 5-Series ≥2007, X5 ≥2014 — **scaling unverified**; MINI's `21 0A` suggests `A-55` |
| `6F1`→`618` | `18` | `21 0A` | ATF temperature | `A-55` | °C | -55..200 | MINI Cooper |
| `6F1`→`618` | `18` | `22 DA2E` | Shift lever position | enum `0=P,1=R,2=N,3=D` | — | — | BMW 3/5-Series, X1, X3, X5, MINI |
| `6F1`→`663` | `63` | `22 D031` | Current gear | `A` | — | 0..255 | BMW 5-Series ≥2007, X5 ≥2014, i3 |
| `6F1`→`619` | `19` | `22 DFE7` | VGT calibration angle | `(A*256+B)*156.25/10000` | ° | 0..1000 | BMW 5-Series ≥2018, X5 ≥2019 |
| `6F1`→`607` | `07` | `22 6335` | HV battery health (SoH) | `D` | % | 0..100 | BMW i3, 5-Series ≥2016, X5 ≥2017 |
| `6F1`→`607` | `07` | `22 E5C7` | HV battery health (newer) | `D` | % | 0..100 | BMW iX3, 5-Series ≥2022, X5 ≥2025 |
| `6F1`→`607` | `07` | `22 DD68` | HV battery voltage | `(A*256+B)/100` | V | 0..655 | BMW i3 |
| `6F1`→`607` | `07` | `22 DD69` | HV battery current | `SIGNED(A*2^24+B*2^16+C*256+D)/100` | A | ±600 sane | BMW i3, 5-Series ≥2016, X5 ≥2017 |
| `6F1`→`607` | `07` | `22 DDBC` | HV state of charge / max / min | `(A*256+B)/10`, `(C*256+D)/10`, `(E*256+F)/10` | % | 0..100 | BMW i3, 5-Series ≥2016, X5 ≥2017 |
| `6F1`→`607` | `07` | `22 E5CE` | HV state of charge (newer) | `(A*256+B)/100` | % | 0..100 | BMW iX3, 5-Series ≥2022, X5 ≥2025 |
| `6F1`→`607` | `07` | `22 DDC0` | HV cell temperature min / max | `SIGNED(A*256+B)/100`, `SIGNED(C*256+D)/100` | °C | -40..80 sane | BMW i3, 5-Series ≥2016, X5 ≥2017 |
| `6F1`→`607` | `07` | `22 DFA0` | Cell SoC min / max / average | `(B[24]*256+B[25])/100`, `(B[26]…)/100`, `(B[28]…)/100` | % | 0..100 | BMW 5-Series ≥2016, X5 ≥2017 |

**Gaps:** no verified BMW **TPMS**, **DPF**, or **12 V battery SoC** DIDs in
OBDb. Community BMW oil-temp threads confirm the general picture (the DME will
NACK oil temp over generic OBD-II and only answers over the BMW protocol —
[E46Fanatics](https://www.e46fanatics.com/threads/oil-temperature-via-obd.1106967/)),
which matches the `6F1` requirement, but published formulas for those three
groups are not available. Don't invent them.

---

## 9. Mercedes-Benz

**Gate:** WMI ∈ {`WDB` (≤~2010), `WDD` (~2009–2020 passenger), `WDC`
(DaimlerChrysler-era SUV), `W1K` (2021+ passenger), `W1N` (2021+ SUV), `W1V`
(2021+ vans), `WDF` (older vans/Sprinter), `VSA` (Vitoria, ES — Vito/V-Class),
`WMX` (AMG GmbH)} × year × fuel.
`WDB`,`WDD`,`W1K`,`W1N` confirmed via [vPIC](https://vpic.nhtsa.dot.gov/api/vehicles/DecodeWMI/W1K?format=json);
`WDF`,`VSA` via Wikibooks.

**This is the weakest brand in the entire document.** OBDb has exactly one
Mercedes repo with data (`Mercedes-Benz-G-Class`, 2 commands, ESP dynamics
only). C-Class / E-Class / S-Class repos are empty stubs.

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `7E1` | `22 2130` | **Transmission oil temperature (722.9 7G-Tronic)** | `L-50` | °C | -50..205 | W204 (C-Class) — *corroborated*, two independent forums ([MBWorld](https://mbworld.org/forums/c63-amg-w204/798801-transmission-temperature.html), [BenzWorld](https://www.benzworld.org/threads/transmission-temperature-using-torque-pro.3074586/)). `L` = payload byte 11 in Torque's letter indexing — **verify byte offset on car** |
| `7E2`→`7EA` | `22 2001` | Wheel speed FL/FR/RL/RR | `(A*256+B)*0.05625`, `(C*256+D)*…`, `(E*256+F)*…`, `(G*256+H)*…` | km/h | 0..1000 | Mercedes G-Class |
| `7E2`→`7EA` | `22 2002` | Longitudinal / lateral acceleration | `SIGNED(B*256+C)/192747` / `SIGNED(D*256+E)/627.6` | g | ±0.17 / ±52 | Mercedes G-Class |
| `7E2`→`7EA` | `22 2002` | Yaw rate | `SIGNED(G*256+H)*2/32767` | rad/s | ±2 | Mercedes G-Class |
| `7E2`→`7EA` | `22 2002` | Steering wheel angle | `SIGNED(J*256+K)/10` | ° | ±3276.8 | Mercedes G-Class |
| `7E2`→`7EA` | `22 2002` | Master brake cylinder pressure | `SIGNED(M*256+N)/65.35884` | bar | ±501 | Mercedes G-Class |

**Gaps — explicitly no trustworthy public source:** Mercedes engine **oil
temperature** DID (many models expose it as *standard* mode 01 PID `0x5C`
"Engine oil temperature" — try that first before hunting a DID), engine **oil
pressure/level**, **DPF soot load** (OM651/OM642 — MBClub threads confirm people
want it and that XENTRY shows it, but nobody has published PID+formula),
**TPMS**, **12 V battery SoC**, **injector correction**.
Mercedes uses a large `22 2xxx`/`22 4xxx` DID space; the only realistic path is a
user-driven range probe on `7E0`/`7E1` plus Torque-CSV import.

---

## 10. Renault / Dacia

**Gate:** WMI ∈ {`VF1` Renault (FR/TR/SI plants), `VF2` Renault LCV, `VNV`
Renault-Nissan Maubeuge, `X7L` Renault Russia (AvtoFramos), `NM1` Oyak-Renault;
Dacia `UU1` (Mioveni RO **and** Somaca/Tanger MA)} × year ≥ 2010 × fuel.
`VF1` confirmed via [vPIC](https://vpic.nhtsa.dot.gov/api/vehicles/DecodeWMI/VF1?format=json); `UU1` via Wikibooks/WALL-E.

**Module map:** `7E0`→`7E8` ECM · `740`→`760` ESP/ABS · `748` 4WD coupler
(Duster) · `743` UCH/BCM (Kadjar) · `7E1` TCM (CVT).

Renault's scaling idiom: temperatures are **decikelvin** (`/10 − 273`), pressures
are millibar (`/1000` → bar).

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `7E0`→`7E8` | `22 2007` | **Engine oil temperature, estimated** | `(A*256+B)/10-273` | °C | -40..180 sane | Renault Mégane ≤2023, Dacia Duster |
| `7E0`→`7E8` | `22 111F` | Engine oil temperature (R9M 1.6 dCi) | `A-50` | °C | -30..120 | 2016 Nissan Qashqai 1.6 dCi R9M — *corroborated*, [Torque forum](https://torque-bhp.com/community/main-forum/renault-nissan-1-6-dci-r9m-engine-transmission-oil-pid-2/); same ECU family as Renault Mégane/Kadjar dCi |
| `7E0`→`7E8` | `22 2001` | Coolant temperature | `(A*256+B)/10-273` | °C | -40..150 | Renault Mégane ≤2023, Dacia Duster |
| `7E0`→`7E8` | `22 2401` | **Boost pressure** | `(A*256+B)/1000` | bar | 0..3 sane | Renault Mégane ≤2023, Dacia Duster |
| `7E0`→`7E8` | `22 240B` | Manifold pressure | `(A*256+B)/10` | kPa | 0..300 sane | Renault Mégane, Dacia Duster |
| `7E0`→`7E8` | `22 FD81` | MAP sensor voltage | `(A*256+B)/4096` | V | 0..5 | Renault Mégane, Dacia Duster |
| `7E0`→`7E8` | `22 2004` | Engine torque | `(A*256+B)/32-400` | N·m | -400..500 sane | Renault Mégane, Dacia Duster |
| `7E0`→`7E8` | `22 2005` | ECU supply voltage | `(A*256+B)/100` | V | 8..16 | Renault Mégane, Dacia Duster |
| `7E0`→`7E8` | `22 2C04` | **Gearbox ratio (current gear)** | `A` | — | 0..8 | Renault Mégane ≤2023, Dacia Duster |
| `7E0`→`7E8` | `22 2C03` | Gearbox ratio, target | `A` | — | 0..8 | same |
| `7E0`→`7E8` | `22 2057` | Alternator power | `(A*256+B)*10` | W | 0..3000 sane | Renault Mégane, Dacia Duster |
| `7E0`→`7E8` | `22 2099` | Alternator load | `A*100/255` | % | 0..100 | Renault Mégane, Dacia Duster |
| `7E0`→`7E8` | `22 222A` | A/C refrigerant pressure | `(A*256+B)/10` | bar | 0..35 sane | Renault Mégane, Dacia Duster |
| `740`→`760` | `22 4B06` | Odometer | `(A*2^24+B*2^16+C*256+D)` | km | — | Renault Mégane (fcm1) |
| `740`→`760` | `22 4B17` | ABS hydraulic unit temperature | `A` | °C | 0..255 | Renault Mégane (fcm1) |
| `740`→`760` | `22 4B0E` | Master cylinder brake pressure | `A` | bar | 0..255 | Renault Mégane (fcm1) |
| `740`→`760` | `22 4B00..4B03` | Wheel speed FL/FR/RL/RR | `(A*256+B)` | km/h | — | Renault Mégane (fcm1) |
| `740`→`760` | `22 4B35` | ABS/ESP/TC/EBD flags (bitfield) | `bit0..bit33` | bool | — | Renault Mégane (fcm1) |
| `748` | `22 3020` | Clutch temperature (4WD coupler) | `A` | °C | 0..255 | Dacia Duster 4×4 |
| `748` | `22 3001` | 12 V battery voltage | `A/10` | V | 0..25.5 | Dacia Duster |
| `748` | `22 3011` | Engine torque, reference | `SIGNED(A*256+B)/2-400` | N·m | — | Dacia Duster |
| `743` | `22 0207` | Odometer | `(A*65536+B*256+C)` | km | — | Renault Kadjar |
| `7E1` | `21 01` | CVT fluid temperature (Jatco CVT) | 5th-order polynomial in `AD`, see source | °C | -30..140 | 2016 Qashqai 1.6 dCi CVT — [Torque forum](https://torque-bhp.com/community/main-forum/renault-nissan-1-6-dci-r9m-engine-transmission-oil-pid-2/); **needs `10 C0` session first** |

**Traps:**
- Renault ECMs frequently need an **init sequence** before answering `22`.
  The R9M thread's working Torque config uses `ATTP6 / ATSH7E0 / 10C0` as the
  start-diagnostic string — `10 C0` is a *Renault-proprietary* session. Same
  read-only-policy question as VAG's `10 03`.
- `22 2007` is a **modeled** oil temperature ("estimated") on Mégane/Duster, not
  a sensor. Label it as such in the UI.
- No DPF DIDs in OBDb for any Renault/Dacia. Renault dCi DPF data exists in
  CLIP but is not published — **gap, do not invent**.
- Dacia Duster's `7E0` rows carry no `rax` in OBDb, i.e. the receive filter was
  auto-guessed; assume `7E8`.

---

## 11. Opel / Vauxhall — two entirely different cars under one badge

**Gate — split by era:**

| Era | WMI | Platform | Addressing |
|---|---|---|---|
| GM era (≤ ~2017) | `W0L`, `VXK` (Vauxhall/Zaragoza), `VSX` (Opel Spain) | Astra J/K, Insignia A, Zafira C, Corsa D/E | 11-bit `7E0`, GM mode `22 xxxx` + proprietary mode `AA` streaming |
| PSA/Stellantis era (≥ ~2018) | **`W0V`** (Opel Automobile GmbH) | Corsa F, Mokka B, Astra L, Grandland, Combo | PSA headers `6xx` (`6A2`→`682`, `6B4`→`694`), DIDs `D4xx`/`D8xx` |

`W0L` and `W0V` both resolve to GM in [vPIC](https://vpic.nhtsa.dot.gov/api/vehicles/DecodeWMI/W0V?format=json)
(legacy registration); `W0V` = Opel Automobile GmbH from 2017, `VXK`/`VSX` per
Wikibooks. **`W0V` is the single most important gate here** — it flips the whole
addressing scheme.

### 11.1 GM-era Opel/Vauxhall diesel (`7E0`) — community, corroborated

From the [opelastraclub "PIDS PARA TORQUE"](https://www.opelastraclub.com/t27293-pids-para-torque)
thread (Astra J 1.6 CDTI 2010–2016, Astra K B16DTH/B16DTL/B16DTE 2016–2021) and
the [Torque Vauxhall/Opel GM DPF thread](https://torque-bhp.com/community/main-forum/vauxhall-opel-gm-dpf-pid/).
**No OBDb coverage — treat as corroborated-community, ship behind a probe.**

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `7E0` | `22 336A` | **DPF soot accumulation** | `A*100/255` | % | 0..100 | Astra J 1.6 CDTI, Astra K B16DTx (some report plain `A` reads 0–100 directly — probe both) |
| `7E0` | `22 3039` | Distance since last DPF regeneration | `(A*256+B)` | km | 0..65535 | Astra K B16DTx |
| `7E0` | `22 20F4` | DPF differential pressure | `SIGNED(A)` | kPa | -128..127 | Astra K |
| `7E0` | `22 20F5` | Calculated DPF flow | `A` | kPa | 0..255 | Astra K |
| `7E0` | `22 20F8` | DPF inlet temperature | `(A*256+B)*0.1-40` | °C | -40..6513 | Astra K |
| `7E0` | `22 3035` | DPF differential-pressure sensor | `(A*256+B)/51` | mV | 0..5000 | Astra K |
| `7E0` | `22 20FA` | DPF regeneration status | `bit0` | bool | 0..1 | Astra K |
| `7E0` | `22 1152` | EGR position | `A*100/255` | % | 0..100 | Astra K B16DTx |
| `7E0` | `22 1543` | Turbo vane position (VGT) | `A*100/255` | % | 0..100 | Astra K B16DTx |
| `7E0` | `22 0023` | Fuel rail pressure | `(A*256+B)*0.01` | MPa | 0..655 | Astra K B16DTx |
| `7E0` | `22 0034` | HO2S 1 (lambda) | `A*20/255` | λ | 0..20 | Astra K B16DTx |
| `5E8` via `AA 0118` | mode `AA` | DPF saturation % / km since regen / regen progress % | byte2×2 / byte3×5 / byte4 | %, km, % | — | Vauxhall/Opel GM — **unverified**, needs `ATCRA 5E8` and a non-standard streaming mode Torque can't drive cleanly |

**GM trap (useful):** GM mirrors the mode-01 PID space into `22 00xx` — `22 000C`
= rpm, `22 0005` = coolant, `22 0010` = MAF, `22 0004` = load, same formulas as
mode 01. This is the exact analogue of Mazda's `22 F4xx` alias. Use it when a
mode-01 PID isn't advertised.

### 11.2 PSA-era Opel (`W0V`) — OBDb, BEV only

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `6B4`→`694` | `22 D410` | HV battery charge (calibrated) | `(A*256+B)/512` | % | 0..100 | Opel/Vauxhall Corsa-e (fcm1) |
| `6B4`→`694` | `22 D810` | HV battery charge (raw) | `(A*256+B)/512` | % | 0..100 | Corsa-e (fcm1) |
| `6B4`→`694` | `22 D815` | HV battery voltage | `(A*256+B)/16` | V | 0..450 sane | Corsa-e (fcm1) |
| `6B4`→`694` | `22 D860` | HV battery health (SoH) | `(B*256+C)/16` | % | 0..100 | Corsa-e (fcm1) |
| `6B4`→`694` | `22 D865` | HV battery energy | `(A*256+B)/64` | kWh | 0..100 sane | Corsa-e (fcm1) |
| `6B4`→`694` | `22 D86F` / `22 D870` | HV cell min / max voltage | `(A*256+B)` | mV | 2500..4300 | Corsa-e (fcm1) |
| `6B4`→`694` | `22 D49C` | Odometer | `(A*2^24+B*2^16+C*256+D)/1000` | km | — | Corsa-e (fcm1) |
| `6A2`→`682` | `22 D402` | Vehicle speed | `(A*256+B)/10` | km/h | 0..250 | Corsa-e (fcm1) |

**Gaps:** nothing verified for PSA-era Opel **combustion** engines (Astra L
1.2 PureTech / 1.5 BlueHDi) — no oil temp, no DPF, no TPMS. Same for
Peugeot/Citroën (see §13).

---

## 12. Honda / Nissan / Volvo

### 12.1 Honda

**Gate:** WMI ∈ {`JHM` (car JP), `JHL` (SUV JP), `SHH`/`SHS` (Honda UK, Swindon —
Civic), `NLA` (Honda Türkiye — Civic sedan), `MRH`/`MLH` (Thailand), `LUC` (China)}
× year. `SHH` confirmed via [vPIC](https://vpic.nhtsa.dot.gov/api/vehicles/DecodeWMI/SHH?format=json), `JHM`/`JHL`/`NLA` via Wikibooks.

**Addressing:** everything is **29-bit** `18DA<target>F1` → `18DAF1<target>`.
OBDb writes it as `hdr: "DA10"`, `rax: "10"`. Targets seen: `10` ECM, `11`,
`1D` TCM, `01`/`16` IMA/HV battery, `0E`, `15`, `28`, `2A`, `60` gauge/cluster.

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `18DA1DF1`→`18DAF11D` | `22 2201` | **ATF temperature** | `B[26]-40` | °C | -40..215 | Honda Civic ≤2014 — *corroborated* by Torque threads using mode 22 PID 2201 for transmission temperature |
| `18DA1DF1` | `22 2221` | Gear | `X` | — | 0..255 | Honda Civic ≤2014, Accord ≤2011 (fcm1) |
| `18DA60F1` | `22 7060` | Maintenance, transmission fluid (life) | `(B[32]*256+B[33])` | — | 0..9999 | Honda Civic |
| `18DA10F1` | `22 2660` | Odometer / time since engine start | `(B[43]*65536+B[44]*256+B[45])` / `(B[46]*256+B[47])` | km / s | — | Honda Accord ≥2011, CR-V (fcm1) |
| `18DA60F1` | `22 7028` | Outside air temperature | `R-40` | °C | -40..215 | Honda Accord ≥2011 (fcm1) |
| `18DA01F1` | `22 202A` | HV battery state of charge | `B[50]` | % | 0..100 | Honda Civic (hybrid) |
| `18DA01F1` | `22 202A` | HV battery total current | `SIGNED(B[57]*256+B[58])/50` | A | -100..200 | Honda Civic |
| `18DA01F1` | `22 202A` | HV battery voltage | `(B[61]*256+B[62])/20` | V | 0..400 | Honda Civic |
| `18DA01F1` | `22 202A` | HV battery total capacity | `(B[167]*256+B[168])/100` | Ah | 0..655 | Honda Civic |
| `18DA01F1`/`18DA16F1` | `22 202C` | HV module 1A/2A/3A/1B/3B/4B temperature | `SIGNED(B[59+2k]*256+B[60+2k])/10` | °C | -40..215 | Honda Civic |

**Gaps:** no verified Honda **engine oil temperature** (the 1.5 VTEC Turbo has
*no oil temperature sensor* — CivicX consensus, so this is a hardware gap not a
data gap), no **oil pressure**, no **TPMS** (Honda uses indirect/ABS-based TPMS
on most EU models — there is nothing to read), no **DPF** (i-DTEC), no **boost**.

### 12.2 Nissan

**Gate:** WMI ∈ {`SJN` Nissan UK Sunderland (Qashqai, Juke, Note, Leaf EU),
`VSK` Nissan Spain Barcelona, `JN1`/`JN8` Japan, `VNV` Maubeuge, `MNT` Thailand,
`MDH` India} × year × drivetrain. `SJN`/`VSK` per Wikibooks + [dot.report SJN](https://dot.report/wmi/SJN); `JN1` per vPIC.

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `758`→`778` | `22 0201/0202/0203/0204` | Tire pressure FL/FR/RR/RL | `A/4` | psi | 0..63.75 | Nissan Altima |
| `758`→`778` | `22 020D..0210` | Tire pressure warning thresholds FL/FR/RR/RL | `A/4` | psi | 0..63.75 | Nissan Altima |
| `743`→`763` | `22 0E25..0E28` | Tire pressure FR/FL/RR/RL | `A*1.7236894` | kPa | 0..440 | Nissan Leaf (fcm1) |
| `797`→`79A` | `22 1103` | 12 V battery voltage | `A/12.5` | V | 0..20 sane | Nissan Leaf (fcm1) |
| `797`→`79A` | `22 1183` | 12 V battery current | `SIGNED(A*256+B)/256` | A | ±128 | Nissan Leaf (fcm1) |
| `797`→`79A` | `22 1156` | Gear | enum `1=P,2=R,3=N,4=D,7=ECO` | — | — | Nissan Leaf (fcm1) |
| `79B`→`7BB` | `21 01` | HV battery voltage | `(S*256+T)/100` | V | 0..450 | Nissan Leaf (fcm1) |
| `79B`→`7BB` | `21 01` | HV state of charge | `(B[31]*65536+B[32]*256+B[33])/8190-17` | % | 0..100 | Nissan Leaf (fcm1) |
| `79B`→`7BB` | `21 01` | HV battery capacity (Ahr) | `(B[35]*65536+B[36]*256+B[37])/10000` | Ah | 0..100 sane | Nissan Leaf (fcm1) |
| `79B`→`7BB` | `21 61` | HV battery health (SoH) | `(C*256+D)/100` | % | 0..100 | Nissan Leaf (fcm1) |
| `7E0` | `22 111F` | Engine oil temperature (R9M 1.6 dCi) | `A-50` | °C | -30..120 | 2016 Qashqai 1.6 dCi — see §10, same ECU as Renault |
| `7E1` | `21 01` | CVT fluid temperature | polynomial, see §10 | °C | -30..140 | 2016 Qashqai CVT — needs `10 C0` |

**Gaps:** no verified petrol-Nissan oil temp/pressure, no DPF, no boost. The
`758`/`797`/`743` TPMS and battery rows are the reliable part.

### 12.3 Volvo — essentially nothing public

**Gate:** WMI ∈ {`YV1` car, `YV4` SUV/XC, `LYV` Volvo China (S90, XC40, XC60),
`XLB` NedCar} × year. `YV1`/`YV4`/`LYV` confirmed via [vPIC](https://vpic.nhtsa.dot.gov/api/vehicles/DecodeWMI/YV4?format=json).

**OBDb: zero.** All nine Volvo repos are empty stubs.

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `7E1` | `22 1E3F` | Transmission fluid temperature | `B` | °C | -40..200 | P3 platform (XC60/V60/S60/XC70 era) — **unverified, single source** ([SwedeSpeed](https://www.swedespeed.com/threads/transmission-fluid-temperature.234661/)); reported *not* working on S80 V8 |

**Explicit gaps for Volvo — no trustworthy public source for any of:** engine oil
temperature (multiple SwedeSpeed threads state modern Volvos **have no engine
oil temperature sensor at all**, so this is likely unobtainable rather than
undiscovered), oil pressure/level, DPF soot/regen, TPMS, 12 V battery SoC,
boost, injector correction, gear.
Volvo's diagnostic data lives behind VIDA/CAN "high-speed" addressing that the
community has not published as mode-22 DIDs. The realistic path is Torque-CSV
import from a user who has one.

---

## 13. Fiat / PSA (Stellantis)

**Gate:**
- **FCA side:** WMI ∈ {`ZFA` Fiat (IT), `ZFB` Fiat MPV, **`SUF` Fiat Auto Poland
  (Tychy — 500, Panda; very common in PL)**, `ZAR` Alfa Romeo, `ZAC` Jeep Italy
  (Melfi — Renegade/Compass), `9BD` Fiat Brazil} × year × fuel.
- **PSA side:** WMI ∈ {`VF3` Peugeot, `VR3` Peugeot (2019+), `VF7` Citroën,
  `VR7` Citroën (2019+), `VR1` DS, `W0V` Opel (see §11)} × year × fuel.
`ZFA`,`ZFB`,`ZAR`,`ZAC`,`VF3` confirmed via vPIC; `SUF`,`VF7` via Wikibooks/WALL-E;
`VR1`/`VR3`/`VR7` per Stellantis WMI reporting (search-corroborated, not in vPIC —
treat as *corroborated*).

**FCA addressing:** 29-bit `18DA<tt>F1`, same as Honda. Targets: `10` ECM,
`18` TCM, `40` BCM, `33` (`DB33` = functional broadcast for mode 01).
**Almost every FCA engine DID requires `10 03` (extended session).**

The **Jeep Renegade** signalset (1.6/2.0 MultiJet, EDC17) is the best public FCA
diesel dataset and applies directly to **Fiat 500X / Tipo / Doblò MultiJet**
(same ECU family) — gate it as *inherited/unverified* on `ZFA`/`SUF`.

| Header→resp | Mode+PID | Name | Formula | Unit | Range | Verified on |
|---|---|---|---|---|---|---|
| `18DA10F1` | `22 18E4` | **DPF soot clogging level** | `(A*256+B)*1000/65535` | % | 0..100 | Jeep Renegade — **session 03**, fcm1 |
| `18DA10F1` | `22 18E2` | DPF differential pressure | `(A*256+B)/10-3276.8` | kPa | -50..50 | Jeep Renegade — **session 03** |
| `18DA10F1` | `22 18DE` | DPF inlet temperature | `(A*256+B)*2/100-40` | °C | -40..900 | Jeep Renegade — **session 03**, fcm1 |
| `18DA10F1` | `22 3915` | DPF outlet temperature | `(A*256+B)*2/100-40` | °C | -40..900 | Jeep Renegade — **session 03** |
| `18DA10F1` | `22 3807` | Distance since last DPF regen | `(A*65536+B*256+C)/10` | km | 0..10000 | Jeep Renegade — **session 03** |
| `18DA10F1` | `22 3809` | Distance between regens (last-5 avg) | `(A*256+B)*10` | km | 0..10000 | Jeep Renegade — **session 03** |
| `18DA10F1` | `22 380A` | Regen duration (last-5 avg) | `(A*256+B)/60` | min | 0..30 | Jeep Renegade — **session 03** |
| `18DA10F1` | `22 3808` | Regen temperature (last-5 avg) | `(A*256+B)/50-40` | °C | -40..900 | Jeep Renegade — **session 03** |
| `18DA10F1` | `22 380B` | **DPF regen progress** | `(A*256+B)*100/65535` | % | 0..100 | Jeep Renegade — **session 03** |
| `18DA10F1` | `22 18EC` | Forced regen state | `A` | enum | 0..255 | Jeep Renegade — **session 03** |
| `18DA10F1` | `22 18A4` / `22 3912` | Regens started / completed | `(A*256+B)` | count | 0..65535 | Jeep Renegade — **session 03** |
| `18DA10F1` | `22 18A5` | Regens interrupted | `A` | count | 0..255 | Jeep Renegade — **session 03** |
| `18DA10F1` | `22 18E8` | DPF flow resistance | `(A*256+B)/10000` | — | 0..10 | Jeep Renegade — **session 03** |
| `18DA10F1` | `22 392B` | NOx storage catalyst load | `(A*256+B)*100/65535` | % | 0..100 | Jeep Renegade — **session 03** |
| `18DA10F1` | `22 195A` | **Turbo boost pressure** | `(A*256+B)/10-3276.8` | kPa | -100..300 | Jeep Renegade — **session 03** |
| `18DA10F1` | `22 1947` / `22 1946` | Fuel rail pressure measured / commanded | `(A*256+B)/20` | bar | 0..3000 | Jeep Renegade — **session 03** |
| `18DA10F1` | `22 18D0` | M-Prop (metering unit) injection command | `(A*256+B)/100` | % | 0..100 | Jeep Renegade — **session 03** |
| `18DA10F1` | `22 3813` | **Engine oil degradation** | `(A*256+B)*100/65535` | % | 0..100 | Jeep Renegade — **session 03** |
| `18DA10F1` | `22 1955` | Battery voltage (ECM) | `(A*256+B)/2000` | V | 0..20 | Jeep Renegade — **session 03** |
| `18DA18F1` | `22 04FE` | **Transmission fluid temperature** | `A-40` | °C | -40..215 | Jeep Renegade |
| `18DA18F1` | `22 1D09` | Automatic transmission temperature (alt) | `A-50` | °C | -50..205 | Jeep Renegade |
| `18DA18F1` | `22 051A` | Current gear / desired gear | `bit4..7` / `bit0..3` | — | 0..15 | Jeep Renegade |
| `18DA18F1` | `22 2102` | Gearbox output speed | `(A*256+B)/4` | rpm | 0..16383 | Jeep Renegade |
| `18DA40F1` | `22 1004` | Battery voltage (BCM) | `A/10` | V | 0..25 | Jeep Renegade |
| `18DB33F1` | `01 7C` | DPF temperature bank 1 sensor 1 / 2 | `(B*256+C)/10-40` / `(D*256+E)/10-40` | °C | -40..900 | Jeep Renegade — standard mode 01, no session needed |

**Gaps — PSA (Peugeot / Citroën / DS / post-2018 Opel combustion): nothing
verified.** No OBDb repo has data; the FAP/DPF and additive (Eolys) data lives in
DiagBox/Lexia and has not been published as mode-22 DIDs with formulas. The
`6xx`-header pattern from Corsa-e (§11.2) is the right *shape* for PSA, but the
combustion-side DIDs are unknown. Do not guess.

**Fiat petrol (500/Panda 1.2/0.9 TwinAir):** no verified extended PIDs at all.

---

## 14. Global implementation notes

### 14.1 The `10 <session>` decision — needs a call

Our Mazda safety rule is *"never send `10`"*. But:
- **VAG:** ~40 % of the DPF DIDs are `din: 03`.
- **FCA:** essentially *all* engine DIDs are `din: 03`.
- **Renault:** the corroborated R9M oil-temp/CVT-temp configs use `10 C0`.

`10 03` (extendedDiagnosticSession) is a **read-only-safe** session in UDS —
it unlocks nothing by itself, and unlike `10 02` (programming) it does not stop
normal ECU operation. The real risks are (a) the ECU sets a "diagnostic active"
flag some cars log, (b) the session times out and needs `3E` tester-present
keep-alives, which we do not want to run while driving.

**Recommendation:** allow `10 03` only when *parked* (speed = 0, engine may run),
behind an explicit user opt-in per vehicle, always followed by `10 01` (return to
default) and never with a `3E` keep-alive loop. Ship those PIDs at Slow tier
only. Keep `10 C0` (Renault-proprietary) disabled until someone verifies it on a
car we control.

### 14.2 Flow control (`fcm1`)

Any row marked `fcm1` returns a multi-frame ISO-TP response that the ELM will
truncate unless you configure flow control:

```
ATFCSH <request header>      // e.g. 6F1, 713, 7A0, 750
ATFCSD 300000                // BS=0, STmin=0 → send all frames
ATFCSM1                      // use the configured FC, mode 1
```

Reset with `ATFCSM0` afterwards. `ATFCSM1` returning anything but `OK` means the
adapter can't do BMW / VAG-`1821` / Hyundai-TPMS / Toyota-TPMS — degrade
gracefully and hide those metrics.

### 14.3 Extended addressing (`eax`)

Emit `eax` as the **first data byte of the request**, not as part of the header:

```
BMW DME oil temp:   ATSH 6F1 · ATCRA 612 · ATFCSH 6F1 · ATFCSD 300000 · ATFCSM1
                    → "12 22 4506"
Toyota TPMS:        ATSH 750 · ATCRA 758 · (tst 2A) · ATFCSM1
                    → "2A 21 30"
```

### 14.4 29-bit brands

Honda and FCA need a protocol switch (`ATSP7`, 29-bit 500 k) plus
`ATSH DA<tt>F1` / `ATCRA 18DAF1<tt>`. That is an expensive round trip — batch
all 29-bit requests together, once per cycle, and switch back to `ATSP6`/`ATSP0`
before the next standard poll. Consider making the whole Honda/FCA extended set
a *parked-only snapshot* rather than a live tier.

### 14.5 Mode-01 mirroring (free wins)

Three brands mirror the mode-01 PID space into a DID range, sometimes exposing
channels that `01` doesn't advertise:

| Brand | Alias | Example |
|---|---|---|
| Mazda | `22 F4nn` ≡ `01 nn` | `F433` baro, `F4A6` odometer |
| Ford | `22 F4nn` ≡ `01 nn` | `22 F40B` manifold pressure |
| GM/Opel | `22 00nn` ≡ `01 nn` | `22 000C` rpm, `22 0005` coolant, `22 0010` MAF |

Probe the alias when a standard PID is missing from the `0100`/`0120` bitmask.

### 14.6 Probe/persist strategy (unchanged from Mazda)

Filter the table by VIN → group by (protocol, header, eax, session) → one request
each under `exclusive{}`. `62`/`61` = supported (persist per VIN);
NRC `31`/`12`/`11` = permanently absent (persist); `NO DATA` = retry next
connect (usually a missing `ATCRA` or `ATFCSM1`). Budget ≤2 extended PIDs +
one header switch per cycle; protocol switches (29-bit) at most once per session.

### 14.7 Safety

Read-only `22`/`21` only. Never `2E`/`2F`/`31`/`14`/`11`/`27`/`85`. `10 03` only
under §14.1's conditions, always paired with `10 01`. Chassis/TPMS modules
parked only. Never blind-scan while driving.

---

## 15. Priority-sensor coverage matrix

✅ verified (OBDb capture) · ◐ corroborated community · ✗ no trustworthy source

| Brand | Oil temp | Oil press | Oil level | Trans/DSG temp | 12 V SoC/I | HV batt | TPMS | DPF | Boost | Injector corr. | Gear |
|---|---|---|---|---|---|---|---|---|---|---|---|
| VW/Audi/Škoda/SEAT | ✅ | ✅ (DSG) | ✅ | ✅ | ✅ | ✅ (ID/e-Golf) | ✅ | ✅ | ✅ | ✅ | ✅ |
| Toyota/Lexus | ✅ | ✅ | ✗ | ✅ | ✅ | ✅ | ✅ | ✗ | ✗ | ✗ | ✅ |
| Ford (EU) | ◐ | ✅ | ✗ | ✅ | ✅ | ✅ (Mach-E) | ✅ | ◐ | ✅ | ✗ | ✅ |
| Hyundai/Kia | ✅ | ✗ | ✗ | ✅ | ✅ | ✅ | ✅ | ◐ (partial) | ✅ | ✗ (duration only) | ✅ |
| Renault/Dacia | ✅ (modeled) + ◐ | ✗ | ✗ | ◐ (CVT) | ✅ | ✗ | ✗ | ✗ | ✅ | ✗ | ✅ |
| Opel pre-PSA | ✗ | ✗ | ✗ | ◐ | ✗ | — | ✗ | ◐ | ✗ (VGT ◐) | ✗ | ✗ |
| Opel post-PSA | ✗ | ✗ | ✗ | ✗ | ✗ | ✅ (Corsa-e) | ✗ | ✗ | ✗ | ✗ | ✗ |
| BMW/Mini | ✅ | ✅ (MINI) | ✗ | ✅ (MINI) / ◐ | ✗ | ✅ | ✗ | ✗ | ◐ (VGT) | ✗ | ✅ |
| Mercedes | ✗ (try `01 5C`) | ✗ | ✗ | ◐ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| Honda | ✗ (no sensor) | ✗ | ✗ | ✅ | ✗ | ✅ | ✗ | ✗ | ✗ | ✗ | ✅ |
| Nissan | ◐ (dCi) | ✗ | ✗ | ◐ (CVT) | ✅ (Leaf) | ✅ (Leaf) | ✅ | ✗ | ✗ | ✗ | ✅ (Leaf) |
| Volvo | ✗ (likely no sensor) | ✗ | ✗ | ◐ (unverified) | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| Fiat/FCA | ✗ (degradation % ✅) | ✗ | ✗ | ✅ | ✅ | — | ✗ | ✅ | ✅ | ✗ | ✅ |
| PSA (Peugeot/Citroën) | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |

### Suggested build order (EU/PL fleet share × data quality)

1. **VW Group** — largest PL parc, richest verified set, standard 11-bit. Start
   with Golf/Tiguan/Jetta rows + the `710` battery block (applies to *every*
   MQB car regardless of engine).
2. **Toyota/Lexus** — mode-21 blocks are one request for many signals; hybrid
   set is a genuine differentiator.
3. **Ford EU** — nearly free, the DID space is the Mazda space we already ship.
4. **Hyundai/Kia** — high PL share, clean `22 E0xx` set.
5. **Renault/Dacia** — high PL share (Dacia especially), decent engine set.
6. **BMW** behind an `ATFCSM1` capability probe.
7. **FCA** (Fiat/Jeep) as a parked-only 29-bit snapshot, if `10 03` is approved.
8. Opel, Honda, Nissan: thin sets, ship what's here.
9. Mercedes, Volvo, PSA: **Torque-CSV import only** until users contribute
   captures. Consider adding an in-app "contribute a capture to OBDb" flow —
   that is literally how every row above came to exist.

---

## 16. Shipped, and where the implementation departs from the notes above

Build order 1–5 and 8 are in. Item 6 (BMW) and item 7 (FCA) are not, for the reason
below, and neither is anything behind `10 03`.

**88 entries across eight new marques, 35 new readings**, on top of the 19 Mazda entries
already shipped (107 entries, 46 distinct readings in all). One file per marque in
`app/src/main/java/com/miskibin/obd2dashboard/obd/`, one gate per entry, and
`ExtendedBrandPidTest` decodes a worked example from the tables above for every one.

| Brand | Shipped | Skipped, and why |
|---|---|---|
| **VW Group** (§4) | **26** — cluster oil temp (`202F`) + odometer (`2203`); oil level `11BA`, boost `1057`; four injector corrections (diesel); the whole `710` battery block (SoC, temperature, voltage, resistance, **both** `2A09` current decodings); DSG `2104`; seven DPF rows (`1ABE`, `2609`, `1ABD`, `1ABA`, `1AC9`, `11B2`, `10F9`, diesel); four `70B` tyre pressures | Every `din: "03"` row (`114E`, `114F`, `1044`, `14F5`, `1156`, `1153`) — session switch, out of scope. `179C`/`20A1`/`11BC` oil-temp variants and `11C4`/`11C5` oil marks — a second entry for a reading another entry already fills. `1821` TPMS bitfields, `17FC00xx` (29-bit, `dbg`), gear/consumption rows — no decodable meaning without an enum table |
| **Toyota/Lexus** (§6) | **7** — `21 51` oil temp, `21 82` gearbox pan temp, `22 1002` twelve-volt (MY2016+), hybrid `1F5B`/`1F9A`×2/`10B2` | `22 1074` oil pressure: two published scalings 12.8× apart, both landing in a plausible range — nothing distinguishes them. `1F5C`/`107B`/`1627`: duplicate readings of the `21` rows. TPMS (`750` + eax `2A`) — extended addressing, see below. Cell voltages, motor torque, `21 C3` block — one request per number is too dear for the value |
| **Ford EU** (§5) | **12** — the Mazda identifiers on a Ford gate (`0415`, `1310` gated, `1E1C` in *sixteenths*), `0461`, `0334`, `0462`, `033E`, `4029`, four `2813`–`2816` tyre pressures | `22 4028` battery SoC — percent-of-255 against plain percent, both plausible, no way to tell. `0579`/`057B` DPF — single forum source. `03DC`/`0548` fuel pressures, misfire counters, `61A5` lamp — thin value against another module switch. `7DF` rows rewritten to `7E0` as the trap says, then dropped as duplicates |
| **Hyundai/Kia** (§7) | **17** — oil temp under **both** services split by year (`22 E001` ≥2014, `21 01` ≤2013), boost `E021`, `E004` SoC + SoH, `21 A0` gearbox, `B002` cluster voltage, `0101` HV charge, **eight** `C00B` tyre pressures (two year-gated layouts), `21 03` distance since regen (diesel) | Tyre *temperatures* and the sensor battery enum. `E002` injection durations — four more readings for a number that is not a correction. `21 06`/`21 1B` DPF state and pressure — a bit and a raw count with no unit |
| **Renault/Dacia** (§10) | **7** — `2007` (modelled) oil temp, `2401` boost, `2004` torque, `2005` supply voltage, `2057` alternator, `222A` refrigerant, `4B06` odometer | `111F` — the same reading from a second identifier; it ships on Nissan instead, where nothing else covers it. CVT temperature and the R9M configs need `10 C0`. `748` Duster rows would fill `battery_voltage` twice. `2001`/`240B`/`FD81` duplicate standard PIDs |
| **Opel GM** (`W0L`) (§11.1) | **5** — `3039`, `20F4`, `20F8`, `1152`, `1543`, all diesel-gated | `336A` soot % — published both as `A*100/255` and as plain `A`, both inside 0–100, so a wrong pick understates soot by 2.55×. `AA 0118` streaming — not a mode this app speaks. `0023`/`0034` duplicate standard PIDs |
| **Opel PSA** (`W0V`) (§11.2) | **5** — `D410`, `D815`, `D860`, `D865`, `D49C` on `6B4`→`694` | `D810` (duplicate of `D410`), cell min/max, `D402` speed (standard PID). Nothing at all for the PSA-era combustion cars: none is published |
| **Nissan** (§12.2) | **8** — four `0201`–`0204` tyre pressures, `1103` twelve-volt, `21 01` HV charge, `21 61` HV health, `111F` oil temp (diesel) | `743` Leaf tyre rows (duplicate), `1183`/`1156` — a current and a gear enum |
| **Mercedes** (§9) | **1** — `7E1 22 2130` gearbox temperature, range-gated | Everything else: the only captured Mercedes data is a G-Class's stability sensors, which are not this app's subject. Engine oil temperature is tried as standard PID `01 5C`, which the app already asks every car for |
| **BMW/MINI** (§8) | **0** | Every BMW identifier needs tester `6F1` with an extended address byte *prepended to the request data*. The adapter layer here sets a header and a receive filter and restores `ATSH 7DF`; it has no notion of a request prefix, and half-supporting one addressing mode is worse than not having it |
| **Honda** (§12.1), **FCA** (§13) | **0** | 29-bit `18DA<tt>F1`, which means a protocol switch (`ATSP7`) and a way back. On top of that, *every* Jeep Renegade engine identifier is `din: "03"`; the Honda and FCA rows that are not would be a protocol switch for a gearbox temperature |
| **Volvo** (§12.3), **PSA** (§13) | **0** | One unverified single-source row for Volvo, nothing at all for Peugeot/Citroën/DS. Exactly as §15 says |

### Departures from the notes above

- **Service `21` is implemented; extended addressing and 29-bit are not.** A one-byte
  local identifier was a small, testable change to the request shape and it is what makes
  Toyota and the older Hyundais readable at all. BMW's `eax` prefix and the Honda/FCA
  protocol switch are each a different thing — a change to *addressing* — and the rule
  was not to half-support one. They are absent, not stubbed.
- **Flow control is configured per module, not per request.** `ATFCSH`/`ATFCSD 300000`/
  `ATFCSM1` go out with the module switch when any parameter on that module needs them and
  `ATFCSM0` comes back in the `finally`, next to the header restore. An adapter that cannot
  do it answers something other than `OK`, the long reads come back short, and a short read
  is treated as no answer — a missing reading rather than a wrong one.
- **One request per reading.** §6 is right that `21 51` is a bargain — one request, many
  signals — and this implementation does not take it: the scheduler's unit of work is a
  parameter, so two readings out of one block cost two requests. That is why only two
  readings are taken from each Toyota block rather than the six that are in there.
- **No `10 03`, and therefore no §14.1.** The recommendation to allow an extended session
  while parked, behind an opt-in, is not implemented. Every identifier that needs one is
  absent, and a test asserts the table contains none of them by number.
- **The diesel gate is the driver's profile, not the VIN.** Nothing in a European VIN says
  which engine was fitted, so the filter and injector rows are offered only once the
  vehicle profile says "diesel". An unfilled profile is not treated as petrol: it is
  treated as not knowing, and the diesel rows are not asked for.
- **`VNV` is claimed by nobody.** §10 gives it to Renault and §12.2 to Nissan — Maubeuge
  builds for both. A VIN that could be either settles nothing, so it matches no marque.
- **Conflicting decodings ship only where the car can settle it.** Two entries with one
  name are allowed when they are rival decodings of *one* request and a plausibility range
  tells them apart — VAG `2A09`'s 24-bit and 32-bit currents, where the wrong reading comes
  out in the thousands of amps. Where both readings are plausible (Ford `4028`, Opel
  `336A`, Toyota `1074`) neither ships. Where one scaling is borrowed rather than captured
  (Ford `1310` from Mazda, Mercedes `2130`'s byte offset) it ships behind a range gate, so
  a car that turns out to answer differently shows nothing rather than something wrong.
- **Reading names are marque-neutral.** The Mazda entries' ids lost their `mazda_` prefix
  (`ext_oil_pressure`, `ext_tyre_pressure_fl`, …) so that a Ford, a Golf and a Mazda fill
  the same tile with the same reading. Nothing had shipped under the old ids.
- **Multi-frame answers are read but not chased.** `22 C00B` and the other long block reads
  are decoded out of the reassembled response, and where the reading sits past the first
  frame the flow-control configuration above is what gets the rest of it.

---

## Sources

**Primary (captured ECU responses):**
- [github.com/OBDb](https://github.com/OBDb) — per-model `signalsets/v3/default.json`, CC BY-SA 4.0.
  Repos used: Audi-Q3, Audi-A4/A6/RS-3/TT/A1, Volkswagen-Golf/Tiguan/Jetta/Polo/Amarok/ID.4/e-Golf,
  Seat-Leon/Ibiza, Toyota-Camry/Prius/RAV4/RAV4-Hybrid/Corolla/Hilux/Land-Cruiser/4Runner,
  Lexus-RX/IS, Ford-Focus/Edge/Explorer/Ranger/Fiesta/F-150/Mustang-Mach-E,
  Hyundai-Elantra/Santa-Fe/Sonata/Kona/Tucson/Tucson-Hybrid/IONIQ-5/IONIQ-6/Ioniq/Kona-Electric,
  Kia-Sportage/Forte/Soul/Optima/EV6, Renault-Megane/Kadjar, Dacia-Duster,
  BMW-3-Series/4-Series/5-Series/X1/X3/X5/i3/iX3, MINI-Cooper, Mercedes-Benz-G-Class,
  Honda-Civic/Accord/CR-V, Nissan-Leaf/Altima, Jeep-Renegade, Opel/VauxhallOpel-Corsa-e.
- [OBDb/.schemas `signals.json`](https://github.com/OBDb/.schemas/blob/main/signals.json) — v3 schema (fmt/bix/len/mul/div/add/sign, hdr/rax/eax/tst/pri/din/dout/fcm1, filter/dbgfilter).
- [OBDb/.schemas `examples/extended-addressing.json`](https://github.com/OBDb/.schemas/blob/main/examples/extended-addressing.json) — BMW `6F1`+eax reference.
- [github.com/drewid74/2024-nd3-mazda-obdii](https://github.com/drewid74/2024-nd3-mazda-obdii) — Mazda baseline (see companion doc).

**Tooling / semantics:**
- [Pelican — Extended OBD-II Parameters](https://pelican.clutch.engineering/scanning/extended-pids/) — rax/eax/fcm1 semantics.
- [Car Scanner — custom PIDs](https://www.carscanner.info/custompids/) — Torque/Car Scanner formula letter convention, start/stop diagnostic commands.

**Community (corroborated):**
- Opel/Vauxhall: [opelastraclub "PIDS PARA TORQUE"](https://www.opelastraclub.com/t27293-pids-para-torque) · [Torque forum "Vauxhall (Opel GM) DPF PID"](https://torque-bhp.com/community/main-forum/vauxhall-opel-gm-dpf-pid/)
- Mercedes: [MBWorld W204 transmission temperature](https://mbworld.org/forums/c63-amg-w204/798801-transmission-temperature.html) · [BenzWorld Torque Pro transmission temperature](https://www.benzworld.org/threads/transmission-temperature-using-torque-pro.3074586/) · [MBClub DPF soot level](https://forums.mbclub.co.uk/threads/dpf-soot-level-display.276581/)
- Renault/Nissan R9M: [Torque forum "Renault/Nissan 1.6 dCi R9M engine transmission oil PID"](https://torque-bhp.com/community/main-forum/renault-nissan-1-6-dci-r9m-engine-transmission-oil-pid-2/)
- VW DPF: [T6 Forum "VW T6 custom PID codes for DPF"](https://www.t6forum.com/threads/vw-t6-custom-pid-codes-for-dpf.33964/)
- Ford DPF: [talkford "Extra PIDs for 2.0 Duratorq"](https://www.talkford.com/threads/extra-pids-for-2-0-duratorq-inside.234095/) · [FORScan forum DPF PID lists](https://forum.forscan.org/viewtopic.php?t=5298)
- Volvo: [SwedeSpeed transmission fluid temperature](https://www.swedespeed.com/threads/transmission-fluid-temperature.234661/) · [SwedeSpeed Volvo OBD2 PID for oil temperature](https://www.swedespeed.com/threads/volvo-obd2-pid-for-oil-temperature.624653/) · [Torque forum Volvo transmission temp](https://torque-bhp.com/community/main-forum/volvo-pid-for-transmission-temp/)
- BMW: [E46Fanatics oil temperature via OBD](https://www.e46fanatics.com/threads/oil-temperature-via-obd.1106967/)
- Honda: [CivicX PIDs for Torque/Torque Pro](https://www.civicx.com/forum/threads/pids-for-torque-torque-pro.23291/) · [Piloteers Honda custom OBD2 PIDs](https://www.piloteers.org/threads/monitor-honda-custom-obd2-pids-transmission-temp-etc.137202/)
- Toyota hybrid: [PriusChat GenIII custom PIDs](https://priuschat.com/threads/geniii-prius-custom-pids-for-torque-app.98693/) (CSV attachment; PID list not reproduced inline — OBDb rows above supersede)

**WMI verification:**
- [NHTSA vPIC DecodeWMI API](https://vpic.nhtsa.dot.gov/api/vehicles/DecodeWMI/WVW?format=json) — authoritative for US-registered WMIs (VW, Audi, BMW, Mercedes, Toyota/Lexus, Hyundai/Kia, Ford, Honda, Volvo, Fiat/Alfa, Renault, Peugeot, GM/Opel).
- [Wikibooks — Vehicle Identification Numbers / WMI](https://en.wikibooks.org/wiki/Vehicle_Identification_Numbers_(VIN_codes)/World_Manufacturer_Identifier_(WMI)) and its machine-readable copy [WALL-E/vin-decoder `wmi-from-wiki.csv`](https://github.com/WALL-E/vin-decoder/blob/master/csv/wmi-from-wiki.csv) — for EU-only WMIs vPIC does not carry (`TMB`, `VSS`, `UU1`, `SJN`, `VSK`, `VSX`, `VXK`, `VF7`, `TMA`, `NLH`, `SUF`, `SB1`, `TW1`, `U6Y`).
- [dot.report WMI SJN](https://dot.report/wmi/SJN) — Nissan Motor Manufacturing (UK).
