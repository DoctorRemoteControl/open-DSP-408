package de.drremote.dsp408controller.core.service;

import java.io.IOException;
import java.util.function.Supplier;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;
import de.drremote.dsp408controller.core.library.DspWriteCapabilities.Operation;
import de.drremote.dsp408controller.core.net.DspClient;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.DspProtocol;
import de.drremote.dsp408controller.core.state.DspState;

/** Owns basic channel gain, mute, phase, delay and naming writes. */
final class DspChannelCommands {
	private final DspState state;
	private final Supplier<DspClient> clientSupplier;
	private final DspWriteVerifier writeVerifier;

	DspChannelCommands(
			DspState state,
			Supplier<DspClient> clientSupplier,
			DspWriteVerifier writeVerifier
	) {
		this.state = state;
		this.clientSupplier = clientSupplier;
		this.writeVerifier = writeVerifier;
	}

	void setGain(DspChannel channel, double db) throws IOException {
		double clamped = DspProtocol.clampDb(db);
		client().setGain(channel, clamped);
		state.markGain(channel, clamped, "tx");
	}

	void mute(DspChannel channel) throws IOException {
		client().mute(channel);
		state.markMuted(channel, true, "tx");
	}

	void unmute(DspChannel channel) throws IOException {
		client().unmute(channel);
		state.markMuted(channel, false, "tx");
	}

	void setPhase(DspChannel channel, boolean inverted) throws IOException {
		writeVerifier.require(Operation.PHASE, channel, "Phase");
		client().setPhase(channel, inverted);
		state.markPhase(channel, inverted, "tx");
	}

	void setDelay(DspChannel channel, double ms) throws IOException {
		double clamped = DspCodecRegistry.clampDelayMs(ms);
		client().setDelay(channel, clamped);
		state.markDelay(channel, clamped, "tx");
	}

	void setDelayUnit(int unitRaw) throws IOException {
		client().setDelayUnit(unitRaw);
	}

	void setDelayUnit(String unit) throws IOException {
		client().setDelayUnit(unit);
	}

	void setChannelName(DspChannel channel, String name) throws IOException {
		requireAnyChannel(channel);
		client().setChannelName(channel, name);
	}

	void setMute(DspChannel channel, boolean muted) throws IOException {
		requireAnyChannel(channel);
		client().setMute(channel, muted);
		state.markMuted(channel, muted, "tx");
	}

	private DspClient client() {
		return clientSupplier.get();
	}

	private static void requireAnyChannel(DspChannel channel) {
		if (channel == null || channel.index() < 0 || channel.index() > 11) {
			throw new IllegalArgumentException("Channel required: InA..InD or Out1..Out8");
		}
	}
}
