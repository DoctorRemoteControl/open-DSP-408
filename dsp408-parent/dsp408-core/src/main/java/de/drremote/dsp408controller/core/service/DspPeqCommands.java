package de.drremote.dsp408controller.core.service;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;
import de.drremote.dsp408controller.core.library.DspLibrary;
import de.drremote.dsp408controller.core.library.DspWriteCapabilities.Operation;
import de.drremote.dsp408controller.core.library.OutputPeqBandDefaults;
import de.drremote.dsp408controller.core.net.DspClient;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.PeqFilterType;
import de.drremote.dsp408controller.core.state.DspState;
import de.drremote.dsp408controller.core.state.PeqBandState;

/** Owns state-preserving PEQ and GEQ writes. */
final class DspPeqCommands {
	private static final String OUTPUT_PEQ_INCOMPLETE_MESSAGE =
			"Band state incomplete. Run scanblocks first so output gain/frequency/Q/type/bypass are known. "
					+ "The filter type may use an explicit library default; bypass never falls back.";
	private static final String INPUT_PEQ_INCOMPLETE_MESSAGE =
			"Band state incomplete. Run scanblocks first so input gain/frequency/Q/type/bypass are known.";
	private static final String INPUT_GEQ_RANGE_MESSAGE = "Input GEQ band index must be in range 1..31";

	private final DspState state;
	private final Supplier<DspClient> clientSupplier;
	private final Supplier<DspLibrary> librarySupplier;
	private final DspWriteVerifier writeVerifier;

	DspPeqCommands(
			DspState state,
			Supplier<DspClient> clientSupplier,
			Supplier<DspLibrary> librarySupplier,
			DspWriteVerifier writeVerifier
	) {
		this.state = state;
		this.clientSupplier = clientSupplier;
		this.librarySupplier = librarySupplier;
		this.writeVerifier = writeVerifier;
	}

	void setOutputPeqFrequency(DspChannel channel, int peqIndex, double hz) throws IOException {
		requireOutput(channel);
		verify(Operation.OUTPUT_PEQ, channel, "Output PEQ");
		PeqBandState resolved = requireKnownOutputPeqBand(channel, peqIndex);
		double normalizedFrequencyHz = DspCodecRegistry.peqFrequency()
				.rawToDouble(DspCodecRegistry.peqFrequency().doubleToRaw(hz));

		client().setOutputPeq(outputNumber(channel), peqIndex, resolved.gainDb(), normalizedFrequencyHz,
				resolved.q(), resolved.filterType(), resolved.bypass());
		state.markOutputPeqBand(channel, peqIndex, resolved.gainDb(), normalizedFrequencyHz, resolved.q(),
				resolved.filterType(), resolved.bypass(), "tx");
	}

	void setOutputPeqQRaw(DspChannel channel, int peqIndex, int qRaw) throws IOException {
		requireOutput(channel);
		setOutputPeqQ(channel, peqIndex, DspCodecRegistry.peqQ().rawToDouble(qRaw));
	}

	void setOutputPeqGainCode(DspChannel channel, int peqIndex, int gainCode) throws IOException {
		requireOutput(channel);
		setOutputPeqGain(channel, peqIndex, DspCodecRegistry.peqGain().rawToDouble(gainCode));
	}

	void setOutputPeqQ(DspChannel channel, int peqIndex, double q) throws IOException {
		requireOutput(channel);
		verify(Operation.OUTPUT_PEQ, channel, "Output PEQ");
		PeqBandState resolved = requireKnownOutputPeqBand(channel, peqIndex);
		double normalizedQ = DspCodecRegistry.peqQ().rawToDouble(DspCodecRegistry.peqQ().doubleToRaw(q));

		client().setOutputPeq(outputNumber(channel), peqIndex, resolved.gainDb(), resolved.frequencyHz(),
				normalizedQ, resolved.filterType(), resolved.bypass());
		state.markOutputPeqBand(channel, peqIndex, resolved.gainDb(), resolved.frequencyHz(), normalizedQ,
				resolved.filterType(), resolved.bypass(), "tx");
	}

	void setOutputPeqGain(DspChannel channel, int peqIndex, double gainDb) throws IOException {
		requireOutput(channel);
		verify(Operation.OUTPUT_PEQ, channel, "Output PEQ");
		PeqBandState resolved = requireKnownOutputPeqBand(channel, peqIndex);
		double normalizedGain = DspCodecRegistry.peqGain()
				.rawToDouble(DspCodecRegistry.peqGain().doubleToRaw(gainDb));

		client().setOutputPeq(outputNumber(channel), peqIndex, normalizedGain, resolved.frequencyHz(),
				resolved.q(), resolved.filterType(), resolved.bypass());
		state.markOutputPeqBand(channel, peqIndex, normalizedGain, resolved.frequencyHz(), resolved.q(),
				resolved.filterType(), resolved.bypass(), "tx");
	}

	void setOutputPeqType(DspChannel channel, int peqIndex, PeqFilterType filterType) throws IOException {
		requireOutput(channel);
		verify(Operation.OUTPUT_PEQ, channel, "Output PEQ");
		PeqBandState resolved = requireKnownOutputPeqBand(channel, peqIndex);

		client().setOutputPeq(outputNumber(channel), peqIndex, resolved.gainDb(), resolved.frequencyHz(),
				resolved.q(), filterType, resolved.bypass());
		state.markOutputPeqBand(channel, peqIndex, resolved.gainDb(), resolved.frequencyHz(), resolved.q(),
				filterType, resolved.bypass(), "tx");
	}

	void setOutputPeq(
			DspChannel channel,
			int peqIndex,
			PeqFilterType filterType,
			double frequencyHz,
			double q,
			double gainDb
	) throws IOException {
		requireOutput(channel);
		verify(Operation.OUTPUT_PEQ, channel, "Output PEQ");
		boolean bypass = requireKnownOutputPeqBypass(channel, peqIndex);
		setOutputPeq(channel, peqIndex, filterType, frequencyHz, q, gainDb, bypass);
	}

	void setOutputPeq(
			DspChannel channel,
			int peqIndex,
			PeqFilterType filterType,
			double frequencyHz,
			double q,
			double gainDb,
			boolean bypass
	) throws IOException {
		requireOutput(channel);
		verify(Operation.OUTPUT_PEQ, channel, "Output PEQ");
		if (peqIndex < 1 || peqIndex > 9) {
			throw new IllegalArgumentException("PEQ index must be in range 1..9");
		}

		double normalizedFrequencyHz = DspCodecRegistry.peqFrequency()
				.rawToDouble(DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz));
		double normalizedQ = DspCodecRegistry.peqQ().rawToDouble(DspCodecRegistry.peqQ().doubleToRaw(q));
		double normalizedGain = DspCodecRegistry.peqGain()
				.rawToDouble(DspCodecRegistry.peqGain().doubleToRaw(gainDb));

		client().setOutputPeq(outputNumber(channel), peqIndex, normalizedGain, normalizedFrequencyHz,
				normalizedQ, filterType, bypass);
		state.markOutputPeqBand(channel, peqIndex, normalizedGain, normalizedFrequencyHz, normalizedQ,
				filterType, bypass, "tx");
	}

	void setOutputPeqBypass(DspChannel channel, int peqIndex, boolean bypass) throws IOException {
		requireOutput(channel);
		verify(Operation.OUTPUT_PEQ, channel, "Output PEQ");
		PeqBandState resolved = requireKnownOutputPeqBand(channel, peqIndex);

		client().setOutputPeq(outputNumber(channel), peqIndex, resolved.gainDb(), resolved.frequencyHz(),
				resolved.q(), resolved.filterType(), bypass);
		state.markOutputPeqBand(channel, peqIndex, resolved.gainDb(), resolved.frequencyHz(), resolved.q(),
				resolved.filterType(), bypass, "tx");
	}

	void setInputPeq(
			DspChannel channel,
			int peqIndex,
			PeqFilterType filterType,
			double frequencyHz,
			double q,
			double gainDb,
			boolean bypass
	) throws IOException {
		requireInput(channel);
		verify(Operation.INPUT_PEQ, channel, "Input PEQ");
		checkInputPeqIndex(peqIndex);

		double normalizedFrequencyHz = DspCodecRegistry.peqFrequency()
				.rawToDouble(DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz));
		double normalizedQ = DspCodecRegistry.peqQ().rawToDouble(DspCodecRegistry.peqQ().doubleToRaw(q));
		double normalizedGain = DspCodecRegistry.peqGain()
				.rawToDouble(DspCodecRegistry.peqGain().doubleToRaw(gainDb));

		client().setInputPeq(inputNumber(channel), peqIndex, normalizedGain, normalizedFrequencyHz, normalizedQ,
				filterType, bypass);
		state.markInputPeqBand(channel, peqIndex, normalizedGain, normalizedFrequencyHz, normalizedQ, filterType,
				bypass, "tx");
	}

	void setInputPeqGain(DspChannel channel, int peqIndex, double gainDb) throws IOException {
		requireInput(channel);
		verify(Operation.INPUT_PEQ, channel, "Input PEQ");
		checkInputPeqIndex(peqIndex);
		PeqBandState resolved = requireKnownInputPeqBand(channel, peqIndex);
		double normalizedGain = DspCodecRegistry.peqGain()
				.rawToDouble(DspCodecRegistry.peqGain().doubleToRaw(gainDb));

		client().setInputPeq(inputNumber(channel), peqIndex, normalizedGain, resolved.frequencyHz(),
				resolved.q(), resolved.filterType(), resolved.bypass());
		state.markInputPeqBand(channel, peqIndex, normalizedGain, resolved.frequencyHz(), resolved.q(),
				resolved.filterType(), resolved.bypass(), "tx");
	}

	void setInputPeqFrequency(DspChannel channel, int peqIndex, double frequencyHz) throws IOException {
		requireInput(channel);
		verify(Operation.INPUT_PEQ, channel, "Input PEQ");
		checkInputPeqIndex(peqIndex);
		PeqBandState resolved = requireKnownInputPeqBand(channel, peqIndex);
		double normalizedFrequencyHz = DspCodecRegistry.peqFrequency()
				.rawToDouble(DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz));

		client().setInputPeq(inputNumber(channel), peqIndex, resolved.gainDb(), normalizedFrequencyHz,
				resolved.q(), resolved.filterType(), resolved.bypass());
		state.markInputPeqBand(channel, peqIndex, resolved.gainDb(), normalizedFrequencyHz, resolved.q(),
				resolved.filterType(), resolved.bypass(), "tx");
	}

	void setInputPeqQ(DspChannel channel, int peqIndex, double q) throws IOException {
		requireInput(channel);
		verify(Operation.INPUT_PEQ, channel, "Input PEQ");
		checkInputPeqIndex(peqIndex);
		PeqBandState resolved = requireKnownInputPeqBand(channel, peqIndex);
		double normalizedQ = DspCodecRegistry.peqQ().rawToDouble(DspCodecRegistry.peqQ().doubleToRaw(q));

		client().setInputPeq(inputNumber(channel), peqIndex, resolved.gainDb(), resolved.frequencyHz(),
				normalizedQ, resolved.filterType(), resolved.bypass());
		state.markInputPeqBand(channel, peqIndex, resolved.gainDb(), resolved.frequencyHz(), normalizedQ,
				resolved.filterType(), resolved.bypass(), "tx");
	}

	void setInputPeqType(DspChannel channel, int peqIndex, PeqFilterType filterType) throws IOException {
		requireInput(channel);
		verify(Operation.INPUT_PEQ, channel, "Input PEQ");
		checkInputPeqIndex(peqIndex);
		PeqBandState resolved = requireKnownInputPeqBand(channel, peqIndex);

		client().setInputPeq(inputNumber(channel), peqIndex, resolved.gainDb(), resolved.frequencyHz(),
				resolved.q(), filterType, resolved.bypass());
		state.markInputPeqBand(channel, peqIndex, resolved.gainDb(), resolved.frequencyHz(), resolved.q(),
				filterType, resolved.bypass(), "tx");
	}

	void setInputPeqBypass(DspChannel channel, int peqIndex, boolean bypass) throws IOException {
		requireInput(channel);
		verify(Operation.INPUT_PEQ, channel, "Input PEQ");
		checkInputPeqIndex(peqIndex);
		PeqBandState resolved = requireKnownInputPeqBand(channel, peqIndex);

		client().setInputPeq(inputNumber(channel), peqIndex, resolved.gainDb(), resolved.frequencyHz(),
				resolved.q(), resolved.filterType(), bypass);
		state.markInputPeqBand(channel, peqIndex, resolved.gainDb(), resolved.frequencyHz(), resolved.q(),
				resolved.filterType(), bypass, "tx");
	}

	void setAllPeqBypass(boolean bypass) throws IOException {
		List<DspChannel> inputs = List.of(
				DspChannel.IN_A, DspChannel.IN_B, DspChannel.IN_C, DspChannel.IN_D);
		List<DspChannel> outputs = List.of(
				DspChannel.OUT_1, DspChannel.OUT_2, DspChannel.OUT_3, DspChannel.OUT_4,
				DspChannel.OUT_5, DspChannel.OUT_6, DspChannel.OUT_7, DspChannel.OUT_8);

		for (DspChannel input : inputs) {
			verify(Operation.INPUT_PEQ, input, "Input PEQ");
			for (int peqIndex = 1; peqIndex <= 8; peqIndex++) {
				requireKnownInputPeqBand(input, peqIndex);
			}
		}
		for (DspChannel output : outputs) {
			verify(Operation.OUTPUT_PEQ, output, "Output PEQ");
			for (int peqIndex = 1; peqIndex <= 9; peqIndex++) {
				requireKnownOutputPeqBand(output, peqIndex);
			}
		}

		for (DspChannel input : inputs) {
			for (int peqIndex = 1; peqIndex <= 8; peqIndex++) {
				setInputPeqBypass(input, peqIndex, bypass);
			}
		}
		for (DspChannel output : outputs) {
			for (int peqIndex = 1; peqIndex <= 9; peqIndex++) {
				setOutputPeqBypass(output, peqIndex, bypass);
			}
		}
	}

	void setInputGeq(DspChannel channel, int bandIndex, double gainDb) throws IOException {
		requireInput(channel);
		verify(Operation.INPUT_GEQ, channel, "Input GEQ");
		checkInputGeqBandIndex(bandIndex);

		double normalizedGain = DspCodecRegistry.peqGain()
				.rawToDouble(DspCodecRegistry.peqGain().doubleToRaw(gainDb));
		double frequencyHz = requireKnownInputGeqFrequency(bandIndex);

		client().setInputGeq(inputNumber(channel), bandIndex, normalizedGain);
		state.markInputGeqBand(channel, bandIndex, normalizedGain, frequencyHz, "tx");
	}

	private DspClient client() {
		return clientSupplier.get();
	}

	private DspLibrary library() {
		return librarySupplier.get();
	}

	private void verify(Operation operation, DspChannel channel, String label) {
		writeVerifier.require(operation, channel, label);
	}

	private PeqBandState requireKnownOutputPeqBand(DspChannel channel, int peqIndex) {
		if (peqIndex < 1 || peqIndex > 9) {
			throw new IllegalArgumentException("PEQ index must be in range 1..9");
		}

		PeqBandState existing = state.outputPeq(channel).band(peqIndex);
		if (existing == null || existing.gainDb() == null || existing.frequencyHz() == null
				|| existing.q() == null || existing.bypass() == null) {
			throw new IllegalStateException(OUTPUT_PEQ_INCOMPLETE_MESSAGE);
		}

		PeqFilterType resolvedFilterType = existing.filterType();
		boolean typeConfirmed = existing.typeConfirmedFromDevice();
		String typeSource = existing.lastTypeUpdateSource();
		if (resolvedFilterType == null) {
			OutputPeqBandDefaults defaults = library().outputPeqDefault(peqIndex);
			if (defaults != null) {
				resolvedFilterType = defaults.filterType();
				typeConfirmed = false;
				typeSource = "library-default";
			}
		}
		if (resolvedFilterType == null) {
			throw new IllegalStateException(OUTPUT_PEQ_INCOMPLETE_MESSAGE);
		}

		PeqBandState resolved = new PeqBandState(peqIndex);
		resolved.updateGain(existing.gainDb(), existing.lastGainUpdateSource(), existing.gainConfirmedFromDevice());
		resolved.updateFrequency(existing.frequencyHz(), existing.lastFrequencyUpdateSource(),
				existing.frequencyConfirmedFromDevice());
		resolved.updateQ(existing.q(), existing.lastQUpdateSource(), existing.qConfirmedFromDevice());
		resolved.updateFilterType(resolvedFilterType, typeSource, typeConfirmed);
		resolved.updateBypass(existing.bypass(), existing.lastBypassUpdateSource(),
				existing.bypassConfirmedFromDevice());
		return resolved;
	}

	private boolean requireKnownOutputPeqBypass(DspChannel channel, int peqIndex) {
		if (peqIndex < 1 || peqIndex > 9) {
			throw new IllegalArgumentException("PEQ index must be in range 1..9");
		}
		PeqBandState existing = state.outputPeq(channel).band(peqIndex);
		if (existing == null || existing.bypass() == null) {
			throw new IllegalStateException(OUTPUT_PEQ_INCOMPLETE_MESSAGE);
		}
		return existing.bypass();
	}

	private PeqBandState requireKnownInputPeqBand(DspChannel channel, int peqIndex) {
		checkInputPeqIndex(peqIndex);
		PeqBandState existing = state.inputPeq(channel).band(peqIndex);
		if (existing == null || existing.gainDb() == null || existing.frequencyHz() == null
				|| existing.q() == null || existing.filterType() == null || existing.bypass() == null) {
			throw new IllegalStateException(INPUT_PEQ_INCOMPLETE_MESSAGE);
		}
		return existing;
	}

	private double requireKnownInputGeqFrequency(int bandIndex) {
		Double frequencyHz = library().inputGeqBandFrequency(bandIndex);
		if (frequencyHz == null) {
			throw new IllegalStateException("Input GEQ band frequency is unknown for band " + bandIndex);
		}
		return frequencyHz;
	}

	private static void requireOutput(DspChannel channel) {
		if (channel == null || channel.index() < 4 || channel.index() > 11) {
			throw new IllegalArgumentException("PEQ write is only supported for output channels Out1..Out8");
		}
	}

	private static void requireInput(DspChannel channel) {
		if (channel == null || channel.index() < 0 || channel.index() > 3) {
			throw new IllegalArgumentException("Input channel required: InA..InD");
		}
	}

	private static int outputNumber(DspChannel channel) {
		return channel.index() - 3;
	}

	private static int inputNumber(DspChannel channel) {
		return channel.index();
	}

	private static void checkInputPeqIndex(int peqIndex) {
		if (peqIndex < 1 || peqIndex > 8) {
			throw new IllegalArgumentException("Input PEQ index must be in range 1..8");
		}
	}

	private static void checkInputGeqBandIndex(int bandIndex) {
		if (bandIndex < 1 || bandIndex > 31) {
			throw new IllegalArgumentException(INPUT_GEQ_RANGE_MESSAGE);
		}
	}
}
