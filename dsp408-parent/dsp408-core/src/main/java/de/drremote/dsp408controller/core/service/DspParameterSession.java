package de.drremote.dsp408controller.core.service;

import java.io.IOException;
import java.util.concurrent.CancellationException;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;
import de.drremote.dsp408controller.core.library.DspFieldLocation;
import de.drremote.dsp408controller.core.library.MuteReadSpec;
import de.drremote.dsp408controller.core.net.DspClient;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.DspProtocol;
import de.drremote.dsp408controller.core.state.DspState;

/** Binds operations to one client, library and identity. Lifecycle guards never enclose I/O. */
public final class DspParameterSession {
    @FunctionalInterface
    interface CurrentGuard { boolean run(Runnable action); }

    private final DspClient client;
    private final DspParameterCatalog catalog;
    private final DspState state;
    private final CurrentGuard guard;

    DspParameterSession(DspClient client, DspParameterCatalog catalog, DspState state, CurrentGuard guard) {
        this.client = client;
        this.catalog = catalog;
        this.state = state;
        this.guard = guard;
    }

    public DspParameterCatalog catalog() { return catalog; }

    public boolean isCurrent() { return guard.run(() -> { }); }

    /** A normal return means an actual ACK. A later identity change only suppresses state publication. */
    public void write(DspChannel channel, DspParameter parameter, Object value) throws IOException {
        write(channel, parameter, value, false);
    }

    public void writeInteractive(DspChannel channel, DspParameter parameter, Object value) throws IOException {
        write(channel, parameter, value, true);
    }

    private void write(DspChannel channel, DspParameter parameter, Object value, boolean interactive) throws IOException {
        Object normalized = catalog.normalize(parameter, value);
        if (!catalog.capability(channel, parameter).writable()) {
            throw new UnsupportedDspOperationException("Parameter is not writable for this captured firmware profile");
        }
        requireCurrent();
        try {
            switch (parameter) {
                case GAIN -> send(DspProtocol.buildGain(channel.index(), (Double) normalized), interactive);
                case MUTE -> send(DspProtocol.buildMute(channel.index(), (Boolean) normalized), interactive);
                case PHASE -> send(DspProtocol.buildPhase(channel.index(), (Boolean) normalized), interactive);
                case DELAY -> send(DspProtocol.buildDelay(channel.index(), (Double) normalized), interactive);
            }
        } catch (CancellationException superseded) {
            throw superseded;
        } catch (IllegalStateException error) {
            throw new IOException("Captured DSP connection is no longer available", error);
        }
        guard.run(() -> publishWrite(channel, parameter, normalized));
    }

    public DspParameterReading readBack(DspChannel channel, DspParameter parameter) throws IOException {
        return readBack(channel, parameter, false);
    }

    public DspParameterReading readBackInteractive(DspChannel channel, DspParameter parameter) throws IOException {
        return readBack(channel, parameter, true);
    }

    private DspParameterReading readBack(DspChannel channel, DspParameter parameter, boolean interactive) throws IOException {
        if (!catalog.capability(channel, parameter).readbackSupported()) {
            throw new UnsupportedDspOperationException("Readback mapping is not confirmed for this captured firmware and channel");
        }
        DspFieldLocation location = parameter == DspParameter.MUTE ? null : catalog.readLocation(channel, parameter);
        MuteReadSpec mute = catalog.library().muteReadSpec();
        int block = parameter == DspParameter.MUTE ? mute.blockIndex() : location.blockIndex();
        long[] before = new long[1];
        if (!guard.run(() -> before[0] = state.blockRevision(block))) throw stale();
        try {
            if (interactive) {
                if (!client.sendPayloadInteractive(DspProtocol.readParameterBlock(block))) {
                    throw new IOException("DSP readback was cancelled before transmission");
                }
            } else {
                client.readParameterBlock(block);
            }
        } catch (IllegalStateException error) {
            throw new IOException("Captured DSP connection is no longer available", error);
        }
        DspState.ReceivedBlock[] received = new DspState.ReceivedBlock[1];
        if (!guard.run(() -> received[0] = state.receivedBlock(block))) throw stale();
        DspState.ReceivedBlock snapshot = received[0];
        if (snapshot == null || snapshot.receiveRevision() <= before[0]) {
            throw new IOException("Readback did not produce a fresh receive revision");
        }
        Object value = decode(channel, parameter, location, mute, snapshot.data());
        return new DspParameterReading(value, block, snapshot.receiveRevision(), snapshot.observedAtEpochMs());
    }

    private Object decode(DspChannel channel, DspParameter parameter, DspFieldLocation location,
            MuteReadSpec mute, byte[] data) throws IOException {
        if (parameter == DspParameter.MUTE) {
            boolean input = channel.index() < 4;
            int offset = input ? mute.inputDataOffset() : mute.outputDataOffset();
            requireLength(data, offset, 1);
            return (data[offset] & (1 << (input ? channel.index() : channel.index() - 4))) != 0;
        }
        int offset = location.dataOffset();
        requireLength(data, offset, 2);
        int raw = (data[offset] & 255) | ((data[offset + 1] & 255) << 8);
        return switch (parameter) {
            case GAIN -> {
                if (raw > 400) throw new IOException("Gain readback is outside the known raw range");
                yield DspCodecRegistry.gain().rawToDouble(raw);
            }
            case DELAY -> {
                if (raw > 65280) throw new IOException("Delay readback is outside the known raw range");
                yield DspCodecRegistry.delayMs().rawToDouble(raw);
            }
            case PHASE -> {
                if (raw > 1) throw new IOException("Phase readback is outside the known raw states");
                yield raw == 1;
            }
            case MUTE -> throw new AssertionError("Mute uses a mask");
        };
    }

    private void send(byte[] payload, boolean interactive) throws IOException {
        boolean acknowledged = interactive ? client.sendPayloadInteractive(payload) : client.sendPayload(payload);
        if (!acknowledged) throw new CancellationException("DSP write was superseded before transmission");
    }

    private void publishWrite(DspChannel channel, DspParameter parameter, Object value) {
        switch (parameter) {
            case GAIN -> state.markGain(channel, (Double) value, "tx");
            case MUTE -> {
                state.markMuted(channel, (Boolean) value, "tx");
                MuteReadSpec spec = catalog.library().muteReadSpec();
                if (spec != null) {
                    boolean input = channel.index() < 4;
                    int bit = input ? channel.index() : channel.index() - 4;
                    state.patchCachedBlockBit(spec.blockIndex(),
                            input ? spec.inputDataOffset() : spec.outputDataOffset(), 1 << bit, (Boolean) value);
                }
            }
            case PHASE -> state.markPhase(channel, (Boolean) value, "tx");
            case DELAY -> state.markDelay(channel, (Double) value, "tx");
        }
    }

    private void requireCurrent() throws IOException {
        if (!isCurrent()) throw stale();
    }

    private static IOException stale() { return new IOException("DSP parameter session is no longer current"); }

    private static void requireLength(byte[] data, int offset, int width) throws IOException {
        if (data == null || offset < 0 || data.length < offset + width) {
            throw new IOException("Fresh parameter block is too short for the confirmed readback mapping");
        }
    }
}
