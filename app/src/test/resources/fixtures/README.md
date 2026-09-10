# Fixtures

Literal replies from the real devices, captured 2026-09-09/10 through Hearth `shell_exec` on the S26
(`curl --interface wlan0`). Owner identifiers are scrubbed (IPs → 192.0.2.x, MACs → 02:00:00:00:00:xx,
serials / ids / cookie → zeros, names → "Test Room …", Sonos RINCON ids → RINCON_000000000AAn01400); the real
values live in gitignored `local/SITE.md`. **Never hand-edit a fixture** — re-capture it with the scratch probes.

## bravia/ — Sony KD-85X80CK, Android TV 12, REST interface 5.7.0

| file | note |
|---|---|
| getInterfaceInformation, getPowerStatus, getSystemSupportedFunction, getVolumeInformation, getSoundSettings (target=outputTerminal, v1.1), getCurrentExternalInputsStatus (v1.1), getRemoteControllerInfo (152 IRCC codes), getSupportedApiInfo (all services) | answer WITHOUT pairing |
| error403 | reply to a gated method before pairing: `{"auth_url":..., "error":[403,"Forbidden"]}` (HTTP 403) |
| accessControl_getMethodTypes | the `actRegister` signature — field is `nickname`, not `nick` |
| getSystemInformation, getWolMode, getApplicationList (37 apps with launch URIs), getNetworkSettings | answer WITH the pairing cookie |
| getVolumeInformation_standby | `[40005, "Display Is Turned off"]` — the volume query while the TV is in standby (the on-state `getVolumeInformation` is reconstructed from the probe log: speaker 18, unmuted, 0..100) |
| getCurrentExternalInputsStatus_onRoku, getPlayingContentInfo_roku | the same two calls with the TV ON and the Roku in front: CEC devices appear as `extInput:cec?...` entries titled "Roku Ultra" / "Sonos Arc"; now-playing has an empty `source` and the device name as `title` |
| getSchemeList, getSourceList_extInput, getContentList_cec | the TV's content tree: schemes `tv`/`extInput`, sources hdmi/composite/cec, and the CEC device list |
| getPlayingContentInfo | `[7, "Illegal State"]` — what the TV says on the home screen / inside an app; "no content", not an error |
| ircc_Display.xml | HTTP 200 SOAP reply to `X_SendIRCC` (Display key) with the cookie |
| eureka_info | Cast endpoint `http://<tv>:8008/setup/eureka_info`, unauthenticated |

## sonos/ — Sonos Arc (S19, sw 94.1), coordinator of the "TV Room" home-theatre group

| file | note |
|---|---|
| sonos_device_description.xml | `GET :1400/xml/device_description.xml` |
| sonos_GetVolume, sonos_GetMute, sonos_GetEQ_NightMode, sonos_GetEQ_DialogLevel | RenderingControl on the coordinator (volume 17, unmuted, night off, speech on) |
| sonos_GetTransportInfo, sonos_GetMediaInfo, sonos_GetPositionInfo | AVTransport; MediaInfo shows the TV input `x-sonos-htastream:<uuid>:spdif` |
| sonos_GetZoneGroupState, sonos_GetZoneGroupAttributes | ZoneGroupTopology; the Arc is `Coordinator`, Sub + two Era 300 are `Satellite` (invisible) |

## roku/ — Roku Ultra 4660X, Roku OS 15.3.4, ECP on :8060

| file | note |
|---|---|
| roku_device_info.xml | `GET /query/device-info` |
| roku_active_app.xml | `GET /query/active-app` |
| roku_root.xml | `GET /` (UPnP device description, with the HTTP headers as captured) |
| roku_error_limited_mode.txt | what `query/apps`, `keypress` and `launch` return while the Roku's network access is **Limited** (how the box was found) |
| roku_apps.xml | `GET /query/apps` after the owner switched network access to Default (2026-09-10) |

