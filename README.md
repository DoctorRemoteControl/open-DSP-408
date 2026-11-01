# open-DSP-408

Java 21 / OSGi controller for t.racks DSP 408 and FIR408 processors. The Maven
reactor is in [`dsp408-parent`](dsp408-parent/README.md); it contains the core
protocol client, runtime service, shell, HTTP API, Matrix integration and Karaf
features.

## Build and test

Install the shared protocol library before building this independent checkout:

```sh
git clone https://github.com/DoctorRemoteControl/dsp408-protocol.git ../dsp408-protocol
mvn -f ../dsp408-protocol/pom.xml install
mvn -f dsp408-parent/pom.xml verify
```

When using the complete local DSP408 workspace, `mvn verify` from its root
builds the sibling modules together. Maven verification uses simulated devices;
it does not connect to the configured physical DSP.

## Documentation

- [Controller setup and commands](dsp408-parent/README.md)
- [Connection lifecycle and failure handling](dsp408-parent/docs/CONNECTION-LIFECYCLE.md)
- [HTTP API](dsp408-parent/dsp408-http/README.md)
- [Runtime configuration](dsp408-parent/dsp408-runtime/README.md)
- [Protocol evidence and field libraries](dsp408-parent/docs/protocol-spec/README.md)

The default configured device is `192.168.0.166:9761`. Build artifacts are
independent from an installed Karaf instance; a successful build does not
identify or replace the bundles currently loaded there.
