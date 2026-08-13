# The upload key: where it lives, how to survive losing it

Play App Signing is on for this app, so there are **two** keys in play:

- the **app signing key** — generated and held by Google, used to sign what
  users actually download. Nothing to do here; Google backs it up.
- the **upload key** — ours, created 2026-08-13. It only proves to Play that an
  upload came from us. Play strips our signature and re-signs with the app
  signing key.

Fingerprints of the upload certificate (`keytool -printcert -jarfile app-release.aab`
should print these; anything else means the build did not pick up
`keystore.properties`):

```
SHA1:   6B:2F:3E:2B:1C:9E:F8:CE:BD:FB:64:CD:ED:8D:62:36:58:65:CC:C1
SHA256: 69:8F:64:B1:DC:3C:97:A1:B3:E9:7F:84:79:86:55:48:61:0F:5A:75:80:24:D7:76:20:CB:28:E6:4A:55:64:D1
Alias:  upload      Valid until: 2053-12-29
```

## Where the pieces are

| What | Where | Notes |
|---|---|---|
| `upload-keystore.jks` | `C:\Users\skibi\keys\obd2-dashboard\` | working copy, outside the repo |
| `keystore.properties` | repo root | gitignored; holds the store/key password in plain text |
| backup of both | `OneDrive\Klucze\obd2-dashboard\` | synced off the machine |

The password is stored next to the key in the backup, which is a deliberate
trade-off: a key whose password lives only in someone's memory is a key that is
already lost. The exposure it buys is small — with Play App Signing an upload
key is **replaceable**, see below — while losing both means going through
Google support for every future release.

Worth doing on top: put the password in a password manager as well, so the
OneDrive copy is not the single point of failure.

## Never

- Do not commit `keystore.properties` or any `*.jks` (both are in
  `.gitignore`; keep them there).
- Do not put the upload key in this repository's **GitHub Actions secrets**.
  Two reasons. Secrets are write-only — you cannot read one back, so it is not
  a backup, only a way to hand the key to CI. And CI here publishes
  *debug-signed* APKs for sideloading; it never needs the upload key. Adding
  it would put the key one compromised action away from a public repo for no
  gain.

## If CI ever does need to sign

Only then, and only for tag builds:

```bash
base64 -w0 upload-keystore.jks > keystore.b64
gh secret set KEYSTORE_BASE64 < keystore.b64
gh secret set KEYSTORE_PASSWORD    # paste when prompted
gh secret set KEY_ALIAS --body upload
rm keystore.b64
```

and in the workflow, before `./gradlew bundleRelease`:

```yaml
- name: Restore upload keystore
  if: startsWith(github.ref, 'refs/tags/v')
  run: |
    echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > "$RUNNER_TEMP/upload.jks"
    printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
      "$RUNNER_TEMP/upload.jks" '${{ secrets.KEYSTORE_PASSWORD }}' \
      '${{ secrets.KEY_ALIAS }}' '${{ secrets.KEYSTORE_PASSWORD }}' \
      > keystore.properties
```

Note the workflow triggers on `push`, not `pull_request`, so a fork cannot run
it and cannot reach the secrets.

## If the key is lost anyway

Not fatal, just slow. In Play Console: **Test and release → Setup → App
signing → Request upload key reset**, generate a fresh keystore, and Google
swaps it in (usually a couple of days). Users are unaffected — the app signing
key never changed, so updates still install over existing copies.
