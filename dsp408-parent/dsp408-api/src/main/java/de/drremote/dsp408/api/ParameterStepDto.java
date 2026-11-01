package de.drremote.dsp408.api;

/** Numeric resolution over a closed range; adjacent ranges may share a boundary. */
public record ParameterStepDto(double fromInclusive, double toInclusive, double step) {
    public ParameterStepDto {
        if (!Double.isFinite(fromInclusive) || !Double.isFinite(toInclusive)
                || !Double.isFinite(step) || fromInclusive > toInclusive || step <= 0) {
            throw new IllegalArgumentException("A resolution needs ordered finite bounds and a positive step.");
        }
    }
}
