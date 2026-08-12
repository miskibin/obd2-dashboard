# Closing the sensor gap with Car Scanner — Mazda 3 Skyactiv-G

Research notes driving the extended-sensor implementation. Sources are cited per
table; entries marked "captured" were validated against real ECU responses.

## How Car Scanner sources extended sensors

- **Connection profiles**: closed, developer-shipped per-brand databases
  (Mazda: SKYACTIV etc.) — ECU addressing + extended PID set + decoders.
- **Custom sensors**: user-entered mode+PID, header, equation.
- **Torque CSV import** — the community lingua franca. Column order:
  `Name, ShortName, ModeAndPID, Equation, Min, Max, Units, Header,
  startDiagnostic, stopDiagnostic, scale, minimumRefreshDelayMillis`.

## Best sources found

- **github.com/OBDb** — per-model signal databases (`Mazda-3`, `Mazda-CX-5`,
  `Mazda-CX-30`, `Mazda-6`, `Mazda-MX-5`, …) with real captured responses as
  test fixtures, model-year attached.
- **github.com/drewid74/2024-nd3-mazda-obdii** — MX-5 ND3 Skyactiv-G captures,
  incl. the only public Mode 06 capture for a modern Mazda and a `22 F4xx`
  range scan.

## PCM — header 7E0 (response 7E8)

| Mode+PID | Name | Formula (A = first byte after `62 xx xx`) | Unit | Verified |
|---|---|---|---|---|
| `22 0415` | Engine oil pressure | `ShortSigned(A,B)`; `0xFFFF` = invalid | kPa | Mazda 3 2014, 2019–2026 captured |
| `22 1310` | Engine oil temperature | `((A*256)+B)/100 − 40` | °C | Mazda 3 2015–2026; ND3 |
| `22 0301` | MAP sensor voltage | `((A*256)+B)/1000` | V | Mazda 3 2014–2026 |
| `22 0307` | Fuel pump load | `((A*256)+B)*100/65535` | % | Mazda 3 2014–2026 |
| `22 0522` | Fuel temperature | `A−40` | °C | Mazda 3 2020+ |
| `22 0519` | Distance since oil change | `((A*256)+B)*4` | km | Mazda 3 (Sky-D) |
| `22 0914`/`0915` | APP sensor 1/2 voltage | `((A*256)+B)/1000` | V | CX-5, MX-5, CX-3, 6 |
| `22 0917`/`0918` | Throttle pos 1/2 voltage | `((A*256)+B)/1000` | V | same |
| `22 091A`/`093C` | Throttle angle desired/actual | `((A*256)+B)/512` | ° | same |
| `22 096E` | Idle speed target | `((A*256)+B)/4` | rpm | CX-5, MX-5 |
| `22 097C` | Generator voltage desired | `((A*256)+B)/2048` | V | CX-5, MX-5 |
| `22 16E9` | Generator output voltage | `((A*256)+B)/8` | V | CX-5, MX-5, 6 |
| `22 16E8` | Generator field coil duty | `((A*256)+B)*200/65535` | % | same |
| `22 1746` | Knock retard | `A*100/284` | ° | CX-5, MX-5 |
| `22 1410` | Injector pulse width | `((A*256)+B)/125` | ms | CX-5, MX-5 |
| `22 16CD`/`CE`/`CF` | Valve timing actual/error/OCV duty | `/16`, `/16`, `*200/65535` | °,°,% | CX-5, MX-5 |
| `22 163E` | Rear O2 fuel trim | `((A*256)+B)*100/8192` | % | CX-5, MX-5 |
| `22 1104` | A/C refrigerant pressure switch | `GetBit(A,7)` | on/off | CX-5, MX-5 |
| `22 1101` | A/C relay b7, brake b6, in-gear b4, PS b0 | `GetBit(A,n)` | — | CX-5, MX-5 |
| `22 1103` | MIL b2, IMRC b3, fan relay b4 | `GetBit(A,n)` | — | CX-5, MX-5 |
| `22 4028`/`4029` | i-stop battery SoC / temp (IBS) | `A` / `A−40` | %/°C | unverified on G |
| `22 1177` | MAF voltage | `((A*256)+B)/1000` | V | CX-5, MX-5 |

Diesel-only (never probe on petrol): `22 0380` regen status, `042C/042D/044B`
DPF soot, `0432/0433` regen counts, `0434` regen distance, `03DB` exhaust ΔP V,
`03DF/050F` VGT, `03FB/0306` EGR, `F47A` DPF ΔP.

## TCM — header 7E1

| Mode+PID | Name | Formula | Note |
|---|---|---|---|
| `22 1E1C` | Transmission fluid temp | `((A*256)+B)/80` °C | conflicting alt `/16` in some lists — ship `/80`, sanity-gate −40..160, cross-check cold-start vs coolant |
| `22 1E12` | Gear position | enum 16=1,32=2,48=3,64=4,80=5,96=6; 50=N,60=R,70=P | prefer `01 A4` |
| `22 1E24` | TC lock-up | `A` | |
| `22 1EE4`/`1EC7` | Torque actual/desired | `(SIGNED(A)*256+B)/50` N·m | |

## Other modules (need ATCRA — see traps)

| Header→resp | Mode+PID | Name | Formula | Applies |
|---|---|---|---|---|
| `720`→`728` | `22 2A05..2A08` | Tyre pressure 1–4 | `A*1373/100000` bar | Mazda 3 2014–2018 (BM/BN) |
| `720`→`728` | `22 2A0A..2A0D` | Tyre temp 1–4 | `A−50` °C | BM/BN |
| `720`→`728` | `22 61B1` | Fuel level (litres) | `A` | 3, 6, CX-3/5/9, MX-5 |
| `726`→`72E` | `22 D922..D925` | Tyre pressure FL/FR/RL/RR | `A/5` psi | Mazda 3 2019+ (BP) |
| `726`→`72E` | `22 D926..D929` | Tyre temp FL/FR/RL/RR | `A−50` °C | BP |
| `760` (DSC) | `22 2B0D` | Brake pedal position | `max(0,ShortSigned(A,B))/2.3` % | ND3; probe on 3 |
| `726` | `22 DA05` | Brake switch | `GetBit(A,7)` | 2022 Mazda 3 |

## The `22 F4xx` alias

Mazda PCM mirrors the Mode 01 PID space at DID `F4nn`, sometimes with more
channels or when `01 nn` is not advertised. Confirmed by ND3 range scan
(60 hits): `F433`=baro, `F463`=ref torque, `F4A6`=odometer, `F4A4`=gear ratio.
Useful: `F470` boost control (des `(B*256+C)/32`, act `(D*256+E)/32` kPa),
`F477` charge-air temp (`C−40`), `F47A` DPF ΔP (`SIGNED(B*256+C)/100` kPa),
`F46F` compressor inlet (`B*8`,`C*8`).

## Not public (do not invent): A/C pressure sensor + voltage, evaporator temp,
compressor cycling/request/load — names exist in Mazda FSM PID tables and
commercial add-ons but DIDs are not public. Path: user range probe of
`22 03xx/11xx/13xx` on 7E0, or Torque CSV import.

## Standard coverage still to add

Mode 01: `0x9D` fuel rate g/s (`((A*256)+B)/50`, ND3-verified), `0x8E` friction
torque (`A−125` %), `0x65` aux I/O + recommended gear (shift light — NOT current
gear), `0x6D` rail pressure control (+rail temp), `0x64` percent-torque points,
`0x70` boost control, `0x7A` DPF ΔP, `0x03`, `0x13`, `0x1C`, `0x41`, `0x9F`.
Ship 9D/8E/65 immediately; validate 70/7A/64/6D on car.

### Mode 06 (per-cylinder misfires live here)

`06 00` → MID bitmask (chains like 0100). `06 <MID>` → SID `46`, repeating
9-byte records `MID|TID|UASID|value16|min16|max16`; pass = min≤value≤max.
MIDs: `01–08` O2, `21/22` catalyst B1/B2, `31/32` EGR, `39–3C` EVAP, `3D`
purge, `41–48` O2 heaters, `A1` misfire general, `A2–AD` misfire cyl 1–12.
Misfire TIDs: `0B` EWMA/10 cycles, `0C` this cycle; UASID `0x24` = raw counts.
ND3 capture: A2–A5 with TID 0B/0C; catalyst OSC at MID `21` TID `82`
(×0.01 g — trending this predicts P0420/P0421 early); O2 switch times MID `02`
TID `05/06` (×0.001 s); heater resistance MID `41/42` TID `80`.

### Mode 09 IPT

`09 08` (spark): first byte = item count, then 16-bit counters in fixed order
(OBDCOND, IGNCNTR, CATCOMP1, CATCOND1, …, EGRCOMP, EGRCOND). Says whether each
monitor actually ran.

## Safety

Read-only `22` only. Never send 2E/2F/31/14/11/27/85; no `10` session change.
NRC: `31/12/11` → permanently unsupported per VIN; `22/21` → retry later;
`78` responsePending → keep waiting. NO DATA on non-7Ex header usually means a
missing `ATCRA`. Don't blind-scan while driving; chassis modules only parked.
Known-dead on ND3: `730/22 20xx,06xx`, `760/22 21xx,06xx`.

## Implementation shape

- `ExtendedPid(id, header, receiveHeader, request, nameRes/hintRes/descriptionRes,
  unit, decimals, bytes, tier, applies: VehiclePredicate, decode)` — Kotlin
  lambdas, no formula interpreter for the curated set. Separate Torque-CSV
  importer for user PIDs marked "unverified". New `MetricId.Extended(id)`.
- VIN gating: marque Mazda (JM1/JM7/JMZ), model year (pos 10), fuel type.
  BM/BN vs BP selects the TPMS block; diesel block gated off on petrol.
- ELM traps: response prefix = SID+0x40 (`22`→`62`); non-7Ex headers need
  `ATCRA <resp>` before and `ATCRA` reset after. Sort due list by header,
  switch once per header per cycle.
- Probe-on-connect under `exclusive{}`: filter by VIN → group by header → one
  request each; `62`=supported (persist per VIN), NRC 31/12/11=absent (persist),
  NO DATA=retry next connect. ~20 requests ≈ 1–2 s once per vehicle.
- Mode 06/09: session-boundary snapshots (connect + disconnect), trended across
  sessions (catalyst OSC, misfires per cylinder, O2 switch times).
- Budget: ≤2 extended PIDs per cycle + one header switch. Oil pressure/temp on
  Medium; rest Slow; TPMS min 15 s interval.

## First slice

1. Mode 01: 9D, 8E, 65, 03, 13.
2. Mode 06 reader + snapshot (per-cylinder misfires, catalyst OSC).
3. ExtendedPid table + probe: `7E0/220415`, `7E0/221310`, `7E1/221E1C`,
   TPMS `726`/`720`.
4. Mode 09 `0908` IPT.
5. Torque CSV import.

### Shipped, and where the implementation departs from the notes above

Items 1–4 are in; item 5 (Torque CSV import) is not.

- **`01 65` layout confirmed.** OBDb's `SAEJ1979` signalset settles it: byte A bit 4
  "recommended gear supported", byte B bits 7–4 the gear itself, and A/B bits 3–0 glow
  plug lamp, manual-trans neutral, auto-trans neutral, PTO. Every channel is gated on its
  own support bit. Wikipedia lists the PID as "auxiliary input/output supported" with no
  bit table at all, so the note above was right and its source was worth chasing.
- **`01 9D` is four bytes, not two**: engine fuel rate `(256A+B)/50` g/s *and* vehicle
  fuel rate `(256C+D)/50`. Both ship as channels; the byte count matters because getting
  it wrong desynchronises every PID after it in a multi-PID frame.
- **`01 13` is used twice**: as a reading (how many probes the exhaust has) and, more
  usefully, to stop the scheduler asking after the `0114`–`011B` the car lists in its
  support block and does not actually have.
- **Mode 06 scaling** comes from the *test* (misfires in counts, catalyst OSC ×0.01 g,
  switch times ×0.001 s) rather than from a UAS identifier table. The standard's table has
  ~60 entries and is not published anywhere checkable; an unknown identifier therefore
  keeps its raw counts and its own pass limits rather than being multiplied by a guess.
- **TPMS is reported in bar on both generations.** The captures are in psi (BP) and bar
  (BM/BN); one metric that changes unit with the model year would be worse than the
  conversion.
- **No disconnect-side Mode 06 snapshot.** A session ends by being cancelled or by the
  link dropping, so the moment there would be something to read is the moment there is no
  longer anything to read it over. One snapshot per connection into a per-VIN history
  gives the same series without the timeouts.
- **The reading names lost their marque.** When the other marques were seeded (see
  `research-multibrand-extended-pids.md` §16) these entries' ids went from `mazda_oil_pressure`
  to `ext_oil_pressure` and so on. A Ford answers `22 0415` for the same reason a Mazda does,
  and a name that says otherwise would mean two tiles for one reading.
- **Extended parameters are eleven-bit CAN only.** `ATSH 7DF` is that protocol's
  functional address and nothing else, and a probe that could not put the header back
  would leave every Mode 01 request going nowhere for the rest of the session.

Sources: OBDb (github.com/OBDb), drewid74/2024-nd3-mazda-obdii, carscanner.info
(profile changelog, custompids), agronick gist (Torque CSV), mazda-si.net,
Mazdas247 threads (CX-9 TFT, TPMS), RLEscalambre & Total Car Diagnostics
(Mode 06), Wikipedia OBD-II PIDs (Mode 09 IPT), Mazda FSM PID tables
(mazdamanuals.com CX-30).
