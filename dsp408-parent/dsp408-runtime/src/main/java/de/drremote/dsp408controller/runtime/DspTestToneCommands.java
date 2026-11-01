package de.drremote.dsp408controller.runtime;

import java.util.Locale;

import de.drremote.dsp408.api.RawTxDto;
import de.drremote.dsp408controller.core.protocol.DspProtocol;
import de.drremote.dsp408controller.core.service.DspController;

/** Owns test-tone source and sine-frequency commands. */
final class DspTestToneCommands {
    private final DspController controller;
    private final DspRuntimeDtoMapper dtoMapper;

    DspTestToneCommands(DspController controller, DspRuntimeDtoMapper dtoMapper) {
        this.controller = controller;
        this.dtoMapper = dtoMapper;
    }

    RawTxDto setSource(String source) throws Exception {
        int sourceRaw = switch (normalizeSource(source)) {
            case "analoginput", "analog", "input", "off" -> 0;
            case "pinknoise", "pink" -> 1;
            case "whitenoise", "white" -> 2;
            case "sine" -> 3;
            default -> throw new IllegalArgumentException("Unknown test tone source: " + source);
        };
        if (sourceRaw == 0) {
            controller.disableTestTone();
        } else {
            controller.sendRaw(DspProtocol.buildTestToneSource(sourceRaw));
            controller.state().markTestToneSource(
                    sourceRaw, DspProtocol.testToneSourceLabel(sourceRaw), "tx"
            );
        }
        return dtoMapper.rawTx(DspProtocol.buildTestToneSource(sourceRaw));
    }

    RawTxDto setSineFrequency(double hz) throws Exception {
        controller.enableTestTone(hz);
        return dtoMapper.rawTx(DspProtocol.buildTestToneSineFrequencyHz(hz));
    }

    RawTxDto setSineFrequencyRaw(int selectorRaw) throws Exception {
        controller.sendRaw(DspProtocol.buildTestToneSineFrequencyRaw(selectorRaw));
        controller.state().markTestToneSource(
                0x03, DspProtocol.testToneSourceLabel(0x03), "tx"
        );
        controller.state().markTestToneSineFrequency(
                selectorRaw, DspProtocol.testToneSineFrequencyHz(selectorRaw), "tx"
        );
        return dtoMapper.rawTx(DspProtocol.buildTestToneSineFrequencyRaw(selectorRaw));
    }

    RawTxDto disable() throws Exception {
        controller.disableTestTone();
        return dtoMapper.rawTx(DspProtocol.buildTestToneOff());
    }

    static String normalizeSource(String source) {
        return source == null
                ? ""
                : source.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }
}
