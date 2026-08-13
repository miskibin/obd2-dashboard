# Release checklist — from this repo to Google Play

Ordered. Steps 1–4 happen in the repo; the rest in Play Console
(https://play.google.com/console). PL notes in *italics* where the console UI
matters.

## 1. Create the upload keystore (once, keep forever)

```bash
keytool -genkeypair -v \
  -keystore upload-keystore.jks \
  -alias upload \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

Store `upload-keystore.jks` outside the repo (and back it up somewhere safe —
with Play App Signing it is recoverable, but painfully). Use a real password.

## 2. keystore.properties + wire it into the build

Create `keystore.properties` in the project root:

```properties
storeFile=/absolute/path/to/upload-keystore.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

It is already listed in `.gitignore` — **never commit it**.

The build reads this file automatically: when `keystore.properties` exists in
the project root, `app/build.gradle.kts` signs the `release` build type with
the upload key it describes; when the file is absent, release builds fall back
to the checked-in debug keystore (fine for local installs, rejected by Play).
So the only step here is creating the file — no Gradle edits needed. Verify
before uploading: `keytool -printcert -jarfile app-release.aab` should show
your upload certificate, not `CN=Android Debug`.

## 3. Build the AAB

```bash
./gradlew bundleRelease
# output: app/build/outputs/bundle/release/app-release.aab
```

Play requires AAB, not APK. Check `versionCode`/`versionName` in
`app/build.gradle.kts` first (currently 2 / "0.2.0"); every upload needs a
higher `versionCode`.

## 4. Sanity-check the bundle

Install it locally once via bundletool, and confirm the release build
(minify + shrink are on) still connects to the adapter and records a trip.

## 5. Create the app in Play Console

"Create app" → name **OBD2 Dashboard**, default language English (US), App,
**Free**. *(Konsola może być po polsku — "Utwórz aplikację".)*

- Free is effectively irreversible (a free app cannot become paid).

## 6. Play App Signing

On first AAB upload you enroll in Play App Signing (default, not optional for
new apps). Choose "Let Google generate the app signing key"; your keystore
from step 1 becomes the **upload key**. Google signs what users download; if
you ever lose the upload key it can be reset via support.

## 7. App content declarations (before any release can go live)

All under **App content** *(„Zawartość aplikacji")*:

- **Privacy policy URL** — the repository is public, so once this file is on
  `main` the ready-to-paste URL is:
  `https://github.com/miskibin/obd2-dashboard/blob/main/docs/play/privacy-policy.md`
  (a GitHub Pages URL also works if you prefer a cleaner look).
- **Ads** → No, the app contains no ads.
- **Data safety** → answers in `docs/play/data-safety.md`.
- **Foreground service** declaration (`connectedDevice`, video link) — see
  the same file.
- **Content rating (IARC questionnaire)** — category: **Utility / Tool**.
  Answer No to everything: no violence, sexuality, drugs, gambling,
  profanity, no user-generated content, no user interaction/chat, no sharing
  of user location, no purchases. Expected rating: PEGI 3 / Everyone.
- **Target audience** → age groups **18+** (simplest), or 13+ if you prefer;
  in no case tick any group under 13 — that triggers Families policy
  requirements this app has no reason to meet. Then "app not designed to
  appeal to children".
- **Government app / News app / COVID app** → No.

## 8. Store listing

Paste from `docs/play/store-listing.md` (EN default + PL localized listing),
upload icon 512×512, feature graphic 1024×500, phone screenshots (demo mode).
**Category: Auto & Vehicles.** Contact email: skibinek109@gmail.com.

## 9. Internal testing first

**Testing → Internal testing** → create release, upload the AAB, add your own
Gmail (and any friends) to the tester list, roll out. Installable within
minutes via the opt-in link. Use this to verify the Play-signed build.

## 10. Closed testing + the 12-tester / 14-day rule

Personal developer accounts **created after Nov 13, 2023** must run a closed
test with **at least 12 testers continuously opted in for 14 days** before
they can apply for production access. **Check whether this applies to your
account**: Play Console shows a "production access" task on the dashboard if
it does. If yes: create a closed track, recruit 12+ testers (r/AndroidClosedTesting
and similar communities exist for this), keep them opted in 14 days, then
answer the production-access application questions.

## 11. Countries and rollout

In each track's settings pick countries — Poland at minimum; "add all
countries" is fine for a free offline app. Then **Production** → create
release (same AAB or a newer one) → review → roll out. First review by Google
typically takes a few days for a new personal account.

## 12. After publish

- Keep the upload keystore + passwords backed up.
- Bump `versionCode` for every future upload.
- Tag the released commit (`v0.2.0`-style, matches existing CI convention).

## Android Auto (future)

The README plans an Android Auto screen via `androidx.car.app`. When that
ships: Play distribution is **mandatory** for Android Auto apps (sideloaded
builds only work in developer mode), and you must opt in to the **Android
Auto form in Play Console → App content** and pass the car-app quality
review. Nothing to do now — just budget review time for that release.
