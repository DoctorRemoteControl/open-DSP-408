# Mobile LAN controller

The optional `dsp408-web` feature serves DSP Raider at `/app/` alongside `/api/v1`.
The API-only `dsp408-http` and managed `dsp408-server` features do not enable the UI.
Open `http://<server-LAN-address>:8181/app/` on a phone in the server's reachable
local network, using the HTTP port configured for that Karaf instance. The server
connects to the DSP; browser clients share its device state and connections.
There is no Node runtime, CDN or build step required on the installed server.

## Install and connect

Build/install the coherent Controller and pinned protocol source using
[`ci/verify.sh`](../ci/verify.sh). In a new or deliberately managed Karaf instance:

```text
feature:repo-add mvn:de.drremote/dsp408-features/0.0.1-SNAPSHOT/xml/features
feature:install dsp408-web
```

`dsp408-all` includes the same browser interface. Install API/core/runtime/HTTP
from the same revision; an older mixed SNAPSHOT installation is not upgraded by
building new source. This change does not deploy to the historical local runtime.

Configure DSP addresses using the [runtime configuration](../dsp408-parent/dsp408-runtime/README.md).
The default device is `main`, `192.168.0.166:9761`. Configure the HTTP listener
(`org.ops4j.pax.web`, `org.ops4j.pax.web.listening.addresses`) for the intended LAN
interface and choose its HTTP port (`org.osgi.service.http.port`). A loopback-only
listener works on the server itself, not on a phone. The phone needs the server's
LAN address; `localhost` on the phone refers to the phone. This increment assumes
a trusted local network: the API has no account/login system and is not an
Internet-access service. Explicit foreign-Origin browser POSTs are rejected;
that supplements same-origin delivery and does not authenticate LAN clients.

Loading the page only reads cached server data. Select a configured processor
and press **Connect** if needed. There are input/output filters and twelve
channel cards, with gain, mute, phase, delay and level displays. Loading, device
selection and monitoring never send an audio control or an implicit full refresh.

## Fast normal controls

Normal controls explicitly use `verify:false`: send once and wait for the device
ACK. Gain previews locally while dragging and sends on release/keyboard change.
Delay sends through Apply. No device-wide synchronization is required after a
change. The app admits one write at a time and never builds an offline queue.

**ACK received** confirms that the device acknowledged the request; it is not a
measured readback. The normalized codec value is displayed with its evidence.
Enable **Read back after every change** in verification settings for optional
per-parameter checking; unsupported readback mappings are then disabled.
An initially unknown mute can explicitly be set to muted without inventing an
unmuted initial state. It cannot implicitly unmute an unknown channel.

The browser opts into the interactive API path with `Prefer: wait=0`. Runtime
transactions and the physical command queue use atomic admission: a slow
synchronization/store/other control or pending work returns `503`, structured
`REJECTED`, `dsp_busy`, `transmitted:false` rather than waiting behind it. One
already-started short meter or keepalive exchange may finish before an accepted
interactive command. Its reserved next slot cannot be overtaken by later work.
An accepted command still waits for its own ACK. Existing clients without this
preference retain their existing queued-write behavior.

The browser limits the cached preflight to 1500 ms and the write response to
2000 ms. A timeout after submission means **uncertain**, not failed/unsent. It
blocks further writes until explicit recovery; reconnect if disconnected and
then Refresh. The app does not automatically resend a write. These client
limits are not a guarantee that a remote operation was cancelled.

## Other users and full snapshots

Before sending, the app compares the displayed baseline with fresh cached
channel data. An edit by another client produces a visible conflict instead of
an automatic overwrite. This is advisory preflight, not atomic server-side
optimistic concurrency; another writer can still intervene after the check.
Late replies from a different selected-device generation are ignored. Uncertain
outcomes remain associated with their original device until recovery.

A possibly transmitted write makes the full settings snapshot `STALE`, even
when one field is verified. Controls remain usable after an ACK. **Refresh** is
an explicit full read of the device settings; ordinary monitoring and control
do not trigger it. Unknown, uncalibrated and stale levels are labelled clearly.

Each visible browser reads cached meters about every 400 ms and other cached
state about every 1500 ms, with no overlapping polls. Hidden pages stop polling
and restart on return. Meter acquisition remains one central server task per
DSP; opening additional browsers does not add physical meter pollers. SSE and
a durable multi-user transaction/version protocol remain separate extensions.

## Response delivery and timing

The browser gives each parameter operation a random 128-bit `Idempotency-Key`.
The HTTP service retains its request fingerprint and result/exception, so
transparent browser transport retries with the same key do not call the DSP
service again. Reusing a key for another body or target conflicts. Active
entries are never evicted; capacity exhaustion rejects before executing work.
The cache holds up to 4096 entries and retains completed results for two minutes.
This protection is held in one servlet process, not durable across
restart or expiry. See the [parameter API](PARAMETER-API.md) for its exact limits.

The page shows command timing. `Server-Timing: backend;dur=...` measures parsing,
cache waiting and DSP service execution for that HTTP attempt. Browser totals
also include network, scheduling, JSON handling and, for control feedback, the
cached safety preflight. The difference is not a pure Wi-Fi measurement. A
cached replay measures the replay request, not the original device operation.

## Tests and development

Java21/Maven tests cover packaged resource policies, same-origin handling,
interactive reservations, busy/uncertain outcomes and duplicate HTTP delivery.
The app itself has no runtime dependencies. Node22+ and a pinned development-only
Playwright package run the model and real-browser checks:

```sh
npm ci --ignore-scripts
npm run test:web
npx playwright-core install chromium
npm run test:browser
```

Use `DSP408_BROWSER_BIN=/absolute/path/to/chrome` to reuse an installed local
browser. `DSP408_BROWSER_EVIDENCE_DIR=/absolute/path/to/evidence` saves desktop
and phone screenshots plus controlled network-profile results. The fresh
[Karaf harness](KARAF-INTEGRATION.md) accepts `--browser-check` to run the actual
packaged page through the real API/runtime to an owned loopback DSP peer.
All automated writes target synthetic peers only.

The old experimental Raider emulator is not the hardware reference. Direct
read-only hardware validation and software/network fixtures are separate.
Simulated HTTP jitter, dropped replies and 30-second response stalls reproduce
possible network symptoms; they do not recreate summer RF conditions or prove
that readbacks caused the original live delay. The current code is tested on
its own merits. A successful browser fixture is not a physical audio-write test.
