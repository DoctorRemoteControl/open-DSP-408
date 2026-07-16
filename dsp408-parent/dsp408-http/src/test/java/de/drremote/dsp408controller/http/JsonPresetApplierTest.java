package de.drremote.dsp408controller.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408.api.DspService;

class JsonPresetApplierTest {
    private final JsonPresetApplier applier = new JsonPresetApplier();

    @Test
    void validatesCompletePresetBeforeAnyDspOperation() {
        JsonPresetApplier.PresetPlan plan = applier.validate(validPreset());

        assertEquals("safe-test", plan.presetId());
        assertEquals(4, plan.inputs().size());
        assertEquals(8, plan.outputs().size());
        assertEquals(List.of("ina", "out1"),
                applier.validationResponse("fir408", plan).get("selectedForUnmute"));
    }

    @Test
    void rejectsMissingOutput() {
        Map<String, Object> preset = validPreset();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outputs = (List<Map<String, Object>>) preset.get("outputs");
        outputs.remove(outputs.size() - 1);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> applier.validate(preset));

        assertTrue(error.getMessage().contains("out8"));
    }

    @Test
    void rejectsUnsupportedFieldsInsteadOfSilentlyIgnoringThem() {
        Map<String, Object> preset = validPreset();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outputs = (List<Map<String, Object>>) preset.get("outputs");
        outputs.get(0).put("peq", List.of());

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> applier.validate(preset));

        assertTrue(error.getMessage().contains("unsupported fields: peq"));
    }

    @Test
    void appliesAllMutesFirstAndDoesNotUnmuteByDefault() throws Exception {
        List<String> calls = new ArrayList<>();
        DspService dsp = recordingService(calls, null);
        JsonPresetApplier.PresetPlan plan = applier.validate(validPreset());

        Map<String, Object> response = applier.apply(dsp, plan, false, true);

        assertEquals(12, calls.subList(0, 12).stream().filter(call -> call.startsWith("mute:")).count());
        assertFalse(calls.stream().anyMatch(call -> call.startsWith("unmute:")));
        assertEquals(true, response.get("allChannelsRemainMuted"));
        assertEquals(102, response.get("operationsApplied"));
    }

    @Test
    void supportsMultipleMatrixInputsWithIndependentCrosspointGains() {
        Map<String, Object> preset = validPreset();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outputs = (List<Map<String, Object>>) preset.get("outputs");
        Map<String, Object> bass = outputs.get(4);
        bass.remove("route");
        bass.put("routes", List.of(
                Map.of("input", "ina", "crosspointGainDb", -6),
                Map.of("input", "inb", "crosspointGainDb", -6)
        ));

        JsonPresetApplier.PresetPlan plan = applier.validate(preset);

        assertEquals(2, plan.outputs().get(4).routes().size());
        assertEquals("ina", plan.outputs().get(4).routes().get(0).input());
        assertEquals(-6.0, plan.outputs().get(4).routes().get(0).crosspointGainDb());
        assertEquals("inb", plan.outputs().get(4).routes().get(1).input());
    }

    @Test
    void unmuteRequiresExplicitApplyOption() throws Exception {
        List<String> calls = new ArrayList<>();
        DspService dsp = recordingService(calls, null);
        JsonPresetApplier.PresetPlan plan = applier.validate(validPreset());

        Map<String, Object> response = applier.apply(dsp, plan, true, false);

        assertEquals(List.of("ina", "out1"), response.get("unmutedChannels"));
        assertEquals(List.of("unmute:ina", "unmute:out1"), calls.subList(calls.size() - 2, calls.size()));
    }

    @Test
    void attemptsEmergencyMuteWhenAnOperationFails() {
        List<String> calls = new ArrayList<>();
        DspService dsp = recordingService(calls, "setGain:ina");
        JsonPresetApplier.PresetPlan plan = applier.validate(validPreset());

        JsonPresetApplier.PresetApplyException error = assertThrows(
                JsonPresetApplier.PresetApplyException.class,
                () -> applier.apply(dsp, plan, false, true)
        );
        Map<String, Object> response = error.response("fir408");

        assertEquals("set gain ina", response.get("failedOperation"));
        assertEquals(true, response.get("emergencyMuteAttempted"));
        assertEquals(true, response.get("allChannelsEmergencyMuted"));
        assertEquals(24, calls.stream().filter(call -> call.startsWith("mute:")).count());
    }

    @Test
    void reportsUnsupportedPresetOperationAsConflict() {
        List<String> calls = new ArrayList<>();
        DspService dsp = recordingService(
                calls,
                "setFirProcessingMode:out1",
                new UnsupportedOperationException("unverified write")
        );
        JsonPresetApplier.PresetPlan plan = applier.validate(validPreset());

        JsonPresetApplier.PresetApplyException error = assertThrows(
                JsonPresetApplier.PresetApplyException.class,
                () -> applier.apply(dsp, plan, false, false)
        );
        Map<String, Object> response = error.response("fir408");

        assertEquals(409, error.httpStatus());
        assertEquals(409, response.get("status"));
        assertEquals("unsupported_operation", response.get("error"));
        assertEquals(true, response.get("allChannelsEmergencyMuted"));
    }

    private DspService recordingService(List<String> calls, String failOn) {
        return recordingService(calls, failOn, new IllegalStateException("simulated failure"));
    }

    private DspService recordingService(List<String> calls, String failOn, RuntimeException failure) {
        return (DspService) Proxy.newProxyInstance(
                DspService.class.getClassLoader(),
                new Class<?>[]{DspService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("dspId")) {
                        return "fir408";
                    }
                    if (method.getName().equals("isConnected")) {
                        return true;
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(this, args);
                    }

                    String call = method.getName();
                    if (args != null && args.length > 0) {
                        call += ":" + args[0];
                    }
                    calls.add(call);
                    if (call.equals(failOn)) {
                        throw failure;
                    }
                    return null;
                }
        );
    }

    private Map<String, Object> validPreset() {
        Map<String, Object> preset = new LinkedHashMap<>();
        preset.put("schemaVersion", "1.0");
        preset.put("presetId", "safe-test");
        preset.put("title", "Safe test");
        preset.put("delayUnit", "ms");

        List<Map<String, Object>> inputs = new ArrayList<>();
        inputs.add(input("ina", "MAIN_L", true));
        inputs.add(input("inb", "MAIN_R", false));
        inputs.add(input("inc", "MEASURE", false));
        inputs.add(input("ind", "SPARE", false));
        preset.put("inputs", inputs);

        List<Map<String, Object>> outputs = new ArrayList<>();
        outputs.add(output("out1", "TOP_L1", "ina", true, true));
        outputs.add(output("out2", "TOP_L2", "ina", true, false));
        outputs.add(output("out3", "TOP_R1", "inb", true, false));
        outputs.add(output("out4", "TOP_R2", "inb", true, false));
        outputs.add(output("out5", "BASS_L", "ina", true, false));
        outputs.add(output("out6", "BASS_R", "inb", true, false));
        outputs.add(output("out7", "RES_7", null, false, false));
        outputs.add(output("out8", "RES_8", null, false, false));
        preset.put("outputs", outputs);
        return preset;
    }

    private Map<String, Object> input(String id, String name, boolean unmute) {
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("id", id);
        channel.put("name", name);
        channel.put("gainDb", -20);
        channel.put("phaseInverted", false);
        channel.put("delayMs", 0);
        channel.put("unmuteAfterApply", unmute);
        return channel;
    }

    private Map<String, Object> output(
            String id,
            String name,
            String routeInput,
            boolean processing,
            boolean unmute
    ) {
        Map<String, Object> channel = input(id, name, unmute);
        channel.put("processingMode", "iir");
        if (!processing) {
            return channel;
        }

        channel.put("route", Map.of(
                "input", routeInput,
                "crosspointGainDb", 0
        ));

        boolean bass = id.equals("out5") || id.equals("out6");
        channel.put("crossover", Map.of(
                "highPass", Map.of(
                        "enabled", true,
                        "hz", bass ? 45 : 160,
                        "slope", "LR24"
                ),
                "lowPass", Map.of(
                        "enabled", bass,
                        "hz", bass ? 160 : 20000,
                        "slope", "LR24"
                )
        ));
        channel.put("limiter", Map.of(
                "enabled", true,
                "thresholdDb", -20,
                "attackMs", bass ? 10 : 5,
                "releaseMs", bass ? 500 : 300
        ));
        return channel;
    }
}
