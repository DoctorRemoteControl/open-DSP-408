package de.drremote.dsp408controller.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408.api.MeterDto;
import de.drremote.dsp408.api.MeterStateDto;

class LimiterWarningMonitorTest {
    @Test
    void warningRoomOnlyAcceptsReadOnlyWarningCommands() {
        assertEquals(LimiterWarningMonitor.RoomCommand.ENABLE,
                LimiterWarningMonitor.parseRoomCommand("warnungen an"));
        assertEquals(LimiterWarningMonitor.RoomCommand.DISABLE,
                LimiterWarningMonitor.parseRoomCommand("Warnungen AUS"));
        assertEquals(LimiterWarningMonitor.RoomCommand.STATUS,
                LimiterWarningMonitor.parseRoomCommand("status"));
        assertEquals(LimiterWarningMonitor.RoomCommand.HELP,
                LimiterWarningMonitor.parseRoomCommand("hilfe"));
        assertEquals(LimiterWarningMonitor.RoomCommand.IGNORE,
                LimiterWarningMonitor.parseRoomCommand("!dsp gain out1 +6"));
    }

    @Test
    void onlyUsesConfirmedOutputLimiterFlags() {
        MeterDto active = meter("out1", "Out1", true, true, true);
        MeterDto unconfirmed = meter("out2", "Out2", true, false, true);
        MeterDto inactive = meter("out3", "Out3", false, true, true);
        MeterDto input = meter("ina", "InA", true, true, true);

        List<MeterDto> result = LimiterWarningMonitor.activeLimiters(
                new MeterStateDto(List.of(active, unconfirmed, inactive, input), 0, true, "test"));

        assertEquals(List.of(active), result);
    }

    @Test
    void producesShortActionableWarnings() {
        String first = LimiterWarningMonitor.warningText(
                List.of(meter("out1", "Out1", true, true, true)), false);
        String reminder = LimiterWarningMonitor.warningText(
                List.of(meter("out1", "Out1", true, true, true)), true);

        assertTrue(first.contains("LIMITER AKTIV"));
        assertTrue(first.contains("Pegel reduzieren"));
        assertFalse(first.contains("raw"));
        assertTrue(reminder.contains("WEITER AKTIV"));
    }

    private static MeterDto meter(String id,
                                  String displayName,
                                  boolean active,
                                  boolean limiterConfirmed,
                                  boolean mappingConfirmed) {
        return new MeterDto(
                id, displayName, 4,
                0, 0, 0, -12.0, 0, active,
                true, limiterConfirmed, true, true, mappingConfirmed,
                "test", "test");
    }
}
