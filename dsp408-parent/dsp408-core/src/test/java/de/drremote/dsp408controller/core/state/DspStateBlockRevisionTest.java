package de.drremote.dsp408controller.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DspStateBlockRevisionTest {
    @Test
    void receivedSnapshotKeepsOriginalBytesRevisionAndTimeAcrossOptimisticPatches() {
        DspState state = new DspState();
        byte[] received = {1, 2};
        state.storeBlock(2, received);
        received[0] = 9;
        var snapshot = state.receivedBlock(2);
        state.patchCachedBlockUInt16LE(2, 0, 280);
        assertArrayEquals(new byte[]{1, 2}, snapshot.data());
        byte[] exposed = snapshot.data();
        exposed[0] = 9;
        assertArrayEquals(new byte[]{1, 2}, state.receivedBlock(2).data());
        assertEquals(state.blockRevision(2), snapshot.receiveRevision());
        assertTrue(snapshot.observedAtEpochMs() > 0);
        state.clearCachedBlocks();
        assertNull(state.receivedBlock(2));
        state.storeBlock(2, new byte[]{3});
        assertTrue(state.receivedBlock(2).receiveRevision() > snapshot.receiveRevision());
        state.reset();
        assertNull(state.receivedBlock(2));
    }


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

        assertTrue(state.patchCachedBlockBit(0x0D, 20, 0x04, true));
        assertEquals(0x04, state.getBlock(0x0D)[20] & 0xFF);
        assertTrue(state.patchCachedBlockBit(0x0D, 20, 0x01, true));
        assertEquals(0x05, state.getBlock(0x0D)[20] & 0xFF);
        assertTrue(state.patchCachedBlockBit(0x0D, 20, 0x04, false));
        assertEquals(0x01, state.getBlock(0x0D)[20] & 0xFF);
        assertEquals(receiveRevision, state.blockRevision(0x0D));
        assertFalse(state.patchCachedBlockBit(0x0E, 0, 0x01, true));
        assertFalse(state.patchCachedBlockBit(0x0D, 50, 0x01, true));
    }
}
