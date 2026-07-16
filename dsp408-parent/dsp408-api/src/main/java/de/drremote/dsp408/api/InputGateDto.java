package de.drremote.dsp408.api;

public record InputGateDto(String id,
                           String displayName,
                           int index,
                           Double thresholdDb,
                           Double holdMs,
                           Double attackMs,
                           Double releaseMs,
                           boolean thresholdConfirmedFromDevice,
                           boolean holdConfirmedFromDevice,
                           boolean attackConfirmedFromDevice,
                           boolean releaseConfirmedFromDevice,
                           String lastThresholdUpdateSource,
                           String lastHoldUpdateSource,
                           String lastAttackUpdateSource,
                           String lastReleaseUpdateSource) {
}
