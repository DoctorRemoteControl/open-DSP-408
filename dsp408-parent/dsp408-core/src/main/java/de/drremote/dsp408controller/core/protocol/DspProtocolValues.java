package de.drremote.dsp408controller.core.protocol;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;

final class DspProtocolValues {
    private DspProtocolValues() {
    }

    static int hzToPeqRaw(double hz) {
        return DspCodecRegistry.peqFrequency().doubleToRaw(hz);
    }

    static double clampDb(double db) {
        return Math.max(-60.0, Math.min(12.0, db));
    }

    static int dbToRaw(double db) {
        return DspCodecRegistry.gain().doubleToRaw(db);
    }

    static double rawToDb(int raw) {
        return DspCodecRegistry.gain().rawToDouble(raw);
    }
}
