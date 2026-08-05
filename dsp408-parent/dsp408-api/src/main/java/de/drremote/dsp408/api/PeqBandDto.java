package de.drremote.dsp408.api;

public record PeqBandDto(int bandIndex,
                         Double gainDb,
                         Double frequencyHz,
                         Double q,
                         String filterType,
                         Boolean bypass,
                         boolean gainConfirmedFromDevice,
                         boolean frequencyConfirmedFromDevice,
                         boolean qConfirmedFromDevice,
                         boolean typeConfirmedFromDevice,
                         boolean bypassConfirmedFromDevice,
                         String lastGainUpdateSource,
                         String lastFrequencyUpdateSource,
                         String lastQUpdateSource,
                         String lastTypeUpdateSource,
                         String lastBypassUpdateSource) {
}
