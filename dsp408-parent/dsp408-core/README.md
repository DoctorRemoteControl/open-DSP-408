# dsp408-core

Protocol, state and command core for the t.racks / Thomann DSP 408 and FIR408.

## Bundle

- Maven artifact: `de.drremote:dsp408-core`
- OSGi symbolic name: `de.drremote.dsp408.core`
- Bundle name: `DSP408 Core`

## Purpose

This bundle is the protocol engine. It knows how to build DSP408/FIR408 payloads, encode/decode frames, connect to the DSP over TCP, parse parameter block dumps and maintain the in-memory DSP state.

## Important Packages

- `core.protocol` - channel enums, PEQ/crossover enums and payload builders.
- `core.net` - TCP client, connection config and frame codec.
- `core.decode` - block and meter payload decoders.
- `core.library` - loader for `DspLib-408.json` and `DspLib-408-fir.json` read layouts.
- `core.state` - mutable DSP state model.
- `core.service` - `DspController`, the high-level core facade.
- `command` - text command processor used by shell/runtime compatibility paths.
- `util` - hex and state formatting helpers.

## DSP Library

The bundled protocol libraries are:

- `src/main/resources/dsp/DspLib-408.json`
- `src/main/resources/dsp/DspLib-408-fir.json`

They document observed commands, value mappings and parameter block read locations. The loader selects the FIR library automatically for device versions starting with `FIR408`.

## Main Entry Point

`DspController` is the main Java facade. It supports:

- connect, disconnect, handshake and keepalive
- block scan and runtime meter polling
- gain, mute, phase, delay and channel name writes
- FIR408 mode/generator/external FIR payloads
- output PEQ, input PEQ, input GEQ
- crossover, gate, compressor and limiter
- matrix route and crosspoint gain
- test tone source/frequency
- raw payload sending

## Build

From `dsp408-parent`:

```bash
mvn -pl dsp408-core -am test
```

## Notes

Some field-level writes require known existing state because the original DSP protocol writes complete parameter structures. In those cases, run a block scan before modifying a single field.

Preset recall, naming and store payloads are implemented. Network IP, device ID, linking and copy-channel remain unsupported.

The command worker serializes complete request/response exchanges. ACK writes
are sent once; a missing ACK invalidates the session because ACK frames do not
identify a command. An `IOException` therefore means completion is uncertain,
not that the device necessarily rejected the value. Required reads use a single one-second response window
and invalidate the session if it expires. Meter samples are best-effort;
verified incoming frames determine whether keepalive probes may be suppressed.

Meter polling shutdown removes queued samples. A selected but unsent sample is cancelled too; a sample whose transmission
has started may finish before the next serialized control command. Checksum-invalid frames
never reach either state decoders or response matching.

See [connection lifecycle](../docs/CONNECTION-LIFECYCLE.md).
