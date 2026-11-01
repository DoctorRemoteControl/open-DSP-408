package de.drremote.dsp408controller.core.net;

import java.io.IOException;

/** Sends encoded DSP payload commands through the serialized transaction path. */
interface DspPayloadTransport {
	boolean send(byte[] payload) throws IOException;

	void sendReliable(byte[] payload) throws IOException;
}
