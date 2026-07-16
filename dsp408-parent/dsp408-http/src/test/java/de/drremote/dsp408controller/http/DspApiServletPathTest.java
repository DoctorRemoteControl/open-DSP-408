package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DspApiServletPathTest {
    @Test
    void directGainRouteDoesNotShadowNestedPeqOrGeqRoutes() {
        assertTrue(DspApiServlet.isDirectChannelGainPath("/channels/ina/gain"));
        assertTrue(DspApiServlet.isDirectChannelGainPath("/channels/out8/gain"));

        assertFalse(DspApiServlet.isDirectChannelGainPath("/channels/ina/geq/1/gain"));
        assertFalse(DspApiServlet.isDirectChannelGainPath("/channels/ina/input-peq/1/gain"));
        assertFalse(DspApiServlet.isDirectChannelGainPath("/channels/out1/peq/1/gain"));
    }
}
