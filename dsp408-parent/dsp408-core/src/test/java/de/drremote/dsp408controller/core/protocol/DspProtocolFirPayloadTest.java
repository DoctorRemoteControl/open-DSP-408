package de.drremote.dsp408controller.core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class DspProtocolFirPayloadTest {

    @Test
    void buildsProcessingModeAndGeneratorPayloads() {
        assertArrayEquals(
                hex("00 01 03 4C 0B 01"),
                DspProtocol.buildFirProcessingMode(DspChannel.OUT_8, FirProcessingMode.FIR));
        assertArrayEquals(
                hex("00 01 0A 4B 05 03 03 1F 00 3F 00 08 00"),
                DspProtocol.buildFirGenerator(
                        DspChannel.OUT_2,
                        FirFilterType.BAND_PASS,
                        FirWindowFunction.HAMMING,
                        40.3,
                        84.4,
                        512));
    }

    @Test
    void encodesExternalCoefficientsAsBigEndianFloat32() {
        List<byte[]> payloads = DspProtocol.buildExternalFirUpload(
                DspChannel.IN_A,
                "fir",
                new double[]{1.0, -0.5, 0.25},
                true);

        assertEquals(3, payloads.size());
        assertArrayEquals(hex("00 01 03 4F 00 00"), payloads.get(0));
        assertArrayEquals(
                hex("00 01 11 4E 00 00 03 00 3F 80 00 00 BF 00 00 00 3E 80 00 00"),
                payloads.get(1));
        assertArrayEquals(
                hex("00 01 0A 5B 00 66 69 72 20 20 20 20 20"),
                payloads.get(2));
    }

    @Test
    void splitsExternalUploadsAfterTwelveCoefficients() {
        double[] coefficients = new double[13];
        List<byte[]> payloads = DspProtocol.buildExternalFirUpload(
                DspChannel.OUT_1, "split", coefficients, false);

        assertEquals(3, payloads.size());
        assertEquals(56, payloads.get(0).length);
        assertEquals(0, payloads.get(0)[5] & 0xFF);
        assertEquals(12, payloads.get(1).length);
        assertEquals(1, payloads.get(1)[5] & 0xFF);
        assertEquals(0x5B, payloads.get(2)[3] & 0xFF);
    }

    @Test
    void keepsTapConversionAndUploadValidationAtTheFacade() {
        assertEquals(0, DspProtocol.firTapCountToRaw(256));
        assertEquals(24, DspProtocol.firTapCountToRaw(1024));
        assertEquals(512, DspProtocol.firTapRawToCount(8));
        assertThrows(IllegalArgumentException.class, () -> DspProtocol.firTapCountToRaw(257));
        assertThrows(IllegalArgumentException.class, () -> DspProtocol.firTapRawToCount(25));
        assertThrows(
                IllegalArgumentException.class,
                () -> DspProtocol.buildExternalFirUpload(
                        DspChannel.OUT_1, "fir", new double[]{Double.NaN}, false));
    }

    private static byte[] hex(String value) {
        String[] tokens = value.split("\\s+");
        byte[] bytes = new byte[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            bytes[i] = (byte) Integer.parseInt(tokens[i], 16);
        }
        return bytes;
    }
}
