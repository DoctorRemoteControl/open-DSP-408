# Read-only FIR408 validation

The physical harness tests the Controller's FIR408 bootstrap, fresh block reads,
meter traffic and repeated connections. It is intended for the connected
FIR408 V0113P, not a firmware compatibility claim for every DSP408 model.

## Run

Build the Controller and pinned protocol first, then select an explicit target:

```sh
python3 integration/live_readonly.py --host 192.168.0.166 \
  --duration-seconds 1800 --reconnect-seconds 300 \
  --output /absolute/path/to/new-validation-state
```

Use a new output directory outside the source checkout. `--duration-seconds 0`
performs bootstrap and two complete configuration dumps without a meter soak.
The tool copies the tested JARs before launching Java, so another build cannot
replace its running artifact inputs. Pass `--maven-repo` if the Jackson JARs are
in a separate Maven repository. Java21, Maven-built core/protocol artifacts and
Python3 are required.

The target must have no other controlling application connected during this
single-client test. A configuration difference fails validation; it is never
written back automatically.

## Wire contract

An owned loopback proxy buffers and validates every outgoing frame before it
reaches the target. Its exact allowlist permits only the observed handshake,
startup status/metadata, known FIR startup windows, parameter/preset-name reads,
meters and keepalive. Audio controls, preset recall/store, PIN login, FIR writes
and unknown request layouts are rejected. Checksums and declared lengths are
validated. The proxy is an allowlisting test tool, not a general protocol proxy.

The Java worker reads all 32 configuration blocks before/after and periodically
during the run. It compares exact bytes and records their concatenated SHA-256.
Every five minutes by default it reconnects and compares a fresh full dump.
Resource samples include heap usage and DSP thread count. Heap allocation can
rise between garbage collections; a rising sample alone does not prove a leak.

Output includes `before.json`, `after.json`, `result.json`, `resources.json`,
`wire.ndjson`, `artifacts.json` and `guard-report.json`. The guard records command
counts, matching-response latency percentiles, errors and cleanup state. The
worker/proxy stop on completion or interruption. Keep these artifacts alongside
the source revision when reporting a device problem.

## Initial measured check, 2026-11-01

The connected device identified itself as **FIR408 V0113P**. Bootstrap plus two
full 32-block dumps produced 100 outgoing and 100 incoming frames, with no guard
errors. Median matching response55.693 ms, p95 66.213 ms, maximum 93.043 ms. Both
configuration dumps matched:

```text
b0013872dd13b9d52fc266dddfd742114c549b5ce964bd472dc77fb84adc9cd2
```

The tested core JAR's 117 class files were subsequently compared against the
staged/verified stability commit `4c51a6d`: every class hash matched. Runtime
artifact SHA-256 and complete local evidence are retained separately under the
workspace's validation state directory. No audio, preset or firmware setting was
changed by this check. The duration and final-build results follow below.

## Completed duration run

The stability build ran for **1802.945 seconds** (30 minutes), making **6688 meter
requests** and **five reconnects**, with **zero missing meter samples**. All 32
blocks remained byte-for-byte identical across the periodic and final dumps.
The guard recorded 8056 requests and 8058 responses; the two extra incoming frames
were the initial device-address/session-ready announcements. Every request had
a matching response, and no command outside the exact read-only allowlist passed.

| Matching-response latency | Measured |
| --- | ---: |
| Median | 56.085 ms |
| p95 | 63.077 ms |
| p99 | 70.152 ms |
| Maximum | 107.337 ms |

All 58 resource samples showed exactly three DSP threads and nine JVM threads.
Heap usage ranged from about 4 MB to 600 MB between collections; later samples
returned to about 29 MB. These are allocation/GC observations, not a proof that
all workloads are free of memory leaks. The process and owned sockets stopped.

## Final product build

The clean standalone build of product commit `f9cd31b` embeds the complete
Controller/protocol source revisions with both dirty flags false. That build
also passed a separate **61.892-second** hardware run: 199 meter requests,
one reconnect, 367 requests and 369 responses, zero missed samples, and the same
32-block configuration digest. Median response 56.201 ms; p95 66.684 ms; maximum
99.172 ms. The final proxy reported zero live threads and open sockets; its Java
process stopped. Subsequent CI fixes affect workflow/build-tool selection and a
test-peer lifetime, not the product's bytecode.

Original snapshots, wire logs, resource samples, artifact identities and cleanup
reports are retained locally in:

```text
Archiv/DSP408/controller-readiness-20261101/
```

The Git repository records these measured aggregates rather than publishing the
live device's full configuration. The run covers this FIR408 V0113P and this
host/network path. It does not validate audio writes, preset flash timing or
other firmware. No audio, preset or firmware settings were changed.

## Parameter API readback check, 2026-11-01

The parameter API build was also checked directly against the connected
FIR408 V0113P through the same exact read-only guard. Its captured profile
reported 48 parameter descriptors. All **38 supported readbacks** completed
with fresh receive revisions; the **10 inferred FIR mute mappings** rejected
readback before sending. Two complete 32-block snapshots and every intervening
received parameter block agreed byte-for-byte. The configuration digest remained
`b0013872dd13b9d52fc266dddfd742114c549b5ce964bd472dc77fb84adc9cd2`.

The check took **8.304 seconds**, with **138 outgoing requests**, **140 incoming
frames** and **138 matching responses**. Median response was 56.807 ms, p95
66.401 ms and maximum 102.162 ms. The guard recorded no errors, no open owned
sockets and no remaining proxy threads; Java stopped. No control, preset or
firmware write passed the guard.

This checks actual-device identity, block freshness, readback execution and
configuration preservation. A stable read value alone does not independently
establish its physical meaning or validate an audio write. The supported
location evidence remains the captured protocol evidence; new write-result
failure paths are exercised by controlled loopback peers.

The older Raider meter emulator is not used as a reference for the physical
FIR408 or to authorize mappings. It advertises DSP408 V0104, supplies static
metadata/block responses and does not handle gain/mute/phase/delay control
writes. The new Karaf test peer is likewise a bounded software fixture, not a
complete hardware model. Its results are explicitly separate from this live
read-only check.

The exact probe source, copied JARs, block snapshots, readback values and wire
log are preserved locally under:

```text
Archiv/DSP408/controller-parameter-api-20261101/
```
