package de.drremote.dsp408.api;

public record MeterDto(String id,
                       String displayName,
                       int index,
                       Integer rawLowByte,
                       Integer rawHighByte,
                       Integer rawValue,
                       Double levelDb,
                       Integer aux,
                       Boolean limiterActive,
                       boolean levelConfirmedFromDevice,
                       boolean limiterConfirmedFromDevice,
                       boolean channelMappingConfirmed,
                       boolean levelCalibrationConfirmed,
                       boolean limiterMappingConfirmed,
                       String lastLevelUpdateSource,
                       String lastLimiterUpdateSource) {
}
