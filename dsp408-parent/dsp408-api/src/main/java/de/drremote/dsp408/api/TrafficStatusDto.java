package de.drremote.dsp408.api;

public record TrafficStatusDto(
        boolean connected,
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
        Long lastTransmitEpochMs,
        boolean centralMeterPolling,
        Long meterPollIntervalMs,
        Long lastMeterObservedAtEpochMs
) {
}
