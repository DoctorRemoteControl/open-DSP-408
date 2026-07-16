package de.drremote.dsp408.api;

public record ChannelDto(String id,
                         String displayName,
                         int index,
                         Double gainDb,
                         Boolean muted,
                         Boolean phaseInverted,
                         Double delayMs,
                         boolean gainConfirmedFromDevice,
                         boolean muteConfirmedFromDevice,
                         boolean phaseConfirmedFromDevice,
                         boolean delayConfirmedFromDevice,
                         boolean gainDirty,
                         boolean muteDirty,
                         boolean phaseDirty,
                         boolean delayDirty,
                         String lastGainUpdateSource,
                         String lastMuteUpdateSource,
                         String lastPhaseUpdateSource,
                         String lastDelayUpdateSource) {
}
