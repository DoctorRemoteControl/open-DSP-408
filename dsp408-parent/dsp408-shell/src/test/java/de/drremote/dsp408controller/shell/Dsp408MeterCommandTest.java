package de.drremote.dsp408controller.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408.api.DspService;
import de.drremote.dsp408.api.MeterDto;
import de.drremote.dsp408.api.MeterStateDto;
import de.drremote.dsp408.api.StateDto;

class Dsp408MeterCommandTest {

    @Test
    void exposesFreshCachedPerChannelAndMultiDspMeterCommands() throws Exception {
        MeterDto out1 = new MeterDto(
                "out1", "Out 1", 4,
                100, 59, 15204, -12.5, 0, true,
                true, true, false, true, true,
                "runtime-meter", "runtime-meter");
        MeterStateDto cached = new MeterStateDto(List.of(out1), 1, true, "runtime-meter");
        StateDto refreshed = new StateDto(true, null, List.of(), List.of(), cached);
        AtomicInteger readCount = new AtomicInteger();
        DspService service = service(cached, refreshed, readCount);
        Dsp408Command command = new Dsp408Command();
        command.bindService(service);

        assertSame(refreshed, command.meters());
        assertSame(cached, command.meterstate());
        assertSame(out1, command.meter("out1"));
        assertSame(out1, command.dsp("fir408", "meter", "out1"));
        assertSame(cached, command.dsp("fir408", "meterstate"));
        assertSame(refreshed, command.dsp("fir408", "meters"));
        assertEquals(2, readCount.get());
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
