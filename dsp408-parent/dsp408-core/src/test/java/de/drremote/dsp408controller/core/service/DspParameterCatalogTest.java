package de.drremote.dsp408controller.core.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.library.DspLibraryLoader;
import de.drremote.dsp408controller.core.protocol.DspChannel;

class DspParameterCatalogTest {
    @Test
    void dsp408PredictedInputDelayLocationsCannotAuthorizeVerifiedReadback() {
        DspParameterCatalog catalog = new DspParameterCatalog(DspLibraryLoader.loadDefault(), "DSP408 V0104", true);
        int confirmed = 0;
        for (DspChannel channel : DspChannel.values()) {
            DspParameterCapability delay = catalog.capability(channel, DspParameter.DELAY);
            boolean predicted = channel == DspChannel.IN_B || channel == DspChannel.IN_C;
            assertTrue(delay.writable());
            assertEquals(!predicted, delay.readbackSupported(), channel.toString());
            assertEquals(predicted ? "inferred" : "observed", delay.readEvidence(), channel.toString());
            if (delay.readbackSupported()) confirmed++;
        }
        assertEquals(10, confirmed);
    }

    @Test
    void onlyExactKnownFirmwareWithMatchingLibraryCanWrite() {
        for (String version : new String[]{"DSP408", "DSP408 V0105", "FIR408 V0113P extra", "", "FIR408 (inferred from 46-byte cmd40)"}) {
            DspParameterCatalog catalog = new DspParameterCatalog(DspLibraryLoader.loadDefault(), version, true);
            assertFalse(catalog.firmwareRecognized(), version);
            assertEquals(48, catalog.parameters().size());
            assertTrue(catalog.parameters().stream().noneMatch(DspParameterCapability::writable));
        }
        assertFalse(new DspParameterCatalog(DspLibraryLoader.loadDefault(), "FIR408 V0113P", true).firmwareRecognized());
        DspParameterCatalog known = new DspParameterCatalog(DspLibraryLoader.loadDefault(), "DSP408 V0104", true);
        assertTrue(known.firmwareRecognized());
        assertTrue(known.parameters().stream().allMatch(DspParameterCapability::writable));
        DspParameterCatalog offline = new DspParameterCatalog(DspLibraryLoader.loadFir408(), "FIR408 V0113P", false);
        assertTrue(offline.firmwareRecognized());
        assertTrue(offline.parameters().stream().noneMatch(DspParameterCapability::writable));
    }

    @Test
    void firMuteOnlyReportsTheTwoObservedChannelsAndOtherReadLayoutsStayAvailable() {
        DspParameterCatalog catalog = new DspParameterCatalog(DspLibraryLoader.loadFir408(), "FIR408 V0113P", true);
        for (DspChannel channel : DspChannel.values()) {
            boolean observed = channel == DspChannel.IN_D || channel == DspChannel.OUT_8;
            DspParameterCapability mute = catalog.capability(channel, DspParameter.MUTE);
            assertEquals(observed, mute.readbackSupported(), channel.toString());
            assertTrue(mute.writable());
            assertEquals(observed ? "observed" : "inferred", mute.readEvidence());
            for (DspParameter parameter : List.of(DspParameter.GAIN, DspParameter.PHASE, DspParameter.DELAY)) {
                assertTrue(catalog.capability(channel, parameter).readbackSupported());
            }
        }
        assertThrows(UnsupportedOperationException.class, () -> catalog.parameters().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.capability(DspChannel.IN_A, DspParameter.GAIN).resolution().clear());
    }

    @Test
    void normalizationRejectsInvalidTypesAndRangesInsteadOfClamping() {
        DspParameterCatalog catalog = new DspParameterCatalog(DspLibraryLoader.loadDefault(), "DSP408 V0104", true);
        for (Object invalid : List.of("0", true, Double.NaN, Double.POSITIVE_INFINITY, -60.001, 12.001)) {
            assertThrows(IllegalArgumentException.class, () -> catalog.normalize(DspParameter.GAIN, invalid));
        }
        for (Object invalid : List.of("0", false, Double.NEGATIVE_INFINITY, -0.001, 680.001)) {
            assertThrows(IllegalArgumentException.class, () -> catalog.normalize(DspParameter.DELAY, invalid));
        }
        for (DspParameter parameter : List.of(DspParameter.MUTE, DspParameter.PHASE)) {
            assertThrows(IllegalArgumentException.class, () -> catalog.normalize(parameter, "true"));
            assertThrows(IllegalArgumentException.class, () -> catalog.normalize(parameter, 1));
            assertThrows(IllegalArgumentException.class, () -> catalog.normalize(parameter, null));
            assertEquals(true, catalog.normalize(parameter, true));
        }
    }

    @Test
    void usesPiecewiseGainAndNinetySixthMillisecondWireQuantization() {
        DspParameterCatalog catalog = new DspParameterCatalog(DspLibraryLoader.loadDefault(), "DSP408 V0104", true);
        assertEquals(-60.0, (Double) catalog.normalize(DspParameter.GAIN, -60.0), 1e-10);
        assertEquals(-20.5, (Double) catalog.normalize(DspParameter.GAIN, -20.26), 1e-10);
        assertEquals(-20.0, (Double) catalog.normalize(DspParameter.GAIN, -20.24), 1e-10);
        assertEquals(-20.0, (Double) catalog.normalize(DspParameter.GAIN, -20.0), 1e-10);
        assertEquals(-19.9, (Double) catalog.normalize(DspParameter.GAIN, -19.86), 1e-10);
        assertEquals(12.0, (Double) catalog.normalize(DspParameter.GAIN, 12), 1e-10);
        assertEquals(0.010416666666666666, (Double) catalog.normalize(DspParameter.DELAY, 0.01), 1e-14);
        assertEquals(680.0, (Double) catalog.normalize(DspParameter.DELAY, 680), 1e-10);
        assertEquals(List.of(new DspParameterStep(-60, -20, .5), new DspParameterStep(-20, 12, .1)),
                catalog.capability(DspChannel.IN_A, DspParameter.GAIN).resolution());
        assertEquals(DspParameter.GAIN, DspParameter.parse("gain"));
        assertThrows(IllegalArgumentException.class, () -> DspParameter.parse("gainDb"));
    }
}
