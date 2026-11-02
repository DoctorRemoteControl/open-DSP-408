package de.drremote.dsp408.api;

/** Additive cached-state and optimistic-concurrency contract for live clients. */
public interface DspControlStateApi {
    default ControlStateDto getControlState() {
        throw new UnsupportedOperationException("This provider does not support control state snapshots.");
    }

    /** An exact strong tag is checked after bounded admission, immediately before transmission. */
    default ParameterWriteResultDto writeParameterConditional(ParameterWriteRequestDto request,
            String expectedEtag, boolean interactive) throws Exception {
        throw new UnsupportedOperationException("This provider does not support conditional parameter writes.");
    }
}
