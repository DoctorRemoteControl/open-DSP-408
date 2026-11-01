# dsp408-http

HTTP/JSON control API for the DSP408/FIR408 runtime.

## Bundle

- Maven artifact: `de.drremote:dsp408-http`
- OSGi symbolic name: `de.drremote.dsp408.http`
- Bundle name: `DSP408 HTTP`
- Requires service: `de.drremote.dsp408.api.DspService`

## Purpose

This bundle exposes `DspService` through an OSGi HTTP Whiteboard servlet. It is useful for scripts, dashboards, integrations and smoke tests without using the Karaf shell.

## Servlet

- Class: `DspApiServlet`
- Pattern: `/api/v1/*`

## Useful Endpoints

- `GET /api/v1/health`
- `GET /api/v1/help`
- `GET /api/v1/openapi.json`
- `GET /api/v1/capabilities`
- `POST /api/v1/parameters/write`
- `GET /api/v1/dsps`
- `GET /api/v1/dsps/{dspId}/state`
- `POST /api/v1/dsps/{dspId}/connection/connect`
- `POST /api/v1/dsps/{dspId}/channels/{channelId}/gain`
- `GET /api/v1/state`
- `GET /api/v1/deviceinfo`
- `POST /api/v1/connection/connect`
- `POST /api/v1/connection/reconnect`
- `POST /api/v1/connection/disconnect`
- `POST /api/v1/device/login`
- `POST /api/v1/preset/load`
- `POST /api/v1/preset/store`
- `GET /api/v1/preset/export`
- `POST /api/v1/preset/name/read`
- `POST /api/v1/preset/validate`
- `POST /api/v1/preset/apply`
- `GET /api/v1/channels`
- `GET /api/v1/channels/{channelId}`
- `GET /api/v1/matrix`
- `GET /api/v1/matrix/{outputId}`
- `GET /api/v1/channels/{channelId}/gate`
- `GET /api/v1/channels/{channelId}/meter`
- `POST /api/v1/channels/{channelId}/name`
- `POST /api/v1/channels/{channelId}/gain`
- `POST /api/v1/channels/{channelId}/mute`
- `POST /api/v1/channels/{channelId}/unmute`
- `POST /api/v1/channels/{channelId}/phase`
- `POST /api/v1/channels/{channelId}/delay`
- `POST /api/v1/delay-unit`
- `POST /api/v1/matrix/route`
- `POST /api/v1/matrix/crosspoint-gain`
- `POST /api/v1/channels/{channelId}/crossover/{hp|lp}`
- `POST /api/v1/channels/{channelId}/crossover/{hp|lp}/{frequency|slope|bypass}`
- `POST /api/v1/fir/mode`
- `POST /api/v1/fir/generator`
- `POST /api/v1/fir/upload`
- `POST /api/v1/channels/{channelId}/peq/{peqIndex}`
- `GET /api/v1/peq` / `GET /api/v1/peq/{channelId}` (decoded values, bypass and device-confirmation state)
- `POST /api/v1/channels/{channelId}/peq/{peqIndex}/{frequency|q|type|bypass}`
- `POST /api/v1/channels/{channelId}/peq/{peqIndex}/q/raw`
- `POST /api/v1/channels/{channelId}/peq/{peqIndex}/gain`
- `POST /api/v1/channels/{channelId}/peq/{peqIndex}/gain/code`
- `POST /api/v1/channels/{channelId}/input-peq/{peqIndex}`
- `POST /api/v1/channels/{channelId}/input-peq/{peqIndex}/{frequency|q|gain|type|bypass}`
- `POST /api/v1/peq/bypass-all` (`{"bypass":true}`; preflights and updates all 104 PEQs)
- `POST /api/v1/channels/{channelId}/geq/{bandIndex}/gain`
- `POST /api/v1/channels/{channelId}/gate`
- `POST /api/v1/channels/{channelId}/gate/{threshold|hold|attack|release}`
- `GET /api/v1/channels/{inputId}/compressor`
- `POST /api/v1/channels/{channelId}/compressor`
- `GET /api/v1/channels/{outputId}/limiter`
- `POST /api/v1/channels/{channelId}/limiter`
- `POST /api/v1/test-tone/source`
- `POST /api/v1/test-tone/sine`
- `POST /api/v1/test-tone/off`
- `GET /api/v1/meters`
- `POST /api/v1/meters/read`
- `GET /api/v1/blocks`
- `GET /api/v1/blocks/{blockIndex}`
- `POST /api/v1/blocks/{blockIndex}/read`
- `POST /api/v1/blocks/scan`
- `GET /api/v1/volume`
- `POST /api/v1/volume/{up|down|mute|unmute|refresh}`
- `POST /api/v1/volume/{set|step}`
- `POST /api/v1/raw`
- `POST /api/v1/command`

## OpenAPI

The bundled API description is available at:

`GET /api/v1/openapi.json`

Source file:

`src/main/resources/openapi/dsp408-openapi.json`

## Example

```bash
curl -X POST http://localhost:8181/api/v1/channels/ina/gain \
  -H "Content-Type: application/json" \
  -d "{\"db\": -6.0}"
```

Multi-DSP endpoints use the same paths with a `/dsps/{dspId}` prefix:

```bash
curl -X POST http://localhost:8181/api/v1/dsps/main/channels/out2/gain \
  -H "Content-Type: application/json" \
  -d "{\"db\": 0.0}"
```

## JSON Presets

Export the complete current FIR408 state, including all 32 raw parameter blocks:

```powershell
Invoke-WebRequest `
  -Uri "http://localhost:8181/api/v1/preset/export" `
  -OutFile ".\fir408-backup.json"
```

Store the complete active state in a native DSP user slot and verify the slot name:

```powershell
Invoke-RestMethod `
  -Method Post `
  -ContentType "application/json" `
  -Body '{"slot":"U01","name":"Live Setup"}' `
  -Uri "http://localhost:8181/api/v1/preset/store"
```

Recall it later with `POST /api/v1/preset/load` and body `{"slot":"U01"}`.
FIR408 exposes no raw parameter-block write command, so the JSON export is a
portable audit/backup while exact restoration is delegated to the DSP's native
U01..U19 slots.

A complete JSON preset can be validated without changing the DSP:

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  --data-binary "@preset.json" \
  http://localhost:8181/api/v1/dsps/fir408/preset/validate
```

Apply the same file:

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  --data-binary "@preset.json" \
  http://localhost:8181/api/v1/dsps/fir408/preset/apply
```

Preset apply safety behavior:

- the complete document is validated before the first DSP write
- all four inputs and all eight outputs are muted first
- `unmuteAfterApply` is ignored by default
- `?unmuteSelected=true` explicitly enables marked channels after every other operation succeeds
- `?refresh=false` skips the final parameter-block scan
- if an operation fails, the server attempts to mute all twelve channels again

The document must contain exactly `ina..ind` and `out1..out8`. It supports
channel names, gain, phase, delay, FIR/IIR mode, zero to four matrix sources
with an independent crosspoint gain per source, HP/LP crossover and output
limiter settings. The legacy singular `route` field remains supported; use
`routes` for sums such as `InA + InB`.
Unknown DSP fields are rejected with HTTP 422 so that unsupported sections are
not silently ignored.

On FIR408, normal write endpoints are also checked against the protocol
library evidence before any network payload is sent. Unverified operations
return HTTP `409` with `error: "unsupported_operation"`. This applies equally
to direct HTTP calls, shell commands, Matrix commands and JSON preset
operations because the check is implemented in the shared controller.

The `/raw` endpoint deliberately bypasses this protection and is intended only
for explicit protocol diagnostics.

PowerShell example:

```powershell
Invoke-RestMethod `
  -Method Post `
  -ContentType "application/json" `
  -InFile .\preset.json `
  -Uri "http://localhost:8181/api/v1/dsps/fir408/preset/apply"
```

Read the last cached meter values:

```bash
curl http://localhost:8181/api/v1/meters
curl http://localhost:8181/api/v1/channels/out1/meter
```

The legacy meter POST returns the cached state while connected. The central
server poller owns fresh meter requests, so repeated HTTP calls do not issue
additional DSP requests. A disconnected DSP returns HTTP `409` with
`error: "dsp_not_connected"`; cached values remain available through the GET
endpoints above. This also applies to `/api/v1/dsps/{dspId}/meters/read`.

```bash
curl -X POST http://localhost:8181/api/v1/meters/read
```

Each meter channel contains the raw two-byte word, the unknown third slot byte
as `aux`, the calibrated `levelDb` value and the runtime `limiterActive` flag
for output channels. The level conversion is empirically calibrated against
the FIR408 internal 1 kHz sine and is limited to `-60 dB` below the observed
meter range.

Channel responses also expose `phaseInverted` and `delayMs` together with
independent `phaseConfirmedFromDevice`, `delayConfirmedFromDevice`, dirty-state
and last-update-source fields. On FIR408, phase is device-confirmed for all
twelve channels from the observed block map.

`GET /api/v1/state` includes `inputGates`, `inputCompressors` and `limiters`.
On FIR408, the original GUI values and the live block dump confirm the four
input gates, four input compressors and eight output limiters. Each DTO keeps
its per-field device-confirmation and update-source information.

## Parameter capabilities and write outcomes

`GET /api/v1/capabilities` returns the RAM-only firmware catalog for gain, mute,
phase and delay on all twelve channels. It does not query or synchronize the
device. The exact recognized profiles are `DSP408 V0104` and `FIR408 V0113P`;
disconnected or unknown identities never advertise `writable: true`. Each
parameter lists its value type, unit, limits, quantization, mapping evidence and
readback support. An older service provider returns unavailable capabilities.

`POST /api/v1/parameters/write` accepts one strict JSON object:

```json
{"channelId":"out1","parameter":"gain","value":-12.34,"verify":true}
```

`verify` defaults to `true` when omitted. Gain and delay require finite JSON
numbers; mute and phase require JSON booleans. Gain ranges from -60 to +12 dB,
with 0.5 dB steps through -20 dB and 0.1 dB steps above. Delay ranges from 0 to
680 ms with 1/96 ms resolution. Results expose both `requestedValue` and the
quantized `normalizedValue`. Unknown fields, nulls and incorrect value types
return HTTP 422 before the parameter service is invoked. Malformed JSON,
duplicate keys and trailing JSON values return HTTP 400.

With verification enabled, unsupported readback is rejected before sending.
FIR408 mute readback is observed only for InD and Out8; use `verify: false` for
ACK-only mute writes on other FIR408 channels. Both endpoints also accept the
`/api/v1/dsps/{dspId}` prefix to address a specific DSP.

| Result | HTTP | Meaning |
| --- | --- | --- |
| `ACKNOWLEDGED` | 200 | The DSP acknowledged the write; no readback verification was requested. |
| `VERIFIED` | 200 | A fresh device readback matches the normalized value. |
| `REJECTED` | 409 / 422 / 503 | Disconnected or unsupported operation / invalid input / command admission overload; nothing was transmitted. |
| `UNCERTAIN` | 502 / 409 / 503 | Transport or readback failure / readback mismatch / readback overload after ACK; the write may have taken effect. |

Every runtime result includes a correlation `requestId`, target DSP/channel,
parameter, requested and normalized values, status, `acknowledged`, nullable
`transmitted`, optional `readback`, `errorCode`, `message` and completion time.
`transmitted: null` means sending may have occurred. Readback includes its block,
fresh receive revision and observation time. An ACK alone never produces
`VERIFIED`. Requests are not retried or rolled back automatically; request IDs
are not idempotency keys. Body validation and older providers retain the
standard HTTP error envelope. Existing v1 control response shapes are unchanged.

## Build

From `dsp408-parent`:

```bash
mvn -pl dsp408-http -am test
```

## Notes

This bundle does not authenticate requests. Put it behind a trusted network boundary or reverse proxy if exposing it beyond localhost/LAN.
