# DSP408Controller

Build with `mvn verify` from the DSP408 workspace root, or install
`../../dsp408-protocol` locally before building this parent alone. The runtime
feature includes the shared protocol bundle required by `dsp408-core`.

DSP408Controller is a Java 21 multi-module project for controlling and inspecting **t.racks / Thomann DSP 408** and **FIR408** devices over TCP.

The project is built around an **Apache Karaf / OSGi** runtime and currently includes:

* a shared public service API
* a core DSP protocol and state layer
* a runtime component that exposes the controller as an OSGi service
* a Karaf shell command bundle
* a Matrix bot integration
* an HTTP/JSON API
* a Karaf features descriptor for installation

The current codebase is focused on **practical control + reverse engineering support**:

* connect / disconnect / reconnect to the DSP
* send handshake and rate-limited, acknowledged keepalive frames
* request device/system information
* send a 4-digit login PIN
* auto-detect DSP408 vs FIR408 libraries after handshake
* address multiple DSPs by configured id
* set gain for all inputs and outputs
* mute / unmute all inputs and outputs
* read parameter blocks `00..1C` on DSP408 and `00..1F` on FIR408
* cache raw block contents
* decode known gain, mute, matrix, crossover and related values from selected blocks
* control FIR408 mode/generator/external FIR payloads
* expose the same functionality via shell, Matrix and HTTP

---

## Connection stability

Automatic startup runs outside the OSGi activation callback. `isConnected()`
means handshake and startup completed; the settings cache separately reports
`SYNCHRONIZING`, `READY`, `DEGRADED`, `STALE` or `DISCONNECTED`. Status reads
remain available during bootstrap.

Commands use a single response-correlated queue. A missing generic ACK closes
the session without repeating the write, so a delayed ACK cannot confirm a
later command. The original write may already have reached the DSP; inspect
fresh device state before deciding to repeat it.

See [connection lifecycle and validation](docs/CONNECTION-LIFECYCLE.md) for
cancellation, polling, timeout and coherent-bundle deployment details.

## Status

This README documents the code that is currently present in these modules:

* `dsp408-api`
* `dsp408-core`
* `dsp408-runtime`
* `dsp408-shell`
* `dsp408-matrix`
* `dsp408-http`
* `dsp408-features`

It intentionally does **not** describe DSP editing features that are not implemented yet.

### Protocol Reference Standard

The canonical reverse-engineering and field-library reference for this project is:

* `docs/protocol-spec/DspLib-408.json`
* `docs/protocol-spec/DspLib-408-fir.json`

Runtime copies are bundled in `dsp408-core/src/main/resources/dsp/`. The Java loader selects the normal or FIR library according to the detected device version.

---

## Requirements

* **Java 21**
* **Maven**
* **Apache Karaf 4.4.10**
* network access to the DSP

Default DSP target from the runtime config:

* IP: `192.168.0.166`
* Port: `9761`

Default HTTP endpoint in Karaf:

* `http://localhost:8181/api/v1`

---

## Project Layout

```text
dsp408-parent
├── dsp408-api
├── dsp408-core
├── dsp408-features
├── dsp408-http
├── dsp408-matrix
├── dsp408-runtime
└── dsp408-shell
```

### Module Overview

#### `dsp408-api`

Public DTOs and the OSGi service contract.

Main types:

* `DspService`
* `StateDto`
* `ChannelDto`
* `BlockDto`
* `DeviceInfoDto`
* `RawTxDto`

#### `dsp408-core`

Core DSP logic without the OSGi runtime wrapper.

Contains:

* TCP client and framing
* protocol builders/parsers
* block decoders
* in-memory state tracking
* shared command processor used by shell and Matrix

Important classes:

* `DspClient`
* `DspFrameCodec`
* `DspProtocol`
* `DspController`
* `DspPayloadRouter`
* `GainBlockDecoder`
* `MuteBlockDecoder`
* `DspCommandProcessor`

#### `dsp408-runtime`

OSGi runtime component that implements `DspService`.

Important classes:

* `DspRuntimeComponent`
* `Dsp408Configuration`
* `VolumeRoomHandler`

#### `dsp408-shell`

Karaf shell command bundle exposing `dsp408:*` commands.

Important class:

* `Dsp408Command`

#### `dsp408-matrix`

Matrix bot integration with:

* control room for full `!dsp ...` commands
* explicit multi-DSP targeting with `!dsp dsp <id> <command>`
* volume room for grouped input volume handling
* machine room for structured Matrix events with optional `dspId`
* structured FIR408 machine commands

Important classes:

* `MatrixBotComponent`
* `MatrixMessageHandler`
* `MatrixApiClient`
* `MatrixRuntimeConfig`
* `MatrixBotConfiguration`

#### `dsp408-http`

HTTP/JSON API based on OSGi HTTP Whiteboard + Servlet.

Important class:

* `DspApiServlet`

OpenAPI file:

* `src/main/resources/openapi/dsp408-openapi.json`

#### `dsp408-features`

Karaf features XML used to install the runtime and optional modules.

---

## Build

Build the full project:

```bash
mvn clean install
```

This produces all module artifacts and the features XML attachment used by Karaf.

---

## Quick Start with Karaf

### 1. Build everything

```bash
mvn clean install
```

### 2. Start Karaf

Example:

```bash
bin/start
bin/client
```

If you started Karaf with a clean state, you need to add the feature repo again.

### 3. Add the features repository

```bash
feature:repo-add mvn:de.drremote/dsp408-features/0.0.1-SNAPSHOT/xml/features
```

### 4. Install features

Install all available DSP modules:

```bash
feature:install dsp408-all
```

Or install only what you need:

```bash
feature:install dsp408-runtime
feature:install dsp408-shell
feature:install dsp408-matrix
feature:install dsp408-http
```

### 5. Configure the runtime

Configuration PID:

```text
de.drremote.dsp408controller
```

Example:

```bash
config:edit de.drremote.dsp408controller
config:property-set dsp.ip 192.168.0.166
config:property-set dsp.port 9761
config:property-set dsp.library fir408
config:property-set auto.connect true
config:property-set auto.read.on.connect true
config:property-set meter.poll.interval.ms 200
config:property-set volume.step.db 1.0
config:update
```

### 6. Test the shell command

```bash
dsp408:connect
dsp408:state
dsp408:deviceinfo
dsp408:refresh
```

You can also use the legacy wrapper style:

```bash
dsp408:cmd connect
dsp408:cmd state
```

---

## Karaf Features

The feature definitions live in `dsp408-features`.

Available features:

* `dsp408-runtime`
* `dsp408-shell`
* `dsp408-matrix`
* `dsp408-http`
* `dsp408-all`

### Feature Composition

#### `dsp408-runtime`

Installs:

* `dsp408-api`
* `dsp408-core`
* `dsp408-runtime`

#### `dsp408-shell`

Installs:

* `dsp408-runtime`
* `dsp408-shell`

#### `dsp408-matrix`

Installs:

* `dsp408-runtime`
* Jackson bundles
* `dsp408-matrix`

#### `dsp408-http`

Installs:

* Karaf `http` feature
* `dsp408-runtime`
* Jackson bundles
* `dsp408-http`

#### `dsp408-all`

Installs everything.

---

## Runtime Service

The runtime is implemented by `DspRuntimeComponent` and registered as the OSGi service:

```text
de.drremote.dsp408.api.DspService
```

This is the shared service used by:

* shell
* Matrix bot
* HTTP servlet

### Runtime PID

```text
de.drremote.dsp408controller
```

### Runtime Properties

| Property               | Meaning                                         |
| ---------------------- | ----------------------------------------------- |
| `dsp.id`               | default DSP id                                  |
| `dsp.ip`               | default DSP IP address                          |
| `dsp.port`             | default DSP TCP port                            |
| `dsp.library`          | protocol library: `fir408`, `dsp408` or `auto` |
| `auto.connect`         | connect automatically when the component starts |
| `auto.read.on.connect` | compatibility flag; the verified startup dump is always performed |
| `meter.poll.interval.ms` | central DSP meter polling interval; clients read the cache |
| `volume.step.db`       | default step for grouped volume up/down         |
| `dsps`                 | optional additional DSPs as `id=ip[:port]`      |

### Runtime Behavior

* If `auto.connect=true`, activation schedules connection work on the lifecycle worker and returns without waiting for the device.
* If the configured DSP IP or port changes while connected, the component reconnects automatically.
* `connect()` always uses the current OSGi configuration and is idempotent while a DSP session already exists; only explicit `reconnect()` replaces that session.
* A new connection reads the complete parameter dump. Required reads use one 1000 ms response window without retransmission; an unanswered read closes the session. Cache readiness is reported separately through `SettingsCacheDto`.
* The runtime is the only active meter poller. HTTP, Android, ESP and Matrix clients read the shared cache.
* Normal settings reads are RAM-only. `POST /refresh` explicitly rebuilds the complete cache from the DSP.
* Normal writes are sent once and wait for the DSP ACK before updating cached state. A missing ACK closes the session and reports uncertain completion; it does not optimistically update cached state. Legacy writes do not trigger a parameter-block readback; reconnect or explicit `POST /refresh` provides authoritative device state. The additive [`POST /parameters/write`](../docs/PARAMETER-API.md) route can verify gain, mute, phase and delay against a fresh block where the firmware catalog confirms its mapping. It reports ACK, verified, rejected and uncertain outcomes separately; a single verified parameter still leaves the full settings snapshot stale.
* Pausing meter polling removes pending and selected-but-unsent samples. A sample whose transmission has started completes before the next serialized command. Polling resumes one interval after a control transaction. An unanswered meter sample gets a short recovery pause; verified responses govern keepalive health probes.
* Old APIs use the default DSP. Multi-DSP APIs use `/api/v1/dsps/{id}/...`.

Example additional DSPs:

```text
dsps = fir1=192.168.0.167:9761,room2=192.168.0.168:9761
```

---

## Shell Commands

The shell bundle exposes direct Karaf commands under the scope:

```text
dsp408
```

### Most useful commands

```bash
dsp408:help
dsp408:dsps
dsp408:select main
dsp408:current
dsp408:dsp fir408 state
dsp408:dsp normal408 gain out1 -10
dsp408:connect
dsp408:disconnect
dsp408:reconnect
dsp408:connected

dsp408:state
dsp408:status
dsp408:channels
dsp408:get ina
dsp408:gain ina -10
dsp408:mute out1
dsp408:unmute out1

dsp408:deviceinfo
dsp408:info
dsp408:login 1234

dsp408:blocks
dsp408:readblock 1C
dsp408:showblock 1C
dsp408:scanblocks
dsp408:refresh

dsp408:raw 00 01 03 35 04 01

dsp408:peqf out2 1 1000
dsp408:peqqraw out2 1 34
dsp408:peqgcode out2 1 0x80
```

### Volume shortcuts

```bash
dsp408:volume help
dsp408:volume status
dsp408:volume refresh
dsp408:volume up
dsp408:volume down
dsp408:volume mute
dsp408:volume unmute
dsp408:volume set -10
dsp408:volume step +1

dsp408:volstatus
dsp408:volrefresh
dsp408:volup
dsp408:voldown
dsp408:volmute
dsp408:volunmute
dsp408:volset -10
dsp408:volstep +1
```

### Legacy fallback

Still supported:

```bash
dsp408:cmd state
dsp408:cmd gain ina -12
```

---

## Shared Command Set

The common command handling is implemented in `DspCommandProcessor` and used by shell + Matrix.

Supported commands:

```text
help
channels
connect
disconnect
reconnect
handshake
keepalive
sysinfo
deviceinfo
login <1234>
gain <channel> <db>
mute <channel>
unmute <channel>
raw <hex payload>
state
get <channel>
refresh
scanblocks
readblock <hex>
blocks
showblock <hex>
peqf <outX> <peq> <hz>
peqqraw <outX> <peq> <raw>
peqgcode <outX> <peq> <code>
```

### Important command notes

* `refresh` is effectively a block scan followed by formatted state output.
* `connect` uses only the configured OSGi target, not ad-hoc IP/port parameters.
* `raw` sends an unframed payload and the code automatically wraps it in DSP framing.
* In **Matrix mode**, `connect`, `disconnect`, `reconnect`, `raw` and `sendraw` are admin-only.

---

## Channel IDs and Aliases

Channel parsing is implemented in `DspChannel.parse(...)`.

### Inputs

| Channel | Accepted aliases          |
| ------- | ------------------------- |
| `InA`   | `ina`, `inputa`, `a`, `0` |
| `InB`   | `inb`, `inputb`, `b`, `1` |
| `InC`   | `inc`, `inputc`, `c`, `2` |
| `InD`   | `ind`, `inputd`, `d`, `3` |

### Outputs

| Channel | Accepted aliases        |
| ------- | ----------------------- |
| `Out1`  | `out1`, `output1`, `4`  |
| `Out2`  | `out2`, `output2`, `5`  |
| `Out3`  | `out3`, `output3`, `6`  |
| `Out4`  | `out4`, `output4`, `7`  |
| `Out5`  | `out5`, `output5`, `8`  |
| `Out6`  | `out6`, `output6`, `9`  |
| `Out7`  | `out7`, `output7`, `10` |
| `Out8`  | `out8`, `output8`, `11` |

---

## HTTP API

The HTTP API is implemented by `DspApiServlet` and exposed under:

```text
/api/v1/*
```

Default Karaf URL:

```text
http://localhost:8181/api/v1
```

### Basic endpoints

```text
GET  /health
GET  /help
GET  /openapi.json
GET  /state
GET  /status
GET  /device
GET  /deviceinfo
GET  /info
GET  /channels
GET  /channels/{channelId}
GET  /channels/{channelId}/meter
GET  /meters
GET  /blocks
GET  /blocks/{blockIndex}
```

### Write / action endpoints

```text
POST /connection/connect
POST /connection/reconnect
POST /connection/disconnect
POST /channels/{channelId}/gain
POST /channels/{channelId}/mute
POST /channels/{channelId}/unmute
POST /blocks/{blockIndex}/read
POST /blocks/scan
POST /scanblocks
POST /refresh
POST /device/login
POST /preset/validate
POST /preset/apply
POST /meters/read
POST /raw
```

### Volume endpoints

```text
GET  /volume
GET  /volume/status
GET  /volume/help
POST /volume/up
POST /volume/down
POST /volume/mute
POST /volume/unmute
POST /volume/refresh
POST /volume/set
POST /volume/step
```

### Quick examples

```bash
curl http://localhost:8181/api/v1/health
curl http://localhost:8181/api/v1/state
curl http://localhost:8181/api/v1/channels/out1
curl http://localhost:8181/api/v1/meters
curl http://localhost:8181/api/v1/channels/out1/meter
curl -X POST http://localhost:8181/api/v1/meters/read
curl -X POST http://localhost:8181/api/v1/connection/connect
curl -X POST http://localhost:8181/api/v1/channels/out1/mute
curl -X POST http://localhost:8181/api/v1/channels/out1/gain \
  -H 'Content-Type: application/json' \
  -d '{"db": -6}'
```

### OpenAPI document

Available at:

```text
http://localhost:8181/api/v1/openapi.json
```

---

## HTTP API Examples

### Connect to the DSP

```bash
curl -X POST http://localhost:8181/api/v1/connection/connect
```

### Read full known state

```bash
curl http://localhost:8181/api/v1/state
```

### Read one channel

```bash
curl http://localhost:8181/api/v1/channels/ina
```

### Set gain

```bash
curl -X POST http://localhost:8181/api/v1/channels/ina/gain \
  -H 'Content-Type: application/json' \
  -d '{"db": 0}'
```

### Mute channel

```bash
curl -X POST http://localhost:8181/api/v1/channels/out1/mute
```

### Unmute channel

```bash
curl -X POST http://localhost:8181/api/v1/channels/out1/unmute
```

### Read cached block

```bash
curl http://localhost:8181/api/v1/blocks/1C
```

### Trigger block scan

```bash
curl -X POST http://localhost:8181/api/v1/blocks/scan
```

### Group input volume set

```bash
curl -X POST http://localhost:8181/api/v1/volume/set \
  -H 'Content-Type: application/json' \
  -d '{"db": 0}'
```

### Validate and apply a complete JSON preset

```bash
curl -X POST \
  -H 'Content-Type: application/json' \
  --data-binary '@preset.json' \
  http://localhost:8181/api/v1/dsps/fir408/preset/validate

curl -X POST \
  -H 'Content-Type: application/json' \
  --data-binary '@preset.json' \
  http://localhost:8181/api/v1/dsps/fir408/preset/apply
```

The apply endpoint validates the complete document and then mutes all inputs
and outputs before writing settings. Channels marked `unmuteAfterApply` remain
muted unless the request explicitly uses `?unmuteSelected=true`.
Preset apply refreshes the DSP block cache by default (`?refresh=true`).
Use `?refresh=false` to skip that dump; decoded write updates then remain
unconfirmed and raw blocks may still describe the previous device state.

---

## Matrix Bot

The Matrix integration is implemented in `dsp408-matrix`.

It supports four usage modes:

* **control room** → full text commands
* **volume room** → simple grouped and individual level commands
* **warning room** → read-only limiter alerts and warning on/off control
* **machine room** → structured request/response events

### Matrix PID

```text
de.drremote.dsp408controller.matrix
```

### Matrix properties

| Property               | Meaning                                   |
| ---------------------- | ----------------------------------------- |
| `enabled`              | enables the bot                           |
| `matrix.url`           | Matrix homeserver base URL                |
| `access.token`         | bot access token                          |
| `control.room.id`      | room for full `!dsp ...` control          |
| `admin.room.id`        | deprecated fallback for `control.room.id` |
| `volume.room.id`       | room for simple input/output level control|
| `volume.room.members.allowed` | allow all senders in the volume room |
| `warning.room.id`      | read-only room for limiter warnings        |
| `machine.room.id`      | room for structured machine events        |
| `allowed.users`        | users allowed in control/volume rooms     |
| `admin.users`          | admin users                               |
| `machine.users`        | users allowed to send machine events      |
| `sync.timeout.ms`      | Matrix sync timeout                       |
| `reconnect.delay.ms`   | retry delay                               |
| `limiter.warnings.enabled` | initial limiter warning state          |
| `limiter.warning.poll.ms` | limiter meter polling interval          |
| `limiter.warning.cooldown.ms` | active-warning reminder interval    |
| `connect.dsp.on.start` | try to connect DSP on startup             |

### Example Matrix config

```bash
config:edit de.drremote.dsp408controller.matrix
config:property-set enabled true
config:property-set matrix.url https://matrix.example.org
config:property-set access.token syt_dummy_token
config:property-set control.room.id '!controlroom:example.org'
config:property-set volume.room.id '!volumeroom:example.org'
config:property-set volume.room.members.allowed false
config:property-set warning.room.id '!warningroom:example.org'
config:property-set machine.room.id '!machineroom:example.org'
config:property-set allowed.users @user1:example.org,@admin:example.org
config:property-set admin.users @admin:example.org
config:property-set machine.users @orchestrator:example.org
config:property-set sync.timeout.ms 30000
config:property-set reconnect.delay.ms 3000
config:property-set limiter.warnings.enabled true
config:property-set limiter.warning.poll.ms 1000
config:property-set limiter.warning.cooldown.ms 15000
config:property-set connect.dsp.on.start true
config:update
```

### Matrix startup behavior

* validates that the configuration is usable
* optionally tries to connect to the DSP
* performs `whoami`
* performs initial sync
* enters a continuous sync loop
* serializes DSP operations through a single-thread executor

---

## Matrix Control Room

The control room accepts full text commands.

### Prefix

Commands must start with:

```text
!dsp
```

### Examples

```text
!dsp help
!dsp dsps
!dsp connect
!dsp state
!dsp meters
!dsp meterstate
!dsp meter out1
!dsp dsp fir408 state
!dsp dsp normal408 gain out1 -10
!dsp gain ina -10
!dsp mute out1
!dsp readblock 1C
!dsp showblock 1C
```

### Permissions

* sender must be in `allowed.users` or `admin.users`
* `connect`, `disconnect`, `reconnect`, `raw` and `sendraw` require admin rights in Matrix mode
* commands without `dsp <id>` target the default DSP

---

## Matrix Volume Room

The volume room is designed for fast level control without a command prefix.
Unknown chat messages are ignored. `in` addresses `InA..InD`, `out` addresses
`Out1..Out8`, and every channel can also be addressed individually.
By default, senders must be listed in `allowed.users` or `admin.users`. Set
`volume.room.members.allowed=true` to let every sender who can post in the
configured volume room use these commands. This does not broaden access to the
control, warning or machine rooms.

The same room also supports output-limiter threshold control. Attack and
release remain unchanged. Group steps preserve the relative threshold offsets:

```text
limiter status
limiter +
limiter -
limiter +1
limiter -1
limiter set -10
limiter out3 -8
limiter out3 +
```

### Supported commands

```text
in +
in -
out +
out -
ina -20
inb +
out1 -10
out5 -
in mute
out unmute
out1 mute
out1 unmute
status
help
```

Fixed group values are explicit so that a number cannot accidentally flatten
all relative channel offsets:

```text
in set -20
out set -20
```

The previous syntax remains available:

```text
!dsp volume help
!dsp volume status
!dsp volume refresh
!dsp volume up
!dsp volume down
!dsp volume step +1
!dsp volume step -1
!dsp volume step +2.5
!dsp volume set -10
!dsp volume set 0
!dsp volume mute
!dsp volume unmute
```

### Behavior

* `status` shows the current known state of all inputs and outputs
* `refresh` scans blocks and refreshes the current values
* `+` / `-` use `volume.step.db` and preserve relative channel offsets
* `step` applies an explicit delta to the selected group or channel
* `set` sets the selected group to the same target gain
* `mute` / `unmute` affect the selected group or channel
* legacy commands without a target continue to address all four inputs

### Important details

* the volume room does **not** establish a DSP connection by itself
* if gains are not known yet, it triggers a refresh/scan first
* after operations, it scans blocks again so the state is confirmed from device data

---

## Matrix Limiter Warning Room

The limiter warning room is read-only with respect to the DSP. It polls the
confirmed live limiter flags for all eight outputs and sends concise warnings,
15-second reminders while limiting continues, and an all-clear message.

```text
warnungen an
warnungen aus
status
help
```

The enabled state is stored in Matrix room state and survives bot restarts.
Normal chat and `!dsp` commands are ignored in this room.

---

## Matrix Machine Room

The machine room uses structured Matrix events instead of normal text messages.

### Event types

Request:

```text
de.drremote.dsp408.command
```

Response:

```text
de.drremote.dsp408.response
```

### Permissions

Allowed senders must be in:

* `machine.users`, or
* `admin.users`

### Supported machine commands

```text
dsp.instances.list
connection.connect
connection.disconnect
connection.reconnect
device.info.get
state.get
meters.read
meters.get
meter.get
blocks.scan
block.read
channel.get
channel.gain.set
channel.mute.set
matrix.route.set
crossover.hp.set
fir.mode.set
fir.generator.set
fir.upload
```

### Mapping examples

| Machine command      | Internal command                                    |
| -------------------- | --------------------------------------------------- |
| `connection.connect` | `!dsp connect`                                      |
| `device.info.get`    | `!dsp deviceinfo`                                   |
| `state.get`          | `!dsp state`                                        |
| `meters.read`        | `!dsp meters`                                       |
| `meters.get`         | `!dsp meterstate`                                   |
| `meter.get`          | `!dsp meter <channelId>`                            |
| `blocks.scan`        | `!dsp scanblocks`                                   |
| `block.read`         | `!dsp readblock <blockIndex>`                       |
| `channel.get`        | `!dsp get <channelId>`                              |
| `channel.gain.set`   | `!dsp gain <channelId> <db>`                        |
| `channel.mute.set`   | `!dsp mute <channelId>` / `!dsp unmute <channelId>` |
| `fir.mode.set`       | `!dsp firmode <outputId> <iir|fir>`                 |
| `fir.generator.set`  | `!dsp firgen <outputId> <type> <window> <hpHz> <lpHz> <taps>` |

Machine requests may include `dspId` at the top level or inside `args`. If omitted, the default DSP is used.

### Example request

```json
{
  "apiVersion": "1.0",
  "requestId": "123",
  "dspId": "fir408",
  "command": "channel.gain.set",
  "args": {
    "channelId": "ina",
    "db": -10
  }
}
```

### Example success response

```json
{
  "apiVersion": "1.0",
  "requestId": "123",
  "ok": true,
  "dspId": "fir408",
  "command": "channel.gain.set",
  "mappedCommand": "!dsp gain ina -10",
  "result": {
    "text": "..."
  }
}
```

### Example error response

```json
{
  "apiVersion": "1.0",
  "requestId": "123",
  "ok": false,
  "dspId": "fir408",
  "error": {
    "message": "..."
  }
}
```

---

## DSP Protocol Implementation

The DSP transport and protocol support is implemented mainly in:

* `DspProtocol`
* `DspFrameCodec`
* `DspClient`
* `DspPayloadRouter`
* `DspController`

### Transport

* TCP
* default target: `192.168.0.166:9761`

### Frame format

Frames are encoded as:

```text
DLE STX + payload + DLE ETX + XOR checksum
```

Byte values:

* `DLE = 0x10`
* `STX = 0x02`
* `ETX = 0x03`

### Implemented payload families

| Function                      | Command byte  |
| ----------------------------- | ------------- |
| handshake init                | `0x10`        |
| handshake ack / version reply | `0x13`        |
| keepalive                     | `0x12`        |
| system info request           | `0x2C`        |
| login                         | `0x2D`        |
| set gain                      | `0x34`        |
| set mute                      | `0x35`        |
| read parameter block          | `0x27`        |
| parameter block response      | `0x24`        |
| ACK payload                   | `01 00 01 01` |

---

## Implemented Decoding

The current decoder layer extracts the following information:

### Device info

* device version from handshake ack reply payload
* raw last system info payload

### Gain decoding

Gain is decoded from known block offsets:

| Block | Channel | Offset |
| ----- | ------- | ------ |
| `02`  | `InA`   | `0x30` |
| `05`  | `InB`   | `0x26` |
| `08`  | `InC`   | `0x1C` |
| `0B`  | `InD`   | `0x12` |
| `0D`  | `Out1`  | `0x16` |
| `0F`  | `Out2`  | `0x1A` |
| `11`  | `Out3`  | `0x1E` |
| `13`  | `Out4`  | `0x22` |
| `15`  | `Out5`  | `0x26` |
| `17`  | `Out6`  | `0x2A` |
| `19`  | `Out7`  | `0x2E` |
| `1C`  | `Out8`  | `0x00` |

### Mute decoding

Mute is decoded from block `1C`:

* input mute mask at offset `0x08`
* output mute mask at offset `0x0A`

---

## Gain Conversion

Gain conversion is implemented in `DspProtocol`.

### Supported range

```text
-60.0 dB .. +12.0 dB
```

### Encoding logic

* for values `<= -20.0 dB`, `0.5 dB` resolution is used
* above that, `0.1 dB` resolution is used
* raw values are clamped to `0..400`

### Raw conversion

For encoding:

```text
if db <= -20.0:
  raw = round((db + 60.0) * 2.0)
else:
  raw = round((db + 28.0) * 10.0)
```

For decoding:

```text
if raw <= 79:
  db = -60.0 + raw * 0.5
else:
  db = -28.0 + raw * 0.1
```

---

## State Tracking

The runtime keeps a live in-memory state in `DspState`.

Per channel, it tracks:

* gain in dB
* mute state
* whether gain is confirmed from the device
* whether mute is confirmed from the device
* dirty flags for gain and mute
* the last update source

Typical sources include:

* `tx`
* `block:02@30 raw=...`
* `block:1C@08`
* `block:1C@0A`

Raw parameter blocks are cached separately and can be inspected later.

### Useful inspection commands

```text
state
get ina
blocks
showblock 1C
deviceinfo
```

---

## Logging

Karaf runtime and Matrix components use SLF4J logging.

Typical log output includes:

* connection open / close
* transmitted payloads and full frames
* received payloads
* checksum errors
* block reads and block responses
* decoded gain values
* decoded mute mask values
* Matrix sync and reconnect errors

---

## Typical Workflows

### Shell / Karaf workflow

```text
1. Build the project
2. Add the features repo in Karaf
3. Install dsp408-runtime + dsp408-shell
4. Configure PID de.drremote.dsp408controller
5. Run dsp408:dsps and dsp408:connect
6. Use dsp408:select <id> for an interactive target or dsp408:dsp <id> <command> for one command
7. Use state / gain / mute / refresh / blocks
```

### HTTP workflow

```text
1. Install dsp408-runtime + dsp408-http
2. Configure the runtime PID
3. POST /connection/connect
4. GET /state or /channels
5. Use /channels/{id}/gain or /mute /unmute
```

### Matrix workflow

```text
1. Install dsp408-runtime + dsp408-matrix
2. Configure runtime PID
3. Configure Matrix PID
4. Enable the Matrix bot
5. Use !dsp commands in the control room
6. Use grouped commands in the volume room
7. Use structured events in the machine room
```

---

## Known Limitations

The current implementation is intentionally focused on:

* transport and connection handling
* handshake / keepalive / sysinfo
* login PIN
* gain, mute, phase/polarity, delay and limiter writes
* crossover HP/LP editing
* routing and matrix crosspoint editing
* complete FIR408 input/output PEQ value and bypass readback, state-preserving writes, and a preflighted 104-band bypass command
* FIR408 mode, generator and external FIR upload commands
* DSP408 and FIR408 protocol library selection
* single-DSP and multi-DSP access over shell / Matrix / HTTP
* parameter block reads
* state caching
* known semantic decoding for implemented controls

Still incomplete in the current codebase:

* full semantic decode of every byte in every parameter block
* a complete high-level DSP editor UI
* end-to-end operation tests with multiple physical DSPs at different IPs

Unknown block contents are still cached and can be inspected with:

```text
blocks
showblock <hex>
raw <hex>
```

---

## Example: Set all inputs to 0 dB

### Karaf shell

```bash
dsp408:volume set 0
```

### HTTP API

```bash
curl -X POST http://localhost:8181/api/v1/volume/set \
  -H 'Content-Type: application/json' \
  -d '{"db": 0}'
```

This affects grouped input channels `InA` to `InD`.

If you instead want to set channels individually, use:

```bash
dsp408:gain ina 0
dsp408:gain inb 0
dsp408:gain inc 0
dsp408:gain ind 0
```

or the HTTP gain endpoint for each channel.

---

## Summary

DSP408Controller is currently a solid Java/Karaf control layer for DSP408/FIR408 devices with:

* a reusable protocol core
* a runtime OSGi service
* named multi-DSP routing
* Karaf shell access
* Matrix integration
* an HTTP/JSON API
* raw block visibility for reverse engineering

It is already useful for daily control tasks and provides a good foundation for deeper DSP reverse engineering, broader readback decoding and future editor UI work.
