package de.drremote.dsp408controller.core.net;

import java.io.IOException;
import java.util.List;

import de.drremote.dsp408controller.core.protocol.CrossoverSlope;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.DspProtocol;
import de.drremote.dsp408controller.core.protocol.FirFilterType;
import de.drremote.dsp408controller.core.protocol.FirProcessingMode;
import de.drremote.dsp408controller.core.protocol.FirWindowFunction;
import de.drremote.dsp408controller.core.protocol.PeqFilterType;

/** Owns crossover, equalizer and FIR payloads. */
final class DspFilterPayloadCommands {
	private final DspPayloadTransport transport;

	DspFilterPayloadCommands(DspPayloadTransport transport) {
		this.transport = transport;
	}

	void setCrossoverHighPass(
			DspChannel channel,
			double frequencyHz,
			CrossoverSlope slope,
			boolean bypass
	) throws IOException {
		send(DspProtocol.buildCrossoverHighPass(channel, frequencyHz, slope, bypass));
	}

	void setCrossoverLowPass(
			DspChannel channel,
			double frequencyHz,
			CrossoverSlope slope,
			boolean bypass
	) throws IOException {
		send(DspProtocol.buildCrossoverLowPass(channel, frequencyHz, slope, bypass));
	}

	void setOutputPeqFrequency(int outputNumber, int peqIndex, double hz) throws IOException {
		send(DspProtocol.buildOutputPeqFrequency(outputNumber, peqIndex, hz));
	}

	void setOutputPeqQRaw(int outputNumber, int peqIndex, int qRaw) throws IOException {
		send(DspProtocol.buildOutputPeqQRaw(outputNumber, peqIndex, qRaw));
	}

	void setOutputPeqGainCode(int outputNumber, int peqIndex, int gainCode) throws IOException {
		send(DspProtocol.buildOutputPeqGainCode(outputNumber, peqIndex, gainCode));
	}

	void setOutputPeq(
			int outputNumber,
			int peqIndex,
			double gainDb,
			double frequencyHz,
			double q,
			PeqFilterType filterType,
			boolean bypass
	) throws IOException {
		send(DspProtocol.buildOutputPeq(
				outputNumber,
				peqIndex,
				gainDb,
				frequencyHz,
				q,
				filterType,
				bypass
		));
	}

	void setInputPeq(
			int inputChannelIndex,
			int peqIndex,
			double gainDb,
			double frequencyHz,
			double q,
			PeqFilterType filterType,
			boolean bypass
	) throws IOException {
		send(DspProtocol.buildInputPeq(
				inputChannelIndex,
				peqIndex,
				gainDb,
				frequencyHz,
				q,
				filterType,
				bypass
		));
	}

	void setInputPeqGain(int inputChannelIndex, int peqIndex, double gainDb) throws IOException {
		send(DspProtocol.buildInputPeqGain(inputChannelIndex, peqIndex, gainDb));
	}

	void setInputPeqFrequency(int inputChannelIndex, int peqIndex, double frequencyHz) throws IOException {
		send(DspProtocol.buildInputPeqFrequency(inputChannelIndex, peqIndex, frequencyHz));
	}

	void setInputPeqQ(int inputChannelIndex, int peqIndex, double q) throws IOException {
		send(DspProtocol.buildInputPeqQ(inputChannelIndex, peqIndex, q));
	}

	void setInputPeqType(int inputChannelIndex, int peqIndex, PeqFilterType filterType) throws IOException {
		send(DspProtocol.buildInputPeqType(inputChannelIndex, peqIndex, filterType));
	}

	void setInputPeqBypass(int inputChannelIndex, int peqIndex, boolean bypass) throws IOException {
		send(DspProtocol.buildInputPeqBypass(inputChannelIndex, peqIndex, bypass));
	}

	void setInputGeq(int inputChannelIndex, int bandIndex, double gainDb) throws IOException {
		send(DspProtocol.buildInputGeq(inputChannelIndex, bandIndex, gainDb));
	}

	void setFirProcessingMode(DspChannel output, FirProcessingMode mode) throws IOException {
		send(DspProtocol.buildFirProcessingMode(output, mode));
	}

	void setFirGenerator(
			DspChannel output,
			FirFilterType type,
			FirWindowFunction window,
			double highPassFrequencyHz,
			double lowPassFrequencyHz,
			int taps
	) throws IOException {
		send(DspProtocol.buildFirGenerator(
				output,
				type,
				window,
				highPassFrequencyHz,
				lowPassFrequencyHz,
				taps
		));
	}

	void uploadExternalFir(
			DspChannel channel,
			String name,
			double[] coefficients,
			boolean includeBeginCommand
	) throws IOException {
		List<byte[]> payloads = DspProtocol.buildExternalFirUpload(
				channel,
				name,
				coefficients,
				includeBeginCommand
		);
		for (byte[] payload : payloads) {
			send(payload);
		}
	}

	private void send(byte[] payload) throws IOException {
		transport.send(payload);
	}
}
