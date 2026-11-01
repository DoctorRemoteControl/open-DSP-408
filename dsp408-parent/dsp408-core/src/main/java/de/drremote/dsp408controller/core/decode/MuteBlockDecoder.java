package de.drremote.dsp408controller.core.decode;

import java.util.Locale;
import java.util.function.Consumer;

import de.drremote.dsp408controller.core.library.DspLibrary;
import de.drremote.dsp408controller.core.library.DspMuteReadEvidence;
import de.drremote.dsp408controller.core.library.MuteReadSpec;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.state.DspState;

public final class MuteBlockDecoder implements BlockDecoder {
    private final DspState state;
    private final Consumer<String> log;
    private final MuteReadSpec spec;
    private final DspLibrary library;

    public MuteBlockDecoder(DspState state, Consumer<String> log, DspLibrary library) {
        this.state = state;
        this.log = log;
        this.spec = library.muteReadSpec();
        this.library = library;
    }

    @Override
    public void decode(int blockIndex, byte[] data) {
        if (spec == null
                || !spec.confirmedFromDevice()
                || data == null
                || blockIndex != spec.blockIndex()) {
            return;
        }

        if (data.length <= spec.inputDataOffset() || data.length <= spec.outputDataOffset()) {
            return;
        }

        int inputMask = readUInt8(data, spec.inputDataOffset());
        int outputMask = readUInt8(data, spec.outputDataOffset());

        for (DspChannel channel : DspChannel.values()) {
            if (!DspMuteReadEvidence.supports(library, channel)) {
                continue;
            }
            boolean input = channel.index() < 4;
            int bit = input ? channel.index() : channel.index() - 4;
            int mask = input ? inputMask : outputMask;
            state.markMutedFromDevice(channel, (mask & (1 << bit)) != 0,
                    maskSource(input ? spec.inputDataOffset() : spec.outputDataOffset()));
        }

        if (log != null) {
            log.accept(String.format(
                    Locale.ROOT,
                    "MUTE RX inputs=0x%02X outputs=0x%02X",
                    inputMask,
                    outputMask
            ));
        }
    }

    private String maskSource(int dataOffset) {
        return String.format(Locale.ROOT, "block:%02X@%02X", spec.blockIndex(), dataOffset);
    }

    private static int readUInt8(byte[] data, int offset) {
        return data[offset] & 0xFF;
    }
}
