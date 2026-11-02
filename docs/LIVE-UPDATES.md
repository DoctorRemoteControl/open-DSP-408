# Live updates and conditional controls

The Java server publishes its cached device state to browser or native clients.
It remains fully headless. The optional phone interface uses the same public API
as other clients. New authentication and user roles are deferred; the existing
local-network deployment and same-origin mutation checks remain in place.

## Subscribe

```sh
curl -N 'http://localhost:8181/api/v1/events?dspId=main'
```

Omit `dspId` for a device-list-only stream. The exact route uses the standard
[OSGi HTTP Whiteboard async property](https://docs.osgi.org/specification/osgi.cmpn/8.0.0/service.http.whiteboard.html)
and Servlet nonblocking output. Existing API and web aliases keep their routes.

| Event | JSON data |
| --- | --- |
| `devices` | Existing `{defaultDsp,dsps}` instance envelope |
| `state` | `ControlStateDto`: target, incarnation, revision, controls, capabilities and cache status |
| `meters` | Existing `{dspId,connected,meters}` cached meter envelope |
| `heartbeat` | `serverTimeEpochMs` |
| `removed` | Original target ID/incarnation and removal/replacement message; stream then closes |

Initial subscriptions and reconnections receive fresh cached data. Event IDs
are delivery cursors; the server does not retain durable history or replay
commands from `Last-Event-ID`. The browser uses `instanceId` and `revision` to
reject old data. A removed ID reused for a different runtime cannot reuse its
old stream or conditional write authority.

One sampler reads cached controls/meters per active target about every 200 ms,
with device membership about every second and heartbeats every 10 seconds. It
never opens a DSP connection, starts another physical meter poller or reads DSP
parameter blocks. Each response coalesces the latest pending event of each
fixed kind; one frame may be in flight. Servlet `WriteListener` readiness gates
chunked writes and flushes, so a slow browser does not block the sampler. There
are at most 128 subscribers; capacity returns 503 before a new stream starts.
Blocked pending output closes after 30 seconds without write progress. Disconnect,
async error/timeout, deactivation and the last subscription release resources.

Unknown devices return 404; malformed IDs return 422. Existing providers without
control snapshots can reject the stream with 409. The interface supports a
cached polling compatibility mode; current servers use streaming.

## Read a control snapshot

```sh
curl -i http://localhost:8181/api/v1/dsps/main/control-state
```

The response includes a strong `ETag`, for example `"INSTANCE.7"`, and
`instanceId` plus numeric `revision` in the JSON. Versions advance for cached
control/capability/connection/cache changes and consume accepted typed writes,
including same-value writes. Meter samples do not advance the control revision.
Snapshot reads stay in RAM and remain responsive while device I/O is pending.

## Submit a conditional edit

Use the ETag from the snapshot on the same concrete device:

```sh
curl -X POST http://localhost:8181/api/v1/dsps/main/parameters/write \
  -H 'Content-Type: application/json' \
  -H 'If-Match: "INSTANCE.7"' \
  -H 'Prefer: wait=0' \
  -H 'Idempotency-Key: unique-operation-00000001' \
  -d '{"channelId":"out8","parameter":"gain","value":-6,"verify":false}'
```

The runtime compares the condition after bounded transaction admission and
immediately before transmission. A mismatch returns 412 with:

```json
{
  "error": "conflict",
  "errorCode": "revision_conflict",
  "message": "Control state changed; inspect current state before retrying.",
  "transmitted": false,
  "current": {"dspId":"main","instanceId":"INSTANCE","revision":8}
}
```

The actual `current` object contains the complete control snapshot. Busy
admission can reject with 503 before comparison. Normal result status remains
ACKNOWLEDGED, VERIFIED, REJECTED or UNCERTAIN as described in the
[parameter API](PARAMETER-API.md). No extra physical readback is implied.

A request key binds the body, exact condition and concrete runtime identity.
A duplicate same-key request replays its recorded outcome before reevaluating
the condition, so losing an HTTP response does not send a second DSP command.
Changing the condition/body/target under that key conflicts. Protection is
process-local and expires with the existing request-cache policy.

Native clients can omit `If-Match` for existing unconditional behavior. Strong
concurrency guarantees apply to participating conditional writes. Device edits
outside the server are known only after the server receives their data; this
API does not continuously reread the full device configuration. A cache event
also does not certify uncertain physical completion.

## Reproduce validation

```sh
python3 integration/live-control-smoke.py \
  --archive dsp408-parent/dsp408-server/target/dsp408-server-0.0.1-SNAPSHOT.tar.gz \
  --state-dir /absolute/new/live-control-test \
  --browser-check
```

This boots the actual packaged JVM with `webEnabled:true`, two EventSource
subscribers and real desktop/phone Chromium contexts against a literal loopback
DSP fixture. It checks timely state push, one physical meter poller, zero
subscriber-induced block reads, stale edits, deduplicated retries and runtime
replacement. All audio writes are synthetic. Use `--allow-development-build`
only for intentional local development packages. CI also retains the separate
API-only package and service lifecycle checks.
