package de.drremote.dsp408controller.core.service;

import java.io.IOException;
import java.util.function.Supplier;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;
import de.drremote.dsp408controller.core.library.DspLibrary;
import de.drremote.dsp408controller.core.library.DspWriteCapabilities.Operation;
import de.drremote.dsp408controller.core.net.DspClient;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.DspProtocol;
import de.drremote.dsp408controller.core.state.CompressorState;
import de.drremote.dsp408controller.core.state.DspState;
import de.drremote.dsp408controller.core.state.InputGateState;

/** Owns state-preserving gate, compressor and limiter writes. */
final class DspDynamicsCommands {
	private static final String INPUT_GATE_INCOMPLETE_MESSAGE =
			"Gate state incomplete. Run scanblocks or use gateset first.";

	private final DspState state;
	private final Supplier<DspClient> clientSupplier;
	private final Supplier<DspLibrary> librarySupplier;
	private final DspWriteVerifier writeVerifier;

	DspDynamicsCommands(
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

	void setInputGate(
			DspChannel channel,
			double thresholdDb,
			double holdMs,
			double attackMs,
			double releaseMs
	) throws IOException {
		requireInput(channel);
		verify(Operation.INPUT_GATE, channel, "Input gate");

		double normalizedThresholdDb = DspCodecRegistry.rawToDynamicsThresholdDb(
				DspCodecRegistry.dynamicsThresholdDbToRaw(thresholdDb, -90.0, 0.0));
		double normalizedHoldMs = DspCodecRegistry.rawToDynamicsTimeMs(
				DspCodecRegistry.dynamicsTimeMsToRaw(holdMs, 10.0, 999.0));
		double normalizedAttackMs = DspCodecRegistry.rawToDynamicsTimeMs(
				DspCodecRegistry.dynamicsTimeMsToRaw(attackMs, 1.0, 999.0));
		double normalizedReleaseMs = DspCodecRegistry.rawToDynamicsTimeMs(
				DspCodecRegistry.dynamicsTimeMsToRaw(releaseMs, 10.0, 3000.0));

		client().setInputGate(
				inputNumber(channel),
				normalizedThresholdDb,
				normalizedHoldMs,
				normalizedAttackMs,
				normalizedReleaseMs
		);
		state.markInputGate(
				channel,
				normalizedThresholdDb,
				normalizedHoldMs,
				normalizedAttackMs,
				normalizedReleaseMs,
				"tx"
		);
	}

	void setInputGateThreshold(DspChannel channel, double thresholdDb) throws IOException {
		requireInput(channel);
		verify(Operation.INPUT_GATE, channel, "Input gate");
		InputGateState resolved = requireKnownInputGate(channel);
		setInputGate(channel, thresholdDb, resolved.holdMs(), resolved.attackMs(), resolved.releaseMs());
	}

	void setInputGateHold(DspChannel channel, double holdMs) throws IOException {
		requireInput(channel);
		verify(Operation.INPUT_GATE, channel, "Input gate");
		InputGateState resolved = requireKnownInputGate(channel);
		setInputGate(channel, resolved.thresholdDb(), holdMs, resolved.attackMs(), resolved.releaseMs());
	}

	void setInputGateAttack(DspChannel channel, double attackMs) throws IOException {
		requireInput(channel);
		verify(Operation.INPUT_GATE, channel, "Input gate");
		InputGateState resolved = requireKnownInputGate(channel);
		setInputGate(channel, resolved.thresholdDb(), resolved.holdMs(), attackMs, resolved.releaseMs());
	}

	void setInputGateRelease(DspChannel channel, double releaseMs) throws IOException {
		requireInput(channel);
		verify(Operation.INPUT_GATE, channel, "Input gate");
		InputGateState resolved = requireKnownInputGate(channel);
		setInputGate(channel, resolved.thresholdDb(), resolved.holdMs(), resolved.attackMs(), releaseMs);
	}

	void setCompressor(
			DspChannel input,
			double thresholdDb,
			String ratioLabel,
			double attackMs,
			double releaseMs,
			double kneeDb
	) throws IOException {
		requireInput(input);
		verify(Operation.INPUT_COMPRESSOR, input, "Input compressor");
		CompressorState existing = requireKnownInputCompressor(input);

		int ratioRaw = DspProtocol.compressorRatioLabelToRaw(ratioLabel);
		String normalizedRatioLabel = library().compressorRatioName(ratioRaw);
		if (normalizedRatioLabel == null || normalizedRatioLabel.isBlank()) {
			normalizedRatioLabel = "raw=" + ratioRaw;
		}

		double normalizedAttackMs = DspCodecRegistry.rawToDynamicsTimeMs(
				DspCodecRegistry.dynamicsTimeMsToRaw(attackMs, 1.0, 999.0));
		double normalizedReleaseMs = DspCodecRegistry.rawToDynamicsTimeMs(
				DspCodecRegistry.dynamicsTimeMsToRaw(releaseMs, 10.0, 3000.0));
		double normalizedKneeDb = Math.max(0.0, Math.min(12.0, Math.round(kneeDb)));
		double normalizedThresholdDb = DspCodecRegistry.rawToDynamicsThresholdDb(
				DspCodecRegistry.dynamicsThresholdDbToRaw(thresholdDb, -90.0, 20.0));

		client().setInputCompressor(
				input,
				ratioRaw,
				DspCodecRegistry.dynamicsTimeMsToRaw(normalizedAttackMs, 1.0, 999.0),
				DspCodecRegistry.dynamicsTimeMsToRaw(normalizedReleaseMs, 10.0, 3000.0),
				(int) Math.round(normalizedKneeDb),
				DspCodecRegistry.dynamicsThresholdDbToRaw(normalizedThresholdDb, -90.0, 20.0),
				DspCodecRegistry.peqFrequency().doubleToRaw(existing.frequencyHz()),
				existing.typeContextRaw(),
				existing.typeRaw()
		);
		state.markCompressor(
				input,
				ratioRaw,
				normalizedRatioLabel,
				normalizedAttackMs,
				normalizedReleaseMs,
				normalizedKneeDb,
				normalizedThresholdDb,
				existing.frequencyHz(),
				existing.typeContextRaw(),
				existing.typeRaw(),
				existing.typeLabel(),
				"tx"
		);
	}

	void setLimiter(DspChannel output, double thresholdDb, double attackMs, double releaseMs)
			throws IOException {
		requireOutput(output);
		int kneeRaw = resolveLimiterKneeRaw(output);
		double normalizedAttackMs = DspCodecRegistry.rawToDynamicsTimeMs(
				DspCodecRegistry.dynamicsTimeMsToRaw(attackMs, 1.0, 999.0));
		double normalizedReleaseMs = DspCodecRegistry.rawToDynamicsTimeMs(
				DspCodecRegistry.dynamicsTimeMsToRaw(releaseMs, 10.0, 3000.0));
		double normalizedThresholdDb = DspCodecRegistry.rawToDynamicsThresholdDb(
				DspCodecRegistry.dynamicsThresholdDbToRaw(thresholdDb, -90.0, 20.0));

		client().setLimiter(output, normalizedAttackMs, normalizedReleaseMs, kneeRaw, normalizedThresholdDb);
		state.markLimiter(output, normalizedAttackMs, normalizedReleaseMs, kneeRaw, normalizedThresholdDb, "tx");
	}

	void setLimiter(DspChannel output, int attackRaw, int releaseRaw, int kneeRaw, int thresholdRaw)
			throws IOException {
		requireOutput(output);
		client().setLimiter(output, attackRaw, releaseRaw, kneeRaw, thresholdRaw);
		state.markLimiter(
				output,
				DspCodecRegistry.rawToDynamicsTimeMs(attackRaw),
				DspCodecRegistry.rawToDynamicsTimeMs(releaseRaw),
				kneeRaw,
				DspCodecRegistry.rawToDynamicsThresholdDb(thresholdRaw),
				"tx"
		);
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

	private InputGateState requireKnownInputGate(DspChannel channel) {
		InputGateState existing = state.inputGate(channel);
		if (existing == null || existing.thresholdDb() == null || existing.holdMs() == null
				|| existing.attackMs() == null || existing.releaseMs() == null) {
			throw new IllegalStateException(INPUT_GATE_INCOMPLETE_MESSAGE);
		}
		return existing;
	}

	private CompressorState requireKnownInputCompressor(DspChannel channel) {
		CompressorState existing = state.compressor(channel);
		if (existing == null || existing.frequencyHz() == null || existing.typeContextRaw() == null
				|| existing.typeRaw() == null || existing.typeLabel() == null) {
			throw new IllegalStateException(
					"Input compressor state incomplete. Run scanblocks first so frequency and type are preserved."
			);
		}
		return existing;
	}

	private int resolveLimiterKneeRaw(DspChannel output) {
		Double known = state.limiter(output) == null ? null : state.limiter(output).kneeDb();
		return known == null || known < 0 || known > 12 ? 0 : (int) Math.round(known);
	}

	private static void requireInput(DspChannel channel) {
		if (channel == null || channel.index() < 0 || channel.index() > 3) {
			throw new IllegalArgumentException("Input channel required: InA..InD");
		}
	}

	private static void requireOutput(DspChannel channel) {
		if (channel == null || channel.index() < 4 || channel.index() > 11) {
			throw new IllegalArgumentException("Output channel required: Out1..Out8");
		}
	}

	private static int inputNumber(DspChannel channel) {
		return channel.index();
	}
}
