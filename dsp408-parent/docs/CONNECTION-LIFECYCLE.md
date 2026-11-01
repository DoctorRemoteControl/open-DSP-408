# Connection lifecycle and failure handling

## Ownership and readiness

`DspRuntimeComponent` publishes its OSGi service without waiting for a device.
`DspConnectionManager` owns one bounded lifecycle worker per configured device.
Automatic activation and configuration changes schedule connection work there.
Explicit `connect()` callers wait for the same in-progress attempt; repeated
connect calls do not replace a healthy connection. Explicit `reconnect()`
requests a replacement. Disconnect and shutdown invalidate the attempt, close
its socket and stop its work; a late result cannot restore a disconnected cache
or restart polling.

Core `isConnected()` requires a completed handshake and observed startup
sequence. Runtime settings synchronization follows bootstrap and reports its
own state through `SettingsCacheDto`; a connected transport alone does not
mean that every parameter block is available. Check `ready` and `phase` before
using a complete settings snapshot. Cached values retained after disconnect
are historical values.

Every socket reader and command-worker frame writer owns its original streams
and connection token. Cleanup
and late callbacks from an old connection cannot close a replacement session.
The socket is closed before its stream wrappers. Invalid checksums are logged
and discarded before decoding or command completion.

## Command completion

One command worker sends a request and waits for its matching response before
starting the next command. Generic ACKs contain no request identifier. Writes
and keepalives are therefore never retried on the same session after missing
an ACK: the session is invalidated and queued commands fail. The write may
already have been applied. Read fresh device state after reconnecting before
choosing whether to repeat a change; there is no automatic rollback.

Selector-bearing reads use one 1000 ms response window without retransmission.
An unanswered required read fails and closes the session. A delayed duplicate
read could otherwise overwrite cache data after a later write. Runtime meters remain best-effort: an absent
sample is dropped after a short recovery pause. Repeated outgoing meter frames
do not establish health; a lack of verified responses allows a keepalive probe
even while polling continues. Its missing ACK invalidates the connection.

Stopping a meter scheduler interrupts its waiter and removes pending meter
commands, including a sample selected by the worker whose transmission has not
yet started. Cancellation and transmission start are serialized by the queue.
An exchange whose transmission has started may finish before subsequent control
work; it cannot be unsent. Interrupted pending control requests are removed too.
If a caller abandons an active control request, the session is invalidated.

| Operation | Response budget |
| --- | ---: |
| Ordinary command / ACK | 500 ms |
| Required read | 1000 ms |
| Runtime meter sample | 150 ms |
| Preset recall | 6000 ms |
| Preset store | 10000 ms |
| FIR generator | 3000 ms |
| FIR transfer | 1000 ms |

The store budget accommodates the existing approximately eight-second flash
silence documented by the runtime. This margin is a conservative software
choice tested with a delayed simulated ACK, not a new hardware measurement.
The queue wait also includes the command response budget. No physical preset
store was performed during this change.

## Cache and HTTP behavior

Preset apply defaults to `refresh=true` on both global and named-device routes.
Explicit `refresh=false` skips the full dump; decoded fields may be updated,
but unconfirmed writes and older raw blocks are not an authoritative snapshot.
HTTP meter polling uses the centrally maintained cache. While disconnected,
`POST /meters/read` returns `409 dsp_not_connected` without the repeated
exception/stacktrace path seen in the historical logs.

## Coherent bundles and deployment evidence

API, core, runtime and integration bundles must come from the same build.
The shared `dsp408-protocol` bundle is also required by the core feature.
Older deploy/cache JARs can share `0.0.1.SNAPSHOT` while exposing incompatible
classes; a source commit date or bundle version alone cannot identify them.
Record the source revision, whether its tree was modified, SHA-256 hashes of
the built JARs and the loaded bundle locations/wiring when diagnosing a
deployment. Follow the installed runtime's own deployment instructions.

This refactor does not deploy, refresh or restart Karaf. An old mixed bundle
installation is not repaired merely by building the new source. The retained
August network-unreachable/timeouts also require a reachable host route and
correct target IP independently of these lifecycle fixes.

## Verification limits

Regression tests use loopback peers, controlled delays and synchronization
latches. They exercise ACK loss, cancellation, stale readers, corrupted frames,
blocked bootstrap, duplicate connects, cache invalidation and actual HTTP route
behavior. They do not prove firmware timing on every DSP version or identify
the exact JARs loaded during the historical incidents. Validate a coherent
installation separately before relying on it for live operation.
