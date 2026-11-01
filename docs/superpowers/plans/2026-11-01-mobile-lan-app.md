# Mobile LAN App Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline, with one independent whole-change review. Independent backend delivery fixes may be delegated via dispatching-parallel-agents while the coordinator owns UI/integration. Follow RED/GREEN for meaningful behavior.

**Goal:** Deliver an English mobile browser controller served by the existing LAN DSP server.
**Architecture:** Same-origin static ES modules in the HTTP bundle consume existing cached API state and structured parameter outcomes. A DOM-independent model owns device generations, conflict checks and one-write admission; the existing server owns physical DSP traffic.
**Tech Stack:** Java21/OSGi, HTML/CSS, ES modules, Node22 built-in tests, JUnit5, local Chromium.
**Spec:** ../specs/2026-11-01-mobile-lan-app-design.md

## Global Constraints

- Local WLAN first, expressly selected by the user; no cloud registration/deployment.
- English UI/code/docs; preserve existing protocol evidence and API response shapes.
- No physical audio, preset or FIR test writes or historical-runtime restarts.
- Keep Java/OSGi and its project-owned assets together in the canonical repository.
- Same-origin app; no third-party runtime asset requests or frontend framework dependencies.

## Review Focus

- Delayed replies after device switches cannot populate the new target or authorize a write.
- An uncertain or lost POST cannot become a success or be automatically replayed.
- Stale/unknown/unsupported values cannot appear as confirmed live measurements.
- Concurrent edits require a visible preflight conflict; the limitation of non-atomic checks stays explicit.
- Packaged HTTP resources and foreign-Origin handling must behave correctly in real Karaf without breaking native API clients.

## Tasks

- [x] Test/implement packaged asset servlet and origin policy. Existing API registration/lifecycle remains intact; native requests and same-origin browsers pass, foreign/null origins reject before effects.
- [x] Test/implement model.mjs: transport errors, finite values, per-device generation, strict capabilities, cached data only, one in-flight write, normal ACK mode and optional verification, preflight conflict, structured results and explicit recovery.
- [x] Build responsive HTML/CSS/app.mjs with real model wiring. Test browser interactions and mobile layout; preserve pending draft/result semantics and never send on page load or slider input alone.
- [x] Wire Node tests and packaged-page checks into CI/Karaf, document LAN startup and limits, run all appropriate checks.
- [x] Independent review and verified RED/GREEN fixes. Publication, hosted CI, workspace lock and retained evidence are recorded in Git history and the external implementation ledger.

## Execution ruling

The user's "ja mach das" authorizes implementing the proposed browser client;
the explicit WLAN answer settles deployment scope. Routine UI/stack choices are
delegated. Backed-up in-place source editing retains the user's canonical branch
and checkout; disposable test state supplies isolation without a new branch.

## Added delivery tasks after user steering

- [x] Backend: atomic interactive queue reservation and runtime tryLock admission, preserving legacy queued callers and honest post-ACK uncertainty.
- [x] HTTP: request-key duplicate suppression, backend timing and interactive header routing, with concurrent/capacity/expiry tests.
- [x] Browser: normal ACK-only controls, optional readback, controlled network jitter/reset/30second-stall tests and full control-response timing.

The original summer cause remains unproven; tests distinguish simulated HTTP
effects, current server queue behavior and actual hardware observations.

## Local handoff

After the clean build and hosted CI pass, start a separate project-owned LAN
instance on a free HTTP port, with auto.connect=false and shell/JMX confined
to loopback. Serve the committed build; the user explicitly connects the real
DSP through the page. Preserve the historical runtime and its configuration.
