package de.drremote.dsp408controller.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.protocol.DspProtocol;

class DspClientResponsePolicyTest {
    @Test
    void acceptsAckForMuteAndRejectsAnUnrelatedMeterReply() {
        byte[] mute = DspProtocol.buildMute(3, true);

        assertTrue(DspCommandPolicy.responseMatches(mute, DspProtocol.ACK));
        assertFalse(DspCommandPolicy.responseMatches(
                mute,
                new byte[]{0x01, 0x00, 0x02, 0x40, 0x00}
        ));
    }

    @Test
    void matchesParameterBlockReplyByCommandAndBlockIndex() {
        byte[] request = DspProtocol.readParameterBlock(0x1C);

        assertTrue(DspCommandPolicy.responseMatches(
                request,
                new byte[]{0x01, 0x00, 0x02, 0x24, 0x1C}
        ));
        assertFalse(DspCommandPolicy.responseMatches(
                request,
                new byte[]{0x01, 0x00, 0x02, 0x24, 0x1B}
        ));
    }

    @Test
    void allowsObservedLongRunningCommandsEnoughTime() {
        assertEquals(6_000L, DspCommandPolicy.responseTimeoutMs(DspProtocol.loadPreset(0)));
        assertEquals(3_000L, DspCommandPolicy.responseTimeoutMs(
                new byte[]{0x00, 0x01, 0x01, 0x4B}
        ));
        assertEquals(1_000L, DspCommandPolicy.responseTimeoutMs(
                new byte[]{0x00, 0x01, 0x01, 0x4E}
        ));
        assertEquals(500L, DspCommandPolicy.responseTimeoutMs(DspProtocol.buildMute(0, true)));
        assertEquals(150L, DspCommandPolicy.responseTimeoutMs(DspProtocol.runtimeMeterRequest()));
    }

    @Test
    void prioritizesSafetyWritesAheadOfBackgroundTraffic() {
        assertEquals(
                DspCommandQueue.Priority.SAFETY,
                DspCommandPolicy.priorityOf(DspProtocol.buildMute(3, true))
        );
        assertEquals(
                DspCommandQueue.Priority.KEEPALIVE,
                DspCommandPolicy.priorityOf(DspProtocol.keepAlive())
        );
        assertEquals(
                DspCommandQueue.Priority.READ,
                DspCommandPolicy.priorityOf(DspProtocol.readParameterBlock(0x1C))
        );
        assertEquals(
                DspCommandQueue.Priority.METER,
                DspCommandPolicy.priorityOf(DspProtocol.runtimeMeterRequest())
        );
    }

    @Test
    void coalescesOnlyCommandsWhoseNewerValueSupersedesThePendingOne() {
        assertEquals("meter", DspCommandPolicy.coalesceKey(DspProtocol.runtimeMeterRequest()));
        assertEquals("keepalive", DspCommandPolicy.coalesceKey(DspProtocol.keepAlive()));
        assertEquals("block:28", DspCommandPolicy.coalesceKey(DspProtocol.readParameterBlock(0x1C)));
        assertEquals(
                "command:53:channel:3",
                DspCommandPolicy.coalesceKey(DspProtocol.buildMute(3, true))
        );
        assertNull(DspCommandPolicy.coalesceKey(DspProtocol.loadPreset(0)));
    }

    @Test
    void distinguishesReadResponsesFromAckWrites() {
        byte[] blockRead = DspProtocol.readParameterBlock(0x1C);
        byte[] mute = DspProtocol.buildMute(3, true);

        assertTrue(DspCommandPolicy.isIdempotentReadRequest(blockRead));
        assertFalse(DspCommandPolicy.expectsAck(blockRead));
        assertFalse(DspCommandPolicy.isIdempotentReadRequest(mute));
        assertTrue(DspCommandPolicy.expectsAck(mute));
    }

}
