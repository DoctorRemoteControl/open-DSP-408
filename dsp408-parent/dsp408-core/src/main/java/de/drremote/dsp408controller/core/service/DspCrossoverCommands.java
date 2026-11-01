package de.drremote.dsp408controller.core.service;

import java.io.IOException;
import java.util.function.Supplier;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;
import de.drremote.dsp408controller.core.library.DspWriteCapabilities.Operation;
import de.drremote.dsp408controller.core.net.DspClient;
import de.drremote.dsp408controller.core.protocol.CrossoverSlope;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.state.CrossoverFilterState;
import de.drremote.dsp408controller.core.state.DspState;

/** Owns state-preserving high-pass and low-pass crossover writes. */
final class DspCrossoverCommands {
	private static final String HIGH_PASS_INCOMPLETE_MESSAGE =
			"High-pass state incomplete. Run scanblocks or use xhpset first.";
	private static final String LOW_PASS_INCOMPLETE_MESSAGE =
			"Low-pass state incomplete. Output low-pass is readable, but input low-pass slope is not read back yet. "
					+ "Use xlpset first.";

	private final DspState state;
	private final Supplier<DspClient> clientSupplier;
	private final DspWriteVerifier writeVerifier;

	DspCrossoverCommands(
			DspState state,
			Supplier<DspClient> clientSupplier,
			DspWriteVerifier writeVerifier
	) {
		this.state = state;
		this.clientSupplier = clientSupplier;
		this.writeVerifier = writeVerifier;
	}

	void setHighPass(DspChannel channel, double frequencyHz, CrossoverSlope slope, boolean bypass)
			throws IOException {
		requireAnyChannel(channel);
		verify(Operation.CROSSOVER_HIGH_PASS, channel, "High-pass crossover");
		double normalizedFrequencyHz = normalizeFrequency(frequencyHz);
		client().setCrossoverHighPass(channel, normalizedFrequencyHz, slope, bypass);
		state.markCrossoverHighPass(channel, normalizedFrequencyHz, slope, bypass, "tx");
	}

	void setHighPassFrequency(DspChannel channel, double frequencyHz) throws IOException {
		requireAnyChannel(channel);
		verify(Operation.CROSSOVER_HIGH_PASS, channel, "High-pass crossover");
		CrossoverFilterState resolved = requireKnownHighPass(channel);
		double normalizedFrequencyHz = normalizeFrequency(frequencyHz);
		client().setCrossoverHighPass(
				channel,
				normalizedFrequencyHz,
				resolvedSlopeForWrite(resolved),
				resolved.bypass()
		);
		state.markCrossoverHighPass(
				channel,
				normalizedFrequencyHz,
				resolved.slope(),
				resolved.bypass(),
				"tx"
		);
	}

	void setHighPassSlope(DspChannel channel, CrossoverSlope slope) throws IOException {
		requireAnyChannel(channel);
		verify(Operation.CROSSOVER_HIGH_PASS, channel, "High-pass crossover");
		CrossoverFilterState resolved = requireKnownHighPass(channel);
		client().setCrossoverHighPass(channel, resolved.frequencyHz(), slope, resolved.bypass());
		state.markCrossoverHighPass(channel, resolved.frequencyHz(), slope, resolved.bypass(), "tx");
	}

	void setHighPassBypass(DspChannel channel, boolean bypass) throws IOException {
		requireAnyChannel(channel);
		verify(Operation.CROSSOVER_HIGH_PASS, channel, "High-pass crossover");
		CrossoverFilterState resolved = requireKnownHighPass(channel);
		client().setCrossoverHighPass(
				channel,
				resolved.frequencyHz(),
				resolvedSlopeForWrite(resolved),
				bypass
		);
		state.markCrossoverHighPass(channel, resolved.frequencyHz(), resolved.slope(), bypass, "tx");
	}

	void setLowPass(DspChannel channel, double frequencyHz, CrossoverSlope slope, boolean bypass)
			throws IOException {
		requireAnyChannel(channel);
		verify(Operation.CROSSOVER_LOW_PASS, channel, "Low-pass crossover");
		double normalizedFrequencyHz = normalizeFrequency(frequencyHz);
		client().setCrossoverLowPass(channel, normalizedFrequencyHz, slope, bypass);
		state.markCrossoverLowPass(channel, normalizedFrequencyHz, slope, bypass, "tx");
	}

	void setLowPassFrequency(DspChannel channel, double frequencyHz) throws IOException {
		requireAnyChannel(channel);
		verify(Operation.CROSSOVER_LOW_PASS, channel, "Low-pass crossover");
		CrossoverFilterState resolved = requireKnownLowPass(channel);
		double normalizedFrequencyHz = normalizeFrequency(frequencyHz);
		client().setCrossoverLowPass(
				channel,
				normalizedFrequencyHz,
				resolvedSlopeForWrite(resolved),
				resolved.bypass()
		);
		state.markCrossoverLowPass(
				channel,
				normalizedFrequencyHz,
				resolved.slope(),
				resolved.bypass(),
				"tx"
		);
	}

	void setLowPassSlope(DspChannel channel, CrossoverSlope slope) throws IOException {
		requireAnyChannel(channel);
		verify(Operation.CROSSOVER_LOW_PASS, channel, "Low-pass crossover");
		CrossoverFilterState resolved = requireKnownLowPass(channel);
		client().setCrossoverLowPass(channel, resolved.frequencyHz(), slope, resolved.bypass());
		state.markCrossoverLowPass(channel, resolved.frequencyHz(), slope, resolved.bypass(), "tx");
	}

	void setLowPassBypass(DspChannel channel, boolean bypass) throws IOException {
		requireAnyChannel(channel);
		verify(Operation.CROSSOVER_LOW_PASS, channel, "Low-pass crossover");
		CrossoverFilterState resolved = requireKnownLowPass(channel);
		client().setCrossoverLowPass(
				channel,
				resolved.frequencyHz(),
				resolvedSlopeForWrite(resolved),
				bypass
		);
		state.markCrossoverLowPass(channel, resolved.frequencyHz(), resolved.slope(), bypass, "tx");
	}

	private DspClient client() {
		return clientSupplier.get();
	}

	private void verify(Operation operation, DspChannel channel, String label) {
		writeVerifier.require(operation, channel, label);
	}

	private CrossoverFilterState requireKnownHighPass(DspChannel channel) {
		CrossoverFilterState existing = state.crossover(channel).highPass();
		if (existing.frequencyHz() == null || existing.bypass() == null
				|| (!existing.bypass() && existing.slope() == null)) {
			throw new IllegalStateException(HIGH_PASS_INCOMPLETE_MESSAGE);
		}
		return existing;
	}

	private CrossoverFilterState requireKnownLowPass(DspChannel channel) {
		CrossoverFilterState existing = state.crossover(channel).lowPass();
		if (existing.frequencyHz() == null || existing.bypass() == null
				|| (!existing.bypass() && existing.slope() == null)) {
			throw new IllegalStateException(LOW_PASS_INCOMPLETE_MESSAGE);
		}
		return existing;
	}

	private static CrossoverSlope resolvedSlopeForWrite(CrossoverFilterState state) {
		if (Boolean.TRUE.equals(state.bypass())) {
			return state.slope() != null ? state.slope() : CrossoverSlope.LR_48;
		}
		if (state.slope() == null) {
			throw new IllegalStateException("Crossover slope is required when bypass is off");
		}
		return state.slope();
	}

	private static double normalizeFrequency(double frequencyHz) {
		return DspCodecRegistry.peqFrequency()
				.rawToDouble(DspCodecRegistry.peqFrequency().doubleToRaw(frequencyHz));
	}

	private static void requireAnyChannel(DspChannel channel) {
		if (channel == null || channel.index() < 0 || channel.index() > 11) {
			throw new IllegalArgumentException("Channel required: InA..InD or Out1..Out8");
		}
	}
}
