package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class DspHttpRequestParserTest {
    private final DspHttpRequestParser parser = new DspHttpRequestParser(new ObjectMapper());

    @Test
    void validatesAndConvertsScalarInputs() {
        Map<String, Object> body = Map.of(
                "gain", "-12.5",
                "enabled", "yes",
                "index", 3
        );

        assertEquals(-12.5, parser.requireDouble(body, "gain"));
        assertTrue(parser.requireBoolean(body, "enabled"));
        assertEquals(3, parser.requireInt(body, "index"));
        assertFalse(parser.toBoolean("off"));
        assertThrows(IllegalArgumentException.class, () -> parser.requireString(body, "name"));
    }

    @Test
    void acceptsFirCoefficientTextAndNormalizesPaths() {
        assertEquals(
                List.of(0.5, -0.25, 0.125),
                parser.requireDoubleList(Map.of("coefficients", "0.5, -0.25; 0.125"), "coefficients")
        );
        assertEquals("/", DspHttpRequestParser.normalizePath(null));
        assertEquals("/channels", DspHttpRequestParser.normalizePath("  /channels  "));
    }
}
