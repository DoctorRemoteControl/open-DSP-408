package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class DspDevicePoolTest {
    @Test
    void normalizesAndValidatesDeviceIds() {
        assertEquals("main", DspDevicePool.normalizeId(null));
        assertEquals("rack.left_1", DspDevicePool.normalizeId(" Rack.Left_1 "));
        assertThrows(
                IllegalArgumentException.class,
                () -> DspDevicePool.normalizeId("rack left")
        );
    }

    @Test
    void parsesDefaultAndExplicitPorts() {
        List<DspDevicePool.DspDefinition> definitions = DspDevicePool.parseDefinitions(
                new String[]{"top=192.168.1.166", "bass=dsp-bass.local:9861"}
        );

        assertEquals(
                new DspDevicePool.DspDefinition("top", "192.168.1.166", 9761),
                definitions.get(0)
        );
        assertEquals(
                new DspDevicePool.DspDefinition("bass", "dsp-bass.local", 9861),
                definitions.get(1)
        );
    }

    @Test
    void rejectsMalformedDefinitions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DspDevicePool.parseDefinitions(new String[]{"missing-endpoint="})
        );
        assertThrows(
                NumberFormatException.class,
                () -> DspDevicePool.parseDefinitions(new String[]{"top=192.168.1.166:not-a-port"})
        );
    }
}
