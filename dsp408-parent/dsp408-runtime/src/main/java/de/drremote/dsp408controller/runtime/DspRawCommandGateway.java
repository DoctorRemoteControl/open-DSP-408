package de.drremote.dsp408controller.runtime;

import de.drremote.dsp408.api.RawTxDto;
import de.drremote.dsp408controller.core.service.DspController;
import de.drremote.dsp408controller.util.Hex;

/** Owns privileged login and untyped raw DSP writes. */
final class DspRawCommandGateway {
    private final DspController controller;
    private final DspRuntimeDtoMapper dtoMapper;
    private final DspSettingsCache settingsCache;
    private final Runnable undoInvalidator;

    DspRawCommandGateway(
            DspController controller,
            DspRuntimeDtoMapper dtoMapper,
            DspSettingsCache settingsCache,
            Runnable undoInvalidator
    ) {
        this.controller = controller;
        this.dtoMapper = dtoMapper;
        this.settingsCache = settingsCache;
        this.undoInvalidator = undoInvalidator;
    }

    RawTxDto sendRawHex(String payloadHex) throws Exception {
        byte[] payload = Hex.parseFlexible(payloadHex);
        controller.sendRaw(payload);
        undoInvalidator.run();
        settingsCache.markStale(
                "A raw command was sent; refresh is required before cached settings are authoritative."
        );
        return dtoMapper.rawTx(payload);
    }

    void loginPin(String pin) throws Exception {
        controller.login(pin);
    }
}
