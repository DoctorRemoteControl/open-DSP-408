package de.drremote.dsp408controller.core.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.library.DspWriteCapabilities.Operation;
import de.drremote.dsp408controller.core.protocol.DspChannel;

class Fir408EvidenceGateTest {
    @Test
    void onlyConfirmedFir408ReadMapsBecomeDeviceConfirmedState() {
        DspLibrary library = DspLibraryLoader.loadFir408();

        assertEquals(12, uniqueGainChannels(library).size());
        assertEquals(12, countPhaseLocations(library));
        assertEquals(Set.of(DspChannel.values()), uniquePhaseChannels(library));
        assertEquals(12, uniqueDelayChannels(library).size());

        assertEquals(8, countMatrixRoutes(library));
        assertEquals(32, uniqueMatrixCrosspoints(library).size());

        assertEquals(outputChannels(), uniqueCrossoverChannels(library));
        assertEquals(outputChannels(), uniqueOutputPeqChannels(library));
        assertEquals(inputChannels(), uniqueInputPeqChannels(library));
        assertEquals(8, library.inputPeqBypassLocations(DspChannel.IN_A).size());
        assertEquals(8, library.inputPeqBypassLocations(DspChannel.IN_B).size());
        assertEquals(8, library.inputPeqBypassLocations(DspChannel.IN_C).size());
        assertEquals(8, library.inputPeqBypassLocations(DspChannel.IN_D).size());

        assertEquals(4, countGateLocations(library));
        assertEquals(inputChannels(), uniqueGateChannels(library));
        assertEquals(0, countGeqLocations(library));
        assertTrue(library.hasInputCompressorLocations());
        assertEquals(8, uniqueLimiterChannels(library).size());
        assertTrue(library.outputPeqDefault(1) == null);

        assertFalse(library.muteReadSpec().confirmedFromDevice());
        assertTrue(library.testToneReadSpec() != null);

        MeterLayout meters = library.meterLayout();
        assertFalse(meters.channelMappingConfirmed());
        assertTrue(meters.levelCalibrationConfirmed());
        assertTrue(meters.limiterMappingConfirmed());
        assertEquals(16176.0, meters.clipThresholdRaw());
        assertTrue(meters.clipThresholdConfirmed(DspChannel.IN_A));
        assertTrue(meters.clipThresholdConfirmed(DspChannel.IN_B));
        assertFalse(meters.clipThresholdConfirmed(DspChannel.OUT_1));
    }

    @Test
    void onlyVerifiedFir408WritesAreExposedThroughNormalApis() {
        DspWriteCapabilities writes = DspLibraryLoader.loadFir408().writeCapabilities();

        assertEquals(Set.of(DspChannel.values()), writes.channels(Operation.PHASE));
        assertEquals(outputChannels(), writes.channels(Operation.OUTPUT_PEQ));
        assertEquals(inputChannels(), writes.channels(Operation.INPUT_PEQ));
        assertEquals(Set.of(), writes.channels(Operation.INPUT_GEQ));
        assertEquals(Set.of(DspChannel.IN_A, DspChannel.IN_D), writes.channels(Operation.INPUT_GATE));

        assertEquals(outputChannels(), writes.channels(Operation.CROSSOVER_HIGH_PASS));
        assertEquals(outputChannels(), writes.channels(Operation.CROSSOVER_LOW_PASS));
        assertEquals(Set.of(DspChannel.IN_A, DspChannel.IN_D), writes.channels(Operation.INPUT_COMPRESSOR));

        assertEquals(outputChannels(), writes.channels(Operation.FIR_PROCESSING_MODE));
        assertEquals(Set.of(DspChannel.OUT_1, DspChannel.OUT_2, DspChannel.OUT_8),
                writes.channels(Operation.FIR_GENERATOR));
        assertEquals(Set.of(DspChannel.IN_A, DspChannel.IN_B, DspChannel.OUT_1, DspChannel.OUT_8),
                writes.channels(Operation.EXTERNAL_FIR_UPLOAD));
    }

    @Test
    void evidenceClassifierRejectsMixedOrPredictedStatuses() {
        assertTrue(DspLibraryLoader.evidenceStatusConfirmed("observed_on_fir408_lan"));
        assertTrue(DspLibraryLoader.evidenceStatusConfirmed("verified_live_fir408_2026_07_16"));
        assertFalse(DspLibraryLoader.evidenceStatusConfirmed("predicted_from_dsp408"));
        assertFalse(DspLibraryLoader.evidenceStatusConfirmed("observed_plus_predicted_strong"));
        assertFalse(DspLibraryLoader.evidenceStatusConfirmed("derived_from_observed_fir408_stride"));
        assertFalse(DspLibraryLoader.evidenceStatusConfirmed("absent_on_fir408"));
        assertFalse(DspLibraryLoader.evidenceStatusConfirmed(null));
    }

    private static Set<DspChannel> uniqueGainChannels(DspLibrary library) {
        Set<DspChannel> result = new HashSet<>();
        library.gainReadLocationsByBlock().values().forEach(
                locations -> locations.forEach(location -> result.add(location.channel()))
        );
        return result;
    }

    private static int countPhaseLocations(DspLibrary library) {
        int count = 0;
        for (int block = 0; block <= library.maxParameterBlockIndex(); block++) {
            count += library.phaseReadLocationsForBlock(block).size();
        }
        return count;
    }

    private static Set<DspChannel> uniquePhaseChannels(DspLibrary library) {
        Set<DspChannel> result = new HashSet<>();
        for (int block = 0; block <= library.maxParameterBlockIndex(); block++) {
            library.phaseReadLocationsForBlock(block)
                    .forEach(location -> result.add(location.channel()));
        }
        return result;
    }

    private static Set<DspChannel> uniqueDelayChannels(DspLibrary library) {
        Set<DspChannel> result = new HashSet<>();
        for (int block = 0; block <= library.maxParameterBlockIndex(); block++) {
            library.delayReadLocationsForBlock(block)
                    .forEach(location -> result.add(location.channel()));
        }
        return result;
    }

    private static int countMatrixRoutes(DspLibrary library) {
        int count = 0;
        for (DspChannel channel : DspChannel.values()) {
            if (library.matrixRouteLocation(channel) != null) {
                count++;
            }
        }
        return count;
    }

    private static Set<String> uniqueMatrixCrosspoints(DspLibrary library) {
        Set<String> result = new HashSet<>();
        for (int block = 0; block <= library.maxParameterBlockIndex(); block++) {
            library.matrixCrosspointLocationsForBlock(block).forEach(
                    location -> result.add(location.output() + "<-" + location.input())
            );
        }
        return result;
    }

    private static Set<DspChannel> uniqueCrossoverChannels(DspLibrary library) {
        Set<DspChannel> result = new HashSet<>();
        for (int block = 0; block <= library.maxParameterBlockIndex(); block++) {
            library.crossoverLocationsForBlock(block)
                    .forEach(location -> result.add(location.channel()));
        }
        return result;
    }

    private static Set<DspChannel> uniqueOutputPeqChannels(DspLibrary library) {
        Set<DspChannel> result = new HashSet<>();
        for (int block = 0; block <= library.maxParameterBlockIndex(); block++) {
            library.outputPeqLocationsForBlock(block)
                    .forEach(location -> result.add(location.channel()));
        }
        return result;
    }

    private static Set<DspChannel> uniqueInputPeqChannels(DspLibrary library) {
        Set<DspChannel> result = new HashSet<>();
        for (int block = 0; block <= library.maxParameterBlockIndex(); block++) {
            library.inputPeqLocationsForBlock(block)
                    .forEach(location -> result.add(location.channel()));
        }
        return result;
    }

    private static int countGateLocations(DspLibrary library) {
        int count = 0;
        for (int block = 0; block <= library.maxParameterBlockIndex(); block++) {
            count += library.inputGateLocationsForBlock(block).size();
        }
        return count;
    }

    private static Set<DspChannel> uniqueGateChannels(DspLibrary library) {
        Set<DspChannel> result = new HashSet<>();
        for (int block = 0; block <= library.maxParameterBlockIndex(); block++) {
            library.inputGateLocationsForBlock(block)
                    .forEach(location -> result.add(location.channel()));
        }
        return result;
    }

    private static int countGeqLocations(DspLibrary library) {
        int count = 0;
        for (int block = 0; block <= library.maxParameterBlockIndex(); block++) {
            count += library.inputGeqLocationsForBlock(block).size();
        }
        return count;
    }

    private static Set<DspChannel> uniqueLimiterChannels(DspLibrary library) {
        Set<DspChannel> result = new HashSet<>();
        for (int block = 0; block <= library.maxParameterBlockIndex(); block++) {
            library.limiterLocationsForBlock(block)
                    .forEach(location -> result.add(location.channel()));
        }
        return result;
    }

    private static Set<DspChannel> outputChannels() {
        return Set.of(
                DspChannel.OUT_1,
                DspChannel.OUT_2,
                DspChannel.OUT_3,
                DspChannel.OUT_4,
                DspChannel.OUT_5,
                DspChannel.OUT_6,
                DspChannel.OUT_7,
                DspChannel.OUT_8
        );
    }

    private static Set<DspChannel> inputChannels() {
        return Set.of(DspChannel.IN_A, DspChannel.IN_B, DspChannel.IN_C, DspChannel.IN_D);
    }
}
