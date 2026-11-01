# Headless Java server

The server runs on Java 21 without a display, browser, Node.js or desktop session.
Its primary interface is HTTP/JSON. One JVM can manage multiple t.racks DSP408
and FIR408 processors; each registered device has its own connection, settings
cache, meter polling and command admission. Registration does not contact a DSP
unless `autoConnect` is explicitly enabled. Other manufacturers are outside the
current protocol contract.

## Install

Build the pinned protocol and Controller sources with [`ci/verify.sh`](../ci/verify.sh).
Install the resulting features in Apache Karaf 4.4.11 on Java 21:

```text
feature:repo-add mvn:de.drremote/dsp408-features/0.0.1-SNAPSHOT/xml/features
feature:install dsp408-server
```

For unattended operation, set `KARAF_OPTS` to include
`-Djava.awt.headless=true` and run Karaf with its normal service/start mechanism.
The feature supplies the runtime and HTTP API; shell and Matrix adapters are
separate optional features. No browser executable or JavaScript tooling is
needed by the installed server. The optional interface is enabled separately by
`feature:install dsp408-web`. `dsp408-all` also enables it.

On a fresh instance, `dsp408-server` creates a managed configuration with zero
devices. Existing operator configuration takes precedence over feature defaults.
For an existing static installation, deliberately configure PID
`de.drremote.dsp408controller` before starting the managed server:

```properties
registry.mode=managed
registry.file=/absolute/persistent/path/device-registry.json
auto.connect=false
```

Use one authoritative `.cfg` or typed `.config` file per PID, rather than leaving
competing configuration files. Static mode remains available with
`registry.mode=static`, `dsp.id`, `dsp.ip`, `dsp.port` and `dsps`; registry mutations
are unavailable in static mode. Switching to managed mode does not automatically
import the old static endpoints. Register the intended devices explicitly.

`registry.file` should point to a writable persistent location owned by the
service account, outside source checkouts and disposable bundle caches. If
omitted, the runtime uses the OSGi bundle data directory. That default survives a
normal JVM restart, but deleting Karaf's data/cache or uninstalling the bundle can
remove it. Preserve the registry alongside service configuration during upgrades
and back up its containing volume. Each registry file must have one owning server
process; the external-change check does not lock out another JVM. Do not share
a registry file between active servers. A corrupt registry prevents activation; it is
preserved for repair rather than replaced with an empty list.

The current distribution is a Java/OSGi application, deployed through Karaf. It
is not a standalone executable JAR. Other OSGi hosts need their own dependency
and Servlet API compatibility validation.

## Register and operate devices

The examples use a local API on port 8181. Use the listener address and port of
your own server. Start by checking readiness and the empty registry:

```sh
api=http://localhost:8181/api/v1
curl --fail-with-body "$api/health"
curl --fail-with-body "$api/devices"
curl --fail-with-body "$api/dsps"
```

Register a FIR408 without opening its TCP connection:

```sh
curl --fail-with-body -X POST "$api/devices" \
  -H 'Content-Type: application/json' \
  -d '{"id":"main","host":"192.168.0.166","port":9761,"library":"fir408","autoConnect":false}'
curl --fail-with-body "$api/devices/main"
```

`id` and `host` are required. Defaults are port `9761`, library `auto` and
`autoConnect:false`. Libraries are `auto`, `fir408` and `dsp408`. IDs are normalized
to lowercase and limited to 64 characters. Hosts are syntactically validated
without DNS lookup or network discovery. Choose the known library explicitly
when the hardware profile is known.

A new registration returns `201` with `{"device":{...},"created":true}`. Repeating
the same definition returns `200` and `created:false`. A different definition
under the same ID, or an already registered normalized host/port, returns `409`.
Definitions are immutable while registered: remove and register to change an
endpoint or its auto-connect policy. DNS aliases can still name the same physical
processor; avoid registering that processor through multiple aliases.

Connect and inspect that specific device:

```sh
curl --fail-with-body -X POST "$api/dsps/main/connection/connect"
curl --fail-with-body "$api/dsps/main/state"
curl --fail-with-body "$api/dsps/main/capabilities"
curl --fail-with-body "$api/dsps/main/settings-cache"
curl --fail-with-body "$api/dsps/main/meters"
```

Connect performs protocol bootstrap and one complete parameter dump. Subsequent
state, capabilities and meter GETs use cached server data. Registering or removing
another device preserves this connection. Connection failure does not remove the
registration or block unrelated device state. With `autoConnect:true`, the device
connects after registration and retries according to the runtime's reconnect
policy, including after a server restart. A manual connection is not silently
persisted as auto-connect.

Managed mode has no implicit default DSP. Use `/dsps/{id}/...` for device
operations; an unqualified target route such as `/state` returns `409`. The global
`/dsps` response contains an empty `defaultDsp` and the current instance list.
Unknown device IDs return `404`.

Disconnect or remove explicitly:

```sh
curl --fail-with-body -X POST "$api/dsps/main/connection/disconnect"
curl --fail-with-body -X DELETE "$api/devices/main"
```

Removal returns `204` with no body, closes that target's resources and persists
its removal. Unknown IDs return `404`. Changes are acknowledged only after the
registry has been stored. Storage errors do not claim success or silently discard
the previous registry. Reusing a removed ID creates a new target; existing
parameter request keys cannot replay a response from its old runtime.

## Control latency and deployment scope

The [parameter API](PARAMETER-API.md) retains its firmware-aware write gates.
Normal interactive clients can use `verify:false` and `Prefer: wait=0` for
ACK-only control with immediate busy rejection. Full refresh and per-parameter
readback remain explicit operations. Registration itself does not run a complete
DSP synchronization. There is no new queue shared between all processors.

Configure `org.ops4j.pax.web` with the intended LAN listening address and HTTP
port. The API currently assumes a trusted local network; it has no account/login
system. Explicit foreign-Origin browser mutations are rejected, including device
registration and deletion. Native API clients without an Origin header are
supported. This origin policy does not authenticate LAN clients. Remote access
and authentication require a separate deployment design.

## Verification

The [headless integration check](KARAF-INTEGRATION.md) starts an actual Java 21
Karaf with `java.awt.headless=true` and no display environment, installs only
`dsp408-server`, and verifies empty startup, registration without network traffic,
independent loopback connections, cancellation on removal and persistence across
a complete JVM restart. `/app/` must remain unavailable throughout. It uses no
physical DSP and performs no audio writes. CI runs this separately from the
optional browser checks.
