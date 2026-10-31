package de.drremote.dsp408controller.core.net;

import java.io.IOException;
import java.io.PushbackInputStream;

/** Existing controller API, implemented by the shared protocol library. */
public final class DspFrameCodec {
    private DspFrameCodec() { }

    public static byte[] encode(byte[] payload) {
        return de.drremote.dsp408.protocol.DspFrameCodec.encode(payload);
    }

    public static DecodedFrame readFrame(PushbackInputStream in) throws IOException {
        var decoded = de.drremote.dsp408.protocol.DspFrameCodec.readFrame(in);
        return new DecodedFrame(decoded.payload(), decoded.checksumValid(),
                decoded.calculatedChecksum(), decoded.receivedChecksum());
    }

    public record DecodedFrame(byte[] payload, boolean checksumValid,
                               int calculatedChecksum, int receivedChecksum) { }
}
