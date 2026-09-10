# Hearth TV — Scope

Standalone Android app that runs the **Family Room TV** (Sony KD-85X80CK) and its **Sonos Arc** home-theatre
group from the owner's phone, over his own Wi-Fi, with nothing else installed anywhere. A port of the TV tile in
Alec's Hearth app, re-built for a house that has no Hearth gateway, no WireGuard and no Alec on hand to fix things.

Status: **SCOPE COMPLETE IN CODE 2026-09-10 — every v1 feature in §4 is built; §8 checks each promise against the code. Stages 0–3 verified on the S26; v0.2.1 closed the last §4.2 items; v0.3.0 adds the Roku tile (Stage 2b), the last v1 feature. Stage 4 is under way: the real icon and `OWNER-GUIDE.md` shipped in v0.4.0. Remaining: the acceptance walk on the S26 at the owner's house, then v1.0 for the owner's phone. §9 records Alec's next-round requests (touchpad, volume bar, volume feedback, Sonos voice), to start after that.**
Revised 2026-09-10: Alec's S26 is the test device and the owner receives a finished product (D6); one public repo
publishes a download page with an in-app update banner (D7); pairing is by PIN, no TV-menu visit needed (D2).

---

## 0. Decisions (LOCKED 2026-09-10 — Alec answered every open item; §7 records the answers)

| # | Decision | Proposed |
|---|---|---|
| D1 | Architecture | **Standalone APK, LAN-only.** The phone talks straight to the TV (Sony REST + IRCC + Wake-on-LAN) and to the Arc (Sonos UPnP). No server, no cloud, no account, no analytics, no background service. Works only while the phone is on the TV's Wi-Fi, exactly like Sony's and Sonos's own apps. |
| D2 | TV authentication | **PIN pairing** (Sony `accessControl.actRegister`: the TV shows a 4-digit code once, the app keeps a registration the TV remembers and renews silently; the TV lists it under *Remote device settings*). The Pre-Shared Key stays as an advanced fallback only. A PSK cannot be read off the TV over the network, by design, so nobody has to visit the TV menu: the PIN flow is the whole setup. **Verified on this TV 2026-09-10.** Firmware gotcha, now a fixture: `actRegister` wants the field `nickname`, not the `nick` every library uses, and answers "Illegal Argument" until it matches what `getMethodTypes` reports. |
| D3 | Stack | Kotlin + Jetpack Compose, the same toolchain as Reach (Kotlin 2.0.21, Compose BOM 2024.09.02, AGP 8.5.2, Gradle 8.9, JDK 21, minSdk 26 / targetSdk 34). OkHttp for HTTP, kotlinx.serialization for JSON, DataStore for settings. No Room. |
| D4 | Volume routing | When the TV's output is *Audio system* (the normal state), the volume and mute buttons drive the **Sonos group directly**; when it is *TV speakers*, they drive the TV. The screen always says which one it is driving. Removes HDMI-CEC from the volume path entirely. |
| D5 | Sonos feature set | v1 = group volume, mute, night sound, speech enhancement, "switch to TV", current source. v2 = sub level, surround level, EQ. |
| D6 | Testing | **TDD, JVM-first; Alec's S26 is the test device, the owner gets a finished product.** Protocol clients and the state machine are pure Kotlin tested against a fake TV built from real fixtures captured from this TV on 2026-09-09. Test builds install silently on the S26 through Hearth (`shell_exec` → `pm install`), and the contract suite runs against the real TV whenever the S26 is on the owner's Wi-Fi. The same checks ship inside the app as a one-tap **Self-test** with a copyable report, for the day the owner's phone misbehaves. No APK is cut unless `gradle test` is green. Details §5. |
| D7 | Distribution | **One public GitHub repo, `hearth-tv`, no VPN.** Source, tests and releases together: GitHub Pages serves a one-page download site and GitHub Releases hosts the signed APK, so the owner has one bookmark and the APK link `…/releases/latest/download/hearth-tv.apk` never changes. The app checks the page's `version.json` on launch and shows an **Update available → Download** banner. Public means two rules: fixtures are scrubbed of the owner's MAC, IPs and names (real values live in gitignored `local/SITE.md`), and the **signing keystore and its passwords are never committed** (gitignored, backed up outside the repo, passwords read from `local.properties`). Alec's test builds also go to the Pi appshelf for silent installs on the S26. Fallback: Quick Share. |
| D8 | Not in v1 | Casting media from the phone, out-of-home control — listed in §6 with what they would take. **Roku moved into v1** (Alec, 2026-09-10). **Voice moved into v1** after Alec's first use (0.2.0, 2026-09-10): the phone's speech recogniser plus a fixed grammar, exactly the v2 sketch in §4.3. |
| D9 | Name | **Hearth TV** (Alec, 2026-09-10). Package `com.alec.hearthtv`, repo `hearth-tv`, APK `hearth-tv.apk`. The placeholder icon from Stage 0 was **replaced in v0.4.0**: a flame inside a screen, generated on the local GPU, traced to vector, recoloured to the app palette, fitted to the adaptive-icon safe zone, with a monochrome layer for themed icons. |

---

## 1. One-line concept

One screen, big buttons: power, volume that always works, inputs by their real names, the streaming apps as tiles,
a D-pad for when you need it, and a "fix the sound" button. Nothing to configure after the first minute.

## 2. Problem / goal

- The owner runs a Sony Google TV, a Sonos Arc surround system, a Roku and a game console from a pile of remotes.
- Alec has exactly this in Hearth for his own LG, but Hearth needs the gateway PC, WireGuard and Alec's network.
- The owner's house has none of that, and Alec cannot walk over to fix a broken build. So the app has to be
  self-contained, self-diagnosing, and correct on the first install. That is what §5 is for.
- **Alec's S26 is the test device**; the owner receives a finished product from the public download page and pulls
  fixes with the in-app Update banner. The S26 runs WireGuard full-tunnel, which is exactly why every socket in the
  app is bound to the Wi-Fi network (§4.2), and why Hearth can install test builds on it silently.

## 3. Facts on the ground (probed 2026-09-09 from Alec's phone on the owner's Wi-Fi)

Addresses, MACs and device ids live in `local/SITE.md` (gitignored). This section keeps only what a public repo may hold.

| | |
|---|---|
| TV | Sony **KD-85X80CK** (X80K family, 85"), Android TV 12, firmware Mar 2026, Cast name "Family Room TV" |
| TV network | **Wired Ethernet** (good for Wake-on-LAN), DHCP address on the house LAN behind an Asus router; address and MAC in `local/SITE.md`. Needs a DHCP reservation (§7). |
| Sony REST API | Live at `http://<tv>/sony/<service>`, interface version 5.7.0. Answers **without any key**: power status, interface info, volume, sound output, the input list, the full IRCC code table, the WoL MAC. Answers **403 + pairing URL** until paired: system info, app list, now-playing, WoL mode, network settings, and the IRCC send endpoint. |
| Supported services | `system`, `audio`, `avContent`, `appControl`, `accessControl` (PIN pairing), `guide`. Includes `setTextForm` (type on the TV from the phone) and `getScreenshot`. |
| Inputs | HDMI 1 (empty), HDMI 2 labelled **"Game"** and physically the **Roku Ultra** (CEC entry "Roku Ultra", port 2), HDMI 3 eARC = **Sonos Arc**, HDMI 4 labelled **"Roku"** but empty (a stale label), Composite. While the TV is on, CEC devices appear as their own entries; the app lists those and hides unconnected ports. |
| Roku | **Roku Ultra** (4660X), Roku OS 15.3.4, on HDMI 2 (the HDMI 4 "Roku" label is stale); ECP on :8060. Was in **Limited** network-access mode (device info and active app answer; app list / keypress / launch refused); Alec set it to **Default** on 2026-09-10 and the full app list (61 apps) was captured. The app still detects Limited mode and shows the fix: Settings → System → Advanced system settings → Control by mobile apps → Network access → Default. Fixtures captured 2026-09-10. |
| Other TV ports | Cast 8008/8009/8443 open; Android TV Remote protocol 6467 open; Simple IP 20060 answers; ADB 5555 closed. |
| Sonos | Arc (model S19, sw 94.1) + Sub + Era 300 surrounds in room "TV Room". UPnP on :1400 answers. Addresses in `local/SITE.md`. |
| Pairing | PIN pairing completed 2026-09-10 from Alec's phone; cookie lifetime 14 days, renewed silently while the TV remembers the client. With it: system info, WoL mode (**enabled**), app list (37 apps incl. Prime Video, Netflix, YouTube, Apple TV, Hulu, Plex, Paramount+, Spotify, Pandora) and the IRCC send endpoint all answer. Registration details in `local/SITE.md`. |
| Audio chain | Verified correct end to end on 2026-09-09: Speakers = Audio system, eARC Auto, Digital audio out Auto 1, DD+ output, Bravia Sync locked on, Atmos confirmed on the Arc. |
| Unknown | A Raspberry Pi on that LAN is the **house DNS server** (the TV's network settings list it first), web UI answers 403. A Pi-hole-class box. Whose? (§7) |

## 4. Features

### 4.1 v1 — the port of the Hearth TV tile

| Feature | LG tile today | On the Sony / Sonos | API |
|---|---|---|---|
| Power on | Wake-on-LAN | WoL magic packet to the TV's MAC + `setPowerStatus true` (works from network standby when *Remote start* is on) | `system.setPowerStatus`, UDP 9 |
| Power off | SSAP | `setPowerStatus false` | `system.setPowerStatus` |
| State line | connected / app / volume | "On · Prime Video", "Standby", volume, which output | `getPowerStatus`, `getPlayingContentInfo`, `getVolumeInformation`, `getSoundSettings` |
| Volume ± / mute | TV only (silently ignored on the soundbar) | **D4**: Sonos group when output = Audio system, TV otherwise | Sonos `RenderingControl`, `audio.setAudioVolume` / `setAudioMute` |
| Inputs | list + switch | CEC device names first ("Roku Ultra", "Sonos Arc"), then the owner's port labels ("Game"); unconnected ports and ports claimed by a CEC device are hidden; highlight current, switch | `avContent.getCurrentExternalInputsStatus`, `setPlayContent extInput:hdmi?port=N` |
| Apps | favourites row + full list | same; favourites = Prime Video, YouTube, Disney+, Netflix, Apple TV, Max, Spotify when installed | `appControl.getApplicationList`, `setActiveApp` |
| Buttons | D-pad, Home, Back, Menu, media, numbers, colours | D-pad (arrows repeat while held), OK, Back, Home, Menu, Info, media row; under **More keys**: Guide, Input, Exit, Options, Ch ±, Subtitles, Audio, 0–9, Netflix, YouTube, the colour keys. Every press ticks. This TV has no Prime key | IRCC over `/sony/ircc`, codes read live from `getRemoteControllerInfo`, never hard-coded |
| Sound output | TV speaker / optical / HDMI | TV speakers ↔ Audio system | `audio.setSoundSettings outputTerminal` |
| **Fix the sound** | — (new) | one tap: output → Audio system, Arc → TV input, then re-read state | `setSoundSettings`, Sonos `AVTransport` `x-sonos-htastream` |
| Type on the TV | — (new) | phone keyboard → the TV's active text field (search boxes, passwords) | `appControl.setTextForm` |
| Sonos row | — (new) | group volume, mute, night sound, speech enhancement, source | Sonos UPnP |
| **Voice** | local LLM on the gateway | mic button → the phone's speech recogniser → a fixed grammar (volume, mute, power, open <app>, switch to <input>, keys, fix the sound, night mode, speech enhancement, type <text>); the words understood are shown | `RecognizerIntent`, then the same actions as the buttons |
| **Roku tile** | — (new) | the Roku Ultra (HDMI 2, CEC "Roku Ultra"): Home, Back, D-pad, OK, play/pause, replay, its own channel list and launch; Home or a channel also switches the TV to the Roku's CEC input first. **Shipped 0.3.0** | Roku ECP on `:8060`: `keypress/<Key>`, `query/apps`, `launch/<id>`, `query/device-info`, `query/active-app` |

### 4.2 v1 — robustness, because nobody can fix it on site

- **Setup wizard** (first launch, ~1 minute): find the TV by SSDP (`ScalarWebAPI`) with manual IP fallback →
  pair (PIN on the TV; a pre-shared key under an advanced link, D2) → find the Sonos home-theatre room by SSDP → done.
  Stores the TV's address, MAC and model. When the TV stops answering, the remote **re-finds it by MAC** (SSDP, then
  the unauthenticated WOL-MAC read) and saves the new address, so DHCP moving the TV heals itself.
- **Self-test** (one tap on the Diagnostics screen): runs the read-only contract checks on the phone it is installed on —
  TV discoverable (SSDP), reachable, paired, wake-on-LAN mode, power state, inputs, volume, sound output, IRCC table,
  Sonos reachable, home-theatre group, update check —
  and renders a pass/fail list with a **Copy report** button. This is the field version of the contract suite in §5.
- **Update check**: on launch and on demand, fetch `version.json` from the public page; if newer, show a banner
  with a Download button that opens the browser at the fixed APK link. No auto-install, no background polling.
- **Diagnostics screen**: app, network binding, TV / pairing / Sonos / Roku settings, update status; the Self-test
  report (reachability, pairing, WoL mode, group state); the **last 20 errors with timestamps**, newest first, with
  Clear; a **Copy report** button producing one text block the owner can paste to Alec.
- **Plain-language errors** with the next step ("Can't see the TV. Is your phone on the home Wi-Fi?").
- **Wi-Fi binding**: every socket (HTTP, UDP for WoL and SSDP) is bound to the Wi-Fi `Network` object, so a VPN
  on the phone cannot swallow LAN traffic. Alec's own phone runs WireGuard full-tunnel, and this is exactly what
  broke the first probes today — the fix is designed in, not discovered later.
- **Standby handling**: when the TV reports standby, the screen collapses to a big power button; after power-on
  it polls state for ~15 s so the tile fills in without a manual refresh.
- **Coalesced volume** (held presses fold into as few device calls as the network keeps up with), **key repeat on
  hold** for volume and the arrows, a **haptic tick** per press, big touch targets, works one-handed.
- **No background work**: the app does nothing when closed. Nothing to drain a battery or leak.

### 4.3 Dropped from the LG tile, and why

| LG feature | Verdict | Reason |
|---|---|---|
| Toast notification on the TV | dropped | no Sony equivalent |
| Open URL / dashboard page on the TV | dropped | this Bravia has no browser |
| Cast local media | v2 | needs the Google Cast SDK and a receiver; the owner can cast from any app already |
| Screen share | dropped | Google Home / Smart View already do it; the app would only open a menu |
| Voice → TV action | **shipped in v1 (0.2.0)** | Hearth's version runs a local LLM on the gateway; here it is the phone's speech recogniser + a small grammar (`VoiceCommandParser`) |
| Raw request passthrough | dropped | the Self-test, the error log and the Copy report answered every "what did the TV say" question that came up; a hidden raw console was never needed |

## 5. Platform, test strategy, delivery

### Tech stack (decided, D3)

Mirror `C:\projects\fitness-app` (Reach): `build.gradle.kts`, Kotlin 2.0.21, Compose BOM 2024.09.02, AGP 8.5.2,
Gradle 8.9 under `tools/`, JDK 21 at `C:\Program Files\Java\jdk-21`, Android SDK at the standard path, release
signing from a NEW keystore kept **outside git** (`keystore/` is gitignored, passwords read from `local.properties`,
a backup copy lives with Alec's other keys). Dependencies added over Reach: OkHttp 4.12,
kotlinx-serialization-json, DataStore Preferences. Test dependencies: JUnit 4, kotlinx-coroutines-test, Turbine,
OkHttp MockWebServer. **No Robolectric**: the Compose layer is thin (it renders `RemoteUiState` and forwards taps)
and is verified on the S26 with `ACCEPTANCE.md`; every behaviour lives in the pure-Kotlin layers under JVM tests.

### Layers (each testable on its own)

```
ui/            Compose screens (Remote, Setup wizard, Diagnostics) + thin ViewModels   ← verified on the S26 (ACCEPTANCE.md)
remote/        RemoteController (state, D4 routing, power, actions, pairing), TvRelocator ← JVM tests with FakeBravia + FakeSonos
voice/         VoiceCommandParser — the spoken-command grammar                          ← JVM tests
diagnostics/   SelfTest (the contract suite inside the app), ErrorLog                   ← JVM tests
update/        UpdateChecker — version.json                                             ← JVM tests with MockWebServer
protocol/      BraviaClient (JSON-RPC, IRCC, PIN/PSK auth), SonosClient (UPnP SOAP subset),
               RokuClient (ECP), SsdpDiscovery, WakeOnLan  ← JVM tests against fakes built from captured fixtures; UDP on loopback
net/           WifiLanTransport: OkHttp + sockets bound to the Wi-Fi Network, resolved per connection
data/          SettingsRepository (DataStore)
```

### TDD rules for this project

1. **Fixtures first.** `src/test/resources/fixtures/bravia/*.json` are the literal responses this TV gave on
   2026-09-09 (power, interface, inputs, volume, sound settings, IRCC table, supported API, the 403 pairing error,
   Cast `eureka_info`), plus `sonos/device_description.xml` from the Arc. Every parser test reads a fixture; no
   hand-typed JSON in tests.
2. **Red → green per feature.** A feature starts as a failing client test, then a failing controller test, then the
   screen renders it and is checked on the S26. No production code without a failing test that needs it.
3. **FakeBraviaServer** is a stateful MockWebServer dispatcher: it holds power / volume / mute / input / active app /
   paired-or-not and answers like the real TV (including 403 until paired, and "display is off" errors in standby).
   FakeSonos does the same for volume, mute, night mode, source. Behaviour tests run against these, in milliseconds.
4. **Contract checks run on the phone, not from Gradle.** No development machine is ever on the owner's LAN, so
   the planned `-PtvIp` Gradle task was dropped. The read-only contract suite is compiled into the app as the
   **Self-test** (§4.2) and was run from the S26 on 2026-09-10; the write set (power, volume, input, sound output,
   pairing) was exercised by hand from the S26 the same day. Every difference found went back into the fakes as a
   fixture (`fixtures/README.md` records how each was captured). This is how we learn what the fake got wrong,
   once, before the owner does.
5. **Definition of done per stage**: JVM suite green; Self-test green on the S26 at the owner's house; the
   acceptance checklist in `ACCEPTANCE.md` walked there.
6. **Gate**: `assembleRelease` depends on `test`. A red suite cannot produce an APK.

### Stages

| Stage | Contents | Size |
|---|---|---|
| 0 | **DONE 2026-09-10.** Repo skeleton from the Reach template, new keystore (outside git), 30 scrubbed fixtures, FakeBraviaServer + FakeSonos + FakeRoku with 20 green JVM tests, `assembleRelease` gated on the suite, public repo `Willits-Alec/hearth-tv` + Pages page + `publish.ps1` + `install-s26.ps1`; v0.0.1 published and installed on the S26 | S |
| 1 | **Code DONE 2026-09-10** (47 tests green, written red first): `BraviaClient` — PIN + PSK auth, power, state, volume/mute/step, sound output, inputs, apps, now-playing, IRCC with the TV's own code table, text entry; `WakeOnLan`; `SsdpDiscovery`; `LanTransport` (Wi-Fi-bound). Real-TV contract run through Alec's phone (2026-09-10): pairing, IRCC, all reads, volume nudge, sound output speaker↔audioSystem, power off→standby→on over the network (no WoL needed while WoL mode is on) — all as the fake predicts; the `-PtvIp` runner was dropped for the in-app Self-test (§5 rule 4) | M |
| 2 | **2a Sonos DONE 2026-09-10** (9 tests red-first): `SonosClient` — description, volume/step/mute, night sound, speech enhancement, transport, source, switch-to-TV, whole-house topology + home-theatre group. **2b Roku DONE 2026-09-10** (v0.3.0, 18 tests red-first): `RokuClient` — device info, active app, the 61-channel list, keypress, launch, Limited-mode detection; `RokuState` in the controller; a Roku card on the remote (name and what it is showing, Switch-to-Roku / Home, its own pad with hold-to-repeat, transport keys, channel chips with favourites); step 4 of the wizard finds the box by SSDP or a typed address and reports a locked box; Self-test gains *Roku reachable* and *Roku control* | S |
| 3 | **Built 2026-09-10 and VERIFIED on the S26 at the owner's house the same day**: wizard found + paired the TV and picked the Arc; remote shows On · KD-85X80CK, inputs by CEC name, 37 apps, live Sonos state; volume-up moved the Arc 25→26 over the network. Unconnected HDMI ports hidden when CEC devices are listed (0.2.0); discovery re-check pending. 3a `RemoteController` (14 tests red-first: state, D4 routing, power with WoL + polling, actions, fix-the-sound, pairing, error surfacing), `UpdateChecker` (3), `SelfTest` (3); 3b Compose UI: Remote screen, Setup wizard (SSDP or typed address → PIN → Sonos), Diagnostics with Self-test, Copy report, update banner; DataStore settings. v0.1.0 installed on the S26 and published. **v0.2.0 (4) built, installed on the S26 and published 2026-09-10** from Alec's first-use feedback: voice commands (`VoiceCommandParser` grammar + mic button via RecognizerIntent, CEC device names beat stale port labels), text entry pre-checks `textInputActive()` and explains when no TV text box has focus (Sony answers Illegal State otherwise), and the app no longer drops back to the connect screen after minimising (Wi-Fi Network resolved per connection, `lastKnown` + quiet refresh under a Reconnecting banner). 91 tests green. **v0.2.1 (5) 2026-09-10 — scope reconciliation (§8)**: error log (last 20, timestamps, Clear, in the Copy report), key repeat on hold + haptic tick + coalesced volume, TV re-found by MAC when DHCP moves it, pre-shared key fallback on the pairing step, the full key set under More keys, Self-test gains discoverable / wake-on-LAN / update checks. 98 tests green. On-device check of 0.2.0/0.2.1 with Alec pending | L |
| 4 | **In progress.** `OWNER-GUIDE.md` written and linked from the download page, and the real icon shipped, both in v0.4.0 (2026-09-10). Left: the acceptance pass on the S26 at the owner's house (`ACCEPTANCE.md`), which needs Alec there and his hands on the phone; then v1.0 published and installed on the owner's phone from the page. Fixes ship as releases he pulls with the Update banner. | S |

Honest estimate: Stage 1 and Stage 3 are the bulk. Everything else is a day-scale item.

### Dev / test / deploy flow

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
Set-Location C:\projects\hearth-tv
$gradle = "C:\projects\fitness-app\tools\gradle-8.9\bin\gradle.bat"   # or .\gradlew.bat
& $gradle testDebugUnitTest          # JVM suite, seconds
& $gradle assembleRelease            # refuses if tests are red
.\install-s26.ps1                    # build + silent install on Alec's S26 through Hearth
.\publish.ps1 -Notes "..."           # tests -> signed APK -> GitHub release -> version.json -> push
```

Test builds → Alec's S26, zero taps: `assembleRelease` → `scp` to the Pi appshelf → Hearth `shell_exec` on the S26
downloads it over the tunnel and runs `pm install -r` (Shizuku), then `am start`. The Self-test on the phone is the
contract suite: run it from Diagnostics whenever the S26 is on the owner's Wi-Fi.

Publish (one script, `publish.ps1`): tests → `assembleRelease` → `gh release create vX.Y hearth-tv.apk` →
write `docs/version.json` (versionCode, versionName, APK URL, notes) → push; GitHub Pages redeploys in about a
minute. Nothing runs on the Pi or the gateway for the owner.

Owner-facing URLs (fixed for the life of the project):
- Page: `https://willits-alec.github.io/hearth-tv/`
- APK:  `https://github.com/Willits-Alec/hearth-tv/releases/latest/download/hearth-tv.apk`

First install on the owner's phone: open the page in Chrome → Download → allow "install unknown apps" for Chrome
once → Install → if Play Protect warns about an unknown developer, choose Install anyway. Later versions: tap the
in-app Update banner (or the page) → Install over the top; pairing and settings survive because the signing key is
the same. Fallback: Quick Share the APK from Alec's phone.

## 6. Out of scope (v1) — and what each would take

- **Out-of-home control.** Needs a device at the owner's house that the phone can reach: a WireGuard peer on a Pi
  (if the Pi at 10.10.10.5 is Alec's, that is the obvious host) or Sonos's cloud API for the audio half. v2 at
  the earliest, and a separate scope.
- **Casting media from the phone.** Cast SDK + a receiver app registration. v2.
- ~~**Voice.**~~ Shipped in 0.2.0, exactly as sketched: the phone's recogniser + a fixed grammar.
- **iOS.** This is an Android APK. If the owner carries an iPhone, none of this ships (§7, question 1).
- **Anything that changes TV settings** beyond sound output and power. No picture modes, no network settings.

## 7. Open questions — ALL ANSWERED by Alec on 2026-09-10

Owner's phone is Android · public repo OK · name **Hearth TV** · Roku tile in v1 · the Pi is Alec's (site peer
later) · DHCP reservations for the TV and the Arc done · volume buttons drive the Sonos (D4 confirmed).
The list below is kept as the record of what was asked.

1. **Owner's phone: Android, and which version?** minSdk 26 covers Android 8 and up. If it is an iPhone, stop here.
2. **App name and icon.**
3. ~~Pairing~~ **DONE 2026-09-10.** Paired from Alec's phone; registration in `local/SITE.md`. `getWolMode` reports
   enabled and the TV answered the API while in standby, so networked power-on is already possible.
4. **DHCP reservation** for the TV (10.10.10.85) and the Arc (10.10.10.208) on the Asus router. Ten minutes, avoids
   the whole re-discovery class of problems. Can you do it, or is the router the owner's?
5. **Roku tile in v1?** Cheap, but scope creep.
6. **Is the Raspberry Pi on that LAN yours?** It is the house's DNS server, which smells like one of your Pi-holes.
   If so it is the natural site peer, and out-of-home control becomes a real option later.
7. **GitHub**: one public repo, `hearth-tv`, under the **Willits-Alec** account (the one `gh` is logged into;
   `awillits-sketch` is the other). Fixtures are scrubbed and the keystore stays out of git. OK?
8. **D4 confirmation**: volume buttons drive the Sonos whenever the TV is on Audio system. Yes?

## 8. Delivery ledger — the scope checked against the build (2026-09-10)

Every promise in §4 and §5, where it lives, and its state. "Amended" means the intent is met another way and the
reason is recorded here; nothing was dropped silently.

| Promise (section) | Where | State |
|---|---|---|
| Power on/off with WoL + polling (4.1) | `RemoteController.powerToggle`, `WakeOnLan` | shipped 0.1.0, verified on the TV |
| State line (4.1) | `StatusCard`, `readTv` | shipped 0.1.0 |
| Volume/mute routed per D4 (4.1) | `volumeTarget`, `volumeStep`, `toggleMute` | shipped 0.1.0, Arc 25→26 verified |
| Inputs by real name, current highlighted (4.1) | `InputsRow` (CEC first, stale ports hidden) | shipped 0.1.0, hiding 0.2.0 |
| Apps: favourites row + full list (4.1) | `AppsRow` (13 favourites when installed, "All N") | shipped 0.1.0 |
| Buttons incl. Guide, numbers, Sony extras (4.1) | `DPad`, `MediaRow`, `MoreKeys` | D-pad/media 0.1.0; Guide, Input, Exit, Options, Ch ±, Subtitles, Audio, 0–9, Netflix, YouTube, colours **0.2.1**. No Prime key exists on this TV |
| Sound output toggle, Fix the sound (4.1) | `SoundCard`, `fixSound` | shipped 0.1.0 |
| Type on the TV (4.1) | `TypeCard`, `typeText` + `textInputActive` pre-check | shipped 0.1.0, pre-check 0.2.0 |
| Sonos row: volume, mute, night, speech, source (4.1, D5) | `SoundCard`, `SonosClient` | shipped 0.1.0 |
| Roku tile (4.1, D8) | `protocol/roku/RokuClient`, `RokuState`, `RokuCard`, wizard step 4 | shipped 0.3.0 (18 tests); a locked box explains the Network-access fix instead of failing |
| Voice (4.3 → v1) | `VoiceCommandParser`, mic button | shipped 0.2.0 |
| Setup wizard: SSDP + manual address → PIN → Sonos (4.2) | `SetupViewModel`, `SetupScreen` | shipped 0.1.0, verified at the owner's house |
| PSK as the advanced fallback (D2) | pairing step → "Use a pre-shared key" | **0.2.1** (client support since 0.1.0) |
| Re-find the TV if DHCP moves it (4.2) | `TvRelocator` (by MAC), `RemoteViewModel.maybeRelocate` | **0.2.1**; UUID replaced by the MAC already stored at setup |
| Self-test with Copy report (4.2) | `SelfTest`, Diagnostics | shipped 0.1.0; discoverable / wake-on-LAN / update checks **0.2.1** |
| Update check + banner (4.2) | `UpdateChecker`, `UpdateBanner` | shipped 0.1.0, banner seen on the S26 |
| Diagnostics: last 20 errors with timestamps, Copy (4.2) | `ErrorLog`, Diagnostics screen | **0.2.1** |
| Plain-language errors (4.2) | hints in `RemoteController`, `SetupViewModel.describe`, VPN note | shipped 0.1.1 |
| Wi-Fi binding of every socket (4.2) | `WifiLanTransport` (Network resolved per connection) | shipped 0.1.0, per-connection 0.2.0 |
| Standby → big power button, poll after power-on (4.2) | `RemoteScreen`, `powerToggle` | shipped 0.1.0 |
| Debounced volume, key repeat on hold, haptic tick (4.2) | `RemoteViewModel.volumeStep` (coalesced), `HoldButton`, `tick()` | **0.2.1** |
| No background work (4.2) | manifest: no service, no receiver | shipped 0.1.0 |
| Raw request passthrough (4.3) | — | **amended: dropped**; Self-test + error log + Copy report cover the need |
| Fixtures first, red → green (5) | `fixtures/`, `Fake*`, every test file | followed; `fixtures/README.md` records each capture |
| Robolectric Compose UI tests (5) | — | **amended**: the Compose layer only renders state and forwards taps; it is checked on the S26 with `ACCEPTANCE.md`, and all behaviour sits in JVM-tested layers |
| `-PtvIp` contract task (5 rule 4) | — | **amended**: no dev machine is ever on that LAN; the in-app Self-test is the contract suite and was run from the S26 |
| `assembleRelease` gated on the suite (5 rule 6) | `app/build.gradle.kts` | in force (109 tests green at this writing) |
| Public repo, Pages, Releases, version.json, keystore out of git (D7) | `publish.ps1`, `docs/`, `.gitignore` | in force since v0.0.1 |
| OWNER-GUIDE, proper icon (Stage 4, D9) | `OWNER-GUIDE.md`, `docs/index.html`, `ic_launcher_*` | shipped 0.4.0 |
| Acceptance walk, v1.0 (Stage 4) | `ACCEPTANCE.md` | **pending — needs Alec at the owner's house** |

## 9. Next scope — Alec's requests, 2026-09-10 (v0.4, after Stage 4)

Recorded the day he first used the remote, to be built **after** the current scope closes (Stage 4: acceptance
walk, owner guide, icon, v1.0). Each item says what it is, how it would be built, and what still needs deciding.

### 9.1 A swipe touchpad instead of the D-pad buttons

A toggle in the **top-right of the Navigate card** flips that card between today's arrow buttons and a thumb-sized
touchpad: flick right → Right, left → Left, up/down likewise, tap → OK, double tap → Back. One component, so the
Roku card gets the same toggle and sends ECP keys instead of IRCC keys. The choice is remembered in settings.

- A long drag emits repeated keys, one per threshold crossed (about 40 dp), so a slow drag scrolls a long list.
- Every emitted key ticks, exactly like the buttons do now.
- The translator (drag distance → list of keys, tap timing → OK / Back) is pure Kotlin, so it is TDD'd against
  synthetic gesture streams; only the thin Compose `pointerInput` wrapper is untested code.
- **D10 to decide:** a double tap for Back means OK cannot fire until the double-tap window (about 250 ms) has
  passed. Either OK gets that delay, or OK stays instant and Back keeps a button on the card. Recommendation:
  take the 250 ms delay — the network round trip is already about that, and the gesture Alec asked for is worth it.

### 9.2 Volume as a bar you drag, not only a rocker

Replace the ± rocker with a horizontal level bar (drag to set, absolute), keeping the ± buttons beside it for one
step at a time and the mute button as it is.

- Both clients already do absolute volume: Sonos `SetVolume`, and the TV's `audio.setAudioVolume` with a level.
- A drag fires far too many values, so it needs latest-wins throttling (about 5 per second) plus a guaranteed
  send on release; today's coalescing only handles relative steps, so that is new plumbing worth its own tests.
- The bar reads its range from the device: Sonos is 0–100, the TV reports its own min and max.

### 9.3 Why the TV shows no volume change (and what to do about it)

**Not a bug, and the number is real.** With the TV's output set to *Audio system*, this app talks to the Arc
directly over UPnP (D4), deliberately bypassing HDMI-CEC — so the TV never hears about the change and never draws
its on-screen bar. The number the app shows is the Arc's own answer: `SetRelativeVolume` replies with `NewVolume`
and the app displays that reply, so a moving number means the Arc confirmed the move. It was checked against the
real system on 2026-09-10 (Arc 25 → 26) and against the Sonos app.

What is genuinely missing is feedback **on the TV**, and there is a way to get it, from a real finding the same
day: the TV's own volume control drives the Arc over CEC in 2-unit steps and does draw the on-screen bar.

- **D11 to decide:** add a setting, *Show volume on the TV*, that routes volume through the TV instead of straight
  to the Arc. Cost: 2-unit granularity and CEC's flakiness. Default off. Recommendation: add it as a setting, not
  as the default, and put a "the Arc says 26" confirmation line under the volume bar for the direct path.
- Also worth doing in the same pass: subscribe to the Sonos UPnP event stream (GENA) so the bar follows changes
  made by the Sonos app or the Arc's own remote, instead of waiting for the 8-second refresh.

### 9.4 Tying the Sonos's voice control into the app

Asked: can the Arc's voice control drive this app, so everything is one system? Honest answer, in tiers.

- **Cannot be done on the LAN.** Sonos Voice Control and Alexa run on the speaker itself and are closed. There is
  no local API to hand them an utterance or to receive what they heard, so the Arc's microphone cannot become this
  app's microphone.
- **Already works, and can grow (free):** the app's own voice (0.2.0) drives the TV, the Roku and the Sonos from
  one grammar — volume, mute, night sound, speech enhancement, apps, inputs, keys. Adding phrases costs a test.
- **Voice search on the TV (small, recommended):** mic → text → open the TV's search box and type it, using the
  text entry that already ships. This is the "say what you want to watch" feature people actually mean.
- **D12 to decide — hands-free.** A wake word on the phone needs `RECORD_AUDIO` and a foreground service, which
  contradicts D1's "no background service". Only worth it if Alec wants the phone listening while it sits on the
  arm of the couch.
- **D13 to decide — the Alexa route.** "Alexa, turn on the TV" through the Arc would need a cloud endpoint and an
  account link: against D1 (LAN-only, no cloud, no account) and its own project if it ever happens.

---

*Sources for the Sony API surface: Sony BRAVIA Professional Displays REST API
(https://pro-bravia.sony.net/remote-display-control/rest-api/reference/) — the consumer sets expose the same
`/sony/*` services, as confirmed by today's probes.*
