package de.drremote.dsp408controller.core.decode;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;
import de.drremote.dsp408controller.core.library.CompressorChannelLocation;
import de.drremote.dsp408controller.core.library.DspLibrary;
import de.drremote.dsp408controller.core.state.DspState;

public final class CompressorBlockDecoder implements BlockDecoder {
    private final DspState state;
    private final Consumer<String> log;
    private final DspLibrary library;

    public CompressorBlockDecoder(DspState state, Consumer<String> log, DspLibrary library) {
        this.state = state;
        this.log = log;
        this.library = library;
    }

    @Override
    public void decode(int blockIndex, byte[] data) {
        if (data == null) {
            return;
        }

        List<CompressorChannelLocation> locations = library.compressorLocationsForBlock(blockIndex);
        if (locations.isEmpty()) {
            return;
        }

        for (CompressorChannelLocation location : locations) {
            Integer ratioRaw = readUInt8(
                    state.getBlock(location.ratio().blockIndex()),
                    location.ratio().dataOffset()
            );
            Integer attackRaw = readUInt16LE(
                    state.getBlock(location.attack().blockIndex()),
                    location.attack().dataOffset()
            );
            Integer releaseRaw = readUInt16LE(
                    state.getBlock(location.release().blockIndex()),
                    location.release().dataOffset()
            );
            Integer kneeRaw = readUInt8(
                    state.getBlock(location.knee().blockIndex()),
                    location.knee().dataOffset()
            );
            Integer thresholdRaw = readUInt16LE(
                    state.getBlock(location.threshold().blockIndex()),
                    location.threshold().dataOffset()
            );
            Integer frequencyRaw = readUInt16LE(
                    state.getBlock(location.frequency().blockIndex()),
                    location.frequency().dataOffset()
            );
            Integer typeContextRaw = readUInt8(
                    state.getBlock(location.typeContext().blockIndex()),
                    location.typeContext().dataOffset()
            );
            Integer typeRaw = readUInt8(
                    state.getBlock(location.type().blockIndex()),
                    location.type().dataOffset()
            );

            if (ratioRaw == null || attackRaw == null || releaseRaw == null || kneeRaw == null
                    || thresholdRaw == null || frequencyRaw == null || typeContextRaw == null || typeRaw == null) {
                continue;
            }

            String ratioLabel = library.compressorRatioName(ratioRaw);
            if (ratioLabel == null) {
                ratioLabel = "raw=" + ratioRaw;
            }

            double attackMs = DspCodecRegistry.rawToDynamicsTimeMs(attackRaw);
            double releaseMs = DspCodecRegistry.rawToDynamicsTimeMs(releaseRaw);
            double kneeDb = kneeRaw;
            double thresholdDb = DspCodecRegistry.rawToDynamicsThresholdDb(thresholdRaw);
            double frequencyHz = DspCodecRegistry.peqFrequency().rawToDouble(frequencyRaw);
            String typeLabel = library.inputCompressorTypeName(typeRaw);
            if (typeLabel == null) {
                typeLabel = "raw=" + typeRaw;
            }

            String source = String.format(
                    Locale.ROOT,
                    "input-comp blocks:%02X@%02X/%02X@%02X/%02X@%02X/%02X@%02X/%02X@%02X/%02X@%02X/%02X@%02X/%02X@%02X raw=%d/%d/%d/%d/%d/%d/%d/%d",
                    location.ratio().blockIndex(),
                    location.ratio().dataOffset(),
                    location.attack().blockIndex(),
                    location.attack().dataOffset(),
                    location.release().blockIndex(),
                    location.release().dataOffset(),
                    location.knee().blockIndex(),
                    location.knee().dataOffset(),
                    location.threshold().blockIndex(),
                    location.threshold().dataOffset(),
                    location.frequency().blockIndex(),
                    location.frequency().dataOffset(),
                    location.typeContext().blockIndex(),
                    location.typeContext().dataOffset(),
                    location.type().blockIndex(),
                    location.type().dataOffset(),
                    ratioRaw,
                    attackRaw,
                    releaseRaw,
                    kneeRaw,
                    thresholdRaw,
                    frequencyRaw,
                    typeContextRaw,
                    typeRaw
            );

            state.markCompressorFromDevice(
                    location.channel(),
                    ratioRaw,
                    ratioLabel,
                    attackMs,
                    releaseMs,
                    kneeDb,
                    thresholdDb,
                    frequencyHz,
                    typeContextRaw,
                    typeRaw,
                    typeLabel,
                    source
            );

            if (log != null) {
                log.accept(String.format(
                        Locale.ROOT,
                        "INPUT COMP RX %s -> ratio=%s attack=%.0f ms release=%.0f ms knee=%.1f dB threshold=%.1f dB freq=%.2f Hz type=%s",
                        location.channel().displayName(),
                        ratioLabel,
                        attackMs,
                        releaseMs,
                        kneeDb,
                        thresholdDb,
                        frequencyHz,
                        typeLabel
                ));
            }
        }
    }

    private static Integer readUInt16LE(byte[] data, int offset) {
        if (data == null || offset < 0 || data.length < offset + 2) {
            return null;
        }
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static Integer readUInt8(byte[] data, int offset) {
        if (data == null || offset < 0 || data.length <= offset) {
            return null;
        }
        return data[offset] & 0xFF;
    }
}
