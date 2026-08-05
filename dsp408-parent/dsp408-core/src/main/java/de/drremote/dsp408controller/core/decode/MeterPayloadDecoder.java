package de.drremote.dsp408controller.core.decode;

import java.util.Locale;
import java.util.function.Consumer;

import de.drremote.dsp408controller.core.library.DspLibrary;
import de.drremote.dsp408controller.core.library.MeterLayout;
import de.drremote.dsp408controller.core.library.MeterSlotLocation;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.DspProtocol;
import de.drremote.dsp408controller.core.state.DspState;

public final class MeterPayloadDecoder {
    private static final DspChannel[] OUTPUTS = {
            DspChannel.OUT_1,
            DspChannel.OUT_2,
            DspChannel.OUT_3,
            DspChannel.OUT_4,
            DspChannel.OUT_5,
            DspChannel.OUT_6,
            DspChannel.OUT_7,
            DspChannel.OUT_8
    };

    private final DspState state;
    private final Consumer<String> log;
    private final DspLibrary library;

    public MeterPayloadDecoder(DspState state, Consumer<String> log, DspLibrary library) {
        this.state = state;
        this.log = log;
        this.library = library;
    }

    public void decode(byte[] payload) {
        if (!DspProtocol.isRuntimeMeterResponse(payload)) {
            return;
        }

        MeterLayout layout = library.meterLayout();
        if (layout == null || !layout.hasSlots()) {
            return;
        }

        if (payload.length < layout.responseLengthBytes()) {
            if (log != null) {
                log.accept("METER RX skipped: payload too short for configured meter layout");
            }
            return;
        }

        state.markMeterRuntimePayloadFromDevice(payload, "cmd40");

        for (MeterSlotLocation slot : layout.slots()) {
            int offset = slot.payloadOffset();
            if (payload.length < offset + slot.widthBytes() || slot.widthBytes() < 3) {
                continue;
            }

            int rawLow = payload[offset] & 0xFF;
            int rawHigh = payload[offset + 1] & 0xFF;
            int slotByte2 = payload[offset + 2] & 0xFF;

            state.markMeterFromDevice(
                    slot.channel(),
                    rawLow,
                    rawHigh,
                    slotByte2,
                    String.format(Locale.ROOT, "cmd40@%02X", offset)
            );

            if (layout.clipThresholdRaw() != null
                    && layout.clipThresholdConfirmed(slot.channel())) {
                int rawValue = rawLow | (rawHigh << 8);
                double preciseRawValue = rawValue + (slotByte2 / 256.0);
                state.markMeterClipFromDevice(
                        slot.channel(),
                        preciseRawValue >= layout.clipThresholdRaw(),
                        String.format(
                                Locale.ROOT,
                                "cmd40@%02X raw=%.6f threshold=%.6f",
                                offset,
                                preciseRawValue,
                                layout.clipThresholdRaw()
                        )
                );
            }

            if (log != null) {
                int rawValue = rawLow | (rawHigh << 8);
                log.accept(String.format(
                        Locale.ROOT,
                        "METER RX %s -> raw=0x%04X aux=0x%02X",
                        slot.channel().displayName(),
                        rawValue,
                        slotByte2
                ));
            }
        }

        if (layout.statusByteOffset() >= 0 && payload.length > layout.statusByteOffset()) {
            int statusByte = payload[layout.statusByteOffset()] & 0xFF;
            state.markMeterStatusByteFromDevice(
                    statusByte,
                    String.format(Locale.ROOT, "cmd40@%02X", layout.statusByteOffset())
            );
        }

        if (layout.limiterMaskOffset() >= 0 && payload.length > layout.limiterMaskOffset()) {
            int limiterMask = payload[layout.limiterMaskOffset()] & 0xFF;
            for (int bit = 0; bit < OUTPUTS.length; bit++) {
                boolean active = (limiterMask & (1 << bit)) != 0;
                state.markLimiterRuntimeActive(
                        OUTPUTS[bit],
                        active,
                        String.format(Locale.ROOT, "cmd40@%02X bit%d", layout.limiterMaskOffset(), bit)
                );
            }

            if (log != null) {
                log.accept(String.format(
                        Locale.ROOT,
                        "METER RX limiterMask=0x%02X",
                        limiterMask
                ));
            }
        }
    }
}
