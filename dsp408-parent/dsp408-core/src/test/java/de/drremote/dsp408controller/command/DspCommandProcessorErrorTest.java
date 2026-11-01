package de.drremote.dsp408controller.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.service.DspController;

class DspCommandProcessorErrorTest {
    @Test
    void ordinaryCommandErrorsRemainTextResponses() {
        DspController controller = new DspController();
        List<String> messages = new ArrayList<>();
        DspCommandProcessor processor = new DspCommandProcessor(controller, () -> null, messages::add,
                DspCommandProcessor.CommandMode.CLI);

        processor.process("gain invalid -20");

        assertEquals(1, messages.size());
        assertTrue(messages.getFirst().startsWith("Error: "));
    }
}
