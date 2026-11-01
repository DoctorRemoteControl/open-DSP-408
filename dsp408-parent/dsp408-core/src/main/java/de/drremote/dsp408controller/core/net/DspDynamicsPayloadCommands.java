package de.drremote.dsp408controller.core.net;

import java.io.IOException;

import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.DspProtocol;

/** Owns gate, compressor, limiter and test-tone payloads. */
final class DspDynamicsPayloadCommands {
	private final DspPayloadTransport transport;

	DspDynamicsPayloadCommands(DspPayloadTransport transport) {
		this.transport = transport;
	}

	void setInputGate(
			int inputChannelIndex,
			double thresholdDb,
			double holdMs,
			double attackMs,
			double releaseMs
	) throws IOException {
		send(DspProtocol.buildInputGate(
				inputChannelIndex,
				thresholdDb,
				holdMs,
				attackMs,
				releaseMs
		));
	}

	void setCompressor(
			DspChannel output,
			String ratioLabel,
			double attackMs,
			double releaseMs,
			double kneeDb,
			double thresholdDb
	) throws IOException {
		send(DspProtocol.buildCompressor(
				output,
				ratioLabel,
				attackMs,
				releaseMs,
				kneeDb,
				thresholdDb
		));
	}

	void setInputCompressor(
			DspChannel input,
			int ratioRaw,
			int attackRaw,
			int releaseRaw,
			int kneeRaw,
			int thresholdRaw,
			int frequencyRaw,
			int typeContextRaw,
			int typeRaw
	) throws IOException {
		send(DspProtocol.buildInputCompressor(
				input,
				ratioRaw,
				attackRaw,
				releaseRaw,
				kneeRaw,
				thresholdRaw,
				frequencyRaw,
				typeContextRaw,
				typeRaw
		));
	}

	void setCompressor(
			DspChannel output,
			int ratioRaw,
			int attackRaw,
			int releaseRaw,
			int kneeRaw,
			int thresholdRaw
	) throws IOException {
		send(DspProtocol.buildCompressor(
				output,
				ratioRaw,
				attackRaw,
				releaseRaw,
				kneeRaw,
				thresholdRaw
		));
	}

	void setLimiter(
			DspChannel output,
			double attackMs,
			double releaseMs,
			int kneeRaw,
			double thresholdDb
	) throws IOException {
		transport.sendReliable(DspProtocol.buildLimiter(
				output,
				attackMs,
				releaseMs,
				kneeRaw,
				thresholdDb
		));
	}

	void setLimiter(
			DspChannel output,
			int attackRaw,
			int releaseRaw,
			int kneeRaw,
			int thresholdRaw
	) throws IOException {
		transport.sendReliable(DspProtocol.buildLimiter(
				output,
				attackRaw,
				releaseRaw,
				kneeRaw,
				thresholdRaw
		));
	}

	void setTestToneSource(int sourceIndex) throws IOException {
		send(DspProtocol.buildTestToneSource(sourceIndex));
	}

	void setTestToneSource(String source) throws IOException {
		send(DspProtocol.buildTestToneSource(source));
	}

	void setTestToneSineFrequencyRaw(int selectorRaw) throws IOException {
		send(DspProtocol.buildTestToneSineFrequencyRaw(selectorRaw));
	}

	void setTestToneSineFrequencyHz(double hz) throws IOException {
		send(DspProtocol.buildTestToneSineFrequencyHz(hz));
	}

	void setTestToneOff() throws IOException {
		send(DspProtocol.buildTestToneOff());
	}

	private void send(byte[] payload) throws IOException {
		transport.send(payload);
	}
}
