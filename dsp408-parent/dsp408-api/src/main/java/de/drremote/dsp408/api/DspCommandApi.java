package de.drremote.dsp408.api;

/** Text-based shell, Matrix and volume-room command entry points. */
public interface DspCommandApi {
    String helpText();

    String executeShell(String line) throws Exception;

    String executeMatrix(String line, boolean isAdmin) throws Exception;

    String executeVolumeRoom(String line) throws Exception;
}
