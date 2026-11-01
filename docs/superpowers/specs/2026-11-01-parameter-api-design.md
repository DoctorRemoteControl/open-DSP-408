# Firmware capabilities and explicit parameter-write outcomes

The user approved improving the API after the recommendation to start with
capabilities and clear write outcomes. This increment covers gain, mute, phase
and delay. Existing v1 endpoint paths and response shapes remain unchanged.
Maintained text is English. No physical audio/preset/FIR write is part of testing.

## API contract

Add GET /api/v1/capabilities and POST /api/v1/parameters/write, with the existing
/dsps/{dspId} prefix variant. The write body is {channelId,parameter,value,verify};
verify defaults to true. Numeric values must be finite and within gain[-60,12]dB
or delay[0,680]ms; gain uses 0.5 dB steps up to -20 dB and 0.1 dB above, delay uses 1/96 ms.
Return requested and normalized values explicitly. Mute/phase require booleans.
Unknown/invalid input fails before effects. Gain/mute/phase/delay are this
catalog's declared scope; other existing typed controls retain their endpoints.

Capabilities describe all 12 channels, units, limits, quantization, write/read
mapping evidence and readback support. Firmware profile IDs are exact
DSP408 V0104 and FIR408 V0113P; unknown identities cannot use the new write API.
Disconnected/unknown identities never advertise writable=true. Capabilities are
RAM-only and never bootstrap or query hardware. Do not copy/synchronize protocol
JSON; use the bundled layouts, codec rules and existing evidence policy.
FIR mute readback is observed only for InD and Out8, not the other inferred
channels. DSP408 delay readback on InB and InC is predicted and must also be
excluded. Existing confirmation flags must not contradict that evidence.
A potentially transmitted parameter write marks the full settings snapshot
stale, including after one verified value; only a full refresh restores ready.

New result statuses: ACKNOWLEDGED, VERIFIED, UNCERTAIN, REJECTED. An ACK is not a
readback. VERIFIED requires a fresh receive revision, a known mapping and a
normalized-value match. The default verify=true rejects unsupported readback
before sending; verify=false returns ACKNOWLEDGED only after ACK. Missing ACK,
failed readback and mismatch retain honest uncertainty; there is no retry or
rollback. Admission rejection is definite REJECTED/503; a readback overload after
ACK is UNCERTAIN/503. Invalid body/range stays422, disconnected/unsupported409,
uncertain I/O502, mismatch409. Results carry requestId,dspId,channelId,parameter,
requestedValue,normalizedValue,status,acknowledged,nullable transmitted,
optional readback,errorCode,message,completedAtEpochMs. Request IDs are correlation
only, not idempotency keys. Transmitted is null when sending may have occurred.

The core session object captures its original client/library/identity. It never
uses a replacement target after reconnect. No lifecycle lock is held over I/O.
Cached acknowledgements are published only under a short current-client guard;
readback snapshot/revision checks are also guarded. Runtime transactions pause
meters around write+readback; legacy concurrent writers may produce a mismatch
rather than an atomicity guarantee. SSE/revisions/idempotency and generalized
batch transactions are separate future increments.

## Shared interfaces and ownership

API DTOs and default methods are coordinator-owned. Add DspParameterApi with
getCapabilities() and writeParameter(ParameterWriteRequestDto); DspService extends
it. Default methods retain older-provider binary compatibility (empty/unavailable
capabilities, unsupported write). Existing method signatures are unchanged.

Core owner provides enum DspParameter(parse,id), DspParameterStep(fromInclusive,
toInclusive,step), DspParameterCapability(parameter,channel,valueType,unit,minimum,
maximum,resolution,writable,readbackSupported,writeEvidence,readEvidence,reason),
DspParameterCatalog(deviceVersion,libraryType,connected,firmwareRecognized,
parameters,capability,normalize), DspParameterReading(value,blockIndex,
receiveRevision,observedAtEpochMs), and bound DspParameterSession(catalog,write,
readBack,isCurrent). DspController exposes parameterCatalog() and
openParameterSession(). Runtime DspParameterCapabilities(controller,idSupplier)
provides get()->CapabilitiesDto. Runtime writer consumes these interfaces.

API DTOs: CapabilitiesDto(dspId,connected,firmwareRecognized,deviceVersion,
libraryType,parameterNames,channels,unavailableReason); ChannelCapabilitiesDto
(id,displayName,index,parameters); ParameterCapabilityDto(name,valueType,unit,
minimum,maximum,resolution,writable,readbackSupported,writeEvidence,readEvidence,
reason); ParameterStepDto(fromInclusive,toInclusive,step).
ParameterWriteRequestDto(channelId,parameter,value,verify) with a three-argument constructor
that defaults verify=true. ParameterWriteResultDto fields as above using enum
ParameterWriteStatus; ParameterReadbackDto(value,blockIndex,receiveRevision,
observedAtEpochMs). Object value fields contain immutable booleans/numbers only.

Coordinator integrates runtime facade fields, all modules, docs, Karaf, Git/CI.
Core/catalog worker owns its new core classes, DspController hooks, mute decoder
correction/tests and runtime DspParameterCapabilities helper. Runtime writer owns
DspParameterWrites(controller,transactions,idSupplier). HTTP worker owns new HTTP
routes/handler/OpenAPI/tests, not API DTOs/runtime facade. Coordinate interface
changes; no overlapping edits, branch changes or source-evidence modifications.
