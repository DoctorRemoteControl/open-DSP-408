package de.drremote.dsp408controller.core.net;

public record DspTrafficSnapshot(
        int pendingCommands,
        int maxPendingCommands,
        long submittedCommands,
        long transmittedCommands,
        long supersededCommands,
        long meterTransmissions,
        long coalescedMeterRequests,
        long controlTransmissions,
        long writeRetries,
        long unacknowledgedWrites,
        String activePriority,
        long lastTransmitEpochMs,
        boolean centralMeterPolling,
        Long meterPollIntervalMs
) {
}
