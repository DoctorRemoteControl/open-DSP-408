# dsp408-matrix

Matrix bot integration for DSP408/FIR408 control.

## Bundle

- Maven artifact: `de.drremote:dsp408-matrix`
- OSGi symbolic name: `de.drremote.dsp408.matrix`
- Bundle name: `DSP408 Matrix Bot`
- Requires service: `de.drremote.dsp408.api.DspService`

## Purpose

This bundle lets Matrix rooms control and monitor DSP408 and FIR408 devices. It supports human-readable `!dsp ...` commands in a control room, a restricted volume room, a read-only limiter warning room and structured machine events for service-to-service control.

## Main Classes

- `MatrixBotComponent` - OSGi component and sync loop.
- `MatrixBotConfiguration` - metatype configuration.
- `MatrixRuntimeConfig` - normalized runtime config.
- `MatrixApiClient` - minimal Matrix Client-Server API client.
- `MatrixMessageHandler` - dispatches room events.
- `MatrixDspCommandDispatcher` - maps `!dsp` commands to `DspService`.

## Configuration PID

`de.drremote.dsp408controller.matrix`

## Configuration Properties

- `enabled` - start/stop the bot.
- `matrix.url` - Matrix homeserver base URL.
- `access.token` - access token for the bot account.
- `control.room.id` - room for full `!dsp` control.
- `admin.room.id` - deprecated fallback for `control.room.id`.
- `volume.room.id` - room for simple grouped and individual level commands.
- `warning.room.id` - read-only room for limiter warnings.
- `machine.room.id` - room for structured machine events.
- `allowed.users` - users allowed to issue normal commands.
- `admin.users` - users allowed to issue admin commands.
- `machine.users` - users allowed to send machine events.
- `sync.timeout.ms` - Matrix sync timeout.
- `reconnect.delay.ms` - delay after Matrix errors.
- `limiter.warnings.enabled` - default warning state when no room state exists.
- `limiter.warning.poll.ms` - live-meter polling interval, minimum 500 ms.
- `limiter.warning.cooldown.ms` - reminder interval, minimum 5000 ms.
- `connect.dsp.on.start` - connect the DSP during bot startup.

The Java configuration methods use underscores, for example `control_room_id()`. In Karaf configuration those names are written with dots, for example `control.room.id`.

## Human Commands

Control room messages must start with `!dsp`.

Examples:

```text
!dsp dsps
!dsp status
!dsp meters
!dsp meterstate
!dsp meter out1
!dsp gain ina -6
!dsp dsp fir408 state
!dsp dsp normal408 gain out1 -6
!dsp route out1 ina
!dsp xgain out1 inb -12
!dsp xhpset out1 80 LR_24 on
!dsp opeqfreq out1 1 1000
!dsp opeqbypass out1 1 on
!dsp peqallbypass on
!dsp firmode out1 fir
!dsp firgen out1 lowpass hamming 0 120 512
!dsp tone 1000
!dsp volume status
!dsp toneoff
```

Admin-only human commands:

- `connect`
- `disconnect`
- `reconnect`
- `raw`
- `sendraw`

The old commands target the default DSP. To address a specific configured DSP, wrap the same command with `dsp <id>`:

```text
!dsp dsp fir408 connected
!dsp dsp fir408 firmode out1 fir
!dsp dsp normal408 route out1 ina
```

## Simple Volume Room

The dedicated volume room also accepts short commands without a `!dsp` prefix.
Unknown chat messages are ignored. `+` and `-` use the configured
`volume.step.db` value, which defaults to 1 dB.

```text
lauter
leiser
in +
in -
out +
out -
ina -20
inb +
out1 -10
out5 -
in mute
out unmute
out1 mute
status
help
```

`lauter` and `leiser` address all inputs together. They are short aliases for
`in +` and `in -`.

`in` controls InA through InD as a group and preserves their relative gain
offsets when `+` or `-` is used. `out` does the same for Out1 through Out8.
A fixed group value is deliberately explicit:

```text
in set -20
out set -20
```

The previous `!dsp volume ...` syntax remains supported.

## Limiter Warning Room

The warning room never forwards commands to the DSP. It reads the confirmed
runtime limiter flags for Out1 through Out8 and sends a short warning on a new
activation. A limiter that remains active produces a reminder after the
configured cooldown. When all affected outputs are free again, the room gets
an all-clear message.

Allowed room commands:

```text
warnungen an
warnungen aus
status
help
```

The on/off state is saved as Matrix room state and therefore survives Karaf
and Matrix bot restarts. Other chat and all `!dsp` commands are ignored.

## Machine Events

Machine control uses Matrix event type:

`de.drremote.dsp408.command`

Responses are sent as:

`de.drremote.dsp408.response`

Example event content:

```json
{
  "requestId": "req-001",
  "dspId": "fir408",
  "command": "channel.gain.set",
  "args": {
    "channelId": "ina",
    "db": -6.0
  }
}
```

Supported machine command families include:

- `dsp.instances.list`
- `connection.*`
- `device.info.get`
- `state.get`
- `blocks.scan`
- `block.read`
- `channel.*`
- `delay.unit.set`
- `preset.*`
- `meters.read`
- `meters.get`
- `meter.get`
- `matrix.*`
- `crossover.*`
- `fir.mode.set`
- `fir.generator.set`
- `fir.upload`
- `output.peq.*`
- `input.peq.*`
- `input.geq.*`
- `input.gate.*`
- `compressor.set`
- `limiter.set`
- `testtone.*`
- `volume.command`

`dspId` may be supplied either at the top level or inside `args`. If it is omitted, the default DSP is used.

Meter commands:

- `meters.read` sends a new meter request to the DSP.
- `meters.get` returns the latest cached meter state.
- `meter.get` returns one cached channel including `levelDb` and `limiterActive`.

Example:

```json
{
  "requestId": "meter-out1",
  "dspId": "fir408",
  "command": "meter.get",
  "args": {
    "channelId": "out1"
  }
}
```

Structured FIR408 example:

```json
{
  "requestId": "req-002",
  "dspId": "fir408",
  "command": "fir.generator.set",
  "args": {
    "outputId": "out1",
    "type": "lowpass",
    "window": "hamming",
    "hpHz": 0,
    "lpHz": 120,
    "taps": 512
  }
}
```

Normal Matrix commands use the shared FIR408 write-evidence gate. An
unverified operation returns `Unsupported` without sending a DSP payload.
Raw protocol commands, where enabled for administrators, deliberately bypass
that protection and are only intended for controlled diagnostics.

## Build

From `dsp408-parent`:

```bash
mvn -pl dsp408-matrix -am test
```

## Notes

The bot trusts Matrix room/user configuration for authorization. Keep access tokens out of source control and use Karaf config for deployment-specific values.
