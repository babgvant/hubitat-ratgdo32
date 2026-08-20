# Hubitat ratgdo32 ESPHome MQTT driver

This project provides a self-contained Hubitat Elevation driver for a current ratgdo32 running the maintained ESPHome firmware in dry-contact + encoder mode. Communication is local through an MQTT broker. No Home Assistant, Node-RED, HomeKit, or cloud service is involved.

## Research result and architecture

The important upstream distinction is hardware/firmware generation:

- [`mqtt-ratgdo`](https://github.com/ratgdo/mqtt-ratgdo) v2.59 (last release October 2024) implements `ratgdo/<device>/status/...` and `command/...`, but its build targets the older ratgdo/ESP8266 board line. It is not the maintained firmware for ratgdo32.
- Current ratgdo32 support lives in [`esphome-ratgdo`](https://github.com/ratgdo/esphome-ratgdo). Its current board matrix includes v3.2 dry-contact builds, and [`base_drycontact_enc.yaml`](https://github.com/ratgdo/esphome-ratgdo/blob/main/base_drycontact_enc.yaml) exposes Door, Obstruction, Encoder, Encoder Reverse, and Encoder Calibration Reset entities.
- The stock ratgdo32 image enables ESPHome's native API, not MQTT. MQTT is supported by ESPHome, but requires compiling a small overlay such as [ratgdo32-mqtt-overlay.yaml](./ratgdo32-mqtt-overlay.yaml). This is the unavoidable architectural limitation for a direct MQTT design on current hardware.
- A standalone Hubitat driver is appropriate: Hubitat's driver MQTT interface owns a broker connection and provides `connect`, `subscribe`, `publish`, `parseMessage`, `isConnected`, and status callbacks. An app/child-driver design helps only when sharing one connection among multiple doors; it adds no value for one controller.
- Hubitat's newer MQTT Import Integration is beta and maps generic entities. The custom driver remains useful because it applies conservative physical-command policy and preserves a stationary partial door as `door=unknown`, `motion=stopped`, and a numeric position.

The encoder implementation persists its last count and calibrated endpoints, derives a 0.0–1.0 cover position, declares travel stopped after a pulse watchdog, and detects a position contradiction after power loss by clearing calibration. Calibration endpoints are learned at the fully closed/open boundaries and retained in ESP32 preferences. Current source exposes raw encoder steps as a diagnostic sensor and normalized position through the Door cover.

The ratgdo cover advertises position, stop, and toggle support. The driver sends only explicit `OPEN` and `CLOSE`; it never sends toggle. In dry-contact mode, ratgdo uses its sensed position/direction and opener relay behavior to synthesize those operations. This still depends on valid encoder calibration (or both limit switches in non-encoder mode).

Light control is intentionally absent: the current dry-contact base does not expose a light entity. Obstruction is exposed by ratgdo, but whether it produces useful data on a Genie installation depends on wiring/electrical compatibility and must be tested on the actual opener.

## MQTT contract

With `topic_prefix: ratgdo32-garage` and the upstream entity names:

| Topic | Payload | Direction | Purpose |
|---|---|---:|---|
| `ratgdo32-garage/status` | `online`, `offline` | ratgdo → Hubitat | Retained birth/LWT/shutdown availability |
| `ratgdo32-garage/cover/door/state` | `open`, `closed`, `opening`, `closing`, `unknown` | ratgdo → Hubitat | ESPHome cover operation/state |
| `ratgdo32-garage/cover/door/position/state` | integer `0`–`100` | ratgdo → Hubitat | Normalized position; 0 closed, 100 open |
| `ratgdo32-garage/cover/door/command` | `OPEN`, `CLOSE` | Hubitat → ratgdo | Explicit endpoint command |
| `ratgdo32-garage/binary_sensor/obstruction/state` | `ON`, `OFF` | ratgdo → Hubitat | Obstruction problem sensor |
| `ratgdo32-garage/sensor/encoder/state` | signed step count | ratgdo → Hubitat | Raw diagnostic encoder count |

ESPHome entity object IDs are derived from entity names. Confirm the final topics in the ESPHome boot log or an MQTT explorer after flashing; all three object IDs are preferences in the driver.

### Difference from the 2023–2024 community driver

The [existing community driver](https://github.com/edasque/hubitat/blob/main/devicehandlers/ratgdoMQTT/ratgdoMQTT.groovy) targets legacy `mqtt-ratgdo` topics (`ratgdo/<device>/status/door`, `/status/light`, `/status/lock`, `/status/availability`, `/status/obstruction`) and publishes commands under `/command/...`. It also issues a legacy `query`, advertises light and lock capabilities, and reconnects in a blocking loop. Those assumptions do not match current ratgdo32 ESPHome firmware.

This driver instead uses standard ESPHome entity topics, consumes a separate position topic, omits unavailable dry-contact light/lock features, reconnects with scheduled exponential backoff, and never retries or queues a door-motion publish. ESPHome's MQTT cover source reports every idle intermediate position as `open`; this driver corrects that semantic loss by combining it with position and publishing Hubitat `door=unknown`, `motion=stopped` for a partial stationary door.

## Installation and configuration

1. Build and flash current ratgdo32 dry-contact encoder firmware using [ratgdo32-mqtt-overlay.yaml](./ratgdo32-mqtt-overlay.yaml). Select another current upstream board package if the controller is not v3.2.
2. Put the broker on the same trusted LAN/VLAN, require authentication, and do not expose port 1883 to the internet. Give ratgdo and Hubitat a broker account restricted to this topic tree when practical.
3. Confirm retained `status`, cover state/position, obstruction, and encoder messages with an MQTT client.
4. Install the driver with Hubitat Package Manager using this manifest URL:

   `https://raw.githubusercontent.com/babgvant/hubitat-ratgdo32/main/packageManifest.json`

   Alternatively, open **Drivers Code**, choose **New Driver**, paste [ratgdo32-esphome-mqtt.groovy](./ratgdo32-esphome-mqtt.groovy), and save.
5. Add a virtual device using **ratgdo32 ESPHome MQTT Garage Door**. Enter broker settings and the exact `topic_prefix`. Defaults assume entity names Door, Obstruction, and Encoder.
6. Save Preferences, then run Initialize. Verify `controllerStatus=online`, position, and door state before testing commands while physically observing the door.

Recommended firmware settings are `discovery: false` (avoids unused Home Assistant discovery traffic), retained availability and state, a fixed node/topic prefix, and a fixed DHCP lease. TLS is supported by the Hubitat preference but requires a broker certificate trusted by the hub; plain MQTT on a tightly controlled local network is simpler.

## Safety and reliability behavior

- Open/close is rejected while offline, unknown, or already moving.
- An endpoint command is suppressed when already at that endpoint.
- A command publish is performed once. Failure reconnects MQTT but never replays the physical command.
- Reconnection only resubscribes; it cannot replay a stale command.
- Final state always comes from ratgdo feedback. No optimistic final or motion event is emitted.
- Retained messages rebuild state after either device reboots. Malformed/unknown messages are logged and ignored or mapped to unknown.
- `ContactSensor` is closed only at fully closed; every other state is open.

## Test plan

Perform physical-motion tests with the door in view and the normal safety sensors working.

1. **Initial connection:** start broker and ratgdo, initialize the driver, and verify online, lastSeen, closed/open endpoint, position, obstruction clear, and raw encoder count.
2. **Open from closed:** invoke Open once; verify actual `opening`, changing position, then `open`/100. Invoke Open again and verify no MQTT command.
3. **Close from open:** invoke Close once; verify `closing`, changing position, then `closed`/0. Invoke Close again and verify no MQTT command.
4. **Partial opening / stop:** stop with the physical control partway. Verify position 1–99, `motion=stopped`, `door=unknown`, and `contact=open`. Then test explicit Open and Close separately.
5. **Obstruction:** interrupt the beam and confirm `detected`; clear it and confirm `clear`. Attempt the opener's normal close and confirm its native reversal/safety behavior.
6. **Manual operation:** use wall button/remotes through complete and partial cycles and verify identical feedback without Hubitat commands.
7. **Broker restart:** restart broker while idle. Verify offline then scheduled reconnect/online and state reconstruction. Confirm no door command appears.
8. **ratgdo reboot:** reboot it while idle. Verify LWT/shutdown offline, birth online, restored position/calibration, and no movement.
9. **Hubitat reboot:** reboot hub, verify reconnect and retained state reconstruction, and verify no command publication.
10. **Encoder:** calibrate by reaching both endpoints, verify monotonic 0–100 travel and direction, reverse the option if necessary, manually move while unpowered if mechanically possible, then verify calibration invalidation/relearning behavior.
11. **Wi-Fi loss/recovery:** disconnect ratgdo Wi-Fi, wait for offline/stale detection, restore it, and verify state reconstruction without command replay.
12. **Bad input:** publish malformed position, obstruction, and state payloads and confirm warnings without a false endpoint event.

## Known limitations

- Current stock ratgdo32 firmware does not enable MQTT; a locally compiled ESPHome overlay is required.
- Hubitat `GarageDoorControl.door` has no `stopped`/partial value. The driver uses standard `unknown` plus custom `motion=stopped` and `position`.
- ESPHome reports an idle partial position as cover state `open`; correct classification requires the retained position message. Before position arrives, the driver remains conservative (`unknown`).
- Hubitat's MQTT client is one connection per device. Multiple garage doors create multiple broker sessions; an app/child architecture may be preferable at larger scale.
- MQTT QoS 0 does not acknowledge application handling. The driver deliberately does not retry physical movement commands.
- Encoder calibration status is not exposed as a dedicated upstream entity. Loss is inferred from unknown/invalid cover state and must be confirmed in device logs or by endpoint calibration.
- Obstruction feedback on the Genie D7155L dry-contact installation was not verified on physical hardware. Light control is unavailable in this upstream dry-contact profile.

## Could not be verified

- Real-device topic strings for a compiled v3.2 image were derived from current ESPHome topic-generation rules and current ratgdo entity names, but were not captured from the user's controller. Confirm them from its boot log/MQTT broker.
- Physical behavior of the Genie Signature Series D7155L, obstruction wiring, relay pulse response, and encoder direction/calibration could not be bench-tested here.
- Hubitat does not publish a machine-readable capability schema/version. Capability names and values were checked against its current Driver Capability List; final compilation and runtime MQTT behavior must be verified on an actual hub.

## Primary references

- [Current esphome-ratgdo repository](https://github.com/ratgdo/esphome-ratgdo)
- [Current ratgdo cover implementation](https://github.com/ratgdo/esphome-ratgdo/blob/main/components/ratgdo/cover/ratgdo_cover.cpp)
- [ESPHome MQTT cover implementation](https://api-docs.esphome.io/mqtt__cover_8cpp_source)
- [ESPHome MQTT availability behavior](https://esphome.io/components/mqtt/)
- [Hubitat MQTT Interface](https://docs2.hubitat.com/en/developer/interfaces/mqtt-interface)
- [Hubitat Driver Capability List](https://docs2.hubitat.com/en/developer/driver/capability-list)
- [Community ratgdo MQTT thread](https://community.hubitat.com/t/beta-ratgdo-driver-w-mqtt-firmware/129078)
- [Legacy mqtt-ratgdo repository](https://github.com/ratgdo/mqtt-ratgdo)
