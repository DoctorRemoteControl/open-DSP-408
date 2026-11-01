package de.drremote.dsp408controller.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.net.DspConnectionConfig;
import de.drremote.dsp408controller.core.net.DspFrameCodec;
import de.drremote.dsp408controller.core.protocol.DspChannel;

class DspControllerTransportTimingTest {
    private static final Path FIR_LIBRARY =
            Path.of("src", "main", "resources", "dsp", "DspLib-408-fir.json");

    @Test
    void acknowledgedFirLimiterWriteIsTransmittedExactlyOnce() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> fakeDsp = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2_000);
                    PushbackInputStream input = new PushbackInputStream(socket.getInputStream(), 1);
                    OutputStream output = socket.getOutputStream();
                    answerFirStartup(input, output);

                    assertEquals(0x3F, command(read(input)));
                    write(output, new byte[]{0x01, 0x00, 0x01, 0x01});

                    socket.setSoTimeout(900);
                    assertThrows(SocketTimeoutException.class, () -> read(input));
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });

            DspController controller = new DspController(FIR_LIBRARY);
            try {
                controller.setPreferredLibraryType("fir408");
                controller.connect(config(server), ignored -> { });
                controller.setLimiter(DspChannel.OUT_1, -20.0, 10.0, 1_000.0);
                fakeDsp.get(2, TimeUnit.SECONDS);
            } finally {
                controller.close();
            }
        }
    }

    @Test
    void blockReadsFollowCorrelatedResponsesWithoutFixedQuietDelay() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            AtomicLong handshakeToFirstBlockMs = new AtomicLong();
            AtomicLong firstToSecondBlockMs = new AtomicLong();

            CompletableFuture<Void> fakeDsp = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2_000);
                    PushbackInputStream input = new PushbackInputStream(socket.getInputStream(), 1);
                    OutputStream output = socket.getOutputStream();
                    long startupReplyAt = answerFirStartup(input, output);

                    DspFrameCodec.DecodedFrame first = read(input);
                    handshakeToFirstBlockMs.set(elapsedMillis(startupReplyAt));
                    assertBlockRequest(first, 0);
                    write(output, blockReply(0));
                    long firstReplyAt = System.nanoTime();

                    DspFrameCodec.DecodedFrame second = read(input);
                    firstToSecondBlockMs.set(elapsedMillis(firstReplyAt));
                    assertBlockRequest(second, 1);
                    write(output, blockReply(1));
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });

            DspController controller = new DspController(FIR_LIBRARY);
            try {
                controller.setPreferredLibraryType("fir408");
                controller.connect(config(server), ignored -> { });
                controller.scanParameterBlocks(List.of(0, 1));
                fakeDsp.get(2, TimeUnit.SECONDS);
            } finally {
                controller.close();
            }

            assertTrue(handshakeToFirstBlockMs.get() < 225,
                    "First block request still has a synthetic post-handshake delay");
            assertTrue(firstToSecondBlockMs.get() < 225,
                    "Second block request still has a synthetic post-response delay");
        }
    }

    private static DspConnectionConfig config(ServerSocket server) {
        return new DspConnectionConfig(
                "127.0.0.1",
                server.getLocalPort(),
                1_000,
                100,
                60_000,
                60_000
        );
    }

    private static long answerFirStartup(PushbackInputStream input, OutputStream output) throws Exception {
        write(output, new byte[]{0x01, 0x00, 0x05, (byte) 0xB8, (byte) 192, (byte) 168, 1, (byte) 166});
        write(output, new byte[]{0x01, 0x00, 0x01, (byte) 0xBE});
        for (int expected : new int[]{0x10, 0x13, 0x2C}) {
            assertEquals(expected, command(read(input)));
            write(output, new byte[]{0x01, 0x00, 0x01, (byte) expected});
        }

        for (int expected : new int[]{0x22, 0x14}) {
            assertEquals(expected, command(read(input)));
            write(output, new byte[]{0x01, 0x00, 0x01, (byte) expected});
        }

        for (int presetIndex = 0; presetIndex <= 0x12; presetIndex++) {
            DspFrameCodec.DecodedFrame request = read(input);
            assertEquals(0x29, command(request));
            assertEquals(presetIndex, request.payload()[4] & 0xFF);
            write(output, new byte[]{0x01, 0x00, 0x02, 0x29, (byte) presetIndex});
        }

        int[] firOffsets = {0, 157, 315, 472, 630, 708, 787, 866, 945, 1024, 1102, 1181};
        for (int expectedOffset : firOffsets) {
            DspFrameCodec.DecodedFrame request = read(input);
            assertEquals(0x56, command(request));
            int actualOffset = (request.payload()[5] & 0xFF) | ((request.payload()[6] & 0xFF) << 8);
            assertEquals(expectedOffset, actualOffset);
            write(output, new byte[]{0x01, 0x00, 0x01, 0x56});
        }
        return System.nanoTime();
    }

    private static void assertBlockRequest(DspFrameCodec.DecodedFrame frame, int selector) {
        assertEquals(0x27, command(frame));
        assertEquals(selector, frame.payload()[4] & 0xFF);
    }

    private static byte[] blockReply(int selector) {
        return new byte[]{0x01, 0x00, 0x02, 0x24, (byte) selector};
    }

    private static DspFrameCodec.DecodedFrame read(PushbackInputStream input) throws Exception {
        DspFrameCodec.DecodedFrame frame = DspFrameCodec.readFrame(input);
        if (frame == null) {
            throw new IllegalStateException("DSP test peer received no frame");
        }
        return frame;
    }

    private static int command(DspFrameCodec.DecodedFrame frame) {
        return frame.payload()[3] & 0xFF;
    }

    private static void write(OutputStream output, byte[] payload) throws Exception {
        output.write(DspFrameCodec.encode(payload));
        output.flush();
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
