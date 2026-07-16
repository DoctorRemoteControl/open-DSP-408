# DSP408 Karaf Shell

This bundle provides Apache Karaf shell commands for operating the DSP408/FIR408 controller directly from a Karaf console.

The command scope is:

```text
dsp408
```

All commands use this format:

```text
dsp408:<command>
```

## Requirements

The DSP408 bundles must be installed and active in Karaf:

```text
bundle:list | grep DSP408
```

The DSP connection must be configured. The complete configuration example is documented here:

```text
docs/config-example.md
```

Minimal Karaf configuration:

```text
config:edit de.drremote.dsp408controller
config:property-set dsp.id main
config:property-set dsp.ip 192.168.0.166
config:property-set dsp.port 9761
config:property-set auto.connect true
config:property-set auto.read.on.connect true
config:property-set volume.step.db 1.0
config:update
```

## First Steps In Karaf

Show the built-in help:

```text
dsp408:help
```

Check whether the controller is connected:

```text
dsp408:connected
```

Connect to the DSP:

```text
dsp408:connect
```

Read the current state:

```text
dsp408:state
```

List channels:

```text
dsp408:channels
```

Read device information:

```text
dsp408:deviceinfo
```

List configured DSPs and select a non-default DSP:

```text
dsp408:dsps
dsp408:select main
dsp408:current
```

Run one command against a specific DSP without changing the selected DSP:

```text
dsp408:dsp fir408 state
dsp408:dsp normal408 gain out1 -6
```

Reconnect after changing configuration or after a connection problem:

```text
dsp408:reconnect
```

Disconnect:

```text
dsp408:disconnect
```

## Typical Smoke Test

After installing the bundles, run:

```text
dsp408:help
dsp408:dsps
dsp408:select main
dsp408:current
dsp408:dsp main state
dsp408:connected
dsp408:connect
dsp408:state
dsp408:channels
dsp408:meters
dsp408:meter out1
```

If `auto.connect true` is configured, `dsp408:connected` may already return `yes`.

## Multi-DSP

`dsp408:select <id>` changes the selected DSP for later shell commands:

```text
dsp408:select fir408
dsp408:state
dsp408:firmode out1 fir
```

`dsp408:dsp <id> <command>` runs a single command against a specific DSP and does not change the selected DSP:

```text
dsp408:dsp fir408 state
dsp408:dsp fir408 firmode out1 fir
dsp408:dsp normal408 route out1 ina
```

## Channel Commands

Read a channel:

```text
dsp408:get ina
dsp408:get out1
```

Set gain:

```text
dsp408:gain ina -6
dsp408:gain out1 -12
```

Mute and unmute:

```text
dsp408:mute out1
dsp408:unmute out1
```

Set a channel name:

```text
dsp408:name out1 SUB_L
```

Set phase:

```text
dsp408:phase out1 180
dsp408:phase out1 normal
```

Set delay:

```text
dsp408:delay out1 2.5
dsp408:delayunit ms
```

## Device And Presets

Send the login PIN:

```text
dsp408:login 1234
```

Load a preset:

```text
dsp408:loadpreset F00
dsp408:loadpreset U01
dsp408:loadpreset 1
```

Read a preset name:

```text
dsp408:readpresetname 0
```

Request fresh meter values from the DSP:

```text
dsp408:meters
```

Read the latest cached meter state or one channel:

```text
dsp408:meterstate
dsp408:meter out1
dsp408:dsp fir408 meter out1
```

The channel result contains the decoded `levelDb` and, for outputs, `limiterActive`.

## Matrix Routing

Route an output to an input:

```text
dsp408:route out1 ina
dsp408:route out2 inb
```

Set matrix crosspoint gain:

```text
dsp408:xgain out1 ina -3
```

## Crossover

Set a complete high-pass filter:

```text
dsp408:xhpset out1 80 LR24 on
```

Set high-pass values individually:

```text
dsp408:xhpfreq out1 80
dsp408:xhpslope out1 LR24
dsp408:xhpbypass out1 off
```

Set a complete low-pass filter:

```text
dsp408:xlpset out1 120 LR24 on
```

Set low-pass values individually:

```text
dsp408:xlpfreq out1 120
dsp408:xlpslope out1 LR24
dsp408:xlpbypass out1 off
```

## FIR408

Switch output processing mode between IIR and FIR:

```text
dsp408:firmode out1 fir
dsp408:firmode out1 iir
```

Generate a FIR filter on a FIR408 output:

```text
dsp408:firgen out1 lowpass hamming 0 120 512
dsp408:firset out1 highpass hamming 80 0 512
```

Upload external FIR coefficients from a file or inline list:

```text
dsp408:firupload out1 SUB_L C:\filters\sub_l.txt
dsp408:firupload out1 TEST 0.0,0.25,0.5,0.25,0.0
```

## EQ

Set a complete output PEQ band:

```text
dsp408:opeqset out1 1 peak 1000 1.0 -3
```

Set output PEQ values individually:

```text
dsp408:opeqfreq out1 1 1000
dsp408:opeqq out1 1 1.0
dsp408:opeqgain out1 1 -3
dsp408:opeqtype out1 1 peak
```

Set a complete input PEQ band:

```text
dsp408:ipeqset ina 1 peak 1000 1.0 -3 on
```

Set an input GEQ band:

```text
dsp408:igeq ina 1 -2
```

Some single-field PEQ, gate, and crossover edits need cached block data. If a command cannot find block data, run:

```text
dsp408:scanblocks
```

## Dynamics

Set an input gate:

```text
dsp408:gateset ina -60 100 10 200
```

Set gate values individually:

```text
dsp408:gatethreshold ina -60
dsp408:gatehold ina 100
dsp408:gateattack ina 10
dsp408:gaterelease ina 200
```

Set and read compressor values:

```text
dsp408:compset ina -12 1:4.0 10 200 3
dsp408:compget ina
```

Set and read limiter values:

```text
dsp408:limitset out1 -1 5 100
dsp408:limitget out1
```

## Test Tone

Set a sine test tone:

```text
dsp408:tone 1000
```

Set the test tone source:

```text
dsp408:tonesource sine
dsp408:tonesource pink
dsp408:tonesource white
dsp408:tonesource analog
```

Disable the test tone:

```text
dsp408:toneoff
```

## Volume Helper

Show volume status:

```text
dsp408:volume status
dsp408:volstatus
```

Increase or decrease volume:

```text
dsp408:volume up
dsp408:volume down
dsp408:volup
dsp408:voldown
```

Set volume directly:

```text
dsp408:volume set -10
dsp408:volset -10
```

Set the step size:

```text
dsp408:volume step +1
dsp408:volstep +1
```

Mute and unmute through the volume helper:

```text
dsp408:volume mute
dsp408:volume unmute
dsp408:volmute
dsp408:volunmute
```

## Debug Commands

List cached block indices:

```text
dsp408:blocks
```

Read a block:

```text
dsp408:readblock 01
```

Show a cached block:

```text
dsp408:showblock 01
```

Send a raw hex payload:

```text
dsp408:raw AA BB CC
```

FIR408 commands whose write layout is not confirmed are rejected with an
`Unsupported` message before a network payload is sent. The raw command
deliberately bypasses that check and should only be used for controlled
protocol diagnostics.

## Legacy Fallback

Most commands can also be passed through the internal command processor with `cmd`:

```text
dsp408:cmd state
dsp408:cmd gain ina -12
```

Prefer the direct Karaf commands, for example `dsp408:state` instead of `dsp408:cmd state`.

## Build

From the parent project:

```bash
mvn -pl dsp408-shell -am test
```
