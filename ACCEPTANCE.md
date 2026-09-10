# Acceptance — walked on the S26 at the owner's house before each release the owner receives

Tick every line. A line that fails blocks the release; fix, re-run the suite, re-install, re-walk.

## v1.0 (fill in as stages land)

- [ ] Fresh install from the public page: download, install, first launch under 60 s.
- [ ] Setup finds the TV and the Sonos without typing an address; PIN pairing completes with the code on screen.
- [ ] TV in standby: the screen shows one big power button; tapping it turns the TV on within 10 s.
- [ ] Power off from the app; the app shows standby within 5 s.
- [ ] Volume up/down/mute while the TV is on Audio system changes the **Sonos**; the screen says "Sonos".
- [ ] Switch output to TV speakers; volume buttons now change the TV; switch back.
- [ ] Inputs list shows "Roku Ultra" and "Sonos Arc" by CEC name, stale or unconnected HDMI ports hidden; tapping Roku Ultra switches the TV to the Roku (HDMI 2).
- [ ] Apps row launches Prime Video, YouTube, Netflix; the state line names the running app.
- [ ] D-pad, Back, Home and play/pause reach the TV; holding Right repeats and the phone ticks per step.
- [ ] Hold Volume up: the level climbs steadily and stops on release.
- [ ] More keys → Guide opens the TV guide; a number key and a colour key reach the TV.
- [ ] Type on the TV: text typed on the phone lands in a TV search box.
- [ ] Type with no TV text box open: the app says so in plain words (no raw Illegal State error).
- [ ] Voice: mic button, say "volume up", "open prime", "switch to roku", "pause"; each lands and the words shown match.
- [ ] Minimise the app for a minute, reopen: the remote is still drawn (Reconnecting banner at most), never the connect screen.
- [ ] Fix the sound: output → Audio system and the Arc → TV input, confirmed on the Sonos app.
- [ ] Setup step 4 finds the Roku (or takes its address); skipping it hides the Roku card entirely.
- [ ] Roku card: Switch to Roku puts it on screen, then Home, the pad, OK, Back, replay and play/pause reach the box.
- [ ] Roku channels: a chip launches it, and the card names what is showing.
- [ ] Self-test: Roku reachable and Roku control are green (a locked box names the Network-access fix instead).
- [ ] Self-test: all green, including TV discoverable, TV wake-on-LAN = enabled, Update check = up to date; Copy report produces a paste-able block.
- [ ] Diagnostics → Recent errors: a deliberately failed action (type with no text box open) appears with a time; Copy report includes it; Clear empties it.
- [ ] Setup → pairing step → "Use a pre-shared key" shows the key field and the TV-menu path (no need to complete it).
- [ ] Update banner: bump the version on the page → banner appears on next launch → Download opens the browser.
- [ ] Kill the app, reopen after 15 minutes: no re-pairing, state loads.
- [ ] Phone on mobile data (Wi-Fi off): the app explains it needs the home Wi-Fi, no crash.
