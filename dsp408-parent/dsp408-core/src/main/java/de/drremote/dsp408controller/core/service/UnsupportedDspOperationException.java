package de.drremote.dsp408controller.core.service;

public final class UnsupportedDspOperationException extends UnsupportedOperationException {
    private static final long serialVersionUID = 1L;

    public UnsupportedDspOperationException(String message) {
        super(message);
    }
}
