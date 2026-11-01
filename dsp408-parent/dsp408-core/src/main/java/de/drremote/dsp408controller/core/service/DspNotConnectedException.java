package de.drremote.dsp408controller.core.service;

/** A lifecycle rejection before a controller operation can obtain a connected client. */
public final class DspNotConnectedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public DspNotConnectedException(String message) {
        super(message);
    }
}
