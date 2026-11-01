package de.drremote.dsp408controller.core.service;

import java.io.IOException;
import java.util.function.Supplier;

import de.drremote.dsp408controller.core.net.DspClient;

/** Owns preset name, load and store commands. */
final class DspPresetCommands {
	private final Supplier<DspClient> clientSupplier;

	DspPresetCommands(Supplier<DspClient> clientSupplier) {
		this.clientSupplier = clientSupplier;
	}

	void readName(int presetIndex) throws IOException {
		client().readPresetName(presetIndex);
	}

	void load(int presetIndex) throws IOException {
		client().loadPreset(presetIndex);
	}

	void load(String slotLabel) throws IOException {
		client().loadPreset(slotLabel);
	}

	void setName(String name) throws IOException {
		client().setPresetName(name);
	}

	void store(int presetIndex) throws IOException {
		client().storePreset(presetIndex);
	}

	void store(String slotLabel) throws IOException {
		client().storePreset(slotLabel);
	}

	private DspClient client() {
		return clientSupplier.get();
	}
}
