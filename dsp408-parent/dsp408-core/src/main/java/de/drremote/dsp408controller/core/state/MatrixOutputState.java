package de.drremote.dsp408controller.core.state;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import de.drremote.dsp408controller.core.protocol.DspChannel;

public final class MatrixOutputState {
    private final DspChannel output;
    private final Set<DspChannel> routedInputs = EnumSet.noneOf(DspChannel.class);
    private final Map<DspChannel, Double> crosspointGainDb = new EnumMap<>(DspChannel.class);

    public MatrixOutputState(DspChannel output) {
        this.output = output;
    }

    public synchronized DspChannel output() {
        return output;
    }

    public synchronized DspChannel routedInput() {
        return routedInputs.size() == 1 ? routedInputs.iterator().next() : null;
    }

    public synchronized void setRoutedInput(DspChannel input) {
        setRoutedInputs(java.util.List.of(input));
    }

    public synchronized void setRoutedInputs(Collection<DspChannel> inputs) {
        routedInputs.clear();
        if (inputs != null) {
            routedInputs.addAll(inputs);
        }
    }

    public synchronized Set<DspChannel> routedInputsSnapshot() {
        return Set.copyOf(routedInputs);
    }

    public synchronized void setCrosspointGain(DspChannel input, double db) {
        crosspointGainDb.put(input, db);
    }

    public synchronized Double crosspointGain(DspChannel input) {
        return crosspointGainDb.get(input);
    }

    public synchronized Map<DspChannel, Double> crosspointGainsSnapshot() {
        return Map.copyOf(crosspointGainDb);
    }
}
