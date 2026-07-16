package de.drremote.dsp408controller.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.drremote.dsp408.api.DspService;
import de.drremote.dsp408.api.MeterDto;
import de.drremote.dsp408.api.MeterStateDto;
import de.drremote.dsp408.api.StateDto;

class MatrixMeterCommandTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void supportsFreshCachedAndPerChannelMeterAccess() throws Exception {
        MeterDto out1 = out1();
        MeterStateDto cached = new MeterStateDto(List.of(out1), 1, true, "runtime-meter");
        StateDto refreshed = new StateDto(true, null, List.of(), List.of(), cached);
        AtomicInteger readCount = new AtomicInteger();
        MatrixDspCommandDispatcher dispatcher =
                new MatrixDspCommandDispatcher(service(cached, refreshed, readCount));

        JsonNode fresh = MAPPER.readTree(dispatcher.execute("!dsp meters", false));
        JsonNode allCached = MAPPER.readTree(dispatcher.execute("!dsp meterstate", false));
        JsonNode channel = MAPPER.readTree(dispatcher.execute("!dsp meter out1", false));

        assertEquals(1, readCount.get());
        assertEquals(-12.5, fresh.path("meters").path("channels").get(0).path("levelDb").asDouble());
        assertEquals("out1", allCached.path("channels").get(0).path("id").asText());
        assertTrue(channel.path("limiterActive").asBoolean());
    }

    @Test
    void mapsStructuredMachineMeterCommands() {
        ObjectNode args = MAPPER.createObjectNode().put("channelId", "out1");

        assertEquals("!dsp meters", MatrixMessageHandler.mapMachineCommand("meters.read", args));
        assertEquals("!dsp meterstate", MatrixMessageHandler.mapMachineCommand("meters.get", args));
        assertEquals("!dsp meter out1", MatrixMessageHandler.mapMachineCommand("meter.get", args));
    }

    private static MeterDto out1() {
        return new MeterDto(
                "out1", "Out 1", 4,
                100, 59, 15204, -12.5, 0, true,
                true, true, false, true, true,
                "runtime-meter", "runtime-meter");
    }

    private static DspService service(MeterStateDto cached, StateDto refreshed, AtomicInteger readCount) {
        return (DspService) Proxy.newProxyInstance(
                DspService.class.getClassLoader(),
                new Class<?>[]{DspService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMeters" -> cached;
                    case "getMeter" -> cached.channels().get(0);
                    case "requestRuntimeMeters" -> {
                        readCount.incrementAndGet();
                        yield refreshed;
                    }
                    case "forDsp" -> proxy;
                    case "dspId" -> "fir408";
                    case "isConnected" -> true;
                    case "toString" -> "MeterTestDspService";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
