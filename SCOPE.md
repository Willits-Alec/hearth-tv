# Hearth TV — Scope

Standalone Android app that runs the **Family Room TV** (Sony KD-85X80CK) and its **Sonos Arc** home-theatre
group from the owner's phone, over his own Wi-Fi, with nothing else installed anywhere. A port of the TV tile in
Alec's Hearth app, re-built for a house that has no Hearth gateway, no WireGuard and no Alec on hand to fix things.

Status: **SCOPE DRAFT 2026-09-09 — awaiting Alec's decisions in §0 and answers in §7. Nothing built yet.**
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
| D8 | Not in v1 | Voice control, casting media from the phone, out-of-home control. All listed in §6 with what they would take. **Roku moved into v1** (Alec, 2026-09-10). |
| D9 | Name | **Hearth TV** (Alec, 2026-09-10). Package `com.alec.hearthtv`, repo `hearth-tv`, APK `hearth-tv.apk`. A placeholder icon ships in Stage 0; a proper one before v1.0. |

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
| Inputs | HDMI 1 (empty), HDMI 2 labelled **"Game"**, HDMI 3 eARC = **Sonos Arc**, HDMI 4 labelled **"Roku"**, Composite. |
| Roku | **Roku Ultra** (4660X), Roku OS 15.3.4, on HDMI 4; ECP on :8060. Currently in **Limited** network-access mode: device info and active app answer, app list / keypress / launch are refused. One-time fix on the Roku: Settings → System → Advanced system settings → Control by mobile apps → Network access → **Default**. The app detects Limited mode and shows exactly that instruction. Fixtures captured 2026-09-10. |
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
| Inputs | list + switch | list with the TV's own labels ("Game", "Roku"), highlight current, switch | `avContent.getCurrentExternalInputsStatus`, `setPlayContent extInput:hdmi?port=N` |
| Apps | favourites row + full list | same; favourites = Prime Video, YouTube, Disney+, Netflix, Apple TV, Max, Spotify when installed | `appControl.getApplicationList`, `setActiveApp` |
| Buttons | D-pad, Home, Back, Menu, media, numbers, colours | D-pad, Home, Back, Guide, media, numbers, plus Sony extras (Netflix, YouTube, Prime keys, Input, Display) | IRCC over `/sony/ircc`, codes read live from `getRemoteControllerInfo`, never hard-coded |
| Sound output | TV speaker / optical / HDMI | TV speakers ↔ Audio system | `audio.setSoundSettings outputTerminal` |
| **Fix the sound** | — (new) | one tap: output → Audio system, Arc → TV input, then re-read state | `setSoundSettings`, Sonos `AVTransport` `x-sonos-htastream` |
| Type on the TV | — (new) | phone keyboard → the TV's active text field (search boxes, passwords) | `appControl.setTextForm` |
| Sonos row | — (new) | group volume, mute, night sound, speech enhancement, source | Sonos UPnP |
| **Roku tile** | — (new) | the Roku Ultra on HDMI 4: Home, Back, D-pad, OK, play/pause, replay, its own app list and launch; one tap also switches the TV to HDMI 4 first | Roku ECP on `:8060`: `keypress/<Key>`, `query/apps`, `launch/<id>`, `query/device-info`, `query/active-app` |

### 4.2 v1 — robustness, because nobody can fix it on site

- **Setup wizard** (first launch, ~1 minute): find the TV by SSDP (`ScalarWebAPI`) with manual IP fallback →
  pair (PIN on the TV, or type the PSK) → find the Sonos home-theatre room by SSDP → done. Stores TV IP, MAC,
  and the TV's UUID so it can **re-find the TV if DHCP moves it**.
- **Self-test** (one tap on the Diagnostics screen): runs the read-only contract checks on the phone it is installed on —
  find TV, paired?, power state, inputs, volume, sound output, IRCC table, find Arc, group state, update check —
  and renders a pass/fail list with a **Copy report** button. This is the field version of the contract suite in §5.
- **Update check**: on launch and on demand, fetch `version.json` from the public page; if newer, show a banner
  with a Download button that opens the browser at the fixed APK link. No auto-install, no background polling.
- **Diagnostics screen**: TV reachable / paired / power state / WoL mode; Arc reachable / group state; last 20
  errors with timestamps; a **Copy diagnostics** button producing a text block the owner can paste to Alec.
- **Plain-language errors** with the next step ("Can't see the TV. Is your phone on the home Wi-Fi?").
- **Wi-Fi binding**: every socket (HTTP, UDP for WoL and SSDP) is bound to the Wi-Fi `Network` object, so a VPN
  on the phone cannot swallow LAN traffic. Alec's own phone runs WireGuard full-tunnel, and this is exactly what
  broke the first probes today — the fix is designed in, not discovered later.
- **Standby handling**: when the TV reports standby, the screen collapses to a big power button; after power-on
  it polls state for ~15 s so the tile fills in without a manual refresh.
- **Debounced volume**, IRCC key-repeat on hold, haptic tick per press, big touch targets, works one-handed.
- **No background work**: the app does nothing when closed. Nothing to drain a battery or leak.

### 4.3 Dropped from the LG tile, and why

| LG feature | Verdict | Reason |
|---|---|---|
| Toast notification on the TV | dropped | no Sony equivalent |
| Open URL / dashboard page on the TV | dropped | this Bravia has no browser |
| Cast local media | v2 | needs the Google Cast SDK and a receiver; the owner can cast from any app already |
| Screen share | dropped | Google Home / Smart View already do it; the app would only open a menu |
| Voice → TV action | v2 | Hearth's version runs a local LLM on the gateway; here it would be on-device speech + a small grammar |
| Raw request passthrough | dev-only | kept in the diagnostics screen behind a long-press, not a user feature |

## 5. Platform, test strategy, delivery

### Tech stack (decided, D3)

Mirror `C:\projects\fitness-app` (Reach): `build.gradle.kts`, Kotlin 2.0.21, Compose BOM 2024.09.02, AGP 8.5.2,
Gradle 8.9 under `tools/`, JDK 21 at `C:\Program Files\Java\jdk-21`, Android SDK at the standard path, release
signing from a NEW keystore kept **outside git** (`keystore/` is gitignored, passwords read from `local.properties`,
a backup copy lives with Alec's other keys). Dependencies added over Reach: OkHttp 4.12,
kotlinx-serialization-json, DataStore Preferences. Test dependencies: JUnit 4, kotlinx-coroutines-test, Turbine,
OkHttp MockWebServer, Robolectric for Compose UI tests.

### Layers (each testable on its own)

```
ui/            Compose screens: Remote, Setup wizard, Diagnostics          ← Compose tests (Robolectric)
viewmodel/     RemoteViewModel: state machine, volume routing (D4), polling ← JVM tests with FakeBravia + FakeSonos
protocol/      BraviaClient (JSON-RPC, IRCC, PIN/PSK auth, WoL), SonosClient (UPnP SOAP subset), RokuClient (ECP), Discovery (SSDP)
               ← JVM tests against MockWebServer + captured fixtures; UDP tested with loopback sockets
net/           WifiBoundHttp: OkHttp + sockets bound to the Wi-Fi Network   ← unit tests with a fake ConnectivityManager
```

### TDD rules for this project

1. **Fixtures first.** `src/test/resources/fixtures/bravia/*.json` are the literal responses this TV gave on
   2026-09-09 (power, interface, inputs, volume, sound settings, IRCC table, supported API, the 403 pairing error,
   Cast `eureka_info`), plus `sonos/device_description.xml` from the Arc. Every parser test reads a fixture; no
   hand-typed JSON in tests.
2. **Red → green per feature.** A feature starts as a failing client test, then a failing ViewModel test, then the
   UI test. No production code without a failing test that needs it.
3. **FakeBraviaServer** is a stateful MockWebServer dispatcher: it holds power / volume / mute / input / active app /
   paired-or-not and answers like the real TV (including 403 until paired, and "display is off" errors in standby).
   FakeSonos does the same for volume, mute, night mode, source. Behaviour tests run against these, in milliseconds.
4. **Contract tests** (`src/contract/`) run the same client calls against the **real TV** when `-PtvIp=10.10.10.85`
   is passed; skipped otherwise. Read-only by default; the write set (power, volume, input) runs only with
   `-PtvWrite=true` and Alec on site. This is how we learn what the fake got wrong, once, before the owner does.
   The same check list is compiled into the app as the **Self-test** (§4.2), so the contract suite also runs on the
   only device that matters, by the owner, in one tap.
5. **Definition of done per stage**: unit + ViewModel + UI tests green; contract tests green against the real TV
   from the S26; the acceptance checklist in `ACCEPTANCE.md` walked on the S26 at the owner's house.
6. **Gate**: `assembleRelease` depends on `test`. A red suite cannot produce an APK.

### Stages

| Stage | Contents | Size |
|---|---|---|
| 0 | **DONE 2026-09-10.** Repo skeleton from the Reach template, new keystore (outside git), 30 scrubbed fixtures, FakeBraviaServer + FakeSonos + FakeRoku with 20 green JVM tests, `assembleRelease` gated on the suite, public repo `Willits-Alec/hearth-tv` + Pages page + `publish.ps1` + `install-s26.ps1`; v0.0.1 published and installed on the S26 | S |
| 1 | **Code DONE 2026-09-10** (47 tests green, written red first): `BraviaClient` — PIN + PSK auth, power, state, volume/mute/step, sound output, inputs, apps, now-playing, IRCC with the TV's own code table, text entry; `WakeOnLan`; `SsdpDiscovery`; `LanTransport` (Wi-Fi-bound). Real-TV contract run: pairing, IRCC, reads and a volume nudge verified through Alec's phone; the full `-PtvIp` runner is still to be wired | M |
| 2 | `SonosClient`: discovery, group coordinator, volume/mute, night sound, speech enhancement, switch-to-TV, source; `RokuClient`: ECP keypress, launch, app list, device info, active app. JVM + contract | S |
| 3 | `RemoteViewModel` (state machine, D4 routing, standby polling, error surfacing) + Compose UI: Remote screen, Setup wizard, Diagnostics with Self-test, Copy report and Update check | L |
| 4 | Acceptance pass on the S26 at the owner's house (`ACCEPTANCE.md`, contract write-tests), then v1.0 published and installed on the owner's phone from the page. `OWNER-GUIDE.md` (one page with pictures), `DEPLOY.md`. Fixes ship as releases he pulls with the Update banner. | S |

Honest estimate: Stage 1 and Stage 3 are the bulk. Everything else is a day-scale item.

### Dev / test / deploy flow

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
Set-Location C:\projects\hearth-tv
.\tools\gradle-8.9\bin\gradle.bat test                       # JVM suite, seconds
.\tools\gradle-8.9\bin\gradle.bat contract -PtvIp=10.10.10.85   # only when a phone/PC is on the owner's LAN
.\tools\gradle-8.9\bin\gradle.bat assembleRelease            # refuses if tests are red
```

Test builds → Alec's S26, zero taps: `assembleRelease` → `scp` to the Pi appshelf → Hearth `shell_exec` on the S26
downloads it over the tunnel and runs `pm install -r` (Shizuku), then `am start`. The contract suite (`-PtvIp`)
runs against the real TV whenever the S26 is on the owner's Wi-Fi, through the same Hearth path.

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
- **Voice.** On-device `SpeechRecognizer` + a fixed grammar ("volume up", "open Prime", "switch to Roku"). v2.
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

---

*Sources for the Sony API surface: Sony BRAVIA Professional Displays REST API
(https://pro-bravia.sony.net/remote-display-control/rest-api/reference/) — the consumer sets expose the same
`/sony/*` services, as confirmed by today's probes.*
