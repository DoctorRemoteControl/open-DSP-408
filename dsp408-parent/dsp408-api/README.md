# dsp408-api

Public Java API for the DSP408/FIR408 controller.

## Bundle

- Maven artifact: `de.drremote:dsp408-api`
- OSGi symbolic name: `de.drremote.dsp408.api`
- Bundle name: `DSP408 API`
- Exported package: `de.drremote.dsp408.api`

## Purpose

This bundle contains the stable service contract used by the other bundles. It does not connect to the DSP by itself and does not contain protocol encoding logic. Runtime implementations, HTTP servlets, Karaf shell commands and the Matrix bot depend on this API.

## Main Types

- `DspService` - main control/query interface.
- `DspParameterApi` - additive firmware capabilities and structured gain/mute/phase/delay writes.
- `CapabilitiesDto` / `ChannelCapabilitiesDto` / `ParameterCapabilityDto` - immutable profile, range, resolution and mapping-evidence snapshots.
- `ParameterWriteRequestDto` / `ParameterWriteResultDto` / `ParameterReadbackDto` - strict values, explicit normalized values, ACK versus verified readback, rejection and uncertain completion.
- `DspInstanceDto` - configured DSP instance summary for multi-DSP setups.
- `StateDto` - current connection, device, channel, full 8x4 matrix and all 104 PEQ bands.
- `MatrixOutputDto` / `MatrixCrosspointDto` - routed inputs plus all four
  decoded crosspoint gains per output, including device-confirmation metadata.
- `PeqChannelDto` / `PeqBandDto` - every input/output PEQ value, bypass flag,
  per-field device confirmation and update source.
- `ChannelDto` - currently exposed per-channel state.
- `InputGateDto` - input-gate values with independent device-confirmation and
  update-source fields for threshold, hold, attack and release.
- `InputCompressorDto` - FIR408 input-compressor values including frequency,
  type and per-field device confirmation.
- `LimiterDto` - persistent output-limiter values plus the runtime-active flag.
- `MeterDto` - decoded runtime level and output limiter state, including separate
  flags for physical payload receipt, channel-map trust, level calibration and
  limiter-map trust.
- `MeterStateDto` - all runtime meters plus the meter status byte.
- `DeviceInfoDto` - device version and system info payload.
- `BlockDto` - cached or freshly read parameter block.
- `RawTxDto` - payload/frame hex returned by write operations.

## Covered Operations

`DspService` exposes the decoded DSP408 functions currently supported by the core:

- connection lifecycle and login
- device info, state, blocks and meters
- gain, mute, channel name, phase and delay
- preset load and preset-name read
- matrix routing and crosspoint gain
- crossover high-pass and low-pass
- output PEQ (including state-preserving bypass), input PEQ and input GEQ
- preflighted all-PEQ bypass for all 104 FIR408 bands
- input gate, FIR408 input compressor and output limiter
- test tone generator
- FIR408 processing mode, FIR generator and external FIR upload
- multi-DSP listing and per-DSP service selection
- shell, Matrix and volume-room command execution helpers

## Not In This Bundle

- No TCP socket handling.
- No DSP frame encoding/decoding.
- No OSGi component implementation.
- No HTTP, shell or Matrix transport.

Functions that are still `not_decoded` in the protocol libraries are intentionally not exposed as high-level methods.

## Parameter capabilities and write outcomes

See the [parameter API contract](../../docs/PARAMETER-API.md) for supported
profiles, evidence limits and HTTP examples. The default methods in
`DspParameterApi` preserve older-provider linkage: an older implementation
returns unavailable capabilities and throws `UnsupportedOperationException` for
the new write operation. Value fields snapshot finite numbers and booleans, and
all catalog lists are defensive copies. `VERIFIED` requires matching normalized
and readback values; an ACK alone produces `ACKNOWLEDGED`.
