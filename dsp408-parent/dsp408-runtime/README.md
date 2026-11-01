# dsp408-runtime

OSGi Declarative Services runtime implementation of `DspService`.

## Bundle

- Maven artifact: `de.drremote:dsp408-runtime`
- OSGi symbolic name: `de.drremote.dsp408.runtime`
- Bundle name: `DSP408 Runtime`
- Provides service: `de.drremote.dsp408.api.DspService`

## Purpose

This bundle wires `dsp408-api` to `dsp408-core`. In managed mode it starts with
zero DSPs and persists API-managed device definitions, with one isolated runtime
per registered ID. Static compatibility mode owns a default `DspController` and
additional configured controllers. Select concrete targets with `DspService.forDsp(id)`.
See [headless server](../../docs/HEADLESS-SERVER.md) for the managed API and storage contract.

## Main Classes

- `DspRuntimeComponent` - OSGi service facade and named DSP pool.
- `DspConnectionManager` - single lifecycle worker, coalesced connects, cancellation and reconnect scheduling.
- `DspSettingsCache` - independent settings synchronization and cancellation-safe cache status.
- `DspTransactionCoordinator` - coordinates foreground work with central meter polling.
- `Dsp408Configuration` - metatype configuration for default DSP IP/port, additional DSPs and startup behavior.
- `VolumeRoomHandler` - grouped InA-InD volume helper used by HTTP, shell and Matrix.

## Configuration PID

`de.drremote.dsp408controller`

## Configuration Properties

- `registry.mode` - `static` (compatibility default) or `managed` (persistent API registry, no implicit target).
- `registry.file` - managed registry JSON path; empty uses OSGi bundle data. Set an external persistent path for deployments.
- `dsp.id` - default DSP id, default `main`.
- `dsp.ip` - default DSP IPv4 address, default `192.168.0.166`.
- `dsp.port` - default DSP TCP port, default `9761`.
- `dsp.library` - protocol library selected before the startup dump: `fir408`, `dsp408` or `auto`.
- `auto.connect` - schedule configured DSP connections after activation; activation does not wait for a device.
- `auto.read.on.connect` - compatibility flag; every connection performs a verified full parameter dump.
- `volume.step.db` - default grouped volume step size.
- `dsps` - optional additional DSPs as `id=ip` or `id=ip:port`.

The Java configuration methods use underscores, for example `dsp_id()`. In Karaf configuration those names are written with dots, for example `dsp.id`.

## Responsibilities

- Open and close TCP connections.
- Maintain one `DspState` per configured DSP.
- Translate API calls into core controller calls.
- Return DTOs and raw transmitted frame info.
- Provide shell-compatible and Matrix-compatible command execution helpers.

## Multi-DSP

In static mode, the old API methods target the default DSP. In managed mode,
use the device registry API and select an explicit ID; there is no default DSP. Use `getDspInstances()` to list configured devices and `forDsp("id")` to target a specific one.

Example configuration:

```text
dsp.id = main
dsp.ip = 192.168.0.166
dsp.port = 9761
dsp.library = fir408
auto.connect = true
auto.read.on.connect = true
dsps = normal408=192.168.0.170:9761,fir408=192.168.0.166:9761
```

## Build

From `dsp408-parent`:

```bash
mvn -pl dsp408-runtime -am test
```

## Connection status

Automatic connect and configuration-driven reconnect run on the device lifecycle
worker. Explicit `connect()` waits for the shared attempt, while repeated calls
leave a ready session intact. `disconnect()` cancels current bootstrap work.
Status reads do not wait for network I/O or a complete parameter dump.

`isConnected()` reports completed protocol bootstrap. Check
`getSettingsCacheStatus().ready()` separately when a complete settings snapshot
is required. Disconnected cached values remain available as historical state.
See [connection lifecycle](../docs/CONNECTION-LIFECYCLE.md) for completion and
readback semantics.

## Notes

The runtime does not expose a user interface by itself. Install `dsp408-shell`, `dsp408-http` or `dsp408-matrix` on top of it for interactive control.
