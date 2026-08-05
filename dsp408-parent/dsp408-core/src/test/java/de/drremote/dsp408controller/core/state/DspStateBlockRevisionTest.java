package de.drremote.dsp408controller.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DspStateBlockRevisionTest {

    @Test
    void distinguishesFreshBlockRepliesFromCachedData() {
        DspState state = new DspState();

        assertEquals(0L, state.blockRevision(0x0D));

        state.storeBlock(0x0D, new byte[]{0x01});
        long firstRevision = state.blockRevision(0x0D);
        assertTrue(firstRevision > 0L);

        state.storeBlock(0x0D, new byte[]{0x01});
        assertTrue(state.blockRevision(0x0D) > firstRevision);

        state.reset();
        assertEquals(0L, state.blockRevision(0x0D));
    }

    @Test
    void mirrorsServerWritesWithoutPretendingTheyAreFreshDeviceReplies() {
        DspState state = new DspState();
        state.storeBlock(0x0D, new byte[50]);
        long receiveRevision = state.blockRevision(0x0D);

        assertTrue(state.patchCachedBlockUInt16LE(0x0D, 18, 0x1234));
        assertEquals(0x34, state.getBlock(0x0D)[18] & 0xFF);
        assertEquals(0x12, state.getBlock(0x0D)[19] & 0xFF);
        assertEquals(receiveRevision, state.blockRevision(0x0D));
        assertFalse(state.patchCachedBlockUInt16LE(0x0E, 0, 1));
        assertFalse(state.patchCachedBlockUInt16LE(0x0D, 49, 1));
    }
}
