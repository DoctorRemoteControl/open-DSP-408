package de.drremote.dsp408controller.util;

import java.util.List;
import java.util.Locale;

import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.state.ChannelState;
import de.drremote.dsp408controller.core.state.CompressorState;
import de.drremote.dsp408controller.core.state.CrossoverFilterState;
import de.drremote.dsp408controller.core.state.CrossoverState;
import de.drremote.dsp408controller.core.state.DspState;
import de.drremote.dsp408controller.core.state.InputGeqBandState;
import de.drremote.dsp408controller.core.state.InputGeqState;
import de.drremote.dsp408controller.core.state.InputGateState;
import de.drremote.dsp408controller.core.state.InputPeqState;
import de.drremote.dsp408controller.core.state.LimiterState;
import de.drremote.dsp408controller.core.state.MatrixOutputState;
import de.drremote.dsp408controller.core.state.MeterChannelState;
import de.drremote.dsp408controller.core.state.OutputPeqState;
import de.drremote.dsp408controller.core.state.PeqBandState;
import de.drremote.dsp408controller.core.state.TestToneState;

public final class DspTextFormatter {
    private DspTextFormatter() {
    }

    public static String formatState(DspState state) {
        if (state == null) {
            return "State is null.";
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;

        for (ChannelState channelState : state.allChannels()) {
            if (!first) {
                sb.append(System.lineSeparator()).append(System.lineSeparator());
            }
            sb.append(formatChannelState(state, channelState.channel()));
            first = false;
        }

        sb.append(System.lineSeparator()).append(System.lineSeparator());
        sb.append(formatMatrixState(state));

        String meterText = formatMetersState(state);
        if (!meterText.isBlank()) {
            sb.append(System.lineSeparator()).append(System.lineSeparator());
            sb.append(meterText);
        }

        String testToneText = formatTestToneState(state);
        if (!testToneText.isBlank()) {
            sb.append(System.lineSeparator()).append(System.lineSeparator());
            sb.append(testToneText);
        }

        if (!state.hasAnyValues()) {
            sb.append(System.lineSeparator());
            if (state.hasCachedBlocks()) {
                sb.append("Blocks were read, but no known values could be decoded yet.");
            } else {
                sb.append("No values read yet. Use 'scanblocks' or 'refresh'.");
            }
        }

        return sb.toString().trim();
    }

    public static String formatMatrixState(DspState state) {
        if (state == null) {
            return "Matrix: -";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Matrix:");

        for (MatrixOutputState outputState : state.allMatrixOutputs()) {
            sb.append(System.lineSeparator());
            sb.append(formatMatrixOutputState(outputState));
        }

        return sb.toString();
    }

    public static String formatMetersState(DspState state) {
        if (state == null || state.meterState() == null || !state.meterState().hasAnyValues()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("Meters:");
        for (MeterChannelState meter : state.meterState().allChannels()) {
            if (!meter.hasAnyValues()) {
                continue;
            }
            sb.append(System.lineSeparator());
            sb.append(String.format(
                    Locale.ROOT,
                    "%-5s  Raw=%-8s Frac=%-6s Clip=%-3s Src=%s",
                    meter.channel().displayName(),
                    meter.rawValue() == null ? "-" : String.format(Locale.ROOT, "0x%04X", meter.rawValue()),
                    meter.slotByte2() == null ? "-" : String.format(Locale.ROOT, "0x%02X", meter.slotByte2()),
                    meter.clipActive() == null ? "-" : (meter.clipActive() ? "ON" : "off"),
                    source(meter.lastUpdateSource())
            ));
        }

        if (state.meterState().statusByteRaw() != null) {
            sb.append(System.lineSeparator());
            sb.append(String.format(
                    Locale.ROOT,
                    "STAT   Raw=%-8s Src=%s",
                    String.format(Locale.ROOT, "0x%02X", state.meterState().statusByteRaw()),
                    source(state.meterState().lastStatusUpdateSource())
            ));
        }

        return sb.toString();
    }

    public static String formatTestToneState(DspState state) {
        if (state == null || state.testToneState() == null || !state.testToneState().hasAnyValues()) {
            return "";
        }

        TestToneState tone = state.testToneState();
        String source = formatValueWithStatus(
                tone.sourceLabel() == null
                        ? (tone.sourceIndex() == null ? null : String.format(Locale.ROOT, "raw=%d", tone.sourceIndex()))
                        : tone.sourceLabel(),
                tone.sourceConfirmedFromDevice(),
                tone.lastSourceUpdateSource()
        );
        String sine = formatValueWithStatus(
                tone.sineFrequencyHz() == null
                        ? (tone.sineFrequencyRaw() == null ? null : String.format(Locale.ROOT, "raw=%d", tone.sineFrequencyRaw()))
                        : String.format(Locale.ROOT, "%.1f Hz (raw=%d)", tone.sineFrequencyHz(), tone.sineFrequencyRaw()),
                tone.sineFrequencyConfirmedFromDevice(),
                tone.lastSineFrequencyUpdateSource()
        );

        return "TestTone: Source=" + source + " Sine=" + sine;
    }

    public static String formatChannelState(ChannelState state) {
        return formatChannelState(state, null, null, null, null, null, null, null);
    }

    public static String formatChannelState(DspState dspState, DspChannel channel) {
        if (dspState == null) {
            return "State is null.";
        }
        if (channel == null) {
            return "Channel is null.";
        }

        return formatChannelState(
                dspState.channel(channel),
                dspState.crossover(channel),
                dspState.inputGate(channel),
                dspState.inputGeq(channel),
                dspState.inputPeq(channel),
                dspState.outputPeq(channel),
                dspState.compressor(channel),
                dspState.limiter(channel)
        );
    }

    private static String formatChannelState(ChannelState state,
                                             CrossoverState crossoverState,
                                             InputGateState inputGateState,
                                             InputGeqState inputGeqState,
                                             InputPeqState inputPeqState,
                                             OutputPeqState outputPeqState,
                                             CompressorState compressorState,
                                             LimiterState limiterState) {
        if (state == null) {
            return "Channel state is null.";
        }

        String channel = state.channel().displayName();

        String gain = state.gainDb() == null ? "-" : String.format(Locale.ROOT, "%.2f dB", state.gainDb());
        String mute = state.muted() == null ? "-" : (state.muted() ? "yes" : "no");
        String phase = state.phaseInverted() == null ? "-" : (state.phaseInverted() ? "180" : "0");
        String delay = state.delayMs() == null ? "-" : String.format(Locale.ROOT, "%.2f ms", state.delayMs());

        String line1 = String.format(
                Locale.ROOT,
                "%-5s  Gain=%-12s Mute=%-4s Phase=%-3s Delay=%s",
                channel,
                gain,
                mute,
                phase,
                delay
        );

        String line2 = String.format(
                Locale.ROOT,
                "%-5s  %s  %s  %s  %s",
                "",
                formatParamStatus("G", state.gainConfirmedFromDevice(), state.gainDirty(), state.lastGainUpdateSource()),
                formatParamStatus("M", state.muteConfirmedFromDevice(), state.muteDirty(), state.lastMuteUpdateSource()),
                formatParamStatus("P", state.phaseConfirmedFromDevice(), state.phaseDirty(), state.lastPhaseUpdateSource()),
                formatParamStatus("D", state.delayConfirmedFromDevice(), state.delayDirty(), state.lastDelayUpdateSource())
        );

        StringBuilder sb = new StringBuilder();
        sb.append(line1).append(System.lineSeparator()).append(line2);

        if (crossoverState != null && crossoverState.hasAnyValues()) {
            if (crossoverState.highPass().hasAnyValues()) {
                sb.append(System.lineSeparator());
                sb.append(formatCrossoverState("XHP", crossoverState.highPass()));
            }
            if (crossoverState.lowPass().hasAnyValues()) {
                sb.append(System.lineSeparator());
                sb.append(formatCrossoverState("XLP", crossoverState.lowPass()));
            }
        }

        if (inputGateState != null && inputGateState.hasAnyValues()) {
            sb.append(System.lineSeparator());
            sb.append(formatInputGateState(inputGateState));
        }

        if (inputGeqState != null && inputGeqState.hasAnyValues()) {
            for (InputGeqBandState bandState : inputGeqState.bands()) {
                if (!bandState.hasAnyValues()) {
                    continue;
                }
                sb.append(System.lineSeparator());
                sb.append(formatInputGeqBandState(bandState));
            }
        }

        if (inputPeqState != null && inputPeqState.hasAnyValues()) {
            for (PeqBandState bandState : inputPeqState.bands()) {
                if (!bandState.hasAnyValues()) {
                    continue;
                }
                sb.append(System.lineSeparator());
                sb.append(formatPeqBandState("IPEQ", bandState, true));
            }
        }

        if (outputPeqState != null && outputPeqState.hasAnyValues()) {
            for (PeqBandState bandState : outputPeqState.bands()) {
                if (!bandState.hasAnyValues()) {
                    continue;
                }
                sb.append(System.lineSeparator());
                sb.append(formatPeqBandState("OPEQ", bandState, true));
            }
        }

        if (compressorState != null && compressorState.hasAnyValues()) {
            sb.append(System.lineSeparator());
            sb.append(formatCompressorState(compressorState));
        }

        if (limiterState != null && limiterState.hasAnyValues()) {
            sb.append(System.lineSeparator());
            sb.append(formatLimiterState(limiterState));
        }

        return sb.toString();
    }

    private static String formatMatrixOutputState(MatrixOutputState state) {
        if (state == null) {
            return "-";
        }

        String routed = state.routedInputsSnapshot().isEmpty()
                ? "-"
                : state.routedInputsSnapshot().stream()
                        .sorted(java.util.Comparator.comparingInt(DspChannel::index))
                        .map(DspChannel::displayName)
                        .collect(java.util.stream.Collectors.joining("+"));

        return String.format(
                Locale.ROOT,
                "%s  Route=%-4s InA=%-10s InB=%-10s InC=%-10s InD=%s",
                state.output().displayName(),
                routed,
                formatDb(state.crosspointGain(DspChannel.IN_A)),
                formatDb(state.crosspointGain(DspChannel.IN_B)),
                formatDb(state.crosspointGain(DspChannel.IN_C)),
                formatDb(state.crosspointGain(DspChannel.IN_D))
        );
    }

    private static String formatDb(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.2f dB", value);
    }

    private static String formatCrossoverState(String prefix, CrossoverFilterState state) {
        if (state == null) {
            return prefix + "   -";
        }

        return String.format(
                Locale.ROOT,
                "%-5s  Freq=%-22s Slope=%-28s Bypass=%s",
                prefix,
                formatValueWithStatus(
                        state.frequencyHz() == null ? null : formatFrequency(state.frequencyHz()),
                        state.frequencyConfirmedFromDevice(),
                        state.lastFrequencyUpdateSource()
                ),
                formatValueWithStatus(
                        state.slope() == null ? null : state.slope().displayName(),
                        state.slopeConfirmedFromDevice(),
                        state.lastSlopeUpdateSource()
                ),
                formatValueWithStatus(
                        state.bypass() == null ? null : (state.bypass() ? "on" : "off"),
                        state.bypassConfirmedFromDevice(),
                        state.lastBypassUpdateSource()
                )
        );
    }

    private static String formatPeqBandState(String prefix, PeqBandState state, boolean showBypass) {
        if (state == null) {
            return prefix + " -";
        }

        String base = String.format(
                Locale.ROOT,
                "%s%-2d  Type=%-28s Gain=%-22s Freq=%-22s Q=%s",
                prefix,
                state.bandIndex(),
                formatValueWithStatus(
                        state.filterType() == null ? null : state.filterType().displayName(),
                        state.typeConfirmedFromDevice(),
                        state.lastTypeUpdateSource()
                ),
                formatValueWithStatus(
                        state.gainDb() == null ? null : String.format(Locale.ROOT, "%.1f dB", state.gainDb()),
                        state.gainConfirmedFromDevice(),
                        state.lastGainUpdateSource()
                ),
                formatValueWithStatus(
                        state.frequencyHz() == null ? null : formatFrequency(state.frequencyHz()),
                        state.frequencyConfirmedFromDevice(),
                        state.lastFrequencyUpdateSource()
                ),
                formatValueWithStatus(
                        state.q() == null ? null : String.format(Locale.ROOT, "%.2f", state.q()),
                        state.qConfirmedFromDevice(),
                        state.lastQUpdateSource()
                )
        );

        if (!showBypass && state.bypass() == null) {
            return base;
        }

        return base + String.format(
                Locale.ROOT,
                "  Bypass=%s",
                formatValueWithStatus(
                        state.bypass() == null ? null : (state.bypass() ? "on" : "off"),
                        state.bypassConfirmedFromDevice(),
                        state.lastBypassUpdateSource()
                )
        );
    }

    private static String formatInputGeqBandState(InputGeqBandState state) {
        if (state == null) {
            return "IGEQ -";
        }

        return String.format(
                Locale.ROOT,
                "IGEQ%-2d Freq=%-22s Gain=%s",
                state.bandIndex(),
                formatValueWithStatus(
                        state.frequencyHz() == null ? null : formatFrequency(state.frequencyHz()),
                        state.frequencyConfirmedFromDevice(),
                        state.lastFrequencyUpdateSource()
                ),
                formatValueWithStatus(
                        state.gainDb() == null ? null : String.format(Locale.ROOT, "%.1f dB", state.gainDb()),
                        state.gainConfirmedFromDevice(),
                        state.lastGainUpdateSource()
                )
        );
    }

    private static String formatInputGateState(InputGateState state) {
        if (state == null) {
            return "GATE  -";
        }

        return String.format(
                Locale.ROOT,
                "GATE  Thresh=%-22s Hold=%-20s Attack=%-20s Release=%s",
                formatValueWithStatus(
                        state.thresholdDb() == null ? null : String.format(Locale.ROOT, "%.1f dB", state.thresholdDb()),
                        state.thresholdConfirmedFromDevice(),
                        state.lastThresholdUpdateSource()
                ),
                formatValueWithStatus(
                        state.holdMs() == null ? null : String.format(Locale.ROOT, "%.0f ms", state.holdMs()),
                        state.holdConfirmedFromDevice(),
                        state.lastHoldUpdateSource()
                ),
                formatValueWithStatus(
                        state.attackMs() == null ? null : String.format(Locale.ROOT, "%.0f ms", state.attackMs()),
                        state.attackConfirmedFromDevice(),
                        state.lastAttackUpdateSource()
                ),
                formatValueWithStatus(
                        state.releaseMs() == null ? null : String.format(Locale.ROOT, "%.0f ms", state.releaseMs()),
                        state.releaseConfirmedFromDevice(),
                        state.lastReleaseUpdateSource()
                )
        );
    }

    private static String formatCompressorState(CompressorState state) {
        return String.format(
                Locale.ROOT,
                "COMP  Ratio=%-24s Attack=%-20s Release=%-20s Knee=%-18s Thresh=%-20s Freq=%-20s Type=%s",
                formatValueWithStatus(
                        state.ratioLabel(),
                        state.ratioConfirmedFromDevice(),
                        state.lastRatioUpdateSource()
                ),
                formatValueWithStatus(
                        state.attackMs() == null ? null : String.format(Locale.ROOT, "%.0f ms", state.attackMs()),
                        state.attackConfirmedFromDevice(),
                        state.lastAttackUpdateSource()
                ),
                formatValueWithStatus(
                        state.releaseMs() == null ? null : String.format(Locale.ROOT, "%.0f ms", state.releaseMs()),
                        state.releaseConfirmedFromDevice(),
                        state.lastReleaseUpdateSource()
                ),
                formatValueWithStatus(
                        state.kneeDb() == null ? null : String.format(Locale.ROOT, "%.1f dB", state.kneeDb()),
                        state.kneeConfirmedFromDevice(),
                        state.lastKneeUpdateSource()
                ),
                formatValueWithStatus(
                        state.thresholdDb() == null ? null : String.format(Locale.ROOT, "%.1f dB", state.thresholdDb()),
                        state.thresholdConfirmedFromDevice(),
                        state.lastThresholdUpdateSource()
                ),
                formatValueWithStatus(
                        state.frequencyHz() == null ? null : formatFrequency(state.frequencyHz()),
                        state.frequencyConfirmedFromDevice(),
                        state.lastFrequencyUpdateSource()
                ),
                formatValueWithStatus(
                        state.typeLabel(),
                        state.typeConfirmedFromDevice(),
                        state.lastTypeUpdateSource()
                )
        );
    }

    private static String formatLimiterState(LimiterState state) {
        return String.format(
                Locale.ROOT,
                "LIMIT Attack=%-20s Release=%-20s Thresh=%-20s Runtime=%-16s Knee=%s",
                formatValueWithStatus(
                        state.attackMs() == null ? null : String.format(Locale.ROOT, "%.0f ms", state.attackMs()),
                        state.attackConfirmedFromDevice(),
                        state.lastAttackUpdateSource()
                ),
                formatValueWithStatus(
                        state.releaseMs() == null ? null : String.format(Locale.ROOT, "%.0f ms", state.releaseMs()),
                        state.releaseConfirmedFromDevice(),
                        state.lastReleaseUpdateSource()
                ),
                formatValueWithStatus(
                        state.thresholdDb() == null ? null : String.format(Locale.ROOT, "%.1f dB", state.thresholdDb()),
                        state.thresholdConfirmedFromDevice(),
                        state.lastThresholdUpdateSource()
                ),
                formatValueWithStatus(
                        state.runtimeActive() == null ? null : (state.runtimeActive() ? "active" : "idle"),
                        state.runtimeConfirmedFromDevice(),
                        state.lastRuntimeUpdateSource()
                ),
                formatValueWithStatus(
                        state.kneeDb() == null ? null : String.format(Locale.ROOT, "%.0f dB", state.kneeDb()),
                        state.kneeConfirmedFromDevice(),
                        state.lastKneeUpdateSource()
                )
        );
    }

    private static String formatValueWithStatus(String value, boolean confirmed, String source) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value + "[" + confirmation(confirmed) + ":" + source(source) + "]";
    }

    public static String formatFrequency(Double hz) {
        if (hz == null) {
            return "-";
        }
        if (hz >= 1000.0) {
            return String.format(Locale.ROOT, "%.2f kHz", hz / 1000.0);
        }
        return String.format(Locale.ROOT, "%.1f Hz", hz);
    }

    private static String formatParamStatus(String key, boolean confirmed, boolean dirty, String source) {
        String c = confirmed ? "yes" : "no";
        String d = dirty ? "yes" : "no";
        String s = source(source);
        return String.format(Locale.ROOT, "%s[c=%s d=%s src=%s]", key, c, d, s);
    }

    private static String source(String source) {
        return source == null || source.isBlank() ? "-" : source;
    }

    private static String confirmation(boolean confirmed) {
        return confirmed ? "dev" : "est";
    }

    public static String formatDeviceInfo(String version, byte[] sysinfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("Device version: ").append(version == null ? "-" : version);
        sb.append(System.lineSeparator());
        sb.append("System info  : ").append(sysinfo == null ? "-" : Hex.format(sysinfo));
        return sb.toString();
    }

    public static String formatCachedBlocks(List<Integer> indices) {
        if (indices == null || indices.isEmpty()) {
            return "No blocks in cache.";
        }

        StringBuilder sb = new StringBuilder("Blocks in cache: ");
        for (int i = 0; i < indices.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.ROOT, "%02X", indices.get(i)));
        }
        return sb.toString();
    }

    public static String formatBlock(int index, byte[] data) {
        if (data == null) {
            return "No block in cache: " + String.format(Locale.ROOT, "%02X", index);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Block ").append(String.format(Locale.ROOT, "%02X", index)).append(":");
        sb.append(System.lineSeparator());
        sb.append("  HEX   : ").append(Hex.format(data));
        sb.append(System.lineSeparator());
        sb.append("  ASCII : ").append(Hex.ascii(data));
        sb.append(System.lineSeparator());
        sb.append("  LEN   : ").append(data.length);
        return sb.toString();
    }
}
