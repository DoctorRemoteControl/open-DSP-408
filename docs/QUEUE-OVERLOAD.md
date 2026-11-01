# DSP command overload

Each connected DSP has a queue for at most **64 pending commands**, in addition
to its one active command/response exchange. Admission is synchronized and does
not wait for space. Commands already admitted still wait for their normal
serialized exchange and existing response/queue deadlines.

The limit counts commands, rather than payload bytes or HTTP requests. It is
fixed per connection and is not a runtime configuration setting.

## Admission and priority

1. A new command with the same coalescing key as a pending command replaces that
   command before capacity is checked. The newest payload wins. Existing keys
   cover runtime meters, keepalive, block reads, and selected channel controls.
   The active exchange is never coalesced.
2. A command is admitted when fewer than 64 commands are pending.
3. At capacity, a safety command may displace exactly one pending command of
   strictly lower priority. The lowest-priority command is selected; among equal
   priorities, the newest command is displaced to preserve older queued work.
4. If none can be displaced, the incoming command fails immediately. Other
   priorities cannot displace pending work. A queue containing only safety
   commands therefore rejects another distinct safety command.

The existing priority order remains safety, control, keepalive, read, meter.
Protocol commands `0x35` and `0x3F` retain their existing safety classification.
Admission never interrupts or displaces the active exchange.

## Observable failures

Rejected and displaced callers receive `RejectedExecutionException`. The
rejected/displaced command is never transmitted, never receives a successful
completion, and cannot mark its requested setting as applied. Queue saturation
alone does not close a usable DSP connection. Once work drains, that same
connection can accept new commands.

Same-key supersession keeps the existing distinct outcome: the older completion
returns `false`; reliable writes report that the command was superseded. Safety
displacement is an exceptional overload failure, even for best-effort reads or
meter requests.

The HTTP API maps overload to status `503` with a JSON envelope:

```json
{
  "error": "dsp_overloaded",
  "message": "DSP command queue is full (64 pending commands)",
  "status": 503
}
```

The message can instead explain safety displacement. This mapping covers direct
control/read/raw routes, preset export, and overload propagated through textual
commands. The textual command processor continues to render ordinary command
errors as text; it propagates overload so HTTP can return the structured failure.

Multi-command operations can finish earlier commands before a later admission
fails. A `503` is not a rollback guarantee. JSON preset application retains its
failed-operation, completed-operation and emergency-mute details in the `503`
response. Emergency muting is attempted and can itself fail under saturation;
`allChannelsEmergencyMuted` reports the actual result. A client should inspect
state and those details before retrying a multi-command operation.

## Diagnostics

`pendingCommands` and `maxPendingCommands` cannot exceed 64 within a connection.
`activePriority` identifies the additional active exchange. `submittedCommands`
counts submission attempts, including rejected commands. Transmitted and
superseded counters keep their existing meanings; safety eviction is not counted
as supersession. There is no separate overload counter.

## Repeatable verification

Run from `dsp408-parent` in a source copy under `Betriebsdaten`, with the pinned
protocol dependency available through the repository's normal build workflow:

```sh
mvn -B -pl dsp408-core,dsp408-http -am test
```

The focused stress/boundary checks are:

```sh
mvn -B -pl dsp408-core -am \
  -Dtest=DspCommandQueueTest,DspCommandOverloadTest,DspControllerTransportTimingTest,DspCommandProcessorErrorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`DspCommandOverloadTest` uses 256 concurrent producers and an in-memory DSP peer.
It holds one exchange active, verifies exactly 64 admitted and 192 rejected
commands, releases the peer, verifies each accepted command transmits once, and
reuses the healthy session. The test has a 15-second deadline and uses no physical
DSP. `DspCommandQueueTest` also races 800 same-key replacements with 16 safety
admissions at capacity, checking newest values, priority order and explicit
victim failures. `DspControllerTransportTimingTest` uses a loopback TCP peer to
verify rejected gain commands never reach the peer or update cached gain, and
that subsequent acknowledged gain commands work through the same connection.

HTTP contract tests exercise default and selected DSP routes, preset failure
metadata, and the OpenAPI `503` response. These checks complement transport
response/cancellation tests; they do not measure hardware throughput or prove a
physical DSP's response timing under load.
