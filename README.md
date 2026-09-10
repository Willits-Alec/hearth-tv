# Hearth TV

One remote for a Sony Bravia (KD-85X80CK, Android TV 12), the Sonos Arc home-theatre group and the Roku Ultra
attached to it — a standalone Android app that talks to all three directly over the home Wi-Fi. No server, no
cloud, no account, nothing running in the background.

- **Owner's install page:** https://willits-alec.github.io/hearth-tv/
- **Scope and decisions:** [SCOPE.md](SCOPE.md)
- **Deploying and publishing:** [DEPLOY.md](DEPLOY.md)
- **Acceptance checklist:** [ACCEPTANCE.md](ACCEPTANCE.md)

## Build and test

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
.\gradlew.bat testDebugUnitTest        # JVM suite: fakes + clients + view models, seconds
.\gradlew.bat assembleRelease          # refuses while the suite is red
```

Release signing reads `keystore/hearth-tv.jks` and `local.properties`, both **outside git**. Without them the
build still produces an unsigned release and a debug APK.

## How it is tested

The protocol layer is pure Kotlin and is exercised against **fakes built from real replies**: every JSON and
XML under `app/src/test/resources/fixtures/` was captured from the actual TV, Arc and Roku (scrubbed of the
owner's addresses and serials). `FakeBraviaServer`, `FakeSonos` and `FakeRoku` answer like the devices did,
including their quirks — the Sony pairing call that wants `nickname` instead of `nick`, the 403-with-auth_url
before pairing, `Illegal State` on the home screen. See SCOPE.md §5.
