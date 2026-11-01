# Fresh Karaf integration

The smoke test installs the packaged public features into pristine Apache Karaf
4.4.11. It checks the actual OSGi resolver, Declarative Services activation,
HTTP API, Gogo shell and connection lifecycle. It uses only owned loopback DSP
fixtures. Matrix stays disabled without a homeserver or access token.

## Run locally or in CI

Use Linux, Java 21 and Python 3.12 or later. First install the source-pinned
protocol and all Controller modules into the same Maven repository, using
[`ci/verify.sh`](../ci/verify.sh). The harness does not compile source or use
another checkout's `target/` directories.

```bash
MAVEN_OPTS="-Dmaven.repo.local=/tmp/dsp408-m2" bash ci/verify.sh
python3 integration/karaf-smoke.py \
  --maven-repo /tmp/dsp408-m2 \
  --state-dir /tmp/dsp408-karaf-smoke
```

Use a new or empty state directory for every run. Existing state is rejected and
preserved. The harness downloads the distribution from Maven Central and checks
its pinned SHA256:

```text
fee48696c2b6473d3d1e6b50ed5156f5a61d4445a118b28c3c70462b52685879
```

`--karaf-archive /path/apache-karaf-4.4.11.tar.gz` uses a cached pristine archive
with the same checksum check. No installed Karaf tree, credentials, cache or
configuration is copied. `integration/codex-karaf.py` is the existing workspace
control wrapper, vendored unchanged so standalone checkouts can use it. All
Karaf control uses the copy in the new instance's `bin/`; the first control
command is `doctor`, a deployment dry run records the feature descriptor, and
further doctor reports follow configuration and feature changes.

Optional arguments:

| Argument | Default | Purpose |
| --- | --- | --- |
| `--controller-version` | `0.0.1-SNAPSHOT` | Installed Controller artifact version |
| `--karaf-version` | `4.4.11` | Distribution with a pinned checksum |
| `--timeout` | `180` | Timeout in seconds per Karaf control command |
| `--reconnects` | `3` | Complete fake DSP connection cycles, minimum 2 |
| `--result-json` | none | Additional JSON output destination |
| `--headless-registry` | disabled | API-only managed registry, isolated targets and JVM restart |

The command prints JSON to stdout, progress to stderr, and always writes
`STATE/result.json` after a provisioned run. Exit status zero requires all checks
and process cleanup to succeed. The extracted instance, logs and evidence remain
in `STATE/karaf`; the owned JVM and fake peers stop on success, failure or a
handled termination signal. Before stopping a JVM, the harness verifies its
process command line contains this instance's exact `karaf.home` path. SSH,
HTTP and JMX listeners use free loopback ports; the final report checks that
their ports closed. It never starts or changes the historical workspace Karaf.
If the stop wrapper cannot launch or times out, cleanup records the error and
falls back to signals, checking ownership again before each signal. An unrelated
PID is never stopped. Offline tests exercise both paths with harmless children.

## Headless managed server

Run the API-first acceptance check independently of browser tooling:

```sh
python3 integration/karaf-smoke.py \
  --maven-repo /tmp/dsp408-m2 \
  --state-dir /tmp/dsp408-headless-registry \
  --headless-registry
```

This installs `dsp408-server` into a fresh Java 21 JVM, with no display environment
and `java.awt.headless=true`. The feature's own configuration must produce an empty
managed registry. The test registers loopback devices, requires no unsolicited
connections, connects two devices, removes a blocked third bootstrap and another
live device without interrupting the first, and restarts the complete JVM. Stored
definitions must survive; manually connected devices must remain offline after
restart. `/app/` must return 404 throughout. No browser or Node executable is used.
All ports, owned processes and fixture threads must close on completion.

## What is checked

1. Install `dsp408-runtime` alone and require a published `DspService`. This
   catches missing core dependencies before HTTP or Matrix can supply them.
   With `auto.connect=false`, no fixture receives an unsolicited connection.
2. Install `dsp408-all` and require active API, protocol, core, runtime, shell,
   HTTP and Matrix bundles. Compare every feature bundle's actual Felix cache
   bytes against its installed Maven artifact SHA256.
3. Require the runtime and disabled Matrix services, then exercise HTTP health,
   state, device listing, selected-device routing, settings cache, meters and
   OpenAPI. A registered HTTP servlet provides observable activation evidence.
4. Exercise shell commands through the wrapper, explicitly printing Gogo return
   values with `shell:echo`; batch sessions otherwise omit those values.
5. Start a silent bootstrap, read status while it is blocked, cancel it through
   HTTP and check the service remains published. Fail a bootstrap against a
   peer that closes its transport and check the API remains usable.
6. Repeatedly connect to a delayed peer sending frames in three-byte fragments,
   verify a complete 32-block FIR408 cache, observe central meter responses,
   temporarily lengthen its meter interval to exercise two scheduled idle
   keepalives, restore normal polling, drop its transport, observe disconnected
   state and connect again.
7. Read capabilities without connecting, then connect a separate loopback emulator.
   Exercise ACK-only and verified Out8 gain/mute/phase/delay writes over HTTP,
   enforce evidence/range preflight before transmission, and require a full
   refresh to restore the settings snapshot after parameter writes.
8. Record final memory/thread snapshots, bundle/service state, command and HTTP
   timings, fixture command counts, artifact provenance and shutdown evidence.

All fixtures validate frame length/checksum and bind only to loopback. The
default fixtures accept session, discovery, read and keepalive requests. A
separate explicitly enabled emulator accepts only the four modeled Out8 controls
and mutates synthetic block bytes at literal, independently specified offsets.
Preset, FIR and unmodeled control writes remain rejected and make the run fail.
No fixture forwards traffic to hardware. This validates transport, lifecycle
and API behavior, not acoustic settings or physical decoding accuracy. Intentional cancelled/dropped bootstraps produce
HTTP error responses and log errors retained in the report. Their presence in
a final doctor log summary does not negate the explicitly checked recovery.

Offline provisioning and fixture regressions are separate from the integration:

```bash
python3 -m unittest discover -s integration -p test_karaf_smoke.py
```

## Resolver regression found on 2026-11-01

A pristine runtime-only installation originally failed with
`com.fasterxml.jackson.databind` missing in version range `[2.17,3)` from
`de.drremote.dsp408.core`. Jackson had been declared only in HTTP and Matrix.
The feature descriptor now declares Jackson annotations, core and databind in
`dsp408-runtime`; HTTP and Matrix inherit them through that feature. The smoke
test retains runtime-only installation as the regression check.

The local readiness evidence is under
`Betriebsdaten/DSP408/readiness-87vhn0eq/`: `karaf-runtime-red/result.json`
records the real resolver failure; `karaf-final/result.json` records the first
complete passing run against the clean CI build, including three fragmented
sessions, 26 HTTP requests and a stopped owned JVM. `karaf-complete/result.json`
adds meter polling, all 10 deployed-cache/hash matches, cancellation in 26 ms,
47 HTTP requests and four closed listener ports in a 36.558-second run.
`karaf-review-complete/result.json` verifies the reviewed cleanup and keepalive
changes: 57.692 seconds, 225 HTTP requests, three successful sessions, two idle
keepalive ACKs, all 10 matching deployed bundles and stopped JVM/listeners/fake
threads. The fake keepalive reply is the Controller's expected generic ACK,
`01 00 01 01`; a socket regression checks it explicitly. Physical read-only duration
validation is a separate command and report; this smoke test contacts no DSP
hardware.

## Packaged mobile browser check

The harness also verifies the packaged `/app/` HTML, CSS, modules and icon,
including MIME, CSP and nosniff headers. Its interactive HTTP check sends an
ACK-only command, repeats its request key and requires exactly one wire write
and zero automatic block reads. Foreign-Origin writes fail before effects.

After `npm ci --ignore-scripts` and `npx playwright-core install chromium`, add
`--browser-check` to exercise the actual mobile page against this owned Karaf
and its writable loopback peer. It explicitly connects the fixture, moves Out8
gain, requires an ACK and timing feedback, then disconnects. `browser-ui.json`
and its screenshot are retained in the state directory. No physical DSP is used.
`DSP408_BROWSER_BIN` may select an installed local Chromium-compatible browser.


## Headless registry validation on 2026-11-01

The fresh managed profile passed with Java headless mode, 36 HTTP requests and a
complete owned JVM restart. Registration produced no device traffic; removing a
blocked bootstrap and another live target preserved the remaining connection.
Definitions survived restart without converting a manual connection into
auto-connect. The optional UI returned 404 throughout. Both JVMs, listeners and
fixture threads stopped. The first run exposed an SCR activation overload: the
single-argument internal helper was selected instead of the BundleContext entry.
The annotated activation method now has a distinct name; default bundle-data
storage is exercised by the integration rather than hidden behind an explicit
registry path. Independent review also exposed a static-to-managed admission
race; connect and reconnect now validate mode and enqueue atomically, while
waiting outside the component monitor. Two loopback regressions failed on real
hidden TCP connections before that fix and pass afterward.
