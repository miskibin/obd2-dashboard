# OBD-II over an ELM327 adapter (Vgate iCar2) from Android — research notes

Scope: everything needed to build the transport + protocol layer of an Android OBD-II
dashboard against a **Vgate iCar2** (and compatible ELM327 clones). Covers hardware
variants and their Android connection paths, the ELM327 AT command layer, SAE J1979
Mode 01 live data, DTC modes 03/07/0A/04, and practical throughput engineering.

All UUIDs, commands, formulas and error strings below are taken from the sources listed
at the bottom; the authoritative reference for the AT layer is the **ELM327 v2.1
datasheet (ELM327DSJ)** from Elm Electronics, and for the PID layer the **SAE J1979**
tables mirrored on Wikipedia.

> **Reality check up front:** the iCar2 is *not* a genuine Elm Electronics ELM327. It is
> an ARM-based clone that reports `ELM327 v2.1` (BT3.0 units often `v1.5`/`v2.1`, newer
> iCar Pro units `v2.2`/`v2.3`). Treat every AT command as "may return `?`" and design
> the init sequence to degrade gracefully. See §2.6.

---

## 1. Hardware variants and Android connection paths

Vgate ships the same ELM327 firmware behind three different radios. They are *not*
interchangeable from an app's point of view — the discovery API, the permission set and
the throughput characteristics are all different.

| Variant | Radio | Advertised / pairing name | PIN | iOS | Android API used |
|---|---|---|---|---|---|
| **iCar2 BT3.0** | Bluetooth Classic 3.0 (SPP) | `V-LINK` (also seen: `OBDII`, `V-LINK-ANDROID`) | `1234` (some units `0000`, `6789`) | ✗ | `BluetoothDevice.createRfcommSocketToServiceRecord` |
| **iCar2 BLE** | Bluetooth 4.0 LE (GATT) | `ELM327` (also seen: `IOS-Vlink`, `Vgate iCar Pro BLE`) | `1234` where prompted; usually no bond at all | ✓ | `BluetoothLeScanner` + `BluetoothGatt` |
| **iCar2 WiFi** | 802.11 b/g/n, AP mode | SSID `V-LINK` / `VLINK` (generic clones: `WIFI_OBDII`, `OBDII`, `OBD2`) | open network | ✓ | `java.net.Socket` to `192.168.0.10:35000` |

Common to all three:

* Powered from the DLC (OBD-II connector) pins 16/4/5 — no battery.
* **Auto-sleep after ~30 minutes** with the engine off, to protect the car battery. The
  BT3.0 unit has a physical wake button; the BLE/WiFi units wake on bus activity or
  re-plug. Your app must handle "adapter was there, now it isn't" as a normal state.
* Claimed protocol coverage: SAE J1850 PWM, SAE J1850 VPW, ISO 9141-2, ISO 14230-4 KWP
  (5-baud + fast init), ISO 15765-4 CAN 11/29-bit @ 250/500 kbaud (+ J1939 and USER1/2
  on the WiFi/Pro SKUs).

### 1.1 Bluetooth Classic (BT3.0) — RFCOMM / SPP

**Service UUID (Serial Port Profile, Bluetooth SIG base):**

```
00001101-0000-1000-8000-00805F9B34FB
```

This is the only UUID that matters for classic. Flow:

1. **Permissions.** API ≥ 31: `BLUETOOTH_CONNECT` (runtime) for `getBondedDevices()`,
   `createRfcommSocketToServiceRecord()` and `connect()`; `BLUETOOTH_SCAN` (runtime,
   `usesPermissionFlags="neverForLocation"` if you never derive location) for
   `startDiscovery()`. API ≤ 30: manifest `BLUETOOTH` + `BLUETOOTH_ADMIN`, plus
   `ACCESS_FINE_LOCATION` at runtime for discovery.
2. **Bond first.** The iCar2 BT3.0 requires a system-level pairing with PIN `1234`.
   Prefer enumerating `bluetoothAdapter.bondedDevices` and matching on name
   (`V-LINK`, `OBDII`, `OBD`) or on the device class, rather than running discovery.
3. **Cancel discovery before connecting.** `bluetoothAdapter.cancelDiscovery()` — the
   inquiry scan starves the connection attempt and is the #1 cause of "connect times
   out after 12 s" on Android.
4. **Connect on a background thread.** `socket.connect()` blocks.
5. **Fallbacks, in order** (cheap clones have broken/absent SDP records):
   * `createRfcommSocketToServiceRecord(SPP_UUID)` — secure, uses SDP.
   * `createInsecureRfcommSocketToServiceRecord(SPP_UUID)` — skips the
     authenticate/encrypt requirement; fixes many clones that advertise SPP but refuse
     the secure channel.
   * Reflection fallback to a hard-coded RFCOMM channel 1:
     ```kotlin
     val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
     val socket = m.invoke(device, 1) as BluetoothSocket
     ```
     Undocumented but universally present; keep it as a last resort.
6. On failure always `socket.close()` before retrying — a half-open RFCOMM socket
   blocks the next attempt until the stack times out.

I/O is a plain byte stream: write ASCII + `\r`, read until `>`.

### 1.2 Bluetooth LE — GATT profiles

There is **no single "ELM327 BLE" profile.** Vgate has shipped at least three different
GATT layouts across iCar2 BLE / iCar Pro BLE / vLinker firmware revisions, and clones
add more. Build a **profile table and auto-probe**, do not hard-code one pair.

Known-good profiles, in the order you should try them:

| # | Vendor / family | Service UUID | Notify (adapter → phone) | Write (phone → adapter) |
|---|---|---|---|---|
| 1 | **"FFF0 family"** — the most common ELM327 BLE clone layout, incl. many iCar2 BLE units and Veepeak | `0000fff0-0000-1000-8000-00805f9b34fb` | `0000fff1-0000-1000-8000-00805f9b34fb` | `0000fff2-0000-1000-8000-00805f9b34fb` |
| 2 | **Vgate / vLinker "IOS-Vlink"** (16-bit `18F0`) | `000018f0-0000-1000-8000-00805f9b34fb` | `00002af0-0000-1000-8000-00805f9b34fb` | `00002af1-0000-1000-8000-00805f9b34fb` |
| 3 | **vLinker / iCar Pro transparent-UART** (Microchip ISSC profile) — *single dual-role characteristic* | `e7810a71-73ae-499d-8c15-faa9aef0c3f2` | `bef8d6c9-9c21-4c9e-b632-bd58c1009f9f` | `bef8d6c9-9c21-4c9e-b632-bd58c1009f9f` (same) |
| 4 | **HM-10 style** (generic BLE-serial modules) — usually single dual-role characteristic | `0000ffe0-0000-1000-8000-00805f9b34fb` | `0000ffe1-0000-1000-8000-00805f9b34fb` | `0000ffe1-...` (same), or `0000ffe2-...` on some |
| 5 | **Nordic UART (NUS)** — rare on OBD dongles but appears on ESP32-based ones | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` (TX from device) | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` (RX to device) |

> ⚠ **FFF1 vs FFF2 is the single most common bug.** The correct assignment is
> **FFF1 = NOTIFY (read), FFF2 = WRITE**. A minority of code on GitHub has these
> swapped; the mature projects (EdiabasLib's `GattSppInfo` table, CanZE's Vgate
> settings, `tronikos/elm327_obdii_ble`, ESPHome `elm327_ble`) all agree on
> FFF1 = read/notify, FFF2 = write. Don't trust the number ordering — trust the
> characteristic **properties**.

**Robust resolution algorithm** (what EdiabasLib / ObdInsight do):

1. Exact match: if a known service UUID is present *and* carries both of its expected
   characteristics with the right properties (`PROPERTY_NOTIFY` on the read one,
   `PROPERTY_WRITE`/`PROPERTY_WRITE_NO_RESPONSE` on the write one) → use it.
2. Single-characteristic fallback *within* a known service: one characteristic that has
   both notify and write → use it for both directions.
3. Generic fallback: scan **all** discovered services, pick the first characteristic with
   `NOTIFY` and the first with `WRITE`/`WRITE_NO_RESPONSE`, skipping the standard
   Generic Access (`1800`), Generic Attribute (`1801`), Device Information (`180A`) and
   Battery (`180F`) services.
4. Treat 16-bit and 128-bit forms as equivalent (`FFF1` ≡ `0000fff1-0000-1000-8000-00805f9b34fb`).

**Android BLE specifics that bite:**

* **Permissions.** API ≥ 31: `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`. API 23–30: scanning
  requires `ACCESS_FINE_LOCATION` **and** location services actually enabled, or the scan
  silently returns zero results.
* **Don't filter the scan by service UUID.** Many ELM327 dongles advertise only a name,
  with the service UUIDs living in the scan response or not advertised at all. Scan
  unfiltered (or with a name-prefix filter for `OBD`, `ELM`, `Vlink`, `Vgate`, `vLinker`,
  `V-LINK`, `IOS-Vlink`) and resolve the profile after `discoverServices()`.
* **Force the LE transport** on dual-mode dongles:
  `device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)`.
  Without it Android may pick BR/EDR and the GATT connection fails with status 133.
* **Enable notifications properly — two steps.**
  ```kotlin
  gatt.setCharacteristicNotification(notifyChar, true)               // local only
  val cccd = notifyChar.getDescriptor(
      UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))       // CCCD
  // API 33+: gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
  cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
  gatt.writeDescriptor(cccd)                                         // the one that matters
  ```
  Skipping the CCCD write is why "connects fine, never receives anything" happens.
* **MTU.** Default ATT MTU is **23** → **20 usable payload bytes** per write/notification.
  Call `gatt.requestMtu(517)` right after `onServicesChanged`/before
  `discoverServices()` and honour the negotiated value in `onMtuChanged`
  (usable = MTU − 3). Many ELM327 dongles refuse and stay at 23 — so **always chunk
  writes to 20 bytes** regardless, and **always treat notifications as a byte stream**
  that must be reassembled until `>` arrives (`0100` responses routinely arrive as 2–3
  notifications).
* **Serialize GATT operations.** Android's stack allows exactly one outstanding GATT
  operation per connection. Queue writes/descriptor-writes/MTU requests and drive the
  queue from the `onCharacteristicWrite` / `onDescriptorWrite` / `onMtuChanged`
  callbacks. Firing two writes back to back silently drops one.
* **Connection interval.** Call
  `gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)` after connect
  — it drops the interval from the ~30–50 ms default toward ~11.25–15 ms, which roughly
  doubles achievable poll rate (§5.1). Drop back to `BALANCED` when the screen is off.
* Use `WRITE_TYPE_NO_RESPONSE` only if the write characteristic actually advertises
  `PROPERTY_WRITE_NO_RESPONSE`; it is faster but some clone firmwares drop bytes.
* Status **133** (`GATT_ERROR`) on connect is usually a stale cached connection: call
  `gatt.close()` (not just `disconnect()`) and retry after ~500 ms, up to 3 times.

### 1.3 WiFi

```
SSID: V-LINK  (open, no password; Vgate cannot change it without factory support)
IP:   192.168.0.10        (adapter runs as AP / DHCP server, clients get 192.168.0.x)
Port: 35000  TCP          — transparent bidirectional bridge to the ELM327 RS232 side
Web:  http://192.168.0.10 — configuration page (can switch the module to STA mode)
```

Other clones you may want in a settings screen: `192.168.0.10:35000` (Vgate/V-LINK,
most common), `192.168.4.1:35000` (ESP8266/ESP32-based), `192.168.1.5:35000`.

Android notes:

* **Bind the socket to the WiFi network.** Android 8+ notices the AP has no internet and
  routes app traffic back over mobile data, so `Socket("192.168.0.10", 35000)` fails or
  hits the internet. Request the network explicitly and bind:
  ```kotlin
  val req = NetworkRequest.Builder()
      .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .build()
  cm.requestNetwork(req, object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
          // either bind the whole process…
          cm.bindProcessToNetwork(network)
          // …or, better, bind just this socket:
          network.bindSocket(socket)
      }
  })
  ```
  `bindProcessToNetwork` replaces the deprecated `setProcessDefaultNetwork`. Prefer
  `Network.bindSocket()` so the rest of the app keeps its internet connection.
* **`socket.tcpNoDelay = true`.** Without it Nagle coalesces the `>` prompt with the next
  response and your read loop stalls for ~40 ms per command.
* Set `soTimeout` (e.g. 2000 ms) so a sleeping adapter doesn't hang the read thread.
* Users must connect to the AP in system WiFi settings first; there's no way to join an
  open AP silently on modern Android (use `WifiNetworkSpecifier` in the `NetworkRequest`
  to prompt for it in-app on API ≥ 29).

### 1.4 Transport abstraction

All three transports reduce to the same interface, which is what makes this tractable:

```kotlin
interface ElmTransport {
    suspend fun open()
    suspend fun write(cmd: String)       // appends '\r'
    fun incoming(): Flow<ByteArray>      // raw chunks
    suspend fun close()
}
```
Put the `>`-terminated framing, echo stripping and NUL filtering in **one** layer above
this, shared by all transports.

---

## 2. ELM327 AT layer

### 2.1 Wire-level facts (from the datasheet)

| Property | Value |
|---|---|
| Serial defaults (internal / USB variants) | **38400 baud, 8N1**, no flow control (9600 if pin 6 low at power-up) |
| Command terminator | **CR = `0x0D`** only. LF is optional/ignored |
| Response terminator | single CR, plus LF if `ATL1` |
| Idle / ready indicator | **`>` = `0x3E`**, printed when the IC is idle and ready |
| Syntax error | a single **`?`** |
| Incomplete command timeout | ~20 s, then `?` |
| Case sensitivity | none — `ATZ` ≡ `atz` ≡ `At Z`; spaces and control chars in input are ignored |
| Repeat last command | send a bare CR |
| Internal RS232 TX buffer | **512 bytes**; overflow → `BUFFER FULL` |
| Default response timeout | `AT ST` = `32` ≈ **200 ms** (units of 4 ms) |
| Adaptive timing default | `AT AT1` (enabled, recommended) |
| Boot banner | `ELM327 v2.1` (or the clone's claimed version) followed by `>` |
| ⚠ Quirk | "a very small chance that NULL characters (byte value 00) may occasionally be inserted into the RS232 data transmitted by the ELM327" — **filter `0x00` from the RX stream** |

### 2.2 The read loop — get this right first

Everything else depends on correct framing.

```
send:  <ascii command> + '\r'
read:  accumulate bytes until you see '>' (0x3E)
```

Rules:

1. **Always wait for `>` before sending the next command.** The datasheet is explicit:
   "software should always wait for either the prompt character ('>' or hex 3E) … before
   beginning to send the next command." Sending early *interrupts* the chip (it aborts
   the in-flight OBD request and prints `STOPPED`).
2. Strip `0x00` bytes on arrival (see quirk above).
3. Normalise line endings: split on `\r` and/or `\n`, drop empties.
4. **Echo:** with `ATE1` (the power-on default) the first line of every response is your
   own command echoed back. Rather than parsing conditionally forever, send `ATE0`
   early — but the *response to `ATE0` itself* will still be echoed. Handle that one
   case by discarding any line equal (case-insensitively, whitespace-stripped) to the
   command you just sent.
5. `SEARCHING...` is an informational line emitted during `ATSP0` protocol discovery, not
   an error. It may appear alone, or prefixed to a real answer. Filter it.
6. A response can span **many lines** (multiple ECUs, or ISO-TP multi-frame — §5.3).
   Collect everything until `>`, then parse.
7. Timeouts: give AT commands ~1 s, OBD requests ~1.5× your `AT ST` setting, and the
   first request after `ATSP0` up to **15 s** (a full protocol search over 12 protocols
   with 5-baud init attempts is slow).

### 2.3 Recommended initialization sequence

Send these one at a time, waiting for `>` after each. Expected replies in brackets.

```
ATWS            # or ATZ. Warm start, faster, keeps the baud rate.      [ELM327 v2.1]
                # wait 1000-2000 ms here regardless of when '>' appears
ATE0            # echo off — halves the bytes on the wire               [OK]
ATL0            # linefeeds off                                          [OK]
ATS0            # spaces off — ~25% fewer bytes per response             [OK]
ATH1            # headers ON  (see note)                                 [OK]
ATAL            # allow long (>7 byte) messages                          [OK]
ATAT1           # adaptive timing on (default, but be explicit)          [OK]
ATST32          # 200 ms per-request ceiling (0x32=50 → 200 ms; tune)    [OK]
ATSP0           # automatic protocol search                              [OK]
ATI             # identify — record the reported version                 [ELM327 v2.1]
ATRV            # battery voltage sanity check                           [12.6V]
0100            # first real request; triggers the protocol search       [41 00 BE 3E B8 11]
ATDPN           # read back which protocol was found                     [A6]
```

Then **lock the protocol** for every subsequent session:

```
ATSP6           # (or whatever ATDPN returned, minus the 'A' prefix)
```

Locking removes the multi-second `SEARCHING...` phase on reconnect and is the single
biggest UX win. Persist the discovered protocol number per-vehicle (keyed by VIN) and
try `ATSP<n>` first, falling back to `ATSP0` if `0100` then returns
`UNABLE TO CONNECT`.

**`ATH1` vs `ATH0` — recommendation: use `ATH1`.** Headers cost ~3 bytes per line, but
without them you cannot tell *which ECU* answered when several respond to the same
request (very common for `0100`, `010C` on hybrids, and any multi-ECU car), and you
cannot distinguish an ISO-TP frame's addressing. Turn headers off only if you are
fighting `BUFFER FULL` or need every last byte of throughput on BLE, and only after
you've pinned the responder with `ATCRA`/`ATSH`.

**Timing between commands.** Genuine chips need no inter-command delay beyond the `>`.
Cheap clones do: insert **50–100 ms** between *initialization* commands (not between
steady-state PID polls) — this is a well-documented workaround for clones that drop
commands issued immediately after `OK`.

### 2.4 AT command reference (the ones you actually need)

| Command | Effect | Default |
|---|---|---|
| `ATZ` | Full reset, LED lamp test, reverts baud rate; prints ID string | — |
| `ATWS` | Warm start — same reset without the lamp test, keeps baud rate | — |
| `ATD` | Restore all settings to factory defaults | — |
| `ATE0` / `ATE1` | Echo off / on | `E1` |
| `ATL0` / `ATL1` | Linefeeds off / on | pin-dependent |
| `ATS0` / `ATS1` | Spaces in responses off / on | `S1` |
| `ATH0` / `ATH1` | Headers off / on | `H0` |
| `ATAL` / `ATNL` | Allow / disallow messages longer than 7 data bytes | `NL` |
| `ATAT0/1/2` | Adaptive timing: off / normal / aggressive | `AT1` |
| `ATST hh` | Response timeout, **hh × 4 ms** (×20 ms if CAN + `CTM5`). `00` restores default. With adaptive timing on, this is the *ceiling*. Max ≈ 1 s (`FF`) | `32` ≈ 200 ms |
| `ATSP h` | Set protocol *and save as default* | `0` |
| `ATSP Ah` | Set protocol `h`, but fall back to auto-search | — |
| `ATTP h` | Try protocol `h` without saving it | — |
| `ATDP` / `ATDPN` | Describe current protocol (text / number) | — |
| `ATRV` | Read input (battery) voltage, e.g. `12.6V` | — |
| `ATI` | Print version ID string | — |
| `ATR0` / `ATR1` | Responses off / on (fire-and-forget mode) | `R1` |
| `ATCAF0` / `ATCAF1` | CAN auto-formatting off / on — CAF1 adds/strips ISO-TP PCI bytes for you | `CAF1` |
| `ATCFC0` / `ATCFC1` | CAN flow control off / on (auto-send FC frames) | `CFC1` |
| `ATSH hhh` / `ATSH hh hh hh` / `ATSH wwxxyyzz` | Set the TX header/CAN ID (11-bit / 3-byte / 29-bit) | protocol default |
| `ATCRA hhh` / `ATCRA hhhhhhhh` | Set CAN RX address filter; `ATCRA` alone resets it | off |
| `ATCM`/`ATCF` | CAN mask / filter (finer than CRA) | — |
| `ATFCSH hhh` | Flow-control header | — |
| `ATFCSD 30 00 00` | Flow-control data (`30`=CTS, block size, ST-min) | — |
| `ATFCSM 0/1/2` | Flow-control mode: auto / user-defined / user data only | `0` |
| `ATBD` | Dump the 12-slot OBD buffer (great for debugging init failures) | — |
| `ATLP` | Enter low-power mode | — |
| `ATMA` | Monitor all traffic (sniffer; exit by sending any char → `STOPPED`) | — |

Protocol numbers for `ATSP`/`ATDPN`:

```
0  Automatic
1  SAE J1850 PWM (41.6 kbaud)
2  SAE J1850 VPW (10.4 kbaud)
3  ISO 9141-2 (5 baud init, 10.4 kbaud)
4  ISO 14230-4 KWP (5 baud init, 10.4 kbaud)
5  ISO 14230-4 KWP (fast init, 10.4 kbaud)
6  ISO 15765-4 CAN (11-bit ID, 500 kbaud)   ← most cars 2008+
7  ISO 15765-4 CAN (29-bit ID, 500 kbaud)
8  ISO 15765-4 CAN (11-bit ID, 250 kbaud)
9  ISO 15765-4 CAN (29-bit ID, 250 kbaud)
A  SAE J1939 CAN (29-bit ID, 250 kbaud)
B  USER1 CAN (11-bit, 125 kbaud, adjustable)
C  USER2 CAN (11-bit, 50 kbaud, adjustable)
```

`ATDPN` returns the number, prefixed with `A` if it was reached via auto-search
(e.g. `A6` means "auto-selected protocol 6").

### 2.5 Error and status strings

Match these **before** trying to hex-parse a line. All are ASCII, uppercase.

| String | Meaning | App reaction |
|---|---|---|
| `?` | Syntax error / unsupported AT command | Command not supported by this clone — continue, don't abort init |
| `SEARCHING...` | Auto protocol search in progress | Informational; keep reading |
| `NO DATA` | No response within `AT ST` | PID unsupported by this ECU, or transient. Mark PID unsupported after N misses |
| `UNABLE TO CONNECT` | Could not initialise any protocol | Ignition off / wrong protocol / not plugged in |
| `STOPPED` | Operation aborted because a character arrived mid-request | You sent a command before `>` — fix your framing |
| `BUS INIT: ...` / `BUS INIT: ERROR` | ISO/KWP slow-init progress/failure | Retry once, then surface |
| `BUS BUSY` | Couldn't get a slot on the bus | Back off and retry |
| `BUS ERROR` | Invalid signal on the bus (often wiring) | Surface |
| `CAN ERROR` | CAN init/TX/RX failure — wrong protocol or bad wiring | Re-run protocol detection |
| `DATA ERROR` | Vehicle responded but data was unrecoverable | Retry the request |
| `<DATA ERROR` | Bad checksum/format on the line it points at | Retry; often a noise burst |
| `BUFFER FULL` | 512-byte TX buffer overflowed | Turn off headers/spaces, use `ATCRA` filters, raise baud |
| `ERR94` | Fatal CAN error; IC self-reset, **all settings lost** | Re-run the whole init sequence. Also blocks further CAN auto-search until power-cycle or `ATFE` |
| `ERRxx` (other) | Internal error | Reset and re-init |
| `LV RESET` | Brown-out reset occurred; settings back to defaults | Re-run init |
| `FB ERROR` | Output feedback failure (wiring) | Surface |
| `ACT ALERT` / `!ACT ALERT` | Inactivity — about to enter low-power mode | Send something to keep it awake |
| `LP ALERT` / `!LP ALERT` | Entering low power in 2 s (unstoppable) | Tear down and prepare to reconnect |
| `OK` | AT command accepted | — |

### 2.6 Cheap-clone pitfalls checklist

* **Version string lies.** iCar2 reports `ELM327 v2.1` (some `v1.5`) but the firmware is
  a reimplementation. Never gate features on the version string; **probe** instead
  (send the command, check for `?`).
* **`?` on legitimate commands.** Clones commonly reject `ATPPS`, `ATCRA`, `ATST32`,
  `ATAT1`, `ATR1`, `ATCEA`, `ATFCSM`. FORScan's clone test is exactly this: good clones
  return a hex table for `ATPPS` and `OK` for `ATCRA`; bad ones return `?`.
  **Design init to tolerate `?` on everything except `ATE0`.**
* **Some clones hard-lock** on certain commands (`ATTP A0` is a known killer) and need a
  physical power cycle. Don't send exotic AT commands speculatively.
* **`ATMA` / monitor modes** are often broken or hard-wired to silent mode (`ATCSM` stuck
  ON), so the adapter can't ACK while listening.
* **Long request truncation.** Some ARM-based clones truncate OBD requests to the first
  two bytes, breaking `ATSH`-addressed UDS (`22 F1 90`) queries and multi-PID batches.
  Test with `0902` (VIN) early — if the VIN comes back correct, ISO-TP RX works.
* **RTR frames dropped silently**, no full-duplex operation.
* **Non-deterministic latency.** Genuine chips answer AT commands in <100 ms; clones can
  take 300 ms+ or drop a command entirely. Retry each init command once on timeout.
* **Persisted state is a trap.** `ATSP` writes to EEPROM and survives power cycles, as do
  several other settings on a warm start (`E`, `L`, `S`, `H`, `M`, `AT`, `CAF`, `CFC`,
  `CSM`, `AL/NL`). Never assume defaults — always set explicitly.
* Some units need `ATZ` rather than `ATWS` to come back cleanly after auto-sleep.

---

## 3. Mode 01 — live data

### 3.1 Request / response format

Request: two hex bytes, `<mode><pid>`, ASCII, CR-terminated. Spaces optional.

```
>010C
41 0C 1A F8
```

Response: `0x40 + mode`, then the PID echo, then the data bytes A, B, C, D…
With `ATS0` you get `410C1AF8`; with `ATH1` you additionally get the header
(`7E8 03 41 0C 1A F8` on CAN, or `48 6B 10 41 0C 1A F8 <chk>` on J1850/ISO).

**Parsing recipe:**

1. Split the accumulated response into lines; drop `SEARCHING...`, blank lines and echo.
2. For each line, strip whitespace, uppercase, verify it's pure hex.
3. If headers are on, locate the `41` (or `4x` for other modes) that is followed by the
   PID you asked for, and take the bytes after it. Don't index by fixed offset — header
   length differs per protocol (3 bytes CAN 11-bit, 4 bytes CAN 29-bit, 3 bytes J1850).
4. Named bytes: `A` = first data byte, `B` = second, etc.

### 3.2 The `>010C1` throughput trick

Append a single hex digit to any OBD request to tell the ELM327 **how many response
lines to expect**; it then returns immediately instead of waiting out the full `AT ST`
timeout looking for more responses:

```
>010C1        # "one response expected"
41 0C 1A F8
```

The datasheet quantifies the gain: "This may save a considerable amount of time, as the
default time for the AT ST timer is 200 msec." For a single-ECU car this roughly
**doubles or triples** the sustainable poll rate.

**How to use it safely:** during a discovery pass, poll each PID *without* the digit and
count how many lines came back; cache that count per (vehicle, PID); use it thereafter.
Setting the count **too low** causes the ELM327 to return to the prompt early and can
cause bus congestion on protocols that require IFR acknowledgement (J1850 PWM) — the
datasheet warns about this explicitly. Never guess.

### 3.3 PID support discovery (bitmasks)

Before polling anything, ask which PIDs exist. `0100` returns 4 bytes = 32 bits covering
PIDs `$01`–`$20`; bit A7 (MSB of byte A) = PID `$01`, descending to bit D0 = PID `$20`.

```
>0100
41 00 BE 3E B8 11
```

`BE3EB811` = `1011 1110 0011 1110 1011 1000 0001 0001` → PID `$01` supported,
`$02` not, `$03` yes, `$04` yes, `$05` yes, … `$20` yes.

**The last bit of each block (`$20`, `$40`, `$60`, `$80`, `$A0`, `$C0`) means "the next
block query is supported."** So:

```
0100 → if bit for $20 set → 0120 → if bit for $40 set → 0140 → 0160 → 0180 → 01A0 → 01C0
```

Same pattern for other modes: `0900` for Mode 09, `0600` for Mode 06.

**Important:** on a multi-ECU car you get one `41 00 …` line *per ECU*. OR the bitmasks
together to get "supported by anything", but remember which ECU answered (headers on) if
you later want to address a specific one.

```kotlin
fun supportedPids(base: Int, a: Int, b: Int, c: Int, d: Int): List<Int> {
    val bits = (a.toLong() shl 24) or (b.toLong() shl 16) or (c.toLong() shl 8) or d.toLong()
    return (1..32).filter { i -> (bits shr (32 - i)) and 1L == 1L }.map { base + it }
}
```

### 3.4 The useful PIDs (SAE J1979)

`A`, `B`, `C`, `D` are the successive data bytes as unsigned integers unless noted.

| PID | Bytes | Name | Formula | Unit | Range |
|---|---|---|---|---|---|
| `01` | 4 | Monitor status since DTCs cleared | bit-encoded — see §4.4 | — | — |
| `03` | 2 | Fuel system status | enumerated (§3.5) | — | — |
| `04` | 1 | **Calculated engine load** | `A × 100 / 255` | % | 0–100 |
| `05` | 1 | **Engine coolant temperature** | `A − 40` | °C | −40…215 |
| `06` | 1 | **Short-term fuel trim, bank 1** | `A × 100 / 128 − 100` | % | −100…99.2 |
| `07` | 1 | **Long-term fuel trim, bank 1** | `A × 100 / 128 − 100` | % | −100…99.2 |
| `08` | 1 | Short-term fuel trim, bank 2 | `A × 100 / 128 − 100` | % | −100…99.2 |
| `09` | 1 | Long-term fuel trim, bank 2 | `A × 100 / 128 − 100` | % | −100…99.2 |
| `0A` | 1 | Fuel pressure (gauge) | `3 × A` | kPa | 0–765 |
| `0B` | 1 | **Intake manifold absolute pressure (MAP)** | `A` | kPa abs | 0–255 |
| `0C` | 2 | **Engine RPM** | `(256 × A + B) / 4` | rpm | 0–16383.75 |
| `0D` | 1 | **Vehicle speed** | `A` | km/h | 0–255 |
| `0E` | 1 | **Timing advance** (before TDC, cyl 1) | `A / 2 − 64` | ° | −64…63.5 |
| `0F` | 1 | **Intake air temperature** | `A − 40` | °C | −40…215 |
| `10` | 2 | **MAF air flow rate** | `(256 × A + B) / 100` | g/s | 0–655.35 |
| `11` | 1 | **Throttle position** | `A × 100 / 255` | % | 0–100 |
| `12` | 1 | Commanded secondary air status | enumerated (1/2/4/8) | — | — |
| `13` | 1 | O2 sensors present (2 banks) | bit-encoded: A0–A3 = B1S1–B1S4, A4–A7 = B2S1–B2S4 | — | — |
| `14`–`1B` | 2 | O2 sensor 1–8: voltage + STFT | `A / 200` V ; `B × 100 / 128 − 100` % (`B = 0xFF` ⇒ trim not used) | V, % | 0–1.275 |
| `1C` | 1 | OBD standards conformance | enumerated | — | — |
| `1D` | 1 | O2 sensors present (4 banks) | bit-encoded B1S1…B4S2 | — | — |
| `1F` | 2 | **Run time since engine start** | `256 × A + B` | s | 0–65535 |
| `21` | 2 | **Distance travelled with MIL on** | `256 × A + B` | km | 0–65535 |
| `22` | 2 | Fuel rail pressure (rel. to manifold) | `0.079 × (256 × A + B)` | kPa | 0–5177.3 |
| `23` | 2 | Fuel rail gauge pressure (direct inj.) | `10 × (256 × A + B)` | kPa | 0–655350 |
| `24`–`2B` | 4 | O2 sensor 1–8: λ + voltage | λ = `2 × (256A + B) / 65536` ; V = `8 × (256C + D) / 65536` | —, V | 0–2, 0–8 |
| `2C` | 1 | Commanded EGR | `A × 100 / 255` | % | 0–100 |
| `2E` | 1 | Commanded evaporative purge | `A × 100 / 255` | % | 0–100 |
| `2F` | 1 | **Fuel tank level input** | `A × 100 / 255` | % | 0–100 |
| `30` | 1 | Warm-ups since codes cleared | `A` | count | 0–255 |
| `31` | 2 | **Distance since codes cleared** | `256 × A + B` | km | 0–65535 |
| `32` | 2 | Evap system vapour pressure | `(256 × A + B) / 4`, **signed** (two's complement) | Pa | −8192…8191.75 |
| `33` | 1 | **Absolute barometric pressure** | `A` | kPa | 0–255 |
| `34`–`3B` | 4 | O2 sensor 1–8: λ + current | λ = `2 × (256A + B) / 65536` ; I = `(256C + D)/256 − 128` | —, mA | 0–2, −128…128 |
| `3C`–`3F` | 2 | Catalyst temperature B1S1…B2S2 | `(256 × A + B) / 10 − 40` | °C | −40…6513.5 |
| `41` | 4 | Monitor status this drive cycle | bit-encoded (byte A always 0) | — | — |
| `42` | 2 | **Control module voltage** | `(256 × A + B) / 1000` | V | 0–65.535 |
| `43` | 2 | Absolute load value | `(256 × A + B) × 100 / 255` | % | 0–25700 |
| `44` | 2 | Commanded air-fuel equivalence ratio (λ) | `2 × (256 × A + B) / 65536` | ratio | 0–2 |
| `45` | 1 | Relative throttle position | `A × 100 / 255` | % | 0–100 |
| `46` | 1 | **Ambient air temperature** | `A − 40` | °C | −40…215 |
| `47` | 1 | Absolute throttle position B | `A × 100 / 255` | % | 0–100 |
| `49` | 1 | Accelerator pedal position D | `A × 100 / 255` | % | 0–100 |
| `4A` | 1 | Accelerator pedal position E | `A × 100 / 255` | % | 0–100 |
| `4C` | 1 | Commanded throttle actuator | `A × 100 / 255` | % | 0–100 |
| `4D` | 2 | Time run with MIL on | `256 × A + B` | min | 0–65535 |
| `4E` | 2 | Time since trouble codes cleared | `256 × A + B` | min | 0–65535 |
| `51` | 1 | Fuel type | enumerated (§3.5) | — | — |
| `52` | 1 | Ethanol fuel percentage | `A × 100 / 255` | % | 0–100 |
| `53` | 2 | Absolute evap system vapour pressure | `(256 × A + B) / 200` | kPa | 0–327.675 |
| `59` | 2 | Fuel rail absolute pressure | `10 × (256 × A + B)` | kPa | 0–655350 |
| `5A` | 1 | Relative accelerator pedal position | `A × 100 / 255` | % | 0–100 |
| `5B` | 1 | Hybrid battery pack remaining life | `A × 100 / 255` | % | 0–100 |
| `5C` | 1 | **Engine oil temperature** | `A − 40` | °C | −40…210 |
| `5D` | 2 | Fuel injection timing | `(256 × A + B) / 128 − 210` | ° | −210…301.99 |
| `5E` | 2 | **Engine fuel rate** | `(256 × A + B) / 20` | L/h | 0–3212.75 |
| `61` | 1 | Driver's demand engine torque | `A − 125` | % | −125…130 |
| `62` | 1 | Actual engine torque | `A − 125` | % | −125…130 |
| `63` | 2 | Engine reference torque | `256 × A + B` | N·m | 0–65535 |
| `66` | 5 | MAF sensor (A/B) | A = support bits; sensor A `(256B + C)/32`, sensor B `(256D + E)/32` | g/s | 0–2047.97 |
| `67` | 3 | Engine coolant temperature (1/2) | A = support bits; `B − 40`, `C − 40` | °C | −40…215 |
| `68` | 3 | Intake air temperature sensor (1/2) | A = support bits; `B − 40`, `C − 40` | °C | −40…215 |
| `A6` | 4 | Odometer | `(A<<24 + B<<16 + C<<8 + D) / 10` | km | 0–429496729.5 |

**Battery / adapter supply voltage** is not a PID — it's the AT command:

```
>ATRV
12.6V
```
Parse by stripping the trailing `V`. Note this is the voltage at the **OBD connector**,
not a true battery terminal reading, and clone accuracy is ±0.3 V (calibratable with
`ATCV dddd`). Prefer PID `42` (control module voltage) if the ECU supports it.

**Boost pressure** is not a standard PID. Derive it:
`boost (kPa gauge) = MAP (PID 0B) − barometric (PID 33)`. If PID `33` is unsupported,
approximate barometric as 101.3 kPa (sea level) or capture PID `0B` at key-on,
engine-off.

**Instantaneous fuel consumption** when PID `5E` is unsupported (most petrol cars):
```
L/h ≈ MAF(g/s) × 3600 / (14.7 × 820)      # 14.7 AFR, 820 g/L petrol density
L/100km = L/h × 100 / speed(km/h)          # guard speed > 0
```
If MAF (PID `10`) is unsupported, estimate it from the speed-density equation using
MAP (`0B`), IAT (`0F`), RPM (`0C`) and engine displacement.

### 3.5 Enumerations

**PID `03` — fuel system status** (byte A = system 1, byte B = system 2; bit-encoded,
exactly one bit should be set):

| Value | Meaning |
|---|---|
| `0` | Motor off |
| `1` | Open loop — engine not yet warm enough |
| `2` | **Closed loop — using O2 sensor feedback** (normal) |
| `4` | Open loop — high load / deceleration fuel cut |
| `8` | Open loop — system fault |
| `16` | Closed loop, but a fault exists in the feedback system |

**PID `51` — fuel type** (selected): 1 Gasoline, 2 Methanol, 3 Ethanol, 4 Diesel,
5 LPG, 6 CNG, 7 Propane, 8 Electric, 17 Hybrid gasoline, 19 Hybrid diesel,
20 Hybrid electric.

### 3.6 Mode 09 — vehicle information

| PID | Content |
|---|---|
| `0900` | Supported Mode 09 PIDs bitmask |
| `0902` | **VIN** — 17 ASCII chars, multi-frame (§5.3) |
| `0904` | Calibration ID |
| `0906` | Calibration Verification Numbers (CVN) |
| `090A` | ECU name |

`0902` is the canonical multi-frame test. On CAN with `ATCAF1` you get:

```
>0902
014
0: 49 02 01 31 44 34
1: 47 50 30 30 52 35 35
2: 42 31 32 33 34 35 36
```
`014` = 0x14 = 20 total data bytes. Concatenate the payloads after the `n:` sequence
prefixes → `49 02 01` + 17 VIN bytes. Drop `49 02` (mode echo) and `01` (data-item
count), ASCII-decode the remaining 17 bytes → `1D4GP00R55B123456`.

On J1850/ISO the same request returns five separate lines each prefixed
`49 02 <seq>` with 4 data bytes; sort by `<seq>` and strip leading `00` filler bytes.

---

## 4. DTCs — Modes 03, 07, 0A and 04

### 4.1 The three read modes

| Mode | Request | Response prefix | Meaning |
|---|---|---|---|
| `03` | `03` | `43` | **Stored / confirmed** DTCs — MIL is on for these |
| `07` | `07` | `47` | **Pending** DTCs — detected this or last drive cycle, not yet confirmed |
| `0A` | `0A` | `4A` | **Permanent** DTCs — cannot be cleared by Mode 04; only the ECU clears them after the monitor passes. Mandatory on 2010+ vehicles |

Always read all three; a dashboard that only shows Mode 03 misses intermittent faults
and gives a false "you've cleared it" impression when permanent codes remain.

### 4.2 Response format

**On CAN (ISO 15765-4)** the response is `43 <count> <DTC pairs…>` where `<count>` is the
total number of DTCs the ECU is reporting. If more than 2 DTCs, the response is
multi-frame:

```
>03
43 02 01 33 04 20                     ← 2 DTCs: 0133, 0420  →  P0133, P0420
```

**On non-CAN protocols (J1850, ISO 9141-2, KWP)** there is no count byte — the response
is `43` followed by up to 3 DTC pairs per line, padded with `00 00`:

```
>03
43 01 33 04 20 00 00
```

**Rules for both:**
* Read the total count from Mode 01 PID `01` (§4.4) as a cross-check.
* `00 00` pairs are padding — **discard them**, they are not "code P0000".
* Multiple ECUs → multiple `43 …` lines. Merge, and tag each code with its ECU header if
  `ATH1` is on (useful: "P0420 from 7E8" vs "U0100 from 7EA").
* `43 00` = "no codes stored". Also possible: `NO DATA` (treat as zero codes, not error).

### 4.3 Decoding a 2-byte DTC

Given bytes `A` and `B`:

```
first char:  (A >> 6) & 0x03   →  0 = 'P' (Powertrain)
                                  1 = 'C' (Chassis)
                                  2 = 'B' (Body)
                                  3 = 'U' (Network / User)
second char: (A >> 4) & 0x03   →  '0'..'3'
third char:   A       & 0x0F   →  '0'..'F'  (hex digit)
fourth char: (B >> 4) & 0x0F   →  '0'..'F'  (hex digit)
fifth char:   B       & 0x0F   →  '0'..'F'  (hex digit)
```

```kotlin
fun decodeDtc(a: Int, b: Int): String? {
    if (a == 0 && b == 0) return null                       // padding
    val sys = "PCBU"[(a shr 6) and 0x03]
    val d2  = ((a shr 4) and 0x03)
    val d3  = (a and 0x0F)
    val d4  = ((b shr 4) and 0x0F)
    val d5  = (b and 0x0F)
    return "%c%d%X%X%X".format(sys, d2, d3, d4, d5)
}
```

Worked examples:

| Bytes | Binary of A | Decoded |
|---|---|---|
| `01 33` | `0000 0001` | `P0133` — O2 sensor circuit slow response, B1S1 |
| `04 20` | `0000 0100` | `P0420` — catalyst efficiency below threshold, bank 1 |
| `43 01` | `0100 0011` | `C0301` |
| `81 30` | `1000 0001` | `B0130` |
| `C1 00` | `1100 0001` | `U0100` — lost comms with ECM/PCM |

Note the second character is only `0`–`3` (2 bits) while characters 3–5 are full hex
nibbles — so `P1A2B` is a valid code but `P4xxx` is not. Second char `0` = SAE generic,
`1` = manufacturer-specific (for P codes).

### 4.4 Mode 01 PID 01 — MIL status and DTC count

```
>0101
41 01 82 07 65 04
```
Byte `A`:
* **bit A7** = MIL (check-engine lamp) illuminated.
* **bits A6–A0** = number of confirmed DTCs.

```kotlin
val milOn    = (a and 0x80) != 0
val dtcCount =  a and 0x7F
```
Bytes `B`, `C`, `D` encode readiness-monitor support and completion status (misfire, fuel
system, components, catalyst, heated catalyst, evap, secondary air, A/C refrigerant, O2
sensor, O2 heater, EGR). Useful for a "ready for emissions test" indicator. PID `41` is
the same layout but for the current drive cycle only (byte A always `00`).

### 4.5 Mode 04 — clear DTCs

```
>04
44
```

`44` = success. What it actually does — be explicit about this in the UI:

* Erases stored (Mode 03) and pending (Mode 07) DTCs.
* Turns off the MIL.
* **Erases freeze-frame data** (Mode 02) — you lose the diagnostic snapshot.
* Resets all readiness monitors to "not ready", meaning the car **will fail an emissions
  test** until a full drive cycle completes (typically 50–150 km of mixed driving).
* Does **not** clear Mode 0A permanent DTCs.

Practical requirements and gotchas:

* Most ECUs require **ignition on, engine off**. Sending `04` with the engine running
  returns `NO DATA` or is silently ignored on many vehicles.
* It can be slow — the ECU may take **>1 s** to answer. Temporarily raise the timeout
  (`ATST FF` ≈ 1 s, or disable adaptive timing) around the `04` request and restore it
  afterwards.
* Always **read and archive** Modes 03/07/0A and Mode 02 freeze frames *before* clearing,
  and require an explicit, well-worded user confirmation.
* Verify afterwards by re-reading `03` and `0101`.

---

## 5. Practical engineering: throughput, batching, filtering

### 5.1 Achievable poll rates

The budget per request is:

```
t_request ≈ t_link_out + t_elm_overhead + t_ecu_response + t_link_in + t_idle_timeout
```

* `t_ecu_response`: ~5–50 ms on CAN, 50–100 ms on ISO 9141/KWP, worse on J1850.
* `t_idle_timeout`: **this dominates by default** — the ELM327 waits out `AT ST`
  (200 ms) after the last response line looking for more. Adaptive timing (`ATAT1`)
  shrinks it toward the observed ECU response time; the response-count digit (§3.2)
  removes it entirely.
* `t_link_*`: transport-dependent (below).

Realistic sustained rates for a **whole-vehicle poll** (single ECU, headers on, spaces
off, echo off, protocol locked with `ATSP6`):

| Transport | Round-trip link latency | Without count digit | With count digit |
|---|---|---|---|
| **WiFi (TCP, `TCP_NODELAY`)** | ~2–5 ms | ~4–5 req/s | **~20–30 req/s** |
| **Classic BT SPP (RFCOMM)** | ~10–30 ms | ~4–5 req/s | **~15–25 req/s** |
| **BLE, default connection interval (~30–50 ms)** | ~60–120 ms (≥2 intervals, more if the response spans several 20-byte notifications) | ~3–4 req/s | **~5–10 req/s** |
| **BLE, `CONNECTION_PRIORITY_HIGH` (~11–15 ms)** | ~25–45 ms | ~4 req/s | **~10–15 req/s** |

Treat these as engineering estimates, not measurements: clone firmware quality varies by
2–3× and the ECU is the other half of the equation. **Measure on the target device and
adapt at runtime.**

Design consequences:

* **Tier your PIDs.** Fast tier (RPM `0C`, speed `0D`, throttle `11`, MAP `0B`, MAF `10`)
  at every cycle; medium tier (coolant `05`, IAT `0F`, load `04`, fuel trims `06`/`07`)
  every 5th cycle; slow tier (fuel level `2F`, ambient `46`, oil temp `5C`, voltage
  `ATRV`, distances `21`/`31`) every 20th cycle or on demand. This is what makes a 6-gauge
  dashboard feel smooth on a 10 req/s BLE link.
* **Never poll unsupported PIDs.** Every `NO DATA` costs a full `AT ST` timeout. Run
  `0100`/`0120`/`0140`… once per vehicle and cache the result keyed by VIN.
* **One in-flight request at a time**, always. There is no pipelining; a second command
  before `>` aborts the first (`STOPPED`).
* Use a **single-threaded command queue with priorities** so a user-initiated DTC read
  can jump ahead of the gauge polling loop without racing it.

### 5.2 Batching multiple PIDs (CAN only)

SAE J1979 permits up to **6 PIDs in one Mode 01 request**, and this is supported only on
ISO 15765-4 (CAN):

```
>01 0C 0D 05 11 04 1F
```

The response contains all six PID/value groups, and because it exceeds 7 data bytes it
comes back as an **ISO-TP multi-frame** message. This is a 3–5× throughput win when it
works.

Preconditions and caveats:

* You must be on a CAN protocol (`ATDPN` returns 6/7/8/9).
* **Use physical addressing** so only one ECU replies: `ATSH 7E0` (engine ECU request ID)
  and ideally `ATCRA 7E8` to filter its reply. With functional addressing (`7DF`, the
  default), several ECUs answer simultaneously and the multi-frame reassembly gets
  confused.
* `ATAL` must be on (allow long messages).
* Many **cheap clones truncate requests to the first two bytes** — the batch silently
  degrades to a single-PID query. Validate at connect time: send a 3-PID batch and check
  that all three PIDs appear in the response; fall back to single-PID polling if not.
* Response ordering follows the request order but **always parse by PID id**, never by
  position — some ECUs omit unsupported PIDs.
* Don't batch across ECUs (e.g. an engine PID and a transmission PID) — split by
  responder.

### 5.3 ISO-TP / multi-frame responses

ISO 15765-2 splits messages >7 data bytes into frames identified by a PCI (Protocol
Control Information) nibble in the first byte:

| PCI nibble | Frame type | Layout |
|---|---|---|
| `0` | **Single Frame (SF)** | `0L` + L data bytes |
| `1` | **First Frame (FF)** | `1LLL` (12-bit total length) + 6 data bytes |
| `2` | **Consecutive Frame (CF)** | `2N` (sequence 0–F, wraps) + up to 7 data bytes |
| `3` | **Flow Control (FC)** | `30 BS STmin` — 30=CTS, 31=Wait, 32=Abort |

**With `ATCAF1` (default), the ELM327 does this for you:** it sends the Flow Control
frame automatically (`ATCFC1`), strips the PCI bytes, and presents the message as a
length line followed by numbered continuation lines:

```
>0902
014                       ← total length, 0x14 = 20 bytes
0: 49 02 01 31 44 34      ← the ':' sequence digit is added by CAN auto-formatting
1: 47 50 30 30 52 35 35
2: 42 31 32 33 34 35 36
```

Parsing:
1. If the first line is a bare 3-hex-digit length with no `n:` prefix, it's the total
   byte count — keep it to validate.
2. For lines matching `^([0-9A-F]):\s*(.*)$`, use the digit for ordering (it wraps
   `0..F..0`), concatenate the payloads.
3. Line `0:` contributes 6 payload bytes (the FF), subsequent lines up to 7 each.
4. Truncate the assembled buffer to the declared length.
5. With `ATH1` on, each line is *additionally* prefixed by the ECU header — group lines
   by header first, then reassemble per ECU.

With `ATCAF0` you see the raw PCI bytes and must do the reassembly *and* send flow
control yourself (`ATFCSM1` + `ATFCSH`/`ATFCSD`). **Leave `ATCAF1` on** unless you are
doing manufacturer-specific UDS work.

Flow-control tuning for slow/flaky links:
```
ATFCSH 7E0          # flow control header = the ECU's request ID
ATFCSD 30 00 00     # 30 = clear-to-send, block size 0 (send all), STmin 0 ms
ATFCSM 1            # use my flow control settings
```
Raising STmin (e.g. `30 00 0A` = 10 ms between consecutive frames) can fix `BUFFER FULL`
and `<DATA ERROR` on adapters that can't keep up.

### 5.4 Reducing bytes on the wire

Ordered by impact:

1. `ATE0` — removes the echo (≈50% of TX bytes).
2. `ATS0` — removes spaces (≈25% of RX bytes).
3. Response count digit (`010C1`) — removes the idle timeout (biggest *time* saver).
4. `ATCRA 7E8` — CAN RX filter: only accept the engine ECU's replies. Eliminates
   duplicate lines from transmission/ABS/BCM modules and cuts response size a lot on
   multi-ECU cars. Reset with bare `ATCRA`.
5. `ATH0` — headers off. **Last resort**: only after you've pinned the responder with
   `ATCRA`, since you lose the ability to tell ECUs apart.
6. `ATL0` — one byte per line, but also simplifies parsing.

### 5.5 Reconnection and lifecycle

* The adapter sleeps after ~30 min engine-off. Detect via read timeout or `LP ALERT` and
  transition to a "reconnecting" state rather than showing an error.
* After any `LV RESET` or `ERR94`, **all settings are back to defaults** — re-run the
  full init sequence, don't just resume polling.
* Keep-alive: if idle, send `ATRV` every ~5 s. It's cheap, doesn't touch the bus, and
  prevents the RS232 inactivity timer from firing `ACT ALERT`.
* On BLE, always `gatt.close()` (not merely `disconnect()`) when tearing down, or the
  next `connectGatt` fails with status 133 until the app is restarted.
* Persist per-VIN: protocol number (`ATDPN`), supported-PID bitmasks, per-PID response
  line counts, and whether batching works. Loading that cache turns a 15 s connect into
  a ~2 s connect.

### 5.6 Testing without a car

* **`Ircama/ELM327-emulator`** — a Python ELM327 emulator with multi-ECU simulation; it
  handles the ISO-TP data link and presents assembled frames. Point your app at it over
  a pty/TCP to test the whole protocol stack on a desktop.
* **`brendan-w/python-OBD`** — a well-tested reference implementation of the init
  sequence, PID decoders and DTC decoding worth reading before writing your own.
* **`fr3ts0n/AndrOBD`** — mature open-source Android OBD app; good reference for
  transport abstraction and multi-adapter handling.
* **nRF Connect** — indispensable for dumping the actual GATT tree of *your* iCar2 BLE
  unit before you trust any UUID table, including this one.

---

## Appendix A — quick command cheat sheet

```
# session bring-up
ATWS  ATE0  ATL0  ATS0  ATH1  ATAL  ATAT1  ATST32  ATSP0
ATI                     -> ELM327 v2.1
ATRV                    -> 12.6V
0100                    -> 41 00 BE 3E B8 11        (supported PIDs 01-20)
ATDPN                   -> A6                        (auto-found protocol 6)
ATSP6                                                (lock it for next time)

# live data
010C1 -> 41 0C 1A F8    RPM   = (0x1A*256 + 0xF8)/4 = 1726
010D1 -> 41 0D 41       Speed = 0x41 = 65 km/h
01051 -> 41 05 5A       Coolant = 0x5A - 40 = 50 °C
0110  -> 41 10 05 DC    MAF   = 1500/100 = 15.00 g/s
012F1 -> 41 2F 80       Fuel  = 128*100/255 = 50.2 %

# diagnostics
0101  -> 41 01 82 ...   MIL on, 2 DTCs
03    -> 43 02 01 33 04 20     P0133, P0420
07    -> 47 00                 no pending codes
0A    -> 4A 00                 no permanent codes
04    -> 44                    cleared

# vehicle id
0902  -> 014 / 0: 49 02 01 31 44 34 / 1: ... / 2: ...   VIN

# multi-PID batch (CAN only, physical addressing)
ATSH 7E0
ATCRA 7E8
01 0C 0D 05 11 04 1F
```

## Appendix B — BLE UUID quick reference

```
# Profile 1 — FFF0 family (most common; iCar2 BLE, Veepeak, generic clones)
service 0000fff0-0000-1000-8000-00805f9b34fb
notify  0000fff1-0000-1000-8000-00805f9b34fb    (adapter -> phone)
write   0000fff2-0000-1000-8000-00805f9b34fb    (phone -> adapter)

# Profile 2 — Vgate/vLinker "IOS-Vlink"
service 000018f0-0000-1000-8000-00805f9b34fb
notify  00002af0-0000-1000-8000-00805f9b34fb
write   00002af1-0000-1000-8000-00805f9b34fb

# Profile 3 — vLinker / iCar Pro transparent UART (single dual-role characteristic)
service e7810a71-73ae-499d-8c15-faa9aef0c3f2
rw      bef8d6c9-9c21-4c9e-b632-bd58c1009f9f

# Profile 4 — HM-10 style
service 0000ffe0-0000-1000-8000-00805f9b34fb
notify  0000ffe1-0000-1000-8000-00805f9b34fb
write   0000ffe1-... (same) or 0000ffe2-0000-1000-8000-00805f9b34fb

# Profile 5 — Nordic UART
service 6e400001-b5a3-f393-e0a9-e50e24dcca9e
notify  6e400003-b5a3-f393-e0a9-e50e24dcca9e
write   6e400002-b5a3-f393-e0a9-e50e24dcca9e

# Always needed
CCCD    00002902-0000-1000-8000-00805f9b34fb

# Bluetooth Classic
SPP     00001101-0000-1000-8000-00805f9b34fb

# WiFi
192.168.0.10 : 35000   (TCP, TCP_NODELAY)
```

---

## Sources

**ELM327 / AT layer**
- [ELM327 v2.1 datasheet (ELM327DSJ), Elm Electronics](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf) — authoritative: prompt `>`/0x3E, CR terminator, 38400 8N1, `AT ST` = 32 ≈ 200 ms, `AT AT0/1/2`, protocol list, 512-byte buffer, error strings, response-count digit, multiline/ISO-TP output format, NULL-byte quirk
- [ELM327 v2.3 command reference — ScanDoc developer guide](https://scandoc.org/en/develop/elm327.html) — condensed AT tables, CAN flow-control commands, example init sequence
- [ELM327 technical reference — TheTom/elm327_obd_for_mac](https://github.com/TheTom/elm327_obd_for_mac/blob/main/ELM327_TECHNICAL_REFERENCE.md) — init ordering, error-string table, inter-command delays for clones
- [can327 — ELM327 driver for Linux SocketCAN (kernel docs)](https://docs.kernel.org/networking/device_drivers/can/can327.html) — clone quirks (RTR dropped, `AT CSM` hard-wired), `BUFFER FULL` behaviour, no full duplex
- [Known problems with China clones of ELM327 — FORScan forum](https://forscan.org/forum/viewtopic.php?t=1575) — clone detection via `ATPPS`/`ATCRA`, commands that lock up clones
- [ELM327 — Wikipedia](https://en.wikipedia.org/wiki/ELM327)
- [python-OBD troubleshooting](https://python-obd.readthedocs.io/en/latest/Troubleshooting/) — reference init sequence (`ATZ`/`ATE0`/`ATH1`/`ATL0`/`ATSPA8`/`0100`), `ATDPN` protocol readback
- [Ircama/ELM327-emulator](https://github.com/Ircama/ELM327-emulator) — testing harness, ISO-TP assembly semantics

**PIDs / J1979**
- [OBD-II PIDs — Wikipedia](https://en.wikipedia.org/wiki/OBD-II_PIDs) — the Mode 01 formula tables, PID 01 MIL/DTC-count layout, PID 03/51 enumerations, Mode 03 DTC bit encoding, Mode 09 VIN, service list 01–0A
- [evrenonur/obd2-elm327-pid-reference](https://github.com/evrenonur/obd2-elm327-pid-reference/blob/master/OBD2_Complete_PID_Reference_EN.md) — consolidated PID/formula reference, mode 03/04 response formats, odometer PID A6
- [OBD service $03 — x-engineer.org](https://x-engineer.org/obd-diagnostic-service-mode-03-request-emission-related-diagnostic-trouble-codes-dtcs/) — Mode 03 CAN response `43 <count> <pairs>`
- [ISO 15765-4 guide — Simma Software](https://simmasoftware.com/iso-15765-4-code-the-complete-guide-to-obd-II-over-can/) — OBD-II over CAN framing
- [ISO 15765-2 — Wikipedia](https://en.wikipedia.org/wiki/ISO_15765-2) — SF/FF/CF/FC PCI encoding
- [OBD-II — Emtron resource centre](https://docs.emtronaustralia.com.au/displays/functions/obd2/index.html) — up to 6 PIDs per request on CAN, physical addressing 0x7E0

**Vgate iCar2 hardware**
- [Vgate iCar2 BT3.0 — product page](https://www.vgatemall.com/products-detail/i-33/)
- [Vgate iCar2 BT3.0 user manual (manuals.plus)](https://manuals.plus/vgate/icar2-bt3-0-elm327-v2-1-obd2-scanner-universal-auto-obd-code-reader-scanner-manual) — device name `V-LINK`, PIN `1234`, LED meanings, 30-minute auto-sleep, wake button, protocol list
- [Vgate iCar2 BLE — product page](https://www.vgatemall.com/products-detail/i-21/) — device name `ELM327`, password `1234`, iOS+Android, ARM chip, auto-sleep
- [Vgate iCar2 WiFi — product page](https://www.vgatemall.com/products-detail/i-20/) — AP mode, IP `192.168.0.10`, protocol list, 30-minute auto-sleep, STA-mode warning
- [Vgate iCar Pro BLE 4.0 manual](https://manuals.plus/vgate/icar-pro-ble-4-0-manual)
- [Configuring Wi-Fi connection to ELM327 adapter — Car Scanner](https://www.carscanner.info/wifi/) — `192.168.0.10:35000`, SSID naming (`VLINK`, `WIFI_OBDII`, `OBDII`)
- [Bluetooth connection troubleshooting — Car Scanner](https://www.carscanner.info/bluetooth-troubleshooting/) — classic vs LE distinction, permissions
- [dconlon/icar_obd_wifi](https://github.com/dconlon/icar_obd_wifi) — Vgate iCar Pro WiFi socket usage

**BLE GATT profiles (concrete UUIDs)**
- [uholeschak/ediabaslib — `BtLeGattSppAndroid.cs`](https://github.com/uholeschak/ediabaslib/blob/master/EdiabasLib/EdiabasLib/BtLeGattSppAndroid.cs) — the `GattSppInfo` profile table: FFE0/FFE1/FFE2, FFE0/FFE1/FFE1, **FFF0 / FFF1 read / FFF2 write**, vLinker `e7810a71…` / `bef8d6c9…` dual-role; CCCD `2902`; 20-byte write chunking
- [fesch/CanZE4iOS — `SettingsViewController.swift`](https://github.com/fesch/CanZE4iOS/blob/master/CanZE/CanZE/SettingsViewController.swift) — Vgate preset: write `FFF2`, read `FFF1`, peripheral name `IOS-Vlink`
- [tronikos/elm327_obdii_ble](https://github.com/tronikos/elm327_obdii_ble) — `DEFAULT_UUID_READ = 0000fff1…`, `DEFAULT_UUID_WRITE = 0000fff2…`
- [rubenmuehlhans/esphome-obd2-ble](https://github.com/rubenmuehlhans/esphome-obd2-ble) — `char_tx_uuid = FFF2`, `char_rx_uuid = FFF1`
- [fpkekko/esp32-dpf-monitor](https://github.com/fpkekko/esp32-dpf-monitor) — FFF0/FFF1/FFF2 tested against Vgate iCar Pro BLE and LELink BLE
- [shinyorg/documentation — OBD BLE](https://github.com/shinyorg/documentation/blob/main/src/content/docs/obd/ble.md) — ELM327 BLE clone profile table, advice to verify with nRF Connect
- [vdvornichenko/obd-ble-serial](https://github.com/vdvornichenko/obd-ble-serial) — FFF0 service, FFF1 notify, FFF2 write; nRF Connect verification advice
- [John-MustangGT/MockStang](https://github.com/John-MustangGT/MockStang) — Vgate `18F0` service, device names `IOS-Vlink`/`IOS-Vgate`/`Vlinker`/`Vgate`
- [kotchasaan/Ford_OBD2_Smart_Gauge](https://github.com/kotchasaan/Ford_OBD2_Smart_Gauge) — `18F0` + `2AF0`/`2AF1` for IOS-Vlink/Vgate
- [kfrancis/ObdInsight — BLE transport design](https://github.com/kfrancis/ObdInsight/blob/main/docs/BLE_TRANSPORT_DESIGN.md) — GATT profile auto-probe strategy and resolution priority
- [douglasjunior/AndroidBluetoothLibrary](https://github.com/douglasjunior/AndroidBluetoothLibrary) — `e7810a71…`/`bef8d6c9…` service+characteristic, `TRANSPORT_LE` requirement for dual-mode devices

**Android platform**
- [Connect Bluetooth devices — Android developers](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices) — `createRfcommSocketToServiceRecord`, `createInsecureRfcommSocketToServiceRecord`, mandatory `cancelDiscovery()` before `connect()`, blocking-call warning
- [Bluetooth permissions — Android developers](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) — `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` (API 31+) vs `BLUETOOTH`/`BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION`
- [Transfer BLE data — Android developers](https://developer.android.com/develop/connectivity/bluetooth/transfer-ble-data) — `setCharacteristicNotification` + CCCD write requirement
- [BluetoothGatt — Android API reference](https://developer.android.com/reference/android/bluetooth/BluetoothGatt) — `requestMtu`, `requestConnectionPriority`, one-operation-at-a-time constraint
- [ConnectivityManager — Android API reference](https://developer.android.com/reference/android/net/ConnectivityManager) — `bindProcessToNetwork` (replaces `setProcessDefaultNetwork`), `Network.bindSocket`
- [Wi-Fi adapters for Android — inCarDoc](https://incardoc.com/en-us/thread/Wi-Fi-Adapters-for-Android) — loss of mobile internet while bound to an OBD AP

**Reference implementations worth reading**
- [fr3ts0n/AndrOBD](https://github.com/fr3ts0n/AndrOBD)
- [brendan-w/python-OBD](https://github.com/brendan-w/python-OBD)
- [barnhill/AndroidOBD](https://github.com/barnhill/AndroidOBD)
