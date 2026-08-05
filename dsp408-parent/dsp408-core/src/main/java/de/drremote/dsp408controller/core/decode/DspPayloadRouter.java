package de.drremote.dsp408controller.core.decode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import de.drremote.dsp408controller.core.library.DspLibrary;
import de.drremote.dsp408controller.core.protocol.DspProtocol;
import de.drremote.dsp408controller.core.state.DspState;
import de.drremote.dsp408controller.util.Hex;

public final class DspPayloadRouter {
    private final DspState state;
    private final Consumer<String> log;
    private final Consumer<String> deviceVersionConsumer;
    private final Consumer<byte[]> systemInfoConsumer;

    private final List<BlockDecoder> blockDecoders;
    private final MeterPayloadDecoder meterPayloadDecoder;

    public DspPayloadRouter(DspState state,
                            Consumer<String> log,
                            Consumer<String> deviceVersionConsumer,
                            Consumer<byte[]> systemInfoConsumer,
                            DspLibrary library) {
        this.state = state;
        this.log = log;
        this.deviceVersionConsumer = deviceVersionConsumer;
        this.systemInfoConsumer = systemInfoConsumer;

        this.blockDecoders = List.of(
                new GainBlockDecoder(state, log, library),
                new MuteBlockDecoder(state, log, library),
                new PhaseBlockDecoder(state, log, library),
                new DelayBlockDecoder(state, log, library),
                new CrossoverBlockDecoder(state, log, library),
                new InputGateBlockDecoder(state, log, library),
                new InputPeqBlockDecoder(state, log, library),
                new InputGeqBlockDecoder(state, log, library),
                new OutputPeqBlockDecoder(state, log, library),
                new MatrixRoutingBlockDecoder(state, log, library),
                new MatrixCrosspointGainBlockDecoder(state, log, library),
                new CompressorBlockDecoder(state, log, library),
                new LimiterBlockDecoder(state, log, library),
                new TestToneBlockDecoder(state, log, library)
        );

        this.meterPayloadDecoder = new MeterPayloadDecoder(state, log, library);
    }

    public void onPayload(byte[] payload) {
        if (DspProtocol.isAck(payload)) {
            return;
        }

        if (DspProtocol.isHandshakeInitReply(payload)) {
            if (log != null) {
                log.accept("Handshake init confirmed by DSP");
            }
            return;
        }

        if (DspProtocol.isHandshakeAckReply(payload)) {
            String deviceVersion = extractAsciiBody(payload);
            if (deviceVersion != null && !deviceVersion.isBlank()) {
                if (deviceVersionConsumer != null) {
                    deviceVersionConsumer.accept(deviceVersion);
                }
                if (log != null) {
                    log.accept("Device version detected: " + deviceVersion);
                }
            }
            return;
        }

        if (DspProtocol.isSystemInfoReply(payload)) {
            byte[] copy = Arrays.copyOf(payload, payload.length);
            if (systemInfoConsumer != null) {
                systemInfoConsumer.accept(copy);
            }
            if (log != null) {
                log.accept("System info received: " + Hex.format(payload));
            }
            return;
        }

        if (DspProtocol.isRuntimeMeterResponse(payload)) {
            meterPayloadDecoder.decode(payload);
            return;
        }

        if (DspProtocol.isPresetNameResponse(payload)) {
            int presetIndex = DspProtocol.presetNameResponseIndex(payload);
            String presetName = DspProtocol.presetNameResponseName(payload);
            state.storePresetName(presetIndex, presetName);
            if (log != null) {
                log.accept("PRESET NAME RX index=" + presetIndex + " name=" + presetName);
            }
            return;
        }

        if (DspProtocol.isBlockResponse(payload)) {
            int blockIndex = DspProtocol.blockResponseIndex(payload);
            byte[] data = DspProtocol.blockResponseData(payload);

            state.storeBlock(blockIndex, data);

            for (BlockDecoder decoder : blockDecoders) {
                try {
                    decoder.decode(blockIndex, data);
                } catch (Exception e) {
                    if (log != null) {
                        log.accept("Decoder error in " + decoder.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }
            }

            if (log != null) {
                log.accept("BLOCK RX index=" + Hex.byteToHex(blockIndex)
                        + " len=" + data.length
                        + " data=" + Hex.format(data));
            }
            return;
        }

        if (log != null) {
            log.accept("UNKNOWN RX PAYLOAD: " + Hex.format(payload));
        }
    }

    private static String extractAsciiBody(byte[] payload) {
        if (payload == null || payload.length <= 4) {
            return null;
        }

        byte[] body = Arrays.copyOfRange(payload, 4, payload.length);
        String text = new String(body, StandardCharsets.US_ASCII).trim();
        return text.isEmpty() ? null : text;
    }
}
