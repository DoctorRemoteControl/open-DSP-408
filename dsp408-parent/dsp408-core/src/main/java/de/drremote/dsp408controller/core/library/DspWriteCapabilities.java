package de.drremote.dsp408controller.core.library;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import de.drremote.dsp408controller.core.protocol.DspChannel;

public final class DspWriteCapabilities {
    public enum Operation {
        PHASE,
        OUTPUT_PEQ,
        INPUT_PEQ,
        INPUT_GEQ,
        INPUT_GATE,
        CROSSOVER_HIGH_PASS,
        CROSSOVER_LOW_PASS,
        INPUT_COMPRESSOR,
        FIR_PROCESSING_MODE,
        FIR_GENERATOR,
        EXTERNAL_FIR_UPLOAD
    }

    private final Map<Operation, Set<DspChannel>> channelsByOperation;

    DspWriteCapabilities(Map<Operation, Set<DspChannel>> channelsByOperation) {
        Map<Operation, Set<DspChannel>> copy = new EnumMap<>(Operation.class);
        for (Operation operation : Operation.values()) {
            Set<DspChannel> channels = channelsByOperation.get(operation);
            copy.put(operation, channels == null || channels.isEmpty()
                    ? Set.of()
                    : Set.copyOf(EnumSet.copyOf(channels)));
        }
        this.channelsByOperation = Map.copyOf(copy);
    }

    static DspWriteCapabilities permissive() {
        Map<Operation, Set<DspChannel>> all = new EnumMap<>(Operation.class);
        Set<DspChannel> channels = Set.copyOf(EnumSet.allOf(DspChannel.class));
        for (Operation operation : Operation.values()) {
            all.put(operation, channels);
        }
        return new DspWriteCapabilities(all);
    }

    public boolean supports(Operation operation, DspChannel channel) {
        return channel != null && channelsByOperation.getOrDefault(operation, Set.of()).contains(channel);
    }

    public Set<DspChannel> channels(Operation operation) {
        return channelsByOperation.getOrDefault(operation, Set.of());
    }
}
