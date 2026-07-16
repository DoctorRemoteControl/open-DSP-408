package de.drremote.dsp408controller.core.state;

import de.drremote.dsp408controller.core.protocol.DspChannel;

public final class CompressorState {
    private final DspChannel channel;

    private Integer ratioIndex;
    private String ratioLabel;
    private Double attackMs;
    private Double releaseMs;
    private Double kneeDb;
    private Double thresholdDb;
    private Double frequencyHz;
    private Integer typeContextRaw;
    private Integer typeRaw;
    private String typeLabel;

    private boolean ratioConfirmedFromDevice;
    private boolean attackConfirmedFromDevice;
    private boolean releaseConfirmedFromDevice;
    private boolean kneeConfirmedFromDevice;
    private boolean thresholdConfirmedFromDevice;
    private boolean frequencyConfirmedFromDevice;
    private boolean typeContextConfirmedFromDevice;
    private boolean typeConfirmedFromDevice;

    private String lastRatioUpdateSource;
    private String lastAttackUpdateSource;
    private String lastReleaseUpdateSource;
    private String lastKneeUpdateSource;
    private String lastThresholdUpdateSource;
    private String lastFrequencyUpdateSource;
    private String lastTypeContextUpdateSource;
    private String lastTypeUpdateSource;

    public CompressorState(DspChannel channel) {
        this.channel = channel;
    }

    public synchronized DspChannel channel() {
        return channel;
    }

    public synchronized DspChannel output() {
        return channel;
    }

    public synchronized Integer ratioIndex() {
        return ratioIndex;
    }

    public synchronized String ratioLabel() {
        return ratioLabel;
    }

    public synchronized Double attackMs() {
        return attackMs;
    }

    public synchronized Double releaseMs() {
        return releaseMs;
    }

    public synchronized Double kneeDb() {
        return kneeDb;
    }

    public synchronized Double thresholdDb() {
        return thresholdDb;
    }

    public synchronized Double frequencyHz() {
        return frequencyHz;
    }

    public synchronized Integer typeContextRaw() {
        return typeContextRaw;
    }

    public synchronized Integer typeRaw() {
        return typeRaw;
    }

    public synchronized String typeLabel() {
        return typeLabel;
    }

    public synchronized boolean ratioConfirmedFromDevice() {
        return ratioConfirmedFromDevice;
    }

    public synchronized boolean attackConfirmedFromDevice() {
        return attackConfirmedFromDevice;
    }

    public synchronized boolean releaseConfirmedFromDevice() {
        return releaseConfirmedFromDevice;
    }

    public synchronized boolean kneeConfirmedFromDevice() {
        return kneeConfirmedFromDevice;
    }

    public synchronized boolean thresholdConfirmedFromDevice() {
        return thresholdConfirmedFromDevice;
    }

    public synchronized boolean frequencyConfirmedFromDevice() {
        return frequencyConfirmedFromDevice;
    }

    public synchronized boolean typeContextConfirmedFromDevice() {
        return typeContextConfirmedFromDevice;
    }

    public synchronized boolean typeConfirmedFromDevice() {
        return typeConfirmedFromDevice;
    }

    public synchronized String lastRatioUpdateSource() {
        return lastRatioUpdateSource;
    }

    public synchronized String lastAttackUpdateSource() {
        return lastAttackUpdateSource;
    }

    public synchronized String lastReleaseUpdateSource() {
        return lastReleaseUpdateSource;
    }

    public synchronized String lastKneeUpdateSource() {
        return lastKneeUpdateSource;
    }

    public synchronized String lastThresholdUpdateSource() {
        return lastThresholdUpdateSource;
    }

    public synchronized String lastFrequencyUpdateSource() {
        return lastFrequencyUpdateSource;
    }

    public synchronized String lastTypeContextUpdateSource() {
        return lastTypeContextUpdateSource;
    }

    public synchronized String lastTypeUpdateSource() {
        return lastTypeUpdateSource;
    }

    public synchronized boolean hasAnyValues() {
        return ratioIndex != null
                || ratioLabel != null
                || attackMs != null
                || releaseMs != null
                || kneeDb != null
                || thresholdDb != null
                || frequencyHz != null
                || typeContextRaw != null
                || typeRaw != null
                || typeLabel != null;
    }

    public synchronized void updateRatio(Integer ratioIndex, String ratioLabel, String source, boolean confirmed) {
        this.ratioIndex = ratioIndex;
        this.ratioLabel = ratioLabel;
        this.lastRatioUpdateSource = source;
        this.ratioConfirmedFromDevice = confirmed;
    }

    public synchronized void updateAttack(Double attackMs, String source, boolean confirmed) {
        this.attackMs = attackMs;
        this.lastAttackUpdateSource = source;
        this.attackConfirmedFromDevice = confirmed;
    }

    public synchronized void updateRelease(Double releaseMs, String source, boolean confirmed) {
        this.releaseMs = releaseMs;
        this.lastReleaseUpdateSource = source;
        this.releaseConfirmedFromDevice = confirmed;
    }

    public synchronized void updateKnee(Double kneeDb, String source, boolean confirmed) {
        this.kneeDb = kneeDb;
        this.lastKneeUpdateSource = source;
        this.kneeConfirmedFromDevice = confirmed;
    }

    public synchronized void updateThreshold(Double thresholdDb, String source, boolean confirmed) {
        this.thresholdDb = thresholdDb;
        this.lastThresholdUpdateSource = source;
        this.thresholdConfirmedFromDevice = confirmed;
    }

    public synchronized void updateFrequency(Double frequencyHz, String source, boolean confirmed) {
        this.frequencyHz = frequencyHz;
        this.lastFrequencyUpdateSource = source;
        this.frequencyConfirmedFromDevice = confirmed;
    }

    public synchronized void updateTypeContext(Integer typeContextRaw, String source, boolean confirmed) {
        this.typeContextRaw = typeContextRaw;
        this.lastTypeContextUpdateSource = source;
        this.typeContextConfirmedFromDevice = confirmed;
    }

    public synchronized void updateType(Integer typeRaw, String typeLabel, String source, boolean confirmed) {
        this.typeRaw = typeRaw;
        this.typeLabel = typeLabel;
        this.lastTypeUpdateSource = source;
        this.typeConfirmedFromDevice = confirmed;
    }
}
