package de.drremote.dsp408controller.core.service;

/** A decoded value copied from a freshly received parameter block. */
public record DspParameterReading(Object value, int blockIndex, long receiveRevision, long observedAtEpochMs) { }
