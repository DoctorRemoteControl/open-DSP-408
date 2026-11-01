package de.drremote.dsp408controller.core.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DspProtocolValuesTest {

    @Test
    void keepsPeqFrequencyAndGainConversionAnchors() {
        assertEquals(31, DspProtocol.hzToPeqRaw(40.3));
        assertEquals(0, DspProtocol.dbToRaw(-60.0));
        assertEquals(80, DspProtocol.dbToRaw(-20.0));
        assertEquals(280, DspProtocol.dbToRaw(0.0));
        assertEquals(-20.0, DspProtocol.rawToDb(80));
        assertEquals(0.0, DspProtocol.rawToDb(280));
    }

    @Test
    void clampsPublicGainRangeWithoutChangingInRangeValues() {
        assertEquals(-60.0, DspProtocol.clampDb(-61.0));
        assertEquals(-3.5, DspProtocol.clampDb(-3.5));
        assertEquals(12.0, DspProtocol.clampDb(13.0));
    }
}
