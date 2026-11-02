package de.drremote.dsp408.api;

import java.util.Objects;

/** A definitely unsent write whose cached control revision is no longer current. */
public class DspControlConflictException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final ControlStateDto currentState;

    public DspControlConflictException(ControlStateDto currentState) {
        super("The DSP control state changed; review the current state before writing again.");
        this.currentState = Objects.requireNonNull(currentState);
    }

    public ControlStateDto currentState() {
        return currentState;
    }
}
