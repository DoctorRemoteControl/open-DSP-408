package de.drremote.dsp408controller.core.state;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import de.drremote.dsp408controller.core.protocol.DspChannel;

public final class MeterState {
    private final Map<DspChannel, MeterChannelState> channels = new EnumMap<>(DspChannel.class);

    private Integer statusByteRaw;
    private String lastStatusUpdateSource;
    private boolean statusConfirmedFromDevice;
    private byte[] lastRuntimePayload;
    private long lastRuntimePayloadObservedAtEpochMs;
    private String lastRuntimePayloadUpdateSource;
    private boolean runtimePayloadConfirmedFromDevice;

    public MeterState() {
        reset();
    }

    public synchronized void reset() {
        channels.clear();
        for (DspChannel channel : DspChannel.values()) {
            channels.put(channel, new MeterChannelState(channel));
        }
        statusByteRaw = null;
        lastStatusUpdateSource = null;
        statusConfirmedFromDevice = false;
        lastRuntimePayload = null;
        lastRuntimePayloadObservedAtEpochMs = 0L;
        lastRuntimePayloadUpdateSource = null;
        runtimePayloadConfirmedFromDevice = false;
    }

    public synchronized MeterChannelState channel(DspChannel channel) {
        return channels.get(channel);
    }

    public synchronized List<MeterChannelState> allChannels() {
        return new ArrayList<>(channels.values());
    }

    public synchronized Integer statusByteRaw() {
        return statusByteRaw;
    }

    public synchronized String lastStatusUpdateSource() {
        return lastStatusUpdateSource;
    }

    public synchronized boolean statusConfirmedFromDevice() {
        return statusConfirmedFromDevice;
    }

    public synchronized byte[] lastRuntimePayload() {
        return lastRuntimePayload == null ? null : Arrays.copyOf(lastRuntimePayload, lastRuntimePayload.length);
    }

    public synchronized Long lastRuntimePayloadObservedAtEpochMs() {
        return lastRuntimePayload == null ? null : lastRuntimePayloadObservedAtEpochMs;
    }

    public synchronized String lastRuntimePayloadUpdateSource() {
        return lastRuntimePayloadUpdateSource;
    }

    public synchronized boolean runtimePayloadConfirmedFromDevice() {
        return runtimePayloadConfirmedFromDevice;
    }

    public synchronized void updateStatusByte(int statusByteRaw, String source, boolean confirmed) {
        this.statusByteRaw = statusByteRaw;
        this.lastStatusUpdateSource = source;
        this.statusConfirmedFromDevice = confirmed;
    }

    public synchronized void updateRuntimePayload(byte[] payload, String source, boolean confirmed) {
        this.lastRuntimePayload = payload == null ? null : Arrays.copyOf(payload, payload.length);
        this.lastRuntimePayloadObservedAtEpochMs = System.currentTimeMillis();
        this.lastRuntimePayloadUpdateSource = source;
        this.runtimePayloadConfirmedFromDevice = confirmed;
    }

    public synchronized boolean hasAnyValues() {
        if (statusByteRaw != null || lastRuntimePayload != null) {
            return true;
        }
        return channels.values().stream().anyMatch(MeterChannelState::hasAnyValues);
    }
}
