# Controller validation and repository readiness

The user approved all five proposed next steps: commit the local work and add
Controller CI; test coherent bundles in fresh Karaf; validate the actual DSP;
run duration/fault tests; and bound command overload. Implementation remains
Java21/Maven and builds on the existing extracted architecture. All maintained
text is English.

## Chosen approach

Use a clean isolated Karaf distribution in Betriebsdaten, not the historical
installation. Use loopback fake DSPs for outages/overload and physical
192.168.0.166:9761 for read-only handshake, complete dumps, meter traffic and
reconnect validation. No audio/preset/FIR writes, production runtime restart or
remote deployment is needed. Record before/after configuration hashes and own
all temporary processes. Reuse source-pinned CI conventions from the sibling
protocol/proxy/Raider repositories. Commit the Controller work only after full
checks, fresh-copy validation and independent review. Publishing is not needed
for local validation; keep CI ready and test its commands locally.

A new architecture rewrite would not test the missing deployment boundary.
Reusing the historical Karaf cache would conceal mixed-bundle issues. The chosen
fresh instance makes dependency and service-activation problems observable.

## Behavior and evidence

Bound pending commands to64 (plus one active exchange). Coalesce matching keys
before applying capacity. Never evict an active request. A safety command may
replace a strictly lower-priority queued command with explicit failure; when
that is impossible, reject explicitly. Overload cannot update device state or
pretend completion; HTTP returns503 with structured error. Bounded overload
must not invalidate an otherwise usable session.

CI installs a pinned protocol source commit, builds all Controller modules and
records source revision/dirty state in artifacts. Karaf integration uses matching
API/core/runtime/shell/HTTP artifacts and verifies service/API availability even
with no DSP. Matrix is installed without credentials and must not contact a
configured homeserver. Exercise default and selected-device routing where useful.

Duration tests must produce measured counts, error/timing summaries, resource
samples and process-cleanup evidence. Aim for a30-minute read-only physical soak
while independent work proceeds, plus deterministic simulated faults/repeated
reconnects. If the physical target is unavailable, retain the exact connection
error, finish all independent work and clearly report the remaining live gap.

Original protocol JSON, captures, historical logs, independent repository refs,
credentials and unrelated Raider WIP are preserved. No broad cleanup/moves.
