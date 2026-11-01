# Controller Readiness Implementation Plan

> **For agentic workers:** Use test-driven development and systematic debugging. Independent queue, CI and Karaf tasks have exclusive file ownership; the coordinator owns live validation, integration and Git completion.

**Goal:** Complete the five approved repository/integration/overload/duration/hardware validation steps.
**Architecture:** Retain existing Controller modules, add a bounded queue and repeatable validation entry points; run fresh Karaf and owned fake/live peers in Betriebsdaten.
**Tech Stack:** Java21, Maven, JUnit5, Karaf4.4.x, Python3, GitHub Actions.
**Spec:** ../specs/2026-11-01-controller-readiness-design.md

## Global Constraints

- English maintained code/docs/messages; preserve119 existing Controller working-tree entries and unrelated repositories.
- Backup/source baseline: workspace .codex-backups/controller-readiness-20261101-tsalbjmw/.
- Tests use isolated copies/runtimes under Betriebsdaten. Keep original historical Karaf stopped and unchanged.
- Physical target192.168.0.166:9761 is used for read-only validation. No audio, preset or firmware changes.
- Keep current branch; build on archived WIP. Only coordinator stages/commits after review. No forced pushes or remote deployment.

## Review Focus

- At capacity, coalescing must preserve the newest value and safety eviction must explicitly fail the displaced caller.
- Saturation must never close a healthy session or mark a rejected command applied.
- Fresh Karaf must resolve required Jackson/protocol/HTTP/Matrix imports and publish DspService despite failed bootstrap.
- Every live command must be session/bootstrap/read/keepalive only; before/after full configuration bytes must agree.
- CI must reproduce a committed standalone checkout with source-pinned protocol dependency; secret/runtime files must not enter Git.

## Task1: Bound command overload (queue worker)

Files: core DspCommandQueue/dispatcher/exception tests and HTTP mapping/contract tests; no build/CI/integration files.
Interfaces: pending limit64; active exchange additional; RuntimeException or typed exception communicates queue full without transport invalidation; global HTTP mapper returns503.

- [ ] Write and observe RED for overflow, at-capacity coalescing, safety displacement, all-safety rejection and healthy-session preservation.
- [ ] Implement capacity and explicit outcomes; retain ACK/ownership/cancellation invariants.
- [ ] Add overload stress with real concurrent producers/fake responses and verify limits/counts.
- [ ] Run complete module tests in isolated copy; document exact overload contract.

## Task2: Standalone CI and artifact identity (CI worker)

Files: .github/workflows/verify.yml, ci/verify.sh, ci/protocol-revision, rootREADME and parent POM metadata only.
Interfaces: ci/verify.sh builds from independent checkout using DSP408_PROTOCOL_DIR override; optional integration step coordinated with Task3.

- [ ] Reuse pinned actions/protocol commit conventions; validate pin and avoid embedded credentials.
- [ ] Record source revision/dirty state in bundle manifests or packaged build metadata.
- [ ] Verify from a fresh isolated Maven repository and source copy; inspect actual packaged metadata.
- [ ] Document standalone/CI commands and dependency pin.

## Task3: Fresh Karaf integration and simulated failures (Karaf worker)

Files: integration/karaf-smoke.py, integration helpers/tests, docs/KARAF-INTEGRATION.md; feature descriptor/module dependency fixes only after notifying coordinator.
Interfaces: CLI accepts state-dir and built artifact/Maven repo paths; outputs JSON evidence, defaults to loopback fake DSP/unreachable target and guaranteed owned-process cleanup.

- [ ] Obtain a pristine trusted Karaf distribution and use its isolated configuration; copy bin/codex-karaf.py wrapper and follow its doctor/dry-run workflow.
- [ ] Install one coherent bundle set, verify service/HTTP/shell/Matrix activation with auto-connect disabled/unreachable fake target, expose resolver failures and fix dependency declarations.
- [ ] Add repeatable integration invocation suitable for CI; test delayed/fragmented responses, drops and repeated connects against fake peers.
- [ ] Record bundle hashes, service states, HTTP responses, timing and shutdown evidence; leave historical Karaf unchanged.

## Task4: Physical/Dauer validation and final repository (coordinator)

Files: integration/live-readonly tooling and English validation report; relevant docs/lock metadata.

- [ ] Check active sessions/ports; read current full configuration using Controller through an owned logging/allowlisting proxy.
- [ ] Reproduce real bootstrap behavior; any fix gets fake-peer RED/GREEN coverage before hardware rerun.
- [ ] Run read-only duration test (target30min) while other work runs; record samples, missed responses, reconnects, thread/memory behavior and final identical dump.
- [ ] Run full workspace and clean standalone CI checks; independent review complete change, fix substantive findings with regressions.
- [ ] Stage only intended Controller source/docs/workflow, commit logical changes, update local workspace lock; verify fresh committed checkout, refs, status and artifact provenance.

## Execution ruling

The user's 'ja mach das alles' approves implementation of the five listed steps.
No additional design approval loop is required. The current119-entry WIP is
preserved by source archive and used in place; branch/worktree changes would
split it unnecessarily. Fresh copies/runtimes isolate execution state. Progress
and decisions are recorded in the backup directory.
