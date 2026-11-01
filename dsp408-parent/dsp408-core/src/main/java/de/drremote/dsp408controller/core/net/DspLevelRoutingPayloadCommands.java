package de.drremote.dsp408controller.core.net;

import java.io.IOException;
import java.util.Collection;

import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.DspProtocol;

/** Owns basic channel, gain, mute and matrix payloads. */
final class DspLevelRoutingPayloadCommands {
	private final DspPayloadTransport transport;

	DspLevelRoutingPayloadCommands(DspPayloadTransport transport) {
		this.transport = transport;
	}

	void setDelayUnit(int unitRaw) throws IOException {
		send(DspProtocol.buildDelayUnit(unitRaw));
	}

	void setDelayUnit(String unit) throws IOException {
		send(DspProtocol.buildDelayUnit(unit));
	}

	void setChannelName(DspChannel channel, String name) throws IOException {
		send(DspProtocol.buildChannelName(channel, name));
	}

	void setGain(int channelIndex, double db) throws IOException {
		transport.sendReliable(DspProtocol.buildGain(channelIndex, db));
	}

	void setMute(int selector, boolean muted) throws IOException {
		transport.sendReliable(DspProtocol.buildMute(selector, muted));
	}

	void setPhase(int channelIndex, boolean inverted) throws IOException {
		send(DspProtocol.buildPhase(channelIndex, inverted));
	}

	void setDelay(int channelIndex, double ms) throws IOException {
		send(DspProtocol.buildDelay(channelIndex, ms));
	}

	void setMatrixRoute(DspChannel output, DspChannel input) throws IOException {
		send(DspProtocol.buildMatrixRoute(output, input));
	}

	void setMatrixRoutes(DspChannel output, Collection<DspChannel> inputs) throws IOException {
		send(DspProtocol.buildMatrixRoutes(output, inputs));
	}

	void setMatrixCrosspointGain(DspChannel output, DspChannel input, double db) throws IOException {
		send(DspProtocol.buildMatrixCrosspointGain(output, input, db));
	}

	private void send(byte[] payload) throws IOException {
		transport.send(payload);
	}
}
