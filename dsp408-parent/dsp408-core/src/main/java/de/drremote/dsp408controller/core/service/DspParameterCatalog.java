package de.drremote.dsp408controller.core.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;
import de.drremote.dsp408controller.core.library.DspFieldLocation;
import de.drremote.dsp408controller.core.library.DspLibrary;
import de.drremote.dsp408controller.core.library.DspMuteReadEvidence;
import de.drremote.dsp408controller.core.library.DspWriteCapabilities.Operation;
import de.drremote.dsp408controller.core.protocol.DspChannel;

/** Immutable, RAM-only capabilities for one captured device identity and library. */
public final class DspParameterCatalog {
    private final DspLibrary library;
    private final String deviceVersion;
    private final boolean connected;
    private final boolean recognized;
    private final List<DspParameterCapability> parameters;

    DspParameterCatalog(DspLibrary library, String deviceVersion, boolean connected) {
        this.library = Objects.requireNonNull(library);
        this.deviceVersion = deviceVersion;
        this.connected = connected;
        recognized = ("DSP408 V0104".equals(deviceVersion)
                && "dsp408_field_library".equals(library.libraryType()))
                || ("FIR408 V0113P".equals(deviceVersion)
                && "dsp408_fir_field_library".equals(library.libraryType()));
        List<DspParameterCapability> entries = new ArrayList<>();
        for (DspChannel channel : DspChannel.values()) {
            for (DspParameter parameter : DspParameter.values()) {
                entries.add(describe(channel, parameter));
            }
        }
        parameters = List.copyOf(entries);
    }

    public String deviceVersion() { return deviceVersion; }
    public String libraryType() { return library.libraryType(); }
    public boolean connected() { return connected; }
    public boolean firmwareRecognized() { return recognized; }
    public List<DspParameterCapability> parameters() { return parameters; }

    public DspParameterCapability capability(DspChannel channel, DspParameter parameter) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(parameter, "parameter");
        return parameters.get(channel.index() * DspParameter.values().length + parameter.ordinal());
    }

    /** Validates before encoding; normalization follows the existing wire codec exactly. */
    public Object normalize(DspParameter parameter, Object value) {
        if (parameter == null) throw new IllegalArgumentException("Parameter is required");
        if (parameter == DspParameter.MUTE || parameter == DspParameter.PHASE) {
            if (!(value instanceof Boolean)) throw new IllegalArgumentException(parameter.id() + " requires a boolean");
            return value;
        }
        if (!(value instanceof Number number)) throw new IllegalArgumentException(parameter.id() + " requires a number");
        double numeric = number.doubleValue();
        double minimum = parameter == DspParameter.GAIN ? -60.0 : 0.0;
        double maximum = parameter == DspParameter.GAIN ? 12.0 : 680.0;
        if (!Double.isFinite(numeric) || numeric < minimum || numeric > maximum) {
            throw new IllegalArgumentException(parameter.id() + " must be finite and within [" + minimum + ", " + maximum + "]");
        }
        var codec = parameter == DspParameter.GAIN ? DspCodecRegistry.gain() : DspCodecRegistry.delayMs();
        return codec.rawToDouble(codec.doubleToRaw(numeric));
    }

    DspLibrary library() { return library; }

    DspFieldLocation readLocation(DspChannel channel, DspParameter parameter) {
        for (int block = 0; block <= library.maxParameterBlockIndex(); block++) {
            List<DspFieldLocation> locations = switch (parameter) {
                case GAIN -> library.gainReadLocationsForBlock(block);
                case PHASE -> library.phaseReadLocationsForBlock(block);
                case DELAY -> library.delayReadLocationsForBlock(block);
                case MUTE -> List.of();
            };
            for (DspFieldLocation location : locations) {
                if (location.channel() == channel) return location;
            }
        }
        return null;
    }

    private DspParameterCapability describe(DspChannel channel, DspParameter parameter) {
        boolean writeMapped = parameter != DspParameter.PHASE || library.writeCapabilities().supports(Operation.PHASE, channel);
        boolean readMapped = parameter == DspParameter.MUTE
                ? DspMuteReadEvidence.supports(library, channel) : readLocation(channel, parameter) != null;
        boolean writable = connected && recognized && writeMapped;
        boolean inferredRead = (parameter == DspParameter.MUTE && library.hasFirFeatures())
                || (parameter == DspParameter.DELAY && "dsp408_field_library".equals(library.libraryType())
                && (channel == DspChannel.IN_B || channel == DspChannel.IN_C));
        String readEvidence = readMapped ? "observed" : inferredRead ? "inferred" : "unavailable";
        String reason = !connected ? "DSP is disconnected"
                : !recognized ? "Firmware identity and library are not a recognized profile"
                : !writeMapped ? "Write mapping is not confirmed"
                : !readMapped ? "Readback mapping is not confirmed for this channel" : null;
        return switch (parameter) {
            case GAIN -> new DspParameterCapability(parameter, channel, "number", "dB", -60.0, 12.0,
                    List.of(new DspParameterStep(-60, -20, .5), new DspParameterStep(-20, 12, .1)),
                    writable, recognized && readMapped, writeEvidence(channel, parameter), readEvidence, reason);
            case DELAY -> new DspParameterCapability(parameter, channel, "number", "ms", 0.0, 680.0,
                    List.of(new DspParameterStep(0, 680, 1.0 / 96.0)),
                    writable, recognized && readMapped, writeEvidence(channel, parameter), readEvidence, reason);
            case MUTE, PHASE -> new DspParameterCapability(parameter, channel, "boolean", null, null, null,
                    List.of(), writable, recognized && readMapped, writeEvidence(channel, parameter), readEvidence, reason);
        };
    }

    private String writeEvidence(DspChannel channel, DspParameter parameter) {
        if (library.hasFirFeatures()) return "observed_layout_with_linear_channel_mapping";
        if (parameter == DspParameter.DELAY) {
            return channel == DspChannel.IN_A || channel == DspChannel.OUT_8 ? "observed" : "inferred_linear_channel_mapping";
        }
        return channel.index() < 4 ? "observed" : "documented_channel_mapping";
    }
}
