package de.drremote.dsp408.api;

/** Keeps parameter values immutable and JSON-representable at the API boundary. */
final class ParameterValues {
    private ParameterValues() { }
    static Object snapshot(Object value) {
        if (value == null || value instanceof Boolean) return value;
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            if (Double.isFinite(numeric)) return numeric;
        }
        throw new IllegalArgumentException("Parameter values must be finite numbers or booleans.");
    }
}
