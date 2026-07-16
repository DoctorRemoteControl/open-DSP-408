package de.drremote.dsp408controller.core.decode;

import java.util.Locale;
import java.util.function.Consumer;

import de.drremote.dsp408controller.core.library.DspLibrary;
import de.drremote.dsp408controller.core.library.MuteReadSpec;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.state.DspState;

public final class MuteBlockDecoder implements BlockDecoder {
    private final DspState state;
    private final Consumer<String> log;
    private final MuteReadSpec spec;

    public MuteBlockDecoder(DspState state, Consumer<String> log, DspLibrary library) {
        this.state = state;
        this.log = log;
        this.spec = library.muteReadSpec();
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

        state.markMutedFromDevice(DspChannel.IN_A, (inputMask & (1 << 0)) != 0, maskSource(spec.inputDataOffset()));
        state.markMutedFromDevice(DspChannel.IN_B, (inputMask & (1 << 1)) != 0, maskSource(spec.inputDataOffset()));
        state.markMutedFromDevice(DspChannel.IN_C, (inputMask & (1 << 2)) != 0, maskSource(spec.inputDataOffset()));
        state.markMutedFromDevice(DspChannel.IN_D, (inputMask & (1 << 3)) != 0, maskSource(spec.inputDataOffset()));

        state.markMutedFromDevice(DspChannel.OUT_1, (outputMask & (1 << 0)) != 0, maskSource(spec.outputDataOffset()));
        state.markMutedFromDevice(DspChannel.OUT_2, (outputMask & (1 << 1)) != 0, maskSource(spec.outputDataOffset()));
        state.markMutedFromDevice(DspChannel.OUT_3, (outputMask & (1 << 2)) != 0, maskSource(spec.outputDataOffset()));
        state.markMutedFromDevice(DspChannel.OUT_4, (outputMask & (1 << 3)) != 0, maskSource(spec.outputDataOffset()));
        state.markMutedFromDevice(DspChannel.OUT_5, (outputMask & (1 << 4)) != 0, maskSource(spec.outputDataOffset()));
        state.markMutedFromDevice(DspChannel.OUT_6, (outputMask & (1 << 5)) != 0, maskSource(spec.outputDataOffset()));
        state.markMutedFromDevice(DspChannel.OUT_7, (outputMask & (1 << 6)) != 0, maskSource(spec.outputDataOffset()));
        state.markMutedFromDevice(DspChannel.OUT_8, (outputMask & (1 << 7)) != 0, maskSource(spec.outputDataOffset()));

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
