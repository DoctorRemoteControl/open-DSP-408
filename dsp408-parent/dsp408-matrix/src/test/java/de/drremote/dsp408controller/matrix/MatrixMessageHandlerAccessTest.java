package de.drremote.dsp408controller.matrix;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MatrixMessageHandlerAccessTest {

    @Test
    void roomMemberModeAllowsUsersOutsideTheExplicitAllowlist() {
        assertTrue(MatrixMessageHandler.allowsVolumeCommand(true, false));
    }

    @Test
    void defaultModeStillRequiresTheExplicitAllowlist() {
        assertFalse(MatrixMessageHandler.allowsVolumeCommand(false, false));
        assertTrue(MatrixMessageHandler.allowsVolumeCommand(false, true));
    }
}
