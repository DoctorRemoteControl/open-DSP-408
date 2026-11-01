# Firmware-aware parameter API

Use `GET /api/v1/capabilities` before the additive
`POST /api/v1/parameters/write` endpoint. Both also support the existing
`/api/v1/dsps/{dspId}/...` device selector. These routes cover gain, mute, phase
and delay; other typed controls keep their existing endpoints. Existing v1
paths and response shapes remain compatible.

## Discover support

```sh
curl http://localhost:8181/api/v1/capabilities
```

The response contains `dspId`, connection and firmware identity, the active
library type, and all 12 channels with four parameter descriptors each. Each
descriptor provides value type, unit, range, resolution, `writable`,
`readbackSupported`, write/read evidence and an unavailability reason where
applicable. This request uses memory only: it never connects or reads the DSP.
Disconnected devices and unknown firmware advertise no writable parameters.
An older Java provider can return an empty, unavailable catalog.

Only these exact identity/library pairs enable this write API:

| Device identity | Library type |
| --- | --- |
| `DSP408 V0104` | `dsp408_field_library` |
| `FIR408 V0113P` | `dsp408_fir_field_library` |

Other firmware may continue to work with existing controls, but requires
independent mapping evidence before this API advertises support. In particular,
readback is unsupported for FIR408 mute except InD and Out8, and DSP408 delay
on InB and InC. Their inferred locations are also excluded from the legacy
state's device-confirmation flags. ACK-only writes remain available where the
catalog reports `writable=true`.

## Write and verify

Example request, after checking the selected connected device's capabilities:

```json
{
  "channelId": "out8",
  "parameter": "gain",
  "value": -12.34,
  "verify": true
}
```

`verify` defaults to `true`. The channel IDs in capabilities are canonical;
use them directly. Numeric values must be finite JSON numbers. Mute and phase
require JSON booleans (`true` means muted or phase-inverted respectively).
Unknown fields, string-to-number coercion, null and duplicate keys are rejected.

| Parameter | Values | Device resolution |
| --- | --- | --- |
| `gain` | -60 to +12 dB | 0.5 dB through -20 dB; 0.1 dB above -20 dB |
| `delay` | 0 to 680 ms | 1/96 ms |
| `mute` | boolean | — |
| `phase` | boolean | — |

Out-of-range requests fail before a write; in-range values are quantized by the
device codec. Results expose both `requestedValue` and `normalizedValue`.
For example, -12.34 dB normalizes to -12.3 dB. With verification enabled, an
unsupported readback fails before sending. Set `verify=false` explicitly to
request an ACK-only operation on a writable parameter.

Every runtime outcome contains `requestId`, `dspId`, channel and parameter,
requested/normalized values, `status`, `acknowledged`, `transmitted`, optional
`readback`, `errorCode`, `message` and completion time.

| Status | Meaning |
| --- | --- |
| `ACKNOWLEDGED` | A real device ACK arrived; readback was not requested. |
| `VERIFIED` | A real ACK arrived and a fresh received block matched the normalized value at a confirmed location. |
| `UNCERTAIN` | The write may have reached the device, or its acknowledged result could not be verified. |
| `REJECTED` | This operation did not transmit a write. |

`transmitted` is `true` after ACK, `false` for a definite pre-send rejection, and
`null` when transmission may have happened without an established ACK. A
verified readback includes its value, block index, receive revision and receive
time. Revisions are scoped to the connected state; they are not persistent
ETags or optimistic-concurrency tokens. Readback uses an immutable snapshot of
received bytes, independent from local cache updates.

| HTTP status | Outcome / error |
| --- | --- |
| 200 | `ACKNOWLEDGED` or `VERIFIED` |
| 400 | Malformed JSON, duplicate keys or trailing JSON |
| 422 | Invalid body/type/channel/value/parameter |
| 409 | Disconnected or unsupported profile/operation/readback; superseded write; readback mismatch |
| 502 | Failed or missing write/readback response, or uncertain session change |
| 503 | Operation or wire queue overload |

A 503 before the write is `REJECTED`; a 503 during readback after ACK is
`UNCERTAIN`. A readback mismatch returns `UNCERTAIN`, includes the actual
readback and uses `readback_mismatch`. Body/parser failures and unsupported older
Java providers use the existing HTTP error envelope; admitted runtime outcomes
use the structured result. Inspect the body as well as the HTTP status.

## Concurrency, cache and recovery

The runtime admits at most 65 concurrent parameter operations, including callers
waiting behind another runtime transaction. The physical command queue is
bounded independently. Meters pause around each admitted write/readback pair.
The operation captures its original client and never continues on a replacement
connection after reconnect. Existing concurrent controls or linked device
behavior can still change a parameter between write and readback; this API does
not promise a multi-parameter transaction or a lasting value.

A potentially transmitted write invalidates the full settings snapshot and any
AutoLimit undo snapshot. Even `VERIFIED` certifies only that one received value;
`GET /settings-cache` remains `STALE`. Use `POST /refresh` to read all blocks and
restore `READY`. Definite rejections preserve cache status.

There is no automatic write retry or rollback. `requestId` is a correlation ID,
not an idempotency key. After an uncertain result, inspect/refresh the device
before deciding whether to issue another command. Missing required responses
close the session so a delayed response cannot acknowledge a later operation.

The public OpenAPI document is available at `GET /api/v1/openapi.json`.
`DspParameterApi` exposes the same capabilities and outcomes to Java callers via
`DspService`; its default methods allow older providers to link safely.

## Validation scope

Unit and real-socket tests cover profile/evidence gates, normalization, real ACKs,
fresh received snapshots, mismatches, lost responses, supersession, overload and
reconnect isolation. Fresh Karaf integration exercises the packaged Java service
and HTTP routes against a strictly loopback Out8 emulator. These simulated writes
do not establish physical audio correctness. The separate
[hardware validation](HARDWARE-VALIDATION.md) covers read-only transport stability.

A separate guarded run against the real FIR408 completed all 38 supported
parameter readbacks, rejected the 10 inferred mappings and preserved all 32
configuration blocks byte-for-byte. See the [measured hardware check](HARDWARE-VALIDATION.md#parameter-api-readback-check-2026-11-01).
The legacy Raider meter emulator is not a reference for this firmware or for
parameter writes; no physical control write has been claimed as validated.

## Interactive browser delivery

`Prefer: wait=0` opts into `DspParameterApi.writeParameterInteractive`. It rejects
busy runtime transactions or an occupied/pending control queue before sending
(`dsp_busy`, HTTP 503, `REJECTED`, `transmitted=false`). A single already-transmitting
meter/keepalive may finish first; the interactive next slot is reserved. An ACK
followed by busy optional verification remains `UNCERTAIN` with known transmission.
Older Java providers reject this method rather than silently using queued writes.
Without the preference the existing write behavior is preserved.

The browser explicitly chooses `verify:false` for normal control; the HTTP body
default remains `verify:true` for compatibility. It measures response time using
`Server-Timing: backend;dur=...` (handler/cache/service time, including device and
queue time). The header is not a measurement of physical DSP processing alone.

An optional `Idempotency-Key` of 16..128 safe ASCII token characters associates
one body and device with its outcome or exception in this servlet process. Same-key
transport repeats reuse that outcome and do not invoke the service again; a
changed body/device conflicts. Calls without a key retain the original behavior.
Request IDs in the result remain correlation IDs and are not deduplication keys.
The cache retains up to 4096 entries; completed outcomes/failures expire two
minutes after completion. Active entries never expire, and no guarantee
continues across server restart or retention expiry. Capacity exhaustion returns
503 before executing a new request. See the mobile controller document for its
client recovery and latency-testing scope.
