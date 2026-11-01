package de.drremote.dsp408.api;

/** A registry storage failure; distinct from a conflicting device definition. */
public class DspDeviceStorageException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public DspDeviceStorageException(String message, Throwable cause) { super(message, cause); }
}
