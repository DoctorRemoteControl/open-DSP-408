package de.drremote.dsp408controller.runtime;

import de.drremote.dsp408controller.core.protocol.DspChannel;

/**
 * FIR408 parameter-block locations used to verify that a control write was
 * committed by the physical DSP.
 */
final class Fir408WriteVerifier {
    private Fir408WriteVerifier() {
    }

    static GainLocation gainLocation(DspChannel channel) {
        return switch (channel) {
            case IN_A -> new GainLocation(0x02, 46);
            case IN_B -> new GainLocation(0x05, 34);
            case IN_C -> new GainLocation(0x08, 22);
            case IN_D -> new GainLocation(0x0B, 10);
            case OUT_1 -> new GainLocation(0x0D, 18);
            case OUT_2 -> new GainLocation(0x0F, 26);
            case OUT_3 -> new GainLocation(0x11, 34);
            case OUT_4 -> new GainLocation(0x13, 42);
            case OUT_5 -> new GainLocation(0x16, 0);
            case OUT_6 -> new GainLocation(0x18, 8);
            case OUT_7 -> new GainLocation(0x1A, 16);
            case OUT_8 -> new GainLocation(0x1C, 24);
        };
    }

    static LimiterLocation limiterLocation(DspChannel channel) {
        return switch (channel) {
            case OUT_1 -> new LimiterLocation(0x0D, 10, 12, 14, 16);
            case OUT_2 -> new LimiterLocation(0x0F, 18, 20, 22, 24);
            case OUT_3 -> new LimiterLocation(0x11, 26, 28, 30, 32);
            case OUT_4 -> new LimiterLocation(0x13, 34, 36, 38, 40);
            case OUT_5 -> new LimiterLocation(0x15, 42, 44, 46, 48);
            case OUT_6 -> new LimiterLocation(0x18, 0, 2, 4, 6);
            case OUT_7 -> new LimiterLocation(0x1A, 8, 10, 12, 14);
            case OUT_8 -> new LimiterLocation(0x1C, 16, 18, 20, 22);
            default -> throw new IllegalArgumentException("Output channel required: " + channel.displayName());
        };
    }

    static int readUInt16LE(byte[] data, int offset) {
        if (data == null || offset < 0 || data.length < offset + 2) {
            throw new IllegalArgumentException("Parameter block is too short for offset " + offset);
        }
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    record GainLocation(int blockIndex, int dataOffset) {
        int raw(byte[] data) {
            return readUInt16LE(data, dataOffset);
        }
    }

    record LimiterLocation(
            int blockIndex,
            int attackOffset,
            int releaseOffset,
            int kneeOffset,
            int thresholdOffset
    ) {
        LimiterRaw read(byte[] data) {
            return new LimiterRaw(
                    readUInt16LE(data, attackOffset),
                    readUInt16LE(data, releaseOffset),
                    readUInt16LE(data, kneeOffset),
                    readUInt16LE(data, thresholdOffset)
            );
        }
    }

    record LimiterRaw(int attack, int release, int knee, int threshold) {
        boolean matches(int expectedAttack, int expectedRelease, int expectedKnee, int expectedThreshold) {
            return attack == expectedAttack
                    && release == expectedRelease
                    && knee == expectedKnee
                    && threshold == expectedThreshold;
        }
    }
}
