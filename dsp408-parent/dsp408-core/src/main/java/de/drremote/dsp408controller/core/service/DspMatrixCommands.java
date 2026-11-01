package de.drremote.dsp408controller.core.service;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import de.drremote.dsp408controller.core.net.DspClient;
import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.state.DspState;

/** Owns matrix route selection and crosspoint gain writes. */
final class DspMatrixCommands {
	private final DspState state;
	private final Supplier<DspClient> clientSupplier;

	DspMatrixCommands(DspState state, Supplier<DspClient> clientSupplier) {
		this.state = state;
		this.clientSupplier = clientSupplier;
	}

	void setRoute(DspChannel output, DspChannel input) throws IOException {
		setRoutes(output, List.of(input));
	}

	void setRoutes(DspChannel output, Collection<DspChannel> inputs) throws IOException {
		requireOutput(output);
		if (inputs == null) {
			throw new IllegalArgumentException("Matrix inputs are required");
		}
		for (DspChannel input : inputs) {
			requireInput(input);
		}
		client().setMatrixRoutes(output, inputs);
		state.markMatrixRoutes(output, inputs);
	}

	void setCrosspointGain(DspChannel output, DspChannel input, double db) throws IOException {
		requireOutput(output);
		requireInput(input);

		double clamped = Math.max(-60.0, Math.min(0.0, db));
		client().setMatrixCrosspointGain(output, input, clamped);
		state.markMatrixCrosspointGain(output, input, clamped);
	}

	private DspClient client() {
		return clientSupplier.get();
	}

	private static void requireOutput(DspChannel channel) {
		if (channel == null || channel.index() < 4 || channel.index() > 11) {
			throw new IllegalArgumentException("Output channel required: Out1..Out8");
		}
	}

	private static void requireInput(DspChannel channel) {
		if (channel == null || channel.index() < 0 || channel.index() > 3) {
			throw new IllegalArgumentException("Input channel required: InA..InD");
		}
	}
}
