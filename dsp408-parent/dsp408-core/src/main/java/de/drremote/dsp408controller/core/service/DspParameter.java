package de.drremote.dsp408controller.core.service;

import java.util.Locale;

/** The explicitly supported parameter-write API scope. */
public enum DspParameter {
    GAIN, MUTE, PHASE, DELAY;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static DspParameter parse(String value) {
        if (value != null) {
            for (DspParameter parameter : values()) {
                if (parameter.id().equals(value)) return parameter;
            }
        }
        throw new IllegalArgumentException("Unknown parameter: " + value);
    }
}
