# Headless Java device registry

Status: implemented and locally verified. Base: 033ebe5d3fda6449ae4fa1f2493329afe335634b.

## Specification

The Java 21 / OSGi server must run without a browser or desktop. The HTTP API is the primary interface. Support DSP408/FIR408 devices addressed by host and port, each with isolated connection, cache, polling and command admission. No live audio writes are required for acceptance. Existing static configuration remains compatible.

Managed registry mode starts with zero devices. Register, list, inspect, connect and remove devices through the API. Definitions survive restart. Registration defaults to no network traffic; only autoConnect=true or an explicit connect starts a connection. Mutations must preserve unrelated runtime identities and connections. Persist before acknowledging changes. Corrupt or unwritable storage must fail without replacing valid state. Removing a device closes its resources; stale request references cannot act on a replacement with the same ID. Duplicate IDs with different definitions and duplicate normalized endpoints conflict. Registered definitions are immutable; remove and register to retarget explicitly.

Registry DTO contract (package de.drremote.dsp408.api):

* DspDeviceDefinitionDto(String id, String host, int port, String library, boolean autoConnect). Normalize/validate ID and host without network lookup, IDs max 64 characters; port 1..65535; library auto/fir408/dsp408. No URLs, paths, controls or whitespace in host. Permit valid IPv6 literals without brackets internally.
* DspDeviceRegistryDto(String mode, boolean persistent, List<DspDeviceDefinitionDto> devices), immutable list.
* DspDeviceRegistrationDto(DspDeviceDefinitionDto device, boolean created).
* DspDeviceRegistryApi extends DspConnectionApi with default getDeviceRegistry() -> unavailable,false,empty; registerDevice(definition) and removeDevice(String id) default unsupported. getDeviceDefinition(String id) lookup or DspDeviceNotFoundException.
* DspDeviceNotFoundException extends IllegalArgumentException; DspDeviceConflictException extends IllegalStateException. DspService also extends DspDeviceRegistryApi; legacy forDsp delegation remains valid.

Runtime configuration gains registry_mode() default static and registry_file() default empty. Managed mode uses an explicit storage path or BundleContext.getDataFile fallback. Root is a registry facade: dspId() is empty and no root DSP watchdog/connection is started. Managed child runtimes remain concrete targets. Legacy mode retains current main and dsps semantics. Versioned JSON schemaVersion=1, devices list; write temporary file, force and atomic replace, no truncate of the original. Registry IO failures surface as errors. Managed live storage path/mode changes with devices must fail clearly rather than silently discarding live registrations.

HTTP routes:

* GET /api/v1/devices -> registry DTO; POST -> registration DTO (201 new, 200 identical retry), strict fields id,host,port,library,autoConnect, defaults port=9761,library=auto,autoConnect=false.
* GET /api/v1/devices/{id} -> definition; DELETE -> 204, unknown ID 404.
* Existing /api/v1/dsps status and /dsps/{id}/... controls remain. Managed root target routes reject with 409 and require selecting an ID. /health, /openapi.json, /dsps and /devices remain accessible.
* Apply existing browser origin policy to every new mutation. Invalid JSON 400, invalid values 422, definition conflict 409, unknown target 404. Storage errors must not return success.
* Idempotency-Key request cache must bind to resolved target service identity as well as ID/body, preventing old responses or writes from being reused for a replaced device.

UI is opt-in. dsp408-http installs the API with no /app registration by default. A dedicated dsp408-web feature enables the existing UI; dsp408-all retains UI through that feature. dsp408-server is a managed headless profile. No Node/browser dependency at runtime. Keep existing firmware gates and fast write admission unchanged.

## Implementation tasks and ownership

1. Runtime/API: DTOs, compatible API extensions, registry persistence and lifecycle with tests proving empty mode, restart, conflict, IO failure, isolation and stale removal. TDD evidence in this document ledger.
2. HTTP: registry routes, error/origin handling, cache target identity and optional web servlet configuration with focused RED/GREEN tests. Own HTTP module only; consume contract above.
3. Integration: parent owns feature profiles, real Java headless Karaf smoke with persistent restart and isolated loopback DSP peers, existing regression harness, CI, README/API/architecture docs. Run -Djava.awt.headless=true.
4. Verify integrated Maven reactor, Python integration tests, Java headless smoke and optional UI regression. One independent whole-change review; fix important findings with regression tests. Commit/push within existing user authorization; preserve sibling edits, update aggregate lock. Upgrade only the owned LAN runtime after evidence and state backup if safe.

## Review focus

Concurrent remove/connect and registration failure, reusing IDs with stale HTTP references, corruption and duplicate definitions in storage, storage path changes, unattended boot with zero devices, service lifecycle resource leaks, mixed static/managed semantics, browser origin protection on DELETE, and actual OSGi service activation without UI or a display.

## Ledger

* Pre-flight: shared API DTO/method signatures and registry_mode/registry_file configuration fixed above before parallel implementation. Runtime and HTTP have disjoint source ownership. Parent owns features, docs and integration.
* Design decision: retain Karaf/Java 21 for this increment. Adjacent native DrRemote host uses a different Servlet API range, so its deployment is a separate compatibility task. Reuse persistence/lifecycle patterns without adding EnergyEngine dependencies.
* Design decision: immutable registered endpoints; explicit delete/re-register makes retargeting reviewable and keeps requests attached to one concrete runtime.

* Task 1 API: eight API tests passed after the absent-symbol RED run (runtime-api-red.log / runtime-api-green.log).
* Task 3 harness: two new regressions failed on a non-headless JVM configuration and an empty 204 DELETE response; both now pass. Python integration tooling: 21/21; provenance script: 8/8; workflow actionlint passed.
* Task 3 UI compatibility: unchanged optional browser model 26/26; browser regression 18/18 passed. Runtime dependency remains Java-only.

* Task 1 runtime: 13 managed-registry tests plus 3 lifecycle alias tests added. Offline children allocate no watchdog; immutable published maps keep reads independent of storage/teardown. Existing API/core/runtime suites passed before review.
* Task 2 HTTP: 290/290 tests passed, including strict registration, target identity, browser origin checks on DELETE, original preset target context, optional UI lifecycle and OpenAPI. Storage failures use DspDeviceStorageException and return 500; definition conflicts return 409.
* Final review: independent review found an atomic-admission race across static-to-managed mode transition. Two loopback tests accepted hidden TCP connections before the fix; both reject before any connection after admission is serialized with configuration changes. Managed suite 15/15 GREEN.
* Integration finding: SCR selected the same-name single-argument activate overload, leaving BundleContext unset. A fresh headless Karaf run failed activation. The annotated entry now has the unique name activateComponent; retained activate(cfg) is only the internal/test helper. The default-storage smoke is the regression gate.
* Final: minor (deferred): cross-process registry-file locking. Deploy one owning JVM per file; shared-file multiwriter operation is outside this increment and is documented in HEADLESS-SERVER.md. External-edit detection still prevents sequential accidental overwrites.

* Final verification: complete workspace Maven verify passed 830/830 Java tests (Controller 550, protocol 19, proxy 7, Raider 254), with no failures or skips. The Controller clean install passed all 550 tests. Python integration 21/21 and provenance tooling 8/8 passed; browser model 26/26 and optional browser regressions 18/18 passed. Workflow actionlint passed.
* Final integration: the corrected real headless Karaf run passed in 20.720 seconds with 36 HTTP requests, two independent DSP connections, no traffic on registration, no UI, cancellation/removal isolation and persisted definitions across a complete Java restart. Both owned JVMs and all fixture/listener threads stopped. Legacy all-features/browser Karaf smoke passed in 60.071 seconds. The 100-cycle connection stress suite passed with no remaining DSP threads in every scenario.
* Evidence: local test and review reports are retained under Betriebsdaten/DSP408/headless-java-20261101; final source/provenance and runtime evidence are archived separately from the repository. No physical DSP audio writes were performed.
