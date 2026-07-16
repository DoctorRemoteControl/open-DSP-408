package de.drremote.dsp408controller.core.state;

import de.drremote.dsp408controller.core.protocol.DspChannel;

public final class LimiterState {
    private final DspChannel output;

    private Double attackMs;
    private Double releaseMs;
    private Double thresholdDb;
    private Double kneeDb;
    private Boolean runtimeActive;

    private boolean attackConfirmedFromDevice;
    private boolean releaseConfirmedFromDevice;
    private boolean thresholdConfirmedFromDevice;
    private boolean kneeConfirmedFromDevice;
    private boolean runtimeConfirmedFromDevice;

    private String lastAttackUpdateSource;
    private String lastReleaseUpdateSource;
    private String lastThresholdUpdateSource;
    private String lastKneeUpdateSource;
    private String lastRuntimeUpdateSource;

    public LimiterState(DspChannel output) {
        this.output = output;
    }

    public synchronized DspChannel output() {
        return output;
    }

    public synchronized Double attackMs() {
        return attackMs;
    }

    public synchronized Double releaseMs() {
        return releaseMs;
    }

    public synchronized Double thresholdDb() {
        return thresholdDb;
    }

    public synchronized Double kneeDb() {
        return kneeDb;
    }

    public synchronized Boolean runtimeActive() {
        return runtimeActive;
    }

    public synchronized boolean attackConfirmedFromDevice() {
        return attackConfirmedFromDevice;
    }

    public synchronized boolean releaseConfirmedFromDevice() {
        return releaseConfirmedFromDevice;
    }

    public synchronized boolean thresholdConfirmedFromDevice() {
        return thresholdConfirmedFromDevice;
    }

    public synchronized boolean kneeConfirmedFromDevice() {
        return kneeConfirmedFromDevice;
    }

    public synchronized boolean runtimeConfirmedFromDevice() {
        return runtimeConfirmedFromDevice;
    }

    public synchronized String lastAttackUpdateSource() {
        return lastAttackUpdateSource;
    }

    public synchronized String lastReleaseUpdateSource() {
        return lastReleaseUpdateSource;
    }

    public synchronized String lastThresholdUpdateSource() {
        return lastThresholdUpdateSource;
    }

    public synchronized String lastKneeUpdateSource() {
        return lastKneeUpdateSource;
    }

    public synchronized String lastRuntimeUpdateSource() {
        return lastRuntimeUpdateSource;
    }

    public synchronized boolean hasAnyValues() {
        return attackMs != null
                || releaseMs != null
                || thresholdDb != null
                || kneeDb != null
                || runtimeActive != null;
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

    public synchronized void updateThreshold(Double thresholdDb, String source, boolean confirmed) {
        this.thresholdDb = thresholdDb;
        this.lastThresholdUpdateSource = source;
        this.thresholdConfirmedFromDevice = confirmed;
    }

    public synchronized void updateKnee(Double kneeDb, String source, boolean confirmed) {
        this.kneeDb = kneeDb;
        this.lastKneeUpdateSource = source;
        this.kneeConfirmedFromDevice = confirmed;
    }

    public synchronized void updateRuntimeActive(Boolean runtimeActive, String source, boolean confirmed) {
        this.runtimeActive = runtimeActive;
        this.lastRuntimeUpdateSource = source;
        this.runtimeConfirmedFromDevice = confirmed;
    }
}
