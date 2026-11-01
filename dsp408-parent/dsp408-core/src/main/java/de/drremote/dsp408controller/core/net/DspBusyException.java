package de.drremote.dsp408controller.core.net;

import java.util.concurrent.RejectedExecutionException;

/** Definite rejection before an interactive command is admitted or transmitted. */
public final class DspBusyException extends RejectedExecutionException {
    private static final long serialVersionUID = 1L;

    public DspBusyException(String message) {
        super(message);
    }
}
