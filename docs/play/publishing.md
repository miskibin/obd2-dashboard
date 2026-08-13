# Publishing a release from the terminal

Uploading through the Play Console web UI means a file picker, which means a
human. This wires up the Google Play Developer API instead, so a release is a
Gradle task. The plugin is [Gradle Play Publisher][gpp], applied by `:app` only
when `play-service-account.json` exists in the repo root.

The one thing the API cannot do is *create* the app — that first bundle had to
go up by hand, and it did (0.3.0, version code 3). Everything after is API work.

## One-time setup (needs a human, once)

1. **Google Cloud → create a service account.** Any project will do; if the Play
   Console already linked one, use that. Console → IAM & Admin → Service
   Accounts → Create. No GCP roles are needed — the permission that matters is
   granted on the Play side, in step 3.
2. **Enable the API.** In the same project: APIs & Services → Enable APIs →
   **Google Play Android Developer API** → Enable.
3. **Grant it access in Play Console.** Users and permissions → Invite user →
   paste the service account's email (`...@....iam.gserviceaccount.com`).
   Scope it to this app and give it, at minimum:
   - *Release to testing tracks* (internal/closed/open), and
   - *Edit and delete draft apps* if you also want listing text pushed.

   Deliberately withholding *Release to production* is a good default: a typo in
   a script then cannot reach real users.
4. **Key → repo.** Service account → Keys → Add key → JSON. Save it as
   `play-service-account.json` in the repo root. It is gitignored. It is **not**
   the upload key ([signing.md](signing.md)) — this one says *who is uploading*,
   the other says *what is signed*.

## Cutting a release

```bash
# 1. Bump both numbers in app/build.gradle.kts: versionCode +1, versionName.
# 2. Build the signed bundle (needs keystore.properties, see signing.md).
./gradlew bundleRelease

# 3. Ship it to the internal testing track.
./gradlew publishBundle
```

`publishBundle` uploads, attaches release notes, and rolls out. Defaults live in
the `play { }` block in `app/build.gradle.kts`: track `internal`, status
`COMPLETED`, bundles rather than APKs, and `ResolutionStrategy.FAIL` so a
forgotten `versionCode` bump is an error instead of a silent no-op.

Useful variations:

```bash
./gradlew publishBundle --track internal        # override the track ad hoc
./gradlew promoteArtifact --from-track internal --promote-track production
./gradlew publishListing                        # push store text/graphics
./gradlew uploadReleasePrivateBundle            # Internal App Sharing link, no track
```

## Release notes

GPP reads them from files, one per language:

```
app/src/main/play/release-notes/en-US/internal.txt
app/src/main/play/release-notes/pl-PL/internal.txt
```

Name the file after the track (`internal.txt`, `production.txt`). Max 500
characters, plain text. Absent files just mean a release without notes.

## In CI, later

The same JSON as a repository secret is the right call here — unlike the upload
key, CI genuinely needs it, and a secret that leaks can be revoked from the GCP
console in seconds without touching the app's signature:

```yaml
- run: echo '${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}' > play-service-account.json
- run: ./gradlew publishBundle
```

Gate it on `startsWith(github.ref, 'refs/tags/v')` so only tagged commits ship.

[gpp]: https://github.com/Triple-T/gradle-play-publisher
