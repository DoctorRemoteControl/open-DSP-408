package de.drremote.dsp408controller.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MeterLevelDecoderTest {
    private static final double CALIBRATION_TOLERANCE_DB = 0.35;

    @Test
    void decodesMeasuredFir408AnchorPoints() {
        assertEquals(-59.0, MeterLevelDecoder.decodeDb(0x3740), CALIBRATION_TOLERANCE_DB);
        assertEquals(-50.0, MeterLevelDecoder.decodeDb(0x3807), CALIBRATION_TOLERANCE_DB);
        assertEquals(-20.0, MeterLevelDecoder.decodeDb(0x3A85), CALIBRATION_TOLERANCE_DB);
        assertEquals(-6.0, MeterLevelDecoder.decodeDb(0x3BA6), CALIBRATION_TOLERANCE_DB);
        assertEquals(0.0, MeterLevelDecoder.decodeDb(0x3C26), CALIBRATION_TOLERANCE_DB);
        assertEquals(6.0, MeterLevelDecoder.decodeDb(0x3CA7), CALIBRATION_TOLERANCE_DB);
        assertEquals(12.0, MeterLevelDecoder.decodeDb(0x3D26), CALIBRATION_TOLERANCE_DB);
    }

    @Test
    void limitsSilenceDecayToMinusSixtyDb() {
        assertEquals(-60.0, MeterLevelDecoder.decodeDb(0x0000), 0.0);
        assertEquals(-60.0, MeterLevelDecoder.decodeDb(0x2457), 0.0);
    }

    @Test
    void includesTheThirdSlotByteAsTheFractionalMeterPart() {
        assertEquals(
                12.01,
                MeterLevelDecoder.decodeDb(0x3D24, 0x04),
                0.01
        );
    }
}
