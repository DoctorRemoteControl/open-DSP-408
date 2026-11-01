package de.drremote.dsp408controller.core.service;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;
import de.drremote.dsp408controller.core.library.DspLibrary;
import de.drremote.dsp408controller.core.library.DspWriteCapabilities.Operation;
import de.drremote.dsp408controller.core.net.DspClient;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.DspProtocol;
import de.drremote.dsp408controller.core.protocol.FirFilterType;
import de.drremote.dsp408controller.core.protocol.FirProcessingMode;
import de.drremote.dsp408controller.core.protocol.FirWindowFunction;

/** Owns FIR processing, generator and external coefficient upload writes. */
final class DspFirCommands {
	private final Supplier<DspClient> clientSupplier;
	private final Supplier<DspLibrary> librarySupplier;
	private final DspWriteVerifier writeVerifier;

	DspFirCommands(
			Supplier<DspClient> clientSupplier,
			Supplier<DspLibrary> librarySupplier,
			DspWriteVerifier writeVerifier
	) {
		this.clientSupplier = clientSupplier;
		this.librarySupplier = librarySupplier;
		this.writeVerifier = writeVerifier;
	}

	void setProcessingMode(DspChannel output, FirProcessingMode mode) throws IOException {
		requireFirLibrary();
		requireOutput(output);
		verify(Operation.FIR_PROCESSING_MODE, output, "FIR processing mode");
		client().setFirProcessingMode(output, mode);
	}

	void setGenerator(
			DspChannel output,
			FirFilterType type,
			FirWindowFunction window,
			double highPassFrequencyHz,
			double lowPassFrequencyHz,
			int taps
	) throws IOException {
		requireFirLibrary();
		requireOutput(output);
		verify(Operation.FIR_GENERATOR, output, "FIR generator");

		double normalizedHighPass = normalizeFrequency(highPassFrequencyHz);
		double normalizedLowPass = normalizeFrequency(lowPassFrequencyHz);
		int normalizedTaps = DspProtocol.firTapRawToCount(DspProtocol.firTapCountToRaw(taps));
		client().setFirGenerator(output, type, window, normalizedHighPass, normalizedLowPass, normalizedTaps);
	}

	List<byte[]> uploadExternalFir(DspChannel channel, String name, double[] coefficients) throws IOException {
		requireFirLibrary();
		requireAnyChannel(channel);
		verify(Operation.EXTERNAL_FIR_UPLOAD, channel, "External FIR upload");

		boolean includeBeginCommand = channel.index() < 4;
		List<byte[]> payloads = DspProtocol.buildExternalFirUpload(
				channel,
				name,
				coefficients,
				includeBeginCommand
		);
		DspClient client = client();
		for (byte[] payload : payloads) {
			client.sendPayload(payload);
		}
		return payloads;
	}

	private DspClient client() {
		return clientSupplier.get();
	}

	private void requireFirLibrary() {
		DspLibrary library = librarySupplier.get();
		if (!library.hasFirFeatures()) {
			throw new UnsupportedDspOperationException(
					"FIR features are not available in " + library.libraryType()
			);
		}
	}

	private void verify(Operation operation, DspChannel channel, String label) {
		writeVerifier.require(operation, channel, label);
	}

	private static double normalizeFrequency(double frequencyHz) {
		return DspCodecRegistry.peqFrequency()
				.rawToDouble(DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz));
	}

	private static void requireOutput(DspChannel channel) {
		if (channel == null || channel.index() < 4 || channel.index() > 11) {
			throw new IllegalArgumentException("Output channel required: Out1..Out8");
		}
	}

	private static void requireAnyChannel(DspChannel channel) {
		if (channel == null || channel.index() < 0 || channel.index() > 11) {
			throw new IllegalArgumentException("Channel required: InA..InD or Out1..Out8");
		}
	}
}
