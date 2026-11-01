# Server Package and Service Implementation Plan

> **For agentic workers:** Use the written design and TDD. Parent owns assembly/integration; one delegated deployment worker owns disjoint Python tooling. One independent final review covers the complete change.

**Goal:** Deliver an offline-installable headless Java server with persistent configuration, rollback and systemd operation.
**Architecture:** Karaf 4.4.11 Maven assembly, immutable releases and external state, foreground launcher and stable service paths.
**Tech Stack:** Java 21, Maven, Karaf, Python standard library, systemd on Linux.
**Spec:** ../specs/2026-11-01-server-package-service-design.md

## Constraints and review focus

Preserve existing repositories and dirty Raider JSON. No physical audio writes. No live credentials/configuration in packages. Updates must preserve registry; failed installs must preserve current. Reused SNAPSHOT artifacts need release-specific caches. Check duplicate processes, broken/hostile symlinks, spaces and systemd specifiers in paths, interrupted installation, offline boot, and supervisor stop/restart behavior. Boot enablement needs real host permissions and must be reported honestly.

### Task 1: Deployment tooling

Owner: delegated worker. Files: dsp408-parent/dsp408-server/src/main/resources/assembly/server/{dsp408ctl.py,run.py,common.py}, tests/test_server_deployment.py (may split focused helper modules).
Consumes: release.json and SHA256SUMS.json defined by spec; Karaf bin/karaf and etc templates.
Produces: install/rollback/service-file CLI and foreground launcher, external state contract above.
- [x] Write and watch failing install, rollback, integrity, hostile-layout and foreground invocation tests.
- [x] Implement smallest version satisfying spec, preserve settings/registry on failure and update.
- [x] Run complete deployment unit suite and report exact RED/GREEN evidence.

### Task 2: Offline Maven server distribution

Owner: parent. Files: dsp408-parent/pom.xml, dsp408-parent/dsp408-server/pom.xml, ci/prepare-server-package.py, tests/test_server_package.py.
Consumes: deployment overlay from task1. Produces: module tar.gz/ZIP and integrity/provenance metadata.
- [x] Write metadata/integrity regressions and a real missing-module/package acceptance gate.
- [x] Add standard Karaf assembly with framework4.4.11 and Controller feature descriptors, javase21 and minimal log/config/feature/dsp408-server boot features.
- [x] Finalize package metadata after assembly and before archive; verify no runtime state or developer credentials.
- [x] Build actual archive and validate metadata/contents.

### Task 3: Package smoke, CI and documentation

Owner: parent. Files: integration/server-package-smoke.py, .github/workflows/verify.yml, README.md, docs/SERVER-INSTALLATION.md, docs/HEADLESS-SERVER.md.
Consumes: install CLI + built tar.gz. Produces: fresh offline startup/restart/duplicate-process evidence and CI archive artifact.
- [x] Exercise extracted payload in isolated state with no Maven/build dependencies, verify global/selected API and no /app.
- [x] Register without network traffic, restart, verify durable definitions and lock admission. Exercise local systemd service lifecycle independently of shell completion.
- [x] Keep existing complete Java/Python checks green; add package validation to CI and document install/update/rollback/service commands.

### Task 4: Review and integration

- [x] Independent review, meaningful RED/GREEN fixes for important findings; run final suites.
- [ ] Commit and push reviewed source; rebuild clean artifact with exact provenance and wait for CI.
- [ ] Back up and migrate only owned disconnected LAN instance, preserving main registration, LAN bind and port8182. Install stable persistent service; attempt authorized boot enablement without asking for credentials.
- [ ] Archive package, hashes, reports and ownership proof; update root lock and verify sibling edits unchanged.

## Ledger

Pre-flight: metadata/schema/paths/CLI are fixed in the spec before parallel work. Parent and worker source ownership is disjoint. User's weiter authorizes the recommended server-package/durable-service step; no extra design confirmation is needed.


* Task1: installer/foreground/service tooling is implemented. 21 deployment unit tests and 6 metadata tests pass; expected RED runs retained before implementation and hardening fixes.
* Task2: actual Karaf assembly builds tar.gz/ZIP with five DSP/protocol bundles and offline closure. Standard Karaf data/tmp/README is an allowed distribution placeholder; live data/logs/registry/host keys remain rejected. Minimal profile contains no SSH/JMX or UI feature. Assembly metadata finalized before archive.
* Task3: real package smoke passed in5.241s: offline fresh boot, Java headless, lock rejection, full loopback dump, operator port change on restart, exact registry retention, idempotent reinstall and unchanged payload. Generated user-service smoke passed in11.769s with spaces/%/$, exact registry retention after stop/start and forced SIGKILL recovery to a new JavaPID. Owned test units/processes/listeners removed; previous live instance untouched.
* CI: full clean Controller install passed550 Java tests including new distribution module. Python deployment/metadata27, existing integration21 and provenance8 passed; actionlint passed. Archive upload action v7.0.2 pinned to the verified official commit. No GitHub Release/tag created.

* Final review: three Important findings reproduced and fixed. Non-executable launchers are rejected before install/rollback selection; all JSON inputs require ordinary non-symlink files before reading; release selection has a recoverable journal under the installation lock. Five new regression cases failed before the fixes (six failures including FIFO/symlink subcases); all32 Python tests now pass. Interrupted install and rollback are exercised in subprocesses terminated between pointer replacements.
* Final: minor (deferred): service smoke can leave its uniquely named inert unit file after a partial write failure because cleanup requires exact complete bytes. Active-unit cleanup is checked; this narrow test-helper failure remains documented for later improvement.

* Reviewed package/service gates pass again after all Important fixes: package5.434s, generated systemd service11.741s. All32 Python deployment/metadata tests,21 existing integration tests,8 provenance tests and actionlint pass. Complete workspace Maven verify passed830 Java tests and included the new archive module. No physical DSP writes or contacts were performed by these tests.
