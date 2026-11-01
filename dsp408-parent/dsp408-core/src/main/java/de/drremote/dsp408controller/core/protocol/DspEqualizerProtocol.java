package de.drremote.dsp408controller.core.protocol;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;

final class DspEqualizerProtocol {
    private static final byte CMD_SET_CROSSOVER_LOW_PASS = 0x31;
    private static final byte CMD_SET_CROSSOVER_HIGH_PASS = 0x32;
    private static final byte CMD_WRITE_PARAMETER = 0x33;
    private static final byte CMD_SET_INPUT_GEQ = 0x48;

    private DspEqualizerProtocol() {
    }

    static byte[] buildCrossoverHighPass(DspChannel channel,
                                         double frequencyHz,
                                         CrossoverSlope slope,
                                         boolean bypass) {
        return buildCrossover(CMD_SET_CROSSOVER_HIGH_PASS, channel, frequencyHz, slope, bypass);
    }

    static byte[] buildCrossoverLowPass(DspChannel channel,
                                        double frequencyHz,
                                        CrossoverSlope slope,
                                        boolean bypass) {
        return buildCrossover(CMD_SET_CROSSOVER_LOW_PASS, channel, frequencyHz, slope, bypass);
    }

    static byte[] buildOutputPeqFrequency(int outputNumber, int peqIndex, double hz) {
        return buildOutputPeq(outputNumber, peqIndex, 0.0, hz, 3.0, PeqFilterType.PEAK);
    }

    static byte[] buildOutputPeqQRaw(int outputNumber, int peqIndex, int qRaw) {
        if (qRaw < 0 || qRaw > 100) {
            throw new IllegalArgumentException("PEQ Q raw must be in range 0..100");
        }
        return buildOutputPeqRaw(
                outputNumber,
                peqIndex,
                DspCodecRegistry.peqGain().doubleToRaw(0.0),
                defaultPeqFrequencyRaw(peqIndex),
                qRaw,
                PeqFilterType.PEAK
        );
    }

    static byte[] buildOutputPeqGainCode(int outputNumber, int peqIndex, int gainCode) {
        if (gainCode < 0 || gainCode > 0x00F0) {
            throw new IllegalArgumentException("PEQ gain code must be in range 0x0000..0x00F0");
        }
        return buildOutputPeqRaw(
                outputNumber,
                peqIndex,
                gainCode,
                defaultPeqFrequencyRaw(peqIndex),
                DspCodecRegistry.peqQ().doubleToRaw(3.0),
                PeqFilterType.PEAK
        );
    }

    static byte[] buildOutputPeq(int outputNumber,
                                 int peqIndex,
                                 double gainDb,
                                 double frequencyHz,
                                 double q,
                                 PeqFilterType filterType) {
        return buildOutputPeq(outputNumber, peqIndex, gainDb, frequencyHz, q, filterType, false);
    }

    static byte[] buildOutputPeq(int outputNumber,
                                 int peqIndex,
                                 double gainDb,
                                 double frequencyHz,
                                 double q,
                                 PeqFilterType filterType,
                                 boolean bypass) {
        return buildOutputPeqRaw(
                outputNumber,
                peqIndex,
                DspCodecRegistry.peqGain().doubleToRaw(gainDb),
                DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz),
                DspCodecRegistry.peqQ().doubleToRaw(q),
                filterType,
                bypass
        );
    }

    static byte[] buildInputPeq(int inputChannelIndex,
                                int peqIndex,
                                double gainDb,
                                double frequencyHz,
                                double q,
                                PeqFilterType filterType,
                                boolean bypass) {
        return buildInputPeqRaw(
                inputChannelIndex,
                peqIndex,
                DspCodecRegistry.peqGain().doubleToRaw(gainDb),
                DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz),
                DspCodecRegistry.peqQ().doubleToRaw(q),
                filterType,
                bypass
        );
    }

    static byte[] buildInputPeqGain(int inputChannelIndex, int peqIndex, double gainDb) {
        return buildInputPeq(
                inputChannelIndex,
                peqIndex,
                gainDb,
                defaultInputPeqFrequency(peqIndex),
                3.0,
                PeqFilterType.PEAK,
                false
        );
    }

    static byte[] buildInputPeqFrequency(int inputChannelIndex, int peqIndex, double frequencyHz) {
        return buildInputPeq(
                inputChannelIndex,
                peqIndex,
                0.0,
                frequencyHz,
                3.0,
                PeqFilterType.PEAK,
                false
        );
    }

    static byte[] buildInputPeqQ(int inputChannelIndex, int peqIndex, double q) {
        return buildInputPeq(
                inputChannelIndex,
                peqIndex,
                0.0,
                defaultInputPeqFrequency(peqIndex),
                q,
                PeqFilterType.PEAK,
                false
        );
    }

    static byte[] buildInputPeqType(int inputChannelIndex,
                                    int peqIndex,
                                    PeqFilterType filterType) {
        return buildInputPeq(
                inputChannelIndex,
                peqIndex,
                0.0,
                defaultInputPeqFrequency(peqIndex),
                3.0,
                filterType,
                false
        );
    }

    static byte[] buildInputPeqBypass(int inputChannelIndex,
                                      int peqIndex,
                                      boolean bypass) {
        return buildInputPeq(
                inputChannelIndex,
                peqIndex,
                0.0,
                defaultInputPeqFrequency(peqIndex),
                3.0,
                PeqFilterType.PEAK,
                bypass
        );
    }

    static byte[] buildInputGeq(int inputChannelIndex, int bandIndex, double gainDb) {
        checkInputChannelIndex(inputChannelIndex);
        checkInputGeqBandIndex(bandIndex);

        int gainRaw = DspCodecRegistry.peqGain().doubleToRaw(gainDb);
        return new byte[]{
                0x00,
                0x01,
                0x05,
                CMD_SET_INPUT_GEQ,
                (byte) inputChannelIndex,
                (byte) (bandIndex - 1),
                (byte) (gainRaw & 0xFF),
                (byte) ((gainRaw >>> 8) & 0xFF)
        };
    }

    private static byte[] buildOutputPeqRaw(int outputNumber,
                                            int peqIndex,
                                            int gainRaw,
                                            int frequencyRaw,
                                            int qRaw,
                                            PeqFilterType filterType) {
        return buildOutputPeqRaw(outputNumber, peqIndex, gainRaw, frequencyRaw, qRaw, filterType, false);
    }

    private static byte[] buildOutputPeqRaw(int outputNumber,
                                            int peqIndex,
                                            int gainRaw,
                                            int frequencyRaw,
                                            int qRaw,
                                            PeqFilterType filterType,
                                            boolean bypass) {
        checkOutputNumber(outputNumber);
        checkPeqIndex(peqIndex);
        checkPeqQRaw(qRaw);

        return new byte[]{
                0x00,
                0x01,
                0x0A,
                CMD_WRITE_PARAMETER,
                (byte) (outputNumber + 3),
                (byte) (peqIndex - 1),
                (byte) (gainRaw & 0xFF),
                (byte) ((gainRaw >>> 8) & 0xFF),
                (byte) (frequencyRaw & 0xFF),
                (byte) ((frequencyRaw >>> 8) & 0xFF),
                (byte) (qRaw & 0xFF),
                (byte) filterType.rawValue(),
                (byte) (bypass ? 0x01 : 0x00)
        };
    }

    private static byte[] buildCrossover(byte command,
                                         DspChannel channel,
                                         double frequencyHz,
                                         CrossoverSlope slope,
                                         boolean bypass) {
        if (channel == null) {
            throw new IllegalArgumentException("Channel is required");
        }
        if (!bypass && slope == null) {
            throw new IllegalArgumentException("Crossover slope is required when bypass is off");
        }

        int frequencyRaw = DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz);
        int modeRaw = bypass ? 0x00 : slope.rawValue();

        return new byte[]{
                0x00,
                0x01,
                0x05,
                command,
                (byte) channel.index(),
                (byte) (frequencyRaw & 0xFF),
                (byte) ((frequencyRaw >>> 8) & 0xFF),
                (byte) modeRaw
        };
    }

    private static byte[] buildInputPeqRaw(int inputChannelIndex,
                                           int peqIndex,
                                           int gainRaw,
                                           int frequencyRaw,
                                           int qRaw,
                                           PeqFilterType filterType,
                                           boolean bypass) {
        checkInputChannelIndex(inputChannelIndex);
        checkInputPeqIndex(peqIndex);
        checkPeqQRaw(qRaw);

        return new byte[]{
                0x00,
                0x01,
                0x0A,
                CMD_WRITE_PARAMETER,
                (byte) inputChannelIndex,
                (byte) (peqIndex - 1),
                (byte) (gainRaw & 0xFF),
                (byte) ((gainRaw >>> 8) & 0xFF),
                (byte) (frequencyRaw & 0xFF),
                (byte) ((frequencyRaw >>> 8) & 0xFF),
                (byte) qRaw,
                (byte) filterType.rawValue(),
                (byte) (bypass ? 0x01 : 0x00)
        };
    }

    private static int defaultPeqFrequencyRaw(int peqIndex) {
        double hz = switch (peqIndex) {
            case 1 -> 40.3;
            case 2 -> 84.4;
            case 3 -> 176.8;
            case 4 -> 370.3;
            case 5 -> 757.9;
            case 6 -> 1590.0;
            case 7 -> 3320.0;
            case 8 -> 6810.0;
            case 9 -> 14250.0;
            default -> throw new IllegalArgumentException("PEQ index must be in range 1..9");
        };
        return DspCodecRegistry.peqFrequency().doubleToRaw(hz);
    }

    private static double defaultInputPeqFrequency(int peqIndex) {
        return switch (peqIndex) {
            case 1 -> 40.3;
            case 2 -> 84.4;
            case 3 -> 176.8;
            case 4 -> 370.3;
            case 5 -> 757.9;
            case 6 -> 1590.0;
            case 7 -> 3320.0;
            case 8 -> 6810.0;
            default -> throw new IllegalArgumentException("Input PEQ index must be in range 1..8");
        };
    }

    private static void checkInputChannelIndex(int inputChannelIndex) {
        if (inputChannelIndex < 0 || inputChannelIndex > 3) {
            throw new IllegalArgumentException("Input channel index must be in range 0..3");
        }
    }

    private static void checkOutputNumber(int outputNumber) {
        if (outputNumber < 1 || outputNumber > 8) {
            throw new IllegalArgumentException("Output number must be in range 1..8");
        }
    }

    private static void checkPeqIndex(int peqIndex) {
        if (peqIndex < 1 || peqIndex > 9) {
            throw new IllegalArgumentException("PEQ index must be in range 1..9");
        }
    }

    private static void checkInputPeqIndex(int peqIndex) {
        if (peqIndex < 1 || peqIndex > 8) {
            throw new IllegalArgumentException("Input PEQ index must be in range 1..8");
        }
    }

    private static void checkInputGeqBandIndex(int bandIndex) {
        if (bandIndex < 1 || bandIndex > 31) {
            throw new IllegalArgumentException("Input GEQ band index must be in range 1..31");
        }
    }

    private static void checkPeqQRaw(int qRaw) {
        if (qRaw < 0 || qRaw > 100) {
            throw new IllegalArgumentException("PEQ Q raw must be in range 0..100");
        }
    }
}
