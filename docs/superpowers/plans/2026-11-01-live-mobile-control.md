# Live Mobile Control Implementation Plan

> **For agentic workers:** Implement the binding design with TDD. Independent backend and frontend workers own disjoint sources; parent owns SSE/packaging/integration. One final fresh-context review.

**Goal:** Mobile controls receive live cached telemetry and coordinate writes through atomic server revision checks.
**Architecture:** Java21/OSGi cached state + conditional commands, async SSE, existing dependency-free mobile UI.
**Spec:** ../specs/2026-11-01-live-mobile-control-design.md

## Ownership and tasks

1. Backend worker: dsp408-api/runtime modules; HTTP DspParameterPostHandler, DspParameterRequestCache, DspGetRouteHandler, DspApiServlet and related tests/OpenAPI. Implement DTO/API, cached revisions, bounded conditional admission, If-Match412 and fingerprint-safe dedupe. RED/GREEN for concurrent/stale/session/legacy behavior.
2. Frontend worker: web assets and existing JS/browser tests only. Implement EventSource updates, version-bound drafts, bounded edits/pending/conflict feedback and real browser regressions. Consume exact event/API contract from spec. Existing polling fallback remains tested.
3. Parent: new HTTP SSE classes/tests, HTTP/feature dependencies, offline package webEnabled setting + deployment tests, integration actual JVM/SSE/two-client tests, CI/docs. RED/GREEN bounds/backpressure/lifecycle; no per-browser DSP reads.
4. Independent review; fix Important findings with failed-first regression tests and green suite. Commit/push, clean artifacts/CI, update root lock, back up and upgrade owned disconnected LAN instance with webEnabled=true. Preserve registry, existing origin checks and no physical audio writes; archive evidence and verify sibling edits.

## Review focus

Old callbacks/events after selection or same-ID replacement; busy path blocking cached reads; stale If-Match retry vs Idempotency-Key; nonblocking output reentrancy/slow clients; sampler/subscriber leaks on cancel/deactivation; browser stream failure and lost command responses; source/provenance and optional-UI default preservation.

## Ledger

Pre-flight: shared DTO/method names, HTTP condition and SSE events fixed in spec before parallel work. New access protection explicitly deferred by user; retain existing guards. No design approval round is needed for the authorized feature work.

* Backend: API contract/runtime conditional method installed. Runtime97 and focused HTTP32 tests pass, including stale offline checks, bounded busy admission and Auto-Limit lock-order regression. Existing49 CRLF lines in RuntimeComponent preserved.
* Frontend:33 model and31 real Chromium tests pass; named SSE lifecycle, cache/epoch/revision ordering, rate limits and definite conflicts tested. No new asset names or runtime JS dependencies.
* Parent SSE:10 delivery tests pass after missing-feature RED, cancellation mutation RED and error-response cleanup RED. One subscriber cancellation no longer interrupts device/heartbeat delivery; errors complete an already-started async request even when the error body cannot be written. Full HTTP311 tests pass.
* Packaging:webEnabled is an optional strict boolean, default false. Two new tests failed before implementation; deployment/metadata34 tests pass. Existing Python integration21 and provenance8 pass; workflow actionlint passes.
* First actual packaged Java + two-browser integration passed7.808s with shared meter polling, no added parameter-block reads, stable conditional ETags, one-wire duplicate replay, stale412, instance replacement and mobile/desktop SSE propagation. All audio writes target literal loopback fixtures only. A harness Request.method attribute error was corrected to get_method; it did not change product code.
* Independent review found one Important selection-generation bug: an obsolete unsupported preflight could disable conditional writes for the newly selected runtime. A regression failed on the missing If-Match tag, then passed after guarding compatibility-state mutation by the captured generation. The regression covers404/405/501, a different device and same-ID replacement;34 model tests pass.
* Deferred Minor review finding: after an idle period the first SSE device-list frame can contain the previous sample until the next200ms sampler tick. Revision/incarnation checks still reject stale commands; the next tick repairs the list. Initial target snapshots can likewise lag by one tick. No additional authentication or authorization was introduced, as requested.
* Deployment preflight found an existing boot race: Pax Web failed to bind the configured LAN address before DHCP completed, but its JVM remained alive. A real foreground regression failed before the fix. The launcher now waits at most 30 seconds for configured addresses, then fails explicitly for service retry; address recovery, deadline and non-retryable errors are covered. Deployment/metadata36 tests pass. Full Controller577 and protocol19 Java tests, model34 and Chromium31 tests pass before commit.
