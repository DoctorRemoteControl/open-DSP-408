package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AutoLimitPlannerTest {
    private static final List<String> OUTPUTS =
            List.of("out1", "out2", "out3", "out4", "out5", "out6", "out7", "out8");

    @Test
    void allOutputsUseOneThresholdFromLoudestMeasuredOutput() {
        Map<String, Double> thresholds = AutoLimitPlanner.initialThresholds(
                OUTPUTS,
                Map.of("out1", -12.2, "out2", -4.3),
                1.0,
                AutoLimitPlanner.ALL_OUTPUTS
        );

        assertEquals(8, thresholds.size());
        thresholds.values().forEach(value -> assertEquals(-14.5, value));
    }

    @Test
    void initialThresholdAccountsForMeterOffsetAndDeviceMaximum() {
        Map<String, Double> thresholds = AutoLimitPlanner.initialThresholds(
                OUTPUTS,
                Map.of("out2", 39.0),
                1.0,
                AutoLimitPlanner.ALL_OUTPUTS
        );

        thresholds.values().forEach(value -> assertEquals(20.0, value));
    }

    @Test
    void onlyActiveLimiterThresholdIsRaisedDuringProbe() {
        Map<String, Double> raised = AutoLimitPlanner.raiseActiveThresholds(
                Map.of("out1", 12.0, "out2", 18.0),
                Set.of("out2")
        );

        assertEquals(12.0, raised.get("out1"));
        assertEquals(19.0, raised.get("out2"));
    }

    @Test
    void thresholdRaiseStopsAtDeviceMaximum() {
        Map<String, Double> raised = AutoLimitPlanner.raiseActiveThresholds(
                Map.of("out2", 20.0),
                Set.of("out2")
        );

        assertEquals(20.0, raised.get("out2"));
    }

    @Test
    void tighteningUsesHalfDbProbeAndKeepsRequestedHeadroom() {
        assertEquals(11.5, AutoLimitPlanner.lowerProbeThreshold(12.0));
        assertEquals(9.0, AutoLimitPlanner.lowerCoarseProbeThreshold(12.0));
        assertEquals(10.5, AutoLimitPlanner.midpointThreshold(9.0, 12.0));
        assertEquals(12.5, AutoLimitPlanner.freeThresholdAboveActiveProbe(11.5, 1.0));
        assertEquals(12.0, AutoLimitPlanner.freeThresholdAboveActiveProbe(11.5, 0.0));
    }

    @Test
    void rejectsUnknownTargetMode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AutoLimitPlanner.normalizeMode("speakers")
        );
    }
}
