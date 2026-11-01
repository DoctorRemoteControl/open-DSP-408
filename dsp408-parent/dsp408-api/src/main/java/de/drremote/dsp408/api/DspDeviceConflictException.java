package de.drremote.dsp408.api;

public class DspDeviceConflictException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public DspDeviceConflictException(String message) { super(message); }
}
