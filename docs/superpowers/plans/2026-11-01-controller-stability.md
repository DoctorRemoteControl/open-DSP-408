# Controller Stability Implementation Plan

> **For agentic workers:** Use systematic debugging and test-driven development. Independent runtime, socket and HTTP changes are delegated with exclusive file ownership; the coordinating implementer owns command dispatch and integration.

**Goal:** Make connection startup, cancellation and command completion reliable while retaining the existing extracted Controller architecture.

**Architecture:** Keep one connection owner and one serialized command worker. Startup runs outside OSGi activation; readiness requires completed bootstrap. Missing generic ACKs invalidate the session because the wire protocol has no request identifier. Cache readiness remains separate from transport readiness.

**Tech Stack:** Java 21, Maven, JUnit 5, OSGi Declarative Services, Karaf HTTP.

**Spec:** The user's approved connection investigation and improvement request, backed by `.codex-backups/open-dsp-investigation-20261101-zon29324/REPORT.md` in the workspace.

## Global Constraints

- Preserve all 104 existing working-tree entries and independent repositories. Before-change source archive: `.codex-backups/controller-stability-20261101-htp6v507/`.
- Maintain English code, messages and documentation. Keep Java/OSGi in its canonical workspace.
- No deployment, service restart, publication, physical DSP queries or writes.
- Use loopback fake devices and latch-based tests; retain protocol JSON/capture evidence unchanged.
- Work in the current checkout to preserve the existing unfinished refactor; test workers use separate copies in Betriebsdaten. No branch changes or mass cleanup.

## Approaches

A new transport framework would discard substantial local work and enlarge the review surface. Increasing timeouts alone would leave lifecycle races and ambiguous ACK attribution. The chosen implementation builds on the current small classes and adds regression tests for each confirmed failure mechanism; only the store deadline changes where its existing flash-silence contract requires it.

## Review Focus

- A delayed ACK from a timed-out write or keepalive must never confirm a later write.
- A paused meter scheduler must not leave a queued sample that transmits during a control transaction.
- A disconnect during bootstrap must prevent late success, cache promotion or polling restart.
- A previous socket reader must not mutate or close a replacement session; invalid checksums must not update state.
- Preset refresh behavior and disconnected meter responses must agree across global/per-device routes and OpenAPI.

## Task 1: Serialized command safety (coordinator)

Files: core net DspCommandDispatcher, DspCommandQueue, DspCommandPolicy, DspTrafficGate and their tests.
Interfaces: preserve DspPayloadTransport.send/sendReliable; add dispatcher pending-meter cancellation consumed by DspClient.stopMeterPolling.

- [x] Reproduce delayed generic ACK contamination with a deterministic dispatcher fake writer and queued second write; verify RED.
- [x] Remove ACK-write retries, fail closed on unanswered ACK including keepalive, and fail required reads when their response window expires. Never report uncertain completion as success.
- [x] Reproduce interrupted queued meter transmission; implement queue cancellation and prevent pre-interrupted submissions. Confirm GREEN.
- [x] Separate recall 6000 ms from store 10000 ms (existing documented 8000 ms silence plus bounded margin), and allow the queue wait to cover legitimate command response budgets. Test a store ACK after 8000 ms without flash writes.
- [x] Base keepalive suppression on verified responses so repeated unanswered meter requests cannot mask a silent peer. Add an integration-level health probe regression.

## Task 2: Connection and socket lifecycle (exclusive socket/runtime workers)

Files: core DspSocketSession, DspClient, DspController; runtime DspConnectionManager, DspRuntimeComponent, DspSettingsCache and focused tests.
Interfaces: isConnected means completed bootstrap; close cancels any current attempt. Runtime cache status remains nonblocking and cancellation-safe.

- [x] Write and observe failing tests for blocked startup, duplicate connect, cancellation and stale reader races.
- [x] Publish lifecycle resources before blocking I/O; associate reader cleanup and callbacks with their own session.
- [x] Discard checksum-invalid frames before both decoding and response correlation.
- [x] Move auto-connect/config reconnect off activation callbacks and coalesce concurrent connection attempts without holding lifecycle locks over network/cache I/O.
- [x] Prevent late cache readiness and meter restart after disconnect/close; run complete runtime/core tests.

## Task 3: HTTP contract consistency (exclusive HTTP worker)

Files: HTTP administration handler, meter handler, OpenAPI, HTTP README and route tests.
Interfaces: existing paths/DTOs retained; omitted refreshPreset restores true, explicit false remains supported; expected disconnected meter polling returns structured 409.

- [x] Exercise omitted/true/false refresh through both actual servlet routes; observe RED, then restore default true and verify GREEN.
- [x] Exercise disconnected meters through both routes; return dsp_not_connected without exception logging or service request; cover a disconnect race.
- [x] Update machine-readable contract and relevant English README behavior.

## Task 4: Integration and review (coordinator)

- [x] Review each worker diff and RED/GREEN evidence against the source archive.
- [x] Document readiness, ACK uncertainty, cache freshness, timeout rationale and coherent bundle deployment limits.
- [x] Run the complete Controller reactor and workspace checks appropriate to integration; summarize actual test counts and failures.
- [x] Obtain an independent whole-change code review, correct substantive findings with regressions, and rerun required verification.
- [x] Record preservation of protocol evidence, Git refs and unrelated repositories. Report remaining hardware/deployment limits honestly.

## Execution rulings

The user authorized continued implementation of the findings. No additional design approval loop is necessary. This work deliberately remains in the current checkout because it builds on a large existing unfinished extraction; its exact before-state is archived. No automatic staging, commit or push will combine pre-existing feature work without review.

Ruling during implementation: a slow read test proved two wire requests at the old 500 ms retry boundary. Selector-bearing reads now receive one 1000 ms response window without retransmission, avoiding stale duplicate replies after later writes.

## Completion evidence

The final Controller reactor passed all eight modules with 204 tests. The full
DSP408 workspace passed all twelve reactor projects with 484 tests, zero failures,
errors or skips. The independent review's three Important findings each have a
reproduced failing regression and a verified correction; there are no deferred
minor findings. Logs, source hashes, bundle hashes and preservation checks are
retained in `.codex-backups/controller-stability-20261101-htp6v507/`.

HEAD, refs and index bytes remain unchanged. All original protocol evidence is
unchanged. Changes are local and have not been committed or deployed.
