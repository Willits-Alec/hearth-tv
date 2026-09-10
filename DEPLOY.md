# Deploy — Hearth TV

Two audiences, two paths. Same signing key for both, so any build installs over any other.

| | Alec's S26 (test device) | The owner's phone (finished product) |
|---|---|---|
| Channel | Pi appshelf over WireGuard, installed silently through Hearth | Public page on GitHub Pages + GitHub Releases |
| Command | `.\install-s26.ps1` | `.\publish.ps1 -Notes "..."` |
| Result | APK on the phone, app launched, version echoed back | New release + `docs/version.json`; the app shows an Update banner |

## Prerequisites (this PC)

- JDK 21 at `C:\Program Files\Java\jdk-21`; Gradle 8.9 (`C:\projects\fitness-app\tools\gradle-8.9` or the wrapper).
- `keystore/hearth-tv.jks` + passwords in `local.properties` — **gitignored. Back both up.** Losing the key means
  the owner must uninstall and re-pair; there is no recovery.
- `gh` logged in as Willits-Alec; `ssh pi` alias; the Hearth gateway running for the S26 path.

## Publish a version

1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.
2. `.\publish.ps1 -Notes "what changed, one line the owner will read"`.
   It runs the JVM suite, builds the signed APK, creates the GitHub release `vX.Y.Z` with `hearth-tv.apk`,
   rewrites `docs/version.json`, commits and pushes. Pages redeploys in about a minute.
3. The owner opens the app → Update banner → Download → Install. Or opens the page directly.

Fixed URLs: page `https://willits-alec.github.io/hearth-tv/`,
APK `https://github.com/Willits-Alec/hearth-tv/releases/latest/download/hearth-tv.apk`.

## Test build on the S26

`.\install-s26.ps1` builds (tests gate it), copies to `dist/`, `scp`s to the Pi appshelf, then asks Hearth to run
`curl` + `pm install -r` + `am start` on the phone and prints the installed versionName. The S26 runs WireGuard
full-tunnel; the app binds its sockets to Wi-Fi, so it works there exactly as on the owner's phone.

## First-time setup that already happened

- 2026-09-10: keystore generated (`keytool`, RSA 2048, 10000 days, alias `hearthtv`).
- 2026-09-10: the TV was PIN-paired from the S26 for contract tests; registration in `local/SITE.md`.
