package de.drremote.dsp408.api;

public record MatrixCrosspointDto(String inputId,
                                  String displayName,
                                  int index,
                                  Double gainDb,
                                  boolean gainConfirmedFromDevice,
                                  String lastGainUpdateSource) {
}
