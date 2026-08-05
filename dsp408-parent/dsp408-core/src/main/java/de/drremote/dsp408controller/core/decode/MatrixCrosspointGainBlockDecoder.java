package de.drremote.dsp408controller.core.decode;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;
import de.drremote.dsp408controller.core.library.DspLibrary;
import de.drremote.dsp408controller.core.library.MatrixCrosspointLocation;
import de.drremote.dsp408controller.core.state.DspState;

public final class MatrixCrosspointGainBlockDecoder implements BlockDecoder {
    private final DspState state;
    private final Consumer<String> log;
    private final DspLibrary library;

    public MatrixCrosspointGainBlockDecoder(DspState state, Consumer<String> log, DspLibrary library) {
        this.state = state;
        this.log = log;
        this.library = library;
    }

    @Override
    public void decode(int blockIndex, byte[] data) {
        if (data == null) {
            return;
        }

        List<MatrixCrosspointLocation> locations = library.matrixCrosspointLocationsForBlock(blockIndex);
        if (locations.isEmpty()) {
            return;
        }

        for (MatrixCrosspointLocation location : locations) {
            if (data.length < location.dataOffset() + 2) {
                continue;
            }

            int raw = readUInt16LE(data, location.dataOffset());
            double decoded = DspCodecRegistry.gain().rawToDouble(raw);
            double db = Math.max(-60.0, Math.min(0.0, decoded));

            state.markMatrixCrosspointGainFromDevice(
                    location.output(),
                    location.input(),
                    db,
                    String.format(
                            Locale.ROOT,
                            "block:%02X@%02X raw=%d",
                            blockIndex,
                            location.dataOffset(),
                            raw
                    )
            );

            if (log != null) {
                log.accept(String.format(
                        Locale.ROOT,
                        "MATRIX GAIN RX %s <- %s -> raw=%d -> %.2f dB (block %02X offset %02X)",
                        location.output().displayName(),
                        location.input().displayName(),
                        raw,
                        db,
                        blockIndex,
                        location.dataOffset()
                ));
            }
        }
    }

    private static int readUInt16LE(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
}
