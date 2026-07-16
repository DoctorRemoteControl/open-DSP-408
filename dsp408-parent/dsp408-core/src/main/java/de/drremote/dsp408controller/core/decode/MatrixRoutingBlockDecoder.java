package de.drremote.dsp408controller.core.decode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import de.drremote.dsp408controller.core.library.DspLibrary;
import de.drremote.dsp408controller.core.library.MatrixRouteLocation;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.state.DspState;

public final class MatrixRoutingBlockDecoder implements BlockDecoder {
    private final DspState state;
    private final Consumer<String> log;
    private final DspLibrary library;

    public MatrixRoutingBlockDecoder(DspState state, Consumer<String> log, DspLibrary library) {
        this.state = state;
        this.log = log;
        this.library = library;
    }

    @Override
    public void decode(int blockIndex, byte[] data) {
        if (data == null) {
            return;
        }

        for (DspChannel output : DspChannel.values()) {
            if (output.index() < 4) {
                continue;
            }

            MatrixRouteLocation location = library.matrixRouteLocation(output);
            if (location == null || location.blockIndex() != blockIndex) {
                continue;
            }

            if (data.length <= location.dataOffset()) {
                continue;
            }

            int raw = data[location.dataOffset()] & 0xFF;
            List<DspChannel> inputs = decodeMask(raw);
            if (inputs == null) {
                if (log != null) {
                    log.accept(String.format(
                            Locale.ROOT,
                            "MATRIX ROUTE RX %s -> unknown raw mask 0x%02X (block %02X offset %02X)",
                            output.displayName(),
                            raw,
                            blockIndex,
                            location.dataOffset()
                    ));
                }
                continue;
            }

            state.markMatrixRoutes(output, inputs);

            if (log != null) {
                log.accept(String.format(
                        Locale.ROOT,
                        "MATRIX ROUTE RX %s <- %s (block %02X offset %02X raw=0x%02X)",
                        output.displayName(),
                        inputs.isEmpty()
                                ? "-"
                                : inputs.stream().map(DspChannel::displayName).reduce((a, b) -> a + "+" + b).orElse("-"),
                        blockIndex,
                        location.dataOffset(),
                        raw
                ));
            }
        }
    }

    private static List<DspChannel> decodeMask(int raw) {
        if ((raw & ~0x0F) != 0) {
            return null;
        }
        List<DspChannel> inputs = new ArrayList<>();
        if ((raw & 0x01) != 0) {
            inputs.add(DspChannel.IN_A);
        }
        if ((raw & 0x02) != 0) {
            inputs.add(DspChannel.IN_B);
        }
        if ((raw & 0x04) != 0) {
            inputs.add(DspChannel.IN_C);
        }
        if ((raw & 0x08) != 0) {
            inputs.add(DspChannel.IN_D);
        }
        return List.copyOf(inputs);
    }
}
