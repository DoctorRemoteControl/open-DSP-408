package de.drremote.dsp408controller.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DspCodecRegistryGuiAnchorTest {

    @Test
    void decodesOriginalFir408GuiFrequencyAndQAnchors() {
        assertEquals(19.7, DspCodecRegistry.peqFrequency().rawToDouble(0), 0.05);
        assertEquals(161.2, DspCodecRegistry.peqFrequency().rawToDouble(91), 0.05);
        assertEquals(176.8, DspCodecRegistry.peqFrequency().rawToDouble(95), 0.05);
        assertEquals(370.3, DspCodecRegistry.peqFrequency().rawToDouble(127), 0.05);
        assertEquals(757.9, DspCodecRegistry.peqFrequency().rawToDouble(158), 0.05);
        assertEquals(20160.0, DspCodecRegistry.peqFrequency().rawToDouble(300), 0.1);

        assertEquals(0.71, DspCodecRegistry.peqQ().rawToDouble(10), 0.005);
        assertEquals(3.00, DspCodecRegistry.peqQ().rawToDouble(35), 0.005);
        assertEquals(128.0, DspCodecRegistry.peqQ().rawToDouble(100), 0.0);
    }
}
