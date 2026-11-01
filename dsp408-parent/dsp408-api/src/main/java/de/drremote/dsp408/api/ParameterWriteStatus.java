package de.drremote.dsp408.api;

/** An ACK alone never means that the parameter was read back and verified. */
public enum ParameterWriteStatus {
    ACKNOWLEDGED, VERIFIED, UNCERTAIN, REJECTED
}
