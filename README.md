# Hubitat ratgdo32 direct HTTP driver

This package connects Hubitat Elevation directly to a ratgdo32 over the local network. It requires no MQTT broker, Home Assistant, Node-RED, Homebridge, or cloud service.

## Architecture

```text
Hubitat ── local HTTP + SSE ──> ratgdo32 ── dry contact ──> Genie D7155L
```

The ratgdo32 must run the current [`ratgdo/homekit-ratgdo32`](https://github.com/ratgdo/homekit-ratgdo32) firmware. Despite its name, Apple Home and HomeKit pairing are not required. The firmware also provides a local HTTP interface:

- `GET /status.json` returns authoritative state and diagnostics.
- `GET /rest/events/subscribe` allocates a server-sent event stream.
- The returned SSE stream provides immediate changes and heartbeats.
- `POST /setgdo?garageDoorState=1` requests open.
- `POST /setgdo?garageDoorState=0` requests close.

These paths and payloads were verified against current firmware source at v3.5.2, not inferred from the older MQTT firmware.

## Current firmware behavior

The firmware supports ratgdo32 hardware, dry-contact openers, the optional rotary encoder, obstruction reporting, and locally generated open/close state. In encoder mode it reports `Open`, `Closed`, `Opening`, `Closing`, `Stopped`, or `Unknown` through HTTP.

`status.json` exposes raw encoder steps but not normalized position or the firmware's learned endpoints. The driver learns raw closed/open endpoints whenever authoritative endpoint states are observed, persists them in Hubitat state, and derives 0–100%. While moving, it polls `status.json` at a configurable low rate because live SSE messages do not include `encSteps`.

## Hubitat model

Standard capabilities:

- `GarageDoorControl`
- `ContactSensor`
- `Refresh`
- `Configuration`
- `Initialize`
- `HealthCheck`

Custom attributes include position, movement, obstruction, controller/stream status, last seen, raw encoder position, encoder calibration status, firmware version, Wi-Fi signal, and authentication status.

A stationary partial door is represented as:

```text
door: unknown
motion: stopped
contact: open
position: 1–99
```

This avoids falsely reporting a partially open door as fully open or closed while remaining inside Hubitat's standard `GarageDoorControl` values.

## Installation

### 1. Flash and configure ratgdo32

1. Flash current HomeKit-ratgdo32 firmware using the project's [browser installer](https://ratgdo.github.io/homekit-ratgdo32/).
2. Select the dry-contact protocol.
3. Enable the rotary encoder if installed and reverse its direction if necessary.
4. Assign the controller a DHCP reservation.
5. Leave **Require Password** disabled. The firmware uses HTTP Digest Authentication for protected commands; Hubitat's event-stream and asynchronous HTTP interfaces do not provide a shared digest-auth session. Isolate the device on a trusted LAN or IoT VLAN instead.
6. Confirm `http://<ratgdo-ip>/status.json` returns JSON from the same network.

HomeKit pairing is optional and unrelated to Hubitat.

### 2. Install through Hubitat Package Manager

Use this manifest URL:

```text
https://raw.githubusercontent.com/babgvant/hubitat-ratgdo32/main/packageManifest.json
```

For manual installation, paste [ratgdo32-http.groovy](./ratgdo32-http.groovy) into **Drivers Code**.

### 3. Create the device

1. Create a Hubitat virtual device using **ratgdo32 Direct HTTP Garage Door**.
2. Enter the reserved ratgdo32 address and port 80.
3. Save Preferences and run Initialize.
4. Confirm `controllerStatus=online`, `streamStatus=connected`, and a valid door state before sending a command.

## Command safety

- Commands are rejected when the controller or state is unknown, while moving, or when firmware authentication is enabled.
- Commands already satisfied at an endpoint are suppressed.
- Only explicit open and close requests are sent; the driver never sends toggle.
- Each user request creates exactly one HTTP request.
- Failed requests are never automatically retried.
- Network/SSE reconnection never queues or replays a physical command.
- HTTP acceptance never creates an optimistic final state; state comes from ratgdo32 feedback.

## Encoder calibration

The firmware calibrates its encoder after complete travel to both endpoints. The driver separately learns the raw step reported at each authoritative endpoint so it can calculate percentage.

After installation or firmware encoder reset:

1. Move fully closed and allow the door to stop.
2. Move fully open and allow it to stop.
3. Verify `encoderStatus=calibrated` and position changes in the correct direction.

If the encoder direction changes, repeat both endpoint cycles. Newly observed endpoint values replace the prior values.

## Test plan

Perform motion tests while physically observing the door.

1. **Initial connection:** Initialize and verify HTTP status, connected SSE, endpoint state, obstruction, firmware, and Wi-Fi values.
2. **Open from closed:** Send Open once; verify `opening`, changing percentage, then `open`/100. A second Open must send nothing.
3. **Close from open:** Send Close once; verify `closing`, changing percentage, then `closed`/0. A second Close must send nothing.
4. **Partial stop:** Stop with the wall control. Verify `unknown`/`stopped`, open contact, and position 1–99.
5. **Resume from partial:** Test explicit Open and Close separately and verify direction.
6. **Obstruction:** Trigger and clear the beam and verify detected/clear. Confirm native close reversal.
7. **Manual operation:** Use wall controls/remotes and verify immediate SSE feedback.
8. **ratgdo reboot:** Verify disconnect/reconnect, refresh, and no door command.
9. **Hubitat reboot:** Verify reconstruction without a command.
10. **Wi-Fi interruption:** Verify offline/reconnect without command replay.
11. **Encoder:** Exercise both endpoints, partial stops, reversal, manual movement, and firmware calibration reset.
12. **Authentication:** Enable Require Password temporarily and confirm commands are rejected clearly; disable it and reconnect.
13. **Malformed response:** Confirm bad JSON/SSE logs a warning without false endpoint state.

## Known limitations

- Firmware HTTP Digest Authentication is not supported. Use network isolation and leave Require Password disabled.
- Percentage is derived in Hubitat because current firmware exposes raw steps but not encoder endpoints or normalized position through HTTP.
- SSE omits raw encoder steps, so `status.json` is polled while moving. Door and obstruction state remain event-driven.
- The firmware HTTP API is not separately versioned as a formal public API. Regression-test firmware updates.
- Dry-contact mode does not provide Genie opener light control. The driver does not expose a misleading Switch capability.
- Obstruction behavior has not been tested on this physical D7155L installation.

## Verified upstream fields

| HTTP JSON field | Type | Driver use |
|---|---|---|
| `garageDoorState` | string | Door and motion state |
| `garageObstructed` | boolean | Obstruction state |
| `encSteps` | integer | Raw and derived position |
| `encoderEnabled` | boolean | Encoder status |
| `encoderReversed` | boolean | Diagnostic context |
| `firmwareVersion` | string | Firmware diagnostic |
| `wifiRSSI` | string | Wi-Fi diagnostic |
| `passwordRequired` | boolean | Prevent unsupported protected commands |
| `upTime` | integer | SSE heartbeat/freshness |

## References

- [Current HomeKit-ratgdo32 firmware](https://github.com/ratgdo/homekit-ratgdo32)
- [Firmware web API source](https://github.com/ratgdo/homekit-ratgdo32/blob/main/src/web.cpp)
- [Firmware encoder source](https://github.com/ratgdo/homekit-ratgdo32/blob/main/src/encoder.cpp)
- [Prior-art Hubitat HTTP driver](https://github.com/mitchjs/Hubitat/blob/main/Drivers/MJS-Gadgets-Http-RatGDO.groovy)
- [Hubitat direct-integration discussion](https://community.hubitat.com/t/ratgdo-direct-integration/162207)
