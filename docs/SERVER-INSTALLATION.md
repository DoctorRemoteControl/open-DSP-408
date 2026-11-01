# Install and operate the headless server

The Linux server archive includes Karaf 4.4.11, the DSP API/runtime and all boot
libraries. Install Java 21 or newer, Python 3.10 or newer and a POSIX shell on the
host. Maven, Node.js, a browser and network access to artifact repositories are
not needed after downloading the archive. DSP control runs in Java; Python only
verifies and prepares the installation/startup before executing the JVM.

The distribution uses [Karaf's custom assembly builder](https://karaf.apache.org/manual/latest/custom-distribution.html).
It does not export a live instance. No device registry, generated SSH key,
operator account or developer Maven repository is bundled. The default profile
starts an empty managed registry and the HTTP API, with no web UI, SSH or JMX
management feature. See [device registration](HEADLESS-SERVER.md).

## Obtain and verify a build

Download the `dsp408-server-<commit>` artifact from a successful GitHub Actions
run. It contains tar.gz, ZIP and `dsp408-server-SHA256SUMS.txt`. Extract the
GitHub artifact wrapper first, then verify the downloads:

```sh
sha256sum --check dsp408-server-SHA256SUMS.txt
tar -xzf dsp408-server-0.0.1-SNAPSHOT.tar.gz
```

The extracted package has its own `release.json` provenance and
`SHA256SUMS.json` covering every payload file. The installer and launcher reject
missing, extra or modified files. These checks detect corruption; obtain the
archive and checksums through a trusted download channel. Development builds
record dirty or unknown source and require `--allow-development-build` when
installed. Normal CI builds record the full Controller and pinned protocol SHAs.

## Install as an unprivileged service user

Choose separate absolute paths for immutable releases and writable state. The
service user must own the state directory and be able to read the release tree.
This example assumes those paths are already writable by the intended user:

```sh
python3 dsp408-server-0.0.1-SNAPSHOT/server/dsp408ctl.py install \
  --install-dir /opt/dsp408 \
  --state-dir /var/lib/dsp408
```

The installer creates `releases/<releaseId>` and selects it with the relative
`current` symlink. It creates default operator settings only when absent. It
does not start or stop services. Existing differing releases are never replaced.
An identical reinstall is idempotent. Keep state outside the installation tree.

Persistent layout:

| Path under state directory | Purpose |
| --- | --- |
| `config/server.json` | Operator listener and polling settings |
| `device-registry.json` | API-managed DSP definitions |
| `log/` | Shared rotating runtime logs |
| `runtimes/<releaseId>/` | Per-release generated config and disposable caches |
| `run/server.lock` | Exclusive admission for one server using this state |

Use literal local IPv4/IPv6 bind addresses in `config/server.json`, for example:

```json
{
  "schemaVersion": 1,
  "listenAddresses": ["127.0.0.1", "192.168.0.101"],
  "httpPort": 8182,
  "meterPollIntervalMs": 200
}
```

Default bind is `127.0.0.1`, port `8181`. A listener change takes effect after
restart. Invalid configuration fails startup. The launcher regenerates Karaf's
runtime-specific `etc` from the selected release and these settings; edit this
persistent JSON rather than generated Karaf files. Device auto-connect belongs
to each API registration, not to the server listener settings.

Foreground operation is useful for diagnosis:

```sh
python3 /opt/dsp408/current/server/run.py --state-dir /var/lib/dsp408
```

It validates Java and package integrity, locks the state, clears inherited
Karaf/Java/display overrides and executes the native server in the foreground
with headless mode. A second server using that state fails before starting.
Each release gets a separate cache, so a newer SNAPSHOT cannot reuse an older
release's bundle cache. Only the bundled system repository is available to the
runtime resolver. Keep release files intact; startup does not write into them.

## Install a systemd service

For a host service independent of login, render a unit naming an existing
non-root account. Run these commands with the appropriate administrative
permissions after assigning directory ownership to that account:

```sh
python3 /opt/dsp408/current/server/dsp408ctl.py service-file \
  --install-dir /opt/dsp408 --state-dir /var/lib/dsp408 \
  --scope system --user dsp408 > /tmp/dsp408-server.service
sudo install -m 0644 /tmp/dsp408-server.service /etc/systemd/system/dsp408-server.service
sudo systemctl daemon-reload
sudo systemctl enable --now dsp408-server.service
```

The generated unit uses `Type=simple`, restarts failed processes, and refers to
`current`, so the next service restart selects the installed release. The
installer never creates users or elevates privileges. Check operation with
`systemctl status dsp408-server` and `journalctl -u dsp408-server`; rotating
logs also remain under the state directory.

For an existing user-owned installation, render with `--scope user` instead,
write the file to `~/.config/systemd/user/dsp408-server.service`, and use
`systemctl --user daemon-reload` and `systemctl --user enable --now dsp408-server`.
User services normally start with the user manager. Boot without login also
requires lingering, enabled by an administrator with
`sudo loginctl enable-linger USERNAME`. This affects that user's enabled user
services; a dedicated system service account is preferable on a production host.

## Update and roll back

Install a new extracted archive using the same install/state paths. Previous
releases remain in place; the old selection is kept as `previous`. The running
JVM keeps using its existing release until an explicit service restart. Release
selection has a recovery journal: after an interrupted install or rollback, the
next install/rollback restores the complete previous selection before proceeding.

```sh
python3 new-package/server/dsp408ctl.py install \
  --install-dir /opt/dsp408 --state-dir /var/lib/dsp408
sudo systemctl restart dsp408-server
curl --fail-with-body http://localhost:8181/api/v1/health
```

To select the previous release and restart:

```sh
python3 /opt/dsp408/current/server/dsp408ctl.py rollback \
  --install-dir /opt/dsp408 --state-dir /var/lib/dsp408
sudo systemctl restart dsp408-server
```

Settings and registry are preserved by both operations. Back them up before
upgrades. Rollback selects code; it does not reverse device audio settings or
migrate registry schemas backward. This version uses registry schema 1. Never
run two different state directories against a manually shared registry file.

## Build and verify from source

Run `ci/verify.sh` against the pinned protocol checkout. The Maven reactor now
also builds `dsp408-parent/dsp408-server/target/dsp408-server-*.tar.gz` and ZIP.
The archive is finalized after dependency assembly and before compression.

```sh
python3 -m unittest discover -s tests -p 'test_*.py'
python3 integration/server-package-smoke.py \
  --archive dsp408-parent/dsp408-server/target/dsp408-server-0.0.1-SNAPSHOT.tar.gz \
  --state-dir /absolute/new/package-test-state
```

The test installs a real archive with fresh caches, boots offline, rejects a
duplicate process, reads a complete loopback DSP configuration, restarts with a
changed listen port, and verifies registry retention and unchanged release
payload. No physical DSP or audio writes are involved. Use
`--allow-development-build` only for intentional local dirty/unknown builds.


On Linux with a running user systemd manager, also check the actual generated
unit, special paths, stop/start persistence and forced-crash recovery:

```sh
python3 integration/server-service-smoke.py \
  --archive dsp408-parent/dsp408-server/target/dsp408-server-0.0.1-SNAPSHOT.tar.gz \
  --state-dir /absolute/new/service-test-state
```

This optional local check creates, starts and removes its own uniquely named
unit. It does not enable a boot service or change lingering. Use the development
flag only for intentional development artifacts.
