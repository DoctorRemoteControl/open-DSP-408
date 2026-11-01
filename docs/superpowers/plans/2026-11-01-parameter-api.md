# Parameter API Implementation Plan

> **For agentic workers:** Follow systematic debugging and test-driven development. Core/catalog, runtime writer and HTTP tasks have exclusive file ownership; coordinator owns shared API records and final integration.

**Goal:** Expose firmware-aware capabilities and honest write/readback outcomes without changing existing v1 response shapes.
**Architecture:** Immutable catalog and bound core session feed runtime capabilities/write services. New typed API defaults preserve older providers. HTTP exposes two additive routes with OpenAPI and real-route tests.
**Tech Stack:** Java21, Maven, JUnit5, Jackson, OSGi/Karaf.
**Spec:** ../specs/2026-11-01-parameter-api-design.md

## Constraints

English maintained text; original protocol JSON/captures untouched. Use canonical
OSGi location/current branch with archived clean baseline ce3faad. Separate test
copies/state under Betriebsdaten. No physical audio/preset/FIR writes or production
service changes. Commit/push only after full checks/review, carrying prior Git
publication authorization forward.

## Review focus

- Unknown firmware/offline identities cannot authorize a new write.
- FIR mute inference cannot become a verified readback or legacy confirmed flag.
- ACK-only success, lost ACK, mismatching readback and post-ACK overload remain distinct.
- Reconnect during an operation never sends to or reads from its replacement client.
- New methods/HTTP routes preserve old provider linkage and v1 payload shapes.

## Tasks

- [x] Coordinator: API immutable DTO/default-method tests RED, then records/interface implementation GREEN. Document the exact wire JSON/status contract.
- [x] Core/catalog: profiles/layout/evidence/range tests RED, immutable catalog and bound sessions GREEN; test stale session and inferred mute flags. Add runtime capabilities mapper.
- [x] Runtime writer: loopback/fake-peer ACK/readback/mismatch/drop/rejection tests RED then write service GREEN. No optimistic verification; default readback preflight and normalization tested.
- [x] HTTP: actual global/selected servlet route tests RED, strict parameter body handling/status mapping and OpenAPI definitions GREEN; old-route response regressions unchanged.
- [x] Coordinator: wire runtime facade, extend fresh Karaf integration for capabilities/new route and simulated writes only, update English docs. Run full workspace and standalone CI.
- [x] Independent review and substantive fixes with regression evidence.

Publication is recorded in the resulting Git history, hosted CI and workspace
lock; exact build/test and hardware evidence is retained outside the checkout.

## Execution rulings

The user accepted the recommendation to start with capabilities/write results;
SSE and optimistic concurrency are not silently included. Decisions above are
implementation choices delegated by that approval; no further approval loop.
Existing source was clean; backups preserve its exact before-state. Work in the
existing checkout, execute tests in isolated copies, retain current branch.
