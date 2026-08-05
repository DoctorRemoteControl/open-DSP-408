package de.drremote.dsp408controller.core.library;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.drremote.dsp408controller.core.protocol.DspChannel;

public final class MeterLayout {
    private final List<MeterSlotLocation> slots;
    private final Map<DspChannel, MeterSlotLocation> slotsByChannel;
    private final int responseLengthBytes;
    private final int statusByteOffset;
    private final int limiterMaskOffset;
    private final Double clipThresholdRaw;
    private final Set<DspChannel> confirmedClipThresholdChannels;
    private final boolean channelMappingConfirmed;
    private final boolean levelCalibrationConfirmed;
    private final boolean limiterMappingConfirmed;

    public MeterLayout(List<MeterSlotLocation> slots,
                       int responseLengthBytes,
                       int statusByteOffset,
                       int limiterMaskOffset,
                       Double clipThresholdRaw,
                       Set<DspChannel> confirmedClipThresholdChannels,
                       boolean channelMappingConfirmed,
                       boolean levelCalibrationConfirmed,
                       boolean limiterMappingConfirmed) {
        this.slots = List.copyOf(new ArrayList<>(slots));

        Map<DspChannel, MeterSlotLocation> byChannel = new EnumMap<>(DspChannel.class);
        for (MeterSlotLocation slot : this.slots) {
            byChannel.put(slot.channel(), slot);
        }
        this.slotsByChannel = Map.copyOf(byChannel);
        this.responseLengthBytes = responseLengthBytes;
        this.statusByteOffset = statusByteOffset;
        this.limiterMaskOffset = limiterMaskOffset;
        this.clipThresholdRaw = clipThresholdRaw;
        this.confirmedClipThresholdChannels =
                Set.copyOf(new HashSet<>(confirmedClipThresholdChannels));
        this.channelMappingConfirmed = channelMappingConfirmed;
        this.levelCalibrationConfirmed = levelCalibrationConfirmed;
        this.limiterMappingConfirmed = limiterMappingConfirmed;
    }

    public List<MeterSlotLocation> slots() {
        return slots;
    }

    public MeterSlotLocation slot(DspChannel channel) {
        return slotsByChannel.get(channel);
    }

    public int responseLengthBytes() {
        return responseLengthBytes;
    }

    public int statusByteOffset() {
        return statusByteOffset;
    }

    public int limiterMaskOffset() {
        return limiterMaskOffset;
    }

    public Double clipThresholdRaw() {
        return clipThresholdRaw;
    }

    public boolean clipThresholdConfirmed(DspChannel channel) {
        return confirmedClipThresholdChannels.contains(channel);
    }

    public boolean channelMappingConfirmed() {
        return channelMappingConfirmed;
    }

    public boolean levelCalibrationConfirmed() {
        return levelCalibrationConfirmed;
    }

    public boolean limiterMappingConfirmed() {
        return limiterMappingConfirmed;
    }

    public boolean hasSlots() {
        return !slots.isEmpty();
    }
}
