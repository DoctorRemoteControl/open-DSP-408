# open-DSP-408

Java 21 / OSGi controller for t.racks DSP 408 and FIR408 processors. The Maven
reactor is in [`dsp408-parent`](dsp408-parent/README.md); it contains the core
protocol client, runtime service, shell, HTTP API, Matrix integration and Karaf
features.

## Build and test

Use Java 21, Maven, Git, Bash and Python 3. This independent checkout builds
against the public protocol source commit recorded in
[`ci/protocol-revision`](ci/protocol-revision):

```sh
git clone https://github.com/DoctorRemoteControl/dsp408-protocol.git ../dsp408-protocol
git -C ../dsp408-protocol checkout --detach "$(cat ci/protocol-revision)"
python3 ci/test_verify.py
MAVEN_OPTS="-Dmaven.repo.local=/absolute/path/to/fresh-maven-repository" bash ci/verify.sh
```

The script installs the protocol and all Controller modules after running their
tests. It checks that the protocol checkout matches the full pinned commit SHA.
Set `DSP408_PROTOCOL_DIR` when the protocol checkout is elsewhere. The Maven
repository path should be outside both source checkouts. No repository credentials
are needed. A normal local build can omit `MAVEN_OPTS` to use Maven's default
repository.

Each Controller bundle manifest records `DSP408-Source-Revision`,
`DSP408-Source-Dirty`, `DSP408-Protocol-Revision` and `DSP408-Protocol-Dirty`.
The script derives full revisions and dirty states from the actual checkouts,
including tracked and untracked changes. A Controller source archive without Git
metadata records `unknown`; the protocol requires Git metadata to verify its pin.
Direct Maven builds default all four fields to `unknown`; they do not claim a
clean source revision. Dirty builds identify a commit with local modifications,
not a reproducible committed snapshot.

[GitHub Actions](.github/workflows/verify.yml) uses Java 21, commit-pinned actions,
a read-only token, the pinned public protocol source and a fresh Maven repository.
After unit verification it checks the read-only command guard, runs 100 loopback
connection and fault cycles, and exercises the packaged bundles in a fresh Karaf
with fake DSPs. To run those integration checks locally after `ci/verify.sh`:

```sh
python3 -m unittest discover -s integration -p 'test_*.py'
python3 integration/connection_stress.py --cycles 100 \
  --maven-repo /absolute/path/to/fresh-maven-repository \
  --state-dir /absolute/path/to/fresh-connection-test-state
python3 integration/karaf-smoke.py \
  --maven-repo /absolute/path/to/fresh-maven-repository \
  --state-dir /absolute/path/to/fresh-karaf-test-state
```

See [Karaf integration](docs/KARAF-INTEGRATION.md) for the runtime checks and
evidence. Maven verification and this CI integration use simulated devices; they
do not contact the physical DSP. In the complete local DSP408 workspace,
`mvn verify` from its root builds the sibling modules together.

## Documentation

- [Queue capacity and overload](docs/QUEUE-OVERLOAD.md)
- [Read-only hardware validation](docs/HARDWARE-VALIDATION.md)
- [Controller setup and commands](dsp408-parent/README.md)
- [Connection lifecycle and failure handling](dsp408-parent/docs/CONNECTION-LIFECYCLE.md)
- [HTTP API](dsp408-parent/dsp408-http/README.md)
- [Runtime configuration](dsp408-parent/dsp408-runtime/README.md)
- [Protocol evidence and field libraries](dsp408-parent/docs/protocol-spec/README.md)

The default configured device is `192.168.0.166:9761`. Build artifacts are
independent from an installed Karaf instance; a successful build does not
identify or replace the bundles currently loaded there.
