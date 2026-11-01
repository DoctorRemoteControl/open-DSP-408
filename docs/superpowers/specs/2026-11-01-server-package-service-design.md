# Installable headless server and persistent service

The user approved continuing with the recommended server package and permanent service. Build on the existing Java 21/Karaf API server; UI remains optional. All maintained content is English. No physical DSP control writes, unrelated repository changes or historical-runtime changes are needed.

## Approach

Use the pinned Karaf 4.4.11 Maven assembly builder rather than exporting a running instance or writing a second OSGi dependency resolver. Add dsp408-server as the last parent Maven module, packaging karaf-assembly, producing tar.gz and ZIP. Ship Java libraries and configuration templates, with no live registry, caches, credentials or developer Maven repository. Java 21, Python 3.10+ (startup/installation tooling) and a POSIX shell are host prerequisites. Running DSP logic remains Java. No Maven, Node, browser or network artifact resolution is needed on the installed host.

A small installer with two release pointers is preferable to copying over an installed runtime: releases stay intact and rollback selects the previous release. Persistent operator configuration and registry live outside releases. Full OS package management is deferred; the archive and systemd unit are sufficient for this increment.

## Distribution contract

Archive root is dsp408-server-0.0.1-SNAPSHOT (project version). The extracted root contains bin/karaf, etc/, system/ and server/ Python tooling. Parent owns Maven POM and ci/prepare-server-package.py; deployment implementation owns src/main/resources/assembly/server/ and tests only.

Root release.json fields: schemaVersion=1, releaseId (safe ID), version, sourceRevision (40-char SHA or unknown), sourceDirty (true/false/unknown strings), protocolRevision, protocolDirty, karafVersion=4.4.11, javaMajor=21. releaseId is version + '-' + sourceRevision[:12] (unknown accepted for development) + '-dirty' when sourceDirty is not false.

Root SHA256SUMS.json is {"schemaVersion":1,"files":{"relative/path":"64-char lowercase sha256",...}}. It covers every regular payload file including release.json, excluding itself. Reject unsafe paths, payload symlinks, unknown schema, missing/extra/modified files, invalid release IDs. This is corruption detection; authenticated distribution remains the responsibility of the download channel. Development provenance requires explicit --allow-development-build on installation.

## Installation and runtime contract

CLI: python3 <unpacked>/server/dsp408ctl.py install --install-dir ABS --state-dir ABS [--allow-development-build]. Install into install-dir/releases/releaseId, atomic relative current/previous symlinks. Reinstalling the identical verified release is idempotent; an existing different release directory must not be overwritten. No automatic start/stop or deletion. rollback --install-dir ABS --state-dir ABS selects previous without deleting releases or changing registry. Verification, copying or pointer failures must preserve the prior usable installation. Refuse hostile path/symlink layouts and overlapping state/release trees.

Persistent state/config/server.json fields: schemaVersion=1, listenAddresses=["127.0.0.1"], httpPort=8181, meterPollIntervalMs=200. Preserve operator config byte-for-byte on reinstall/update. Registry: state/device-registry.json. Never reset an existing registry. Runtime-specific etc and data are state/runtimes/releaseId/{etc,data}; seed runtime etc from the selected release on each start then apply the persistent JSON settings. A separate cache per release prevents old SNAPSHOT bundles from surviving an update. Shared logs: state/log. Runtime registry mode is always managed, auto.connect=false root; per-device autoConnect remains stored in registry. No /app, SSH or management service required for default startup.

Launcher: python3 install-dir/current/server/run.py --state-dir ABS. Verify release integrity and Java version >=21, take an exclusive state/run/server.lock before touching active config, prepare settings, scrub inherited Karaf/Java/display variables, explicitly bind KARAF_HOME/BASE/DATA/ETC and java.awt.headless=true. Exec the selected native bin/karaf server in the foreground with an inheritable lock descriptor. Duplicate launch must fail immediately. The state owner retains writable runtime/cache/config/registry, while release payload is untouched. Settings validate types, port, bind addresses and meter interval; no silent fallback after invalid config. Use only the bundled system Maven repository with a separate empty local Maven cache and empty settings, offline resolution enabled.

Service-file command emits a systemd unit for --scope user or system, with absolute install/state paths, Type=simple, Restart=on-failure, appropriate restart/stop limits and default.target (user) or multi-user.target (system). Quote arguments for systemd, including %/$/spaces. System scope takes an explicit non-root service user. The stable current path selects upgrades on restart. Generated files do not enable or restart services automatically. Actual local deployment uses the already authorized owned LAN server only, preserving its registry and port8182. Root authentication may be required for boot without login; do all reviewable packaging/service work first, then report any precise privileged step that cannot run.

## Acceptance

Unit tests exercise actual install/update/rollback/corruption/failure behavior and real launcher invocation against a harmless foreground fixture. Real package smoke boots only bundled offline dependencies with fresh caches, starts empty, registers an offline loopback DSP, restarts and retains definitions, rejects a duplicate process and disables UI. Test service lifecycle on an isolated owned user unit before replacing the current owned LAN development instance. Back up its config/registry first and refuse an unnoticed connected DSP. Production startup must persist independently of the command terminal. No reboot of the user's machine.

CI builds and validates the archive, then uploads it as a build artifact using a commit-pinned action. Publishing a GitHub Release/tag is outside this increment. One independent whole-change review; fix important findings with RED/GREEN tests. Existing commit/push authorization persists. Retain test/evidence outside source and update workspace lock after the final source commit.
