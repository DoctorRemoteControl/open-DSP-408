package de.drremote.dsp408controller.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.EOFException;
import java.io.OutputStream;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408.api.ChannelDto;
import de.drremote.dsp408controller.core.net.DspConnectionConfig;
import de.drremote.dsp408controller.core.net.DspFrameCodec;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.service.DspController;

class DspTransactionCoordinatorTest {
    @Test
    void muteUsesAckAndLocalStateWithoutParameterBlockReadback() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> fakeDsp = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10_000);
                    PushbackInputStream input = new PushbackInputStream(socket.getInputStream(), 1);
                    OutputStream output = socket.getOutputStream();
                    answerFirStartup(input, output);

                    DspFrameCodec.DecodedFrame mute = read(input);
                    assertEquals(0x35, command(mute));
                    write(output, new byte[]{0x01, 0x00, 0x01, 0x01});

                    socket.setSoTimeout(300);
                    assertThrows(SocketTimeoutException.class, () -> read(input));
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });

            DspController controller = new DspController();
            try {
                controller.setPreferredLibraryType("fir408");
                controller.connect(config(server), ignored -> { });
                DspTransactionCoordinator coordinator = new DspTransactionCoordinator(
                        controller,
                        () -> { }
                );
                DspSettingsCache settingsCache = new DspSettingsCache(
                        controller,
                        coordinator,
                        () -> { }
                );
                DspLevelCommands levels = new DspLevelCommands(
                        controller,
                        new DspRuntimeDtoMapper(controller),
                        coordinator,
                        settingsCache,
                        ignored -> { }
                );

                ChannelDto result = levels.setMute(DspChannel.OUT_8.displayName(), true);

                assertTrue(result.muted());
                assertFalse(result.muteConfirmedFromDevice());
                assertTrue(result.muteDirty());
                fakeDsp.get(2, TimeUnit.SECONDS);
            } finally {
                controller.close();
            }
        }
    }

    @Test
    void muteWithoutAckKeepsTheServerStateAndClosesWithoutRetry() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> fakeDsp = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(10_000);
                    PushbackInputStream input = new PushbackInputStream(socket.getInputStream(), 1);
                    OutputStream output = socket.getOutputStream();
                    answerFirStartup(input, output);

                    assertEquals(0x35, command(read(input)));
                    assertThrows(EOFException.class, () -> DspFrameCodec.readFrame(input),
                            "Missing ACK must close without replaying mute");
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            });

            DspController controller = new DspController();
            try {
                controller.setPreferredLibraryType("fir408");
                controller.connect(config(server), ignored -> { });
                controller.state().markMuted(DspChannel.OUT_8, false, "test");
                DspTransactionCoordinator coordinator = new DspTransactionCoordinator(
                        controller,
                        () -> { }
                );
                DspSettingsCache settingsCache = new DspSettingsCache(
                        controller,
                        coordinator,
                        () -> { }
                );
                DspLevelCommands levels = new DspLevelCommands(
                        controller,
                        new DspRuntimeDtoMapper(controller),
                        coordinator,
                        settingsCache,
                        ignored -> { }
                );

                assertThrows(IOException.class,
                        () -> levels.setMute(DspChannel.OUT_8.displayName(), true));
                assertFalse(controller.state().channel(DspChannel.OUT_8).muted());
                assertFalse(controller.isConnected());
                fakeDsp.get(2, TimeUnit.SECONDS);
            } finally {
                controller.close();
            }
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

    private static void answerFirStartup(PushbackInputStream input, OutputStream output) throws Exception {
        write(output, new byte[]{0x01, 0x00, 0x05, (byte) 0xB8, (byte) 192, (byte) 168, 1, (byte) 166});
        write(output, new byte[]{0x01, 0x00, 0x01, (byte) 0xBE});
        for (int expected : new int[]{0x10, 0x13, 0x2C, 0x22, 0x14}) {
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
}
