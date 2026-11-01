# Control latency and network-fault checks

The reported summer symptom was a phone GUI taking 20–30 seconds to report that
it and the DSP were synchronized. Readbacks, server work queues and guest Wi-Fi
interference are possible contributors. The implementation has changed since
then; these tests assess the current build, not the historic RF conditions.

## Separate the paths

| Path | Current evidence |
| --- | --- |
| Browser ↔ HTTP server | Controlled real-Chromium tests inject HTTP delay/jitter, reset replies and 30 second stalls. |
| Server work admission | Real-socket Java tests hold transactions/active/pending exchanges and prove that interactive requests reject before sending, rather than waiting behind them. |
| Server ↔ physical DSP | A separate allowlisted read-only run measures actual FIR408 responses and preserves its configuration. |

Existing queued APIs still support long operations. The interactive browser
path opts out of waiting behind them; it does not make a flash operation or a
bad network intrinsically faster. One started short meter/keepalive exchange
may finish before its reserved command. Accepted controls still wait for ACK.
Normal controls require no automatic parameter readback or full settings dump.

## Controlled browser results

The retained local Chromium run used a synthetic HTTP API, not the old Raider
emulator. Representative observations:

| Injected condition | Observed behavior |
| --- | --- |
| 20..180 ms HTTP jitter plus 60 ms backend work | About 237 ms until visible feedback; exactly one logical write. |
| Reply held 30000 ms after processing | About 2026 ms until UNCERTAIN; one logical write; further writes blocked. |
| Cached preflight held 30000 ms | About 1526 ms until timeout; zero control writes submitted. |
| TCP reset before a POST reply | Chromium itself repeated the HTTP request; the same request key executes only one logical operation. |

The browser does not automatically replay a control. Server request-key tests
verify concurrent duplicates, changed bodies/targets, retained exceptions,
capacity, completion-relative expiry and no lock across device I/O. This is
bounded process-local protection, not durable exactly-once delivery.

The fresh Karaf check also runs the actual packaged phone page through the
real HTTP/API/runtime/core path to a controlled loopback DSP. It verifies ACK-only
control, timing feedback, resource policies, duplicate suppression and zero
automatic block reads for that command. Its measured synthetic-peer response
is not a prediction of physical DSP response time.

## Physical FIR408 check, 2026-11-01

The current core completed a 61.965 second allowlisted read-only run against
FIR408 V0113P at 192.168.0.166:9761:

- 200 meter requests, zero missing samples, one reconnect.
- 368 outgoing requests, 370 incoming frames and 368 matching responses.
- Matching-response p50 56.105 ms, p95 65.876 ms, p99 69.638 ms, maximum 99.461 ms.
- All 32 configuration blocks byte-identical before, after and across reconnect.
- No guard errors, open owned sockets or remaining proxy threads; Java stopped.

The configuration digest remained
`b0013872dd13b9d52fc266dddfd742114c549b5ce964bd472dc77fb84adc9cd2`.
Only handshake/discovery, known read windows, parameter reads, meters and
keepalive were allowed. No audio/preset/FIR control write was performed.
This measures the server-to-DSP path today, not the phone's Wi-Fi or a crowded
live venue. No multi-second delay occurred during this bounded run.

## Reading the timings

The app's control-response time includes its cached preflight and command HTTP
roundtrip. `Server-Timing: backend;dur=...` includes server parsing, request-cache
waiting and DSP service/queue time for that HTTP attempt. A cached duplicate
measures the replay handler, not its original operation. Browser/network/JSON
and scheduling overhead account for the rest; subtracting the numbers is not
a direct Wi-Fi measurement. If a reply is lost, its server timing may be unknown.
A client timeout reports uncertainty and is not proof of remote cancellation.

Original probe inputs, wire/configuration snapshots, artifact identities,
controlled profiles, browser screenshots, RED/GREEN regressions and final CI
reports are retained outside source in
`Archiv/DSP408/controller-mobile-lan-20261101/`. Hardware settings stay local;
Git contains only the measured aggregates and reproducible software tests.
