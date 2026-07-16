package de.drremote.dsp408.api;

public record LimiterDto(String id,
                         String displayName,
                         int index,
                         Double attackMs,
                         Double releaseMs,
                         Double thresholdDb,
                         Double kneeDb,
                         Boolean runtimeActive,
                         boolean attackConfirmedFromDevice,
                         boolean releaseConfirmedFromDevice,
                         boolean thresholdConfirmedFromDevice,
                         boolean kneeConfirmedFromDevice,
                         boolean runtimeConfirmedFromDevice,
                         String lastAttackUpdateSource,
                         String lastReleaseUpdateSource,
                         String lastThresholdUpdateSource,
                         String lastKneeUpdateSource,
                         String lastRuntimeUpdateSource) {
}
