package de.drremote.dsp408controller.core.decode;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;
import de.drremote.dsp408controller.core.library.DspLibrary;
import de.drremote.dsp408controller.core.library.LimiterChannelLocation;
import de.drremote.dsp408controller.core.state.DspState;

public final class LimiterBlockDecoder implements BlockDecoder {
    private final DspState state;
    private final Consumer<String> log;
    private final DspLibrary library;

    public LimiterBlockDecoder(DspState state, Consumer<String> log, DspLibrary library) {
        this.state = state;
        this.log = log;
        this.library = library;
    }

    @Override
    public void decode(int blockIndex, byte[] data) {
        if (data == null) {
            return;
        }

        List<LimiterChannelLocation> locations = library.limiterLocationsForBlock(blockIndex);
        if (locations.isEmpty()) {
            return;
        }

        for (LimiterChannelLocation location : locations) {
            Integer attackRaw = readUInt16LE(
                    state.getBlock(location.attack().blockIndex()),
                    location.attack().dataOffset()
            );
            Integer releaseRaw = readUInt16LE(
                    state.getBlock(location.release().blockIndex()),
                    location.release().dataOffset()
            );
            Integer kneeRaw = readUInt16LE(
                    state.getBlock(location.knee().blockIndex()),
                    location.knee().dataOffset()
            );
            Integer thresholdRaw = readUInt16LE(
                    state.getBlock(location.threshold().blockIndex()),
                    location.threshold().dataOffset()
            );

            if (attackRaw == null || releaseRaw == null || kneeRaw == null || thresholdRaw == null) {
                continue;
            }

            double attackMs = DspCodecRegistry.rawToDynamicsTimeMs(attackRaw);
            double releaseMs = DspCodecRegistry.rawToDynamicsTimeMs(releaseRaw);
            double thresholdDb = DspCodecRegistry.rawToDynamicsThresholdDb(thresholdRaw);

            String source = String.format(
                    Locale.ROOT,
                    "limit blocks:%02X@%02X/%02X@%02X/%02X@%02X/%02X@%02X raw=%d/%d/%d/%d",
                    location.attack().blockIndex(),
                    location.attack().dataOffset(),
                    location.release().blockIndex(),
                    location.release().dataOffset(),
                    location.knee().blockIndex(),
                    location.knee().dataOffset(),
                    location.threshold().blockIndex(),
                    location.threshold().dataOffset(),
                    attackRaw,
                    releaseRaw,
                    kneeRaw,
                    thresholdRaw
            );

            state.markLimiterFromDevice(
                    location.channel(),
                    attackMs,
                    releaseMs,
                    kneeRaw,
                    thresholdDb,
                    source
            );

            if (log != null) {
                log.accept(String.format(
                        Locale.ROOT,
                        "LIMIT RX %s -> attack=%.0f ms release=%.0f ms threshold=%.1f dB knee=%.0f dB",
                        location.channel().displayName(),
                        attackMs,
                        releaseMs,
                        thresholdDb,
                        (double) kneeRaw
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
}
