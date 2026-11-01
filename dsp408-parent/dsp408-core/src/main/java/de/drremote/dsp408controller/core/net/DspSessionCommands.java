package de.drremote.dsp408controller.core.net;

import java.io.IOException;
import java.util.function.Consumer;

import de.drremote.dsp408controller.core.protocol.DspProtocol;

/** Owns handshake, discovery, preset and authentication payloads. */
final class DspSessionCommands {
	private static final int[] FIR_STARTUP_WINDOW_OFFSETS = {
			0, 157, 315, 472, 630, 708, 787, 866, 945, 1024, 1102, 1181
	};

	private final DspPayloadTransport transport;
	private final Consumer<String> log;

	DspSessionCommands(DspPayloadTransport transport, Consumer<String> log) {
		this.transport = transport;
		this.log = log;
	}

	void sendHandshakeSequence() throws IOException {
		send(DspProtocol.handshakeInit());
		send(DspProtocol.handshakeAck());
		send(DspProtocol.systemInfoRequest());
	}

	void sendObservedStartupSequence(boolean firDevice) throws IOException {
		send(DspProtocol.startupZeroBlockRequest());
		send(DspProtocol.startupStatusRequest());

		int lastPresetIndex = firDevice ? 0x12 : 0x13;
		for (int presetIndex = 0; presetIndex <= lastPresetIndex; presetIndex++) {
			readPresetName(presetIndex);
		}

		if (firDevice) {
			for (int offset : FIR_STARTUP_WINDOW_OFFSETS) {
				send(DspProtocol.readFirStartupWindow(offset));
			}
		}
	}

	void sendKeepAlive() throws IOException {
		send(DspProtocol.keepAlive());
	}

	void requestSystemInfo() throws IOException {
		send(DspProtocol.systemInfoRequest());
	}

	boolean requestRuntimeMeters() throws IOException {
		return send(DspProtocol.runtimeMeterRequest());
	}

	void readParameterBlock(int blockIndex) throws IOException {
		send(DspProtocol.readParameterBlock(blockIndex));
	}

	void readPresetName(int presetIndex) throws IOException {
		send(DspProtocol.readPresetName(presetIndex));
	}

	void loadPreset(int presetIndex) throws IOException {
		send(DspProtocol.loadPreset(presetIndex));
	}

	void loadPreset(String slotLabel) throws IOException {
		send(DspProtocol.loadPreset(slotLabel));
	}

	void setPresetName(String name) throws IOException {
		send(DspProtocol.buildPresetName(name));
	}

	void storePreset(int presetIndex) throws IOException {
		send(DspProtocol.storePreset(presetIndex));
	}

	void storePreset(String slotLabel) throws IOException {
		send(DspProtocol.storePreset(slotLabel));
	}

	void login(String pin) throws IOException {
		send(DspProtocol.login(pin));
		log("Login PIN sent");
	}

	private boolean send(byte[] payload) throws IOException {
		return transport.send(payload);
	}

	private void log(String message) {
		if (log != null) {
			log.accept(message);
		}
	}
}
