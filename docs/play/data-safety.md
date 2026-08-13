# Play Console — Data safety form answers

Fill in under **App content → Data safety**. The answers below are grounded in
the code as of 2026-08-13: the manifest declares no `INTERNET` permission, so
the app cannot transmit anything; all storage (trip CSVs, fault-code history,
vehicle profile incl. VIN, settings) is app-private on the device; the only
way data leaves the device is the user sharing a CSV through the Android share
sheet (a user-initiated system action to an app the user picks).

## Question-by-question

**1. "Does your app collect or share any of the required user data types?"**
→ **No.**

Justification: Play defines "collection" as data transmitted off the device to
the developer or third parties. This app performs no network transmission at
all (no `INTERNET` permission). On-device-only processing and storage is
explicitly exempt from disclosure. The user-initiated CSV export via the
system share sheet is the user sending their own file to an app they choose —
it is not collection or sharing by this app under Play's definitions.

After answering "No" here, the console skips the rest of the questionnaire.
The items below are what the skipped questions would have been, with the
reason each is moot — keep them for reference in case Google's reviewer asks:

**2. "Is all of the user data collected by your app encrypted in transit?"**
→ Not shown / **N/A** — no data is collected, so nothing is ever in transit.

**3. "Do you provide a way for users to request that their data is deleted?"**
→ Not shown / **N/A** — the developer holds no user data to delete.
Everything lives on the device; uninstalling or clearing app storage removes
it all. (You may state this in the optional free-text/privacy-policy link.)

**4. Data types checklist (location, personal info, financial, health,
messages, photos, files, app activity, device IDs, etc.)**
→ All **not collected**. Notes for the two a reviewer might question:
- *Location*: `BLUETOOTH_SCAN` is declared with `neverForLocation`;
  `ACCESS_FINE_LOCATION` exists only for Android ≤ 11 (`maxSdkVersion="30"`)
  because the OS required it for BLE scanning there. No location is read,
  derived, stored, or transmitted.
- *Device or other IDs*: the app stores the **vehicle's** VIN locally to key
  the vehicle profile. It is not a user/device identifier, and it never
  leaves the device.

**5. Preview and submit** — the resulting public label should read
"No data collected" / "No data shared".

## Related declarations outside the Data safety form

These live elsewhere in **App content** — do not miss them:

- **Foreground service permissions.** The app declares
  `FOREGROUND_SERVICE_CONNECTED_DEVICE` and a service with
  `foregroundServiceType="connectedDevice"` (keeps the OBD polling loop alive
  in background). Since targetSdk 34+, Play Console requires a declaration
  per foreground-service type: pick the `connectedDevice` use case
  ("maintain a connection to an external device"), describe the OBD2 adapter
  polling, and Google **asks for a short video link demonstrating the
  feature** (screen recording of connecting and the persistent notification
  is enough). Confirm the exact form in the console UI — the wording and
  whether video is mandatory has changed over time.
- **Bluetooth permissions.** `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` are not
  part of the sensitive-permissions declaration flow today, but if the
  console flags the legacy `ACCESS_FINE_LOCATION` (maxSdk 30) under
  "Sensitive app permissions → Location", declare: used only on Android 11
  and below because the OS requires it for Bluetooth scanning; core feature:
  connecting to the OBD2 adapter; no location is accessed. Owner must check
  whether the console actually raises this prompt for a maxSdkVersion-limited
  permission — it may not.
- **Ads** → No. **News app** → No. **COVID-19 app** → No.
  **Data safety** and **Privacy policy** are separate items; the policy URL
  (see `docs/play/privacy-policy.md` header) is required regardless of
  answering "no collection".

---

## Notatka po polsku

Formularz: **Zawartość aplikacji → Bezpieczeństwo danych**. Odpowiedź na
pierwsze pytanie ("Czy aplikacja zbiera lub udostępnia dane użytkownika?") to
**Nie** — aplikacja nie ma uprawnienia INTERNET, więc nic nie wysyła; dalsze
pytania (szyfrowanie w tranzycie, usuwanie danych) wtedy nie wystąpią.
Osobno, w tej samej sekcji, wypełnij deklarację **usługi pierwszoplanowej**
(typ `connectedDevice` — utrzymanie połączenia z adapterem OBD2; Google może
poprosić o krótki film). Etykieta w sklepie powinna brzmieć: "Nie zbiera
danych" / "Nie udostępnia danych".
