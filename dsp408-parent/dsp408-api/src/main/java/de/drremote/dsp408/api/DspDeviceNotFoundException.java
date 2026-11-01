package de.drremote.dsp408.api;

public class DspDeviceNotFoundException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public DspDeviceNotFoundException(String message) { super(message); }
}
