package de.drremote.dsp408controller.core.state;

import de.drremote.dsp408controller.core.protocol.DspChannel;

public final class MeterChannelState {
    private final DspChannel channel;

    private Integer rawLowByte;
    private Integer rawHighByte;
    private Integer rawValue;
    private Integer slotByte2;
    private Boolean clipActive;

    private boolean confirmedFromDevice;
    private boolean clipDerivedFromDeviceMeter;
    private String lastUpdateSource;
    private String lastClipUpdateSource;

    public MeterChannelState(DspChannel channel) {
        this.channel = channel;
    }

    public synchronized DspChannel channel() {
        return channel;
    }

    public synchronized Integer rawLowByte() {
        return rawLowByte;
    }

    public synchronized Integer rawHighByte() {
        return rawHighByte;
    }

    public synchronized Integer rawValue() {
        return rawValue;
    }

    public synchronized Integer slotByte2() {
        return slotByte2;
    }

    public synchronized Boolean clipActive() {
        return clipActive;
    }

    public synchronized boolean confirmedFromDevice() {
        return confirmedFromDevice;
    }

    public synchronized boolean clipDerivedFromDeviceMeter() {
        return clipDerivedFromDeviceMeter;
    }

    public synchronized String lastUpdateSource() {
        return lastUpdateSource;
    }

    public synchronized String lastClipUpdateSource() {
        return lastClipUpdateSource;
    }

    public synchronized boolean hasAnyValues() {
        return rawLowByte != null
                || rawHighByte != null
                || rawValue != null
                || slotByte2 != null
                || clipActive != null;
    }

    public synchronized void update(int rawLowByte, int rawHighByte, int slotByte2, String source, boolean confirmed) {
        this.rawLowByte = rawLowByte;
        this.rawHighByte = rawHighByte;
        this.rawValue = (rawLowByte & 0xFF) | ((rawHighByte & 0xFF) << 8);
        this.slotByte2 = slotByte2;
        this.lastUpdateSource = source;
        this.confirmedFromDevice = confirmed;
    }

    public synchronized void updateClipFromMeter(boolean clipActive, String source, boolean confirmed) {
        this.clipActive = clipActive;
        this.lastClipUpdateSource = source;
        this.clipDerivedFromDeviceMeter = confirmed;
    }

}
