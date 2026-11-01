# Mobile LAN DSP control

The user wants a DSP attached to a server and a phone/browser controlling it,
and explicitly selected local WLAN for the first version. Extend the existing
Controller HTTP bundle; preserve independent repositories, current branches,
protocol evidence, existing API shapes and the unrelated Raider timing edit.
All maintained code, UI and documentation are English.

## Approach and boundaries

Serve a buildless, responsive HTML/CSS/ES-module app at `/app/` from the same
OSGi HTTP service as `/api/v1`. Compared with a separate app server this avoids
an additional runtime, CORS and duplicated device ownership. Cloud hosting
cannot reach the private DSP directly and is outside the confirmed LAN scope.
Existing Java services remain the sole protocol/connection owners.

Use cached GET endpoints with nonoverlapping, visibility-aware polling: meters
at about 400 ms, channel/capability/cache state at about 1500 ms. Each browser
never asks for new hardware meter reads or an implicit full settings refresh.
SSE remains a later transport optimization. No frontend runtime dependencies,
CDNs, service worker, offline control queue, automatic connect, automatic write
retry or automatic hardware writes at page load. No users/Internet access in
this local trusted-network increment; reject explicit foreign-Origin browser
POSTs while preserving native callers without Origin. This is not authentication.

## Experience

A purposeful dark audio-console layout with mint accents: identity/header,
server and device status, configured device selector, explicit connect/disconnect,
full refresh, inputs/outputs/all filters and accessible channel cards. Mobile
cards fit a 360 px viewport; desktop uses a compact multi-column grid. Each card
shows a fresh/unknown/stale meter, gain fader, mute and phase buttons, delay with
an explicit Apply action, and pending/ACK/verified/rejected/uncertain feedback.
Control touch targets are at least 44 px, controls have names/units and keyboard
support, status uses text as well as color, and reduced motion is respected.
No placeholder or fabricated live values. Empty/error/unknown profile states
are explicit and keep unsafe controls disabled.

Gain input previews locally and submits once on release/keyboard change. Delay
submits only via Apply. Only one write is in flight per browser; no unbounded
command stream. Capabilities control value bounds, quantization hints, writable
state and readback availability. Normal controls explicitly use verify=false
and wait for ACK only; readback is optional. Unconfirmed readbacks stay disabled
when optional verification is enabled; the user can select normal ACK-only mode. Normal mode is now the default after the
user explained the live latency problem; optional verification disables mappings
without confirmed readback. Only an explicit set-mute action is allowed for
unknown mute state; an unknown state never becomes an implicit unmute. Mute and
phase are booleans. Display normalized results without implying an ACK is a
readback. Gain/mute/phase/delay are this UI's control scope.

Preserve a snapshot of the last displayed field value. Before a write, reread
cached channels and reject a changed baseline with a visible conflict instead
of silently overwriting another user's edit. This is advisory preflight, not
atomic server-side optimistic concurrency. Late GET/write responses from an old
device generation cannot update a newly selected device. Device changes reset
optional verification and pending local drafts. Network loss disables controls.
UNCERTAIN or lost POST response blocks further writes until explicit refresh
succeeds; never replay the write. REJECTED does not change device values. A
single VERIFIED result leaves the overall snapshot STALE until a full refresh.

## Components

- `DspWebAppServlet`: explicit asset allowlist and HTTP-service lifecycle;
  content types, no-store, nosniff and a restrictive same-origin CSP.
- `DspBrowserOriginPolicy`: same-origin mutation guard at the API boundary.
- `web/index.html`, `styles.css`: accessible responsive control surface.
- `web/model.mjs`: API transport, selected-device generation, cached reads,
  bounded writes, conflict/uncertainty/result rules, independent from the DOM.
- `web/app.mjs`: DOM rendering, events, timers and visibility handling.
- Node built-in tests for model behavior, JUnit servlet/origin contract tests,
  browser tests with controlled HTTP responses and real packaged Karaf checks.

## Validation and publication

Test meaningful state/side-effect rules RED then GREEN; do not test cosmetic
prose. Browser QA covers desktop/mobile, no horizontal overflow, device switch,
write release, ACK-only opt-in, stale meters, conflicts, loss and unknown outcomes.
Only synthetic peers receive test writes. Read-only physical use remains allowed
but cannot certify physical write behavior. Extend hosted CI with Node model
tests and verify packaged web resources in fresh Karaf. Independently review,
fix substantive findings with regression evidence, commit/push after checks,
wait for hosted CI and update the local workspace lock. Preserve test/runtime
and hardware evidence outside source; do not deploy to the historical runtime.

## User steering: live latency and Wi-Fi

The user reports20–30second historic waits and notes guest Wi-Fi may have caused
interference; they also note the implementation has improved since summer. The
historic cause is not established. Test today's software with separate HTTP
jitter/stalls, DSP response faults and occupied backend transactions. Normal
controls require no automatic block readback/full refresh. The browser bounds
its cached preflight at1500ms and command response at2000ms, shows uncertainty
rather than success after a lost response and does not replay commands.

Add explicit interactive admission through Prefer:wait=0 and an additive API
method. Busy runtime/control queue rejects before transmission; one started
short meter/keepalive may finish, then a reserved slot runs the control. Legacy
queued writes remain compatible. Add a bounded servlet request cache for
Idempotency-Key, because actual Chromium testing showed transparent HTTP replay
on reset connections even with one app fetch. Never expire active entries;
cache original exceptions as well. This is process-local transport protection.
Add monotonic backend Server-Timing and browser round-trip measurements; the
difference includes overhead and is not a direct RF measurement.
