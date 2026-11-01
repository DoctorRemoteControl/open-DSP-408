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

The Java worker reads all32 configuration blocks before/after and periodically
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
full32-block dumps produced100 outgoing and100 incoming frames, with no guard
errors. Median matching response55.693ms, p95 66.213ms, maximum93.043ms. Both
configuration dumps matched:

```text
b0013872dd13b9d52fc266dddfd742114c549b5ce964bd472dc77fb84adc9cd2
```

The tested core JAR's117 class files were subsequently compared against the
staged/verified stability commit `4c51a6d`: every class hash matched. Runtime
artifact SHA-256 and complete local evidence are retained separately under the
workspace's validation state directory. No audio, preset or firmware setting was
changed by this check. Longer-run and final-build results are recorded after
those runs complete.
