package de.drremote.dsp408controller.core.service;

import de.drremote.dsp408controller.core.library.DspWriteCapabilities.Operation;
import de.drremote.dsp408controller.core.protocol.DspChannel;

@FunctionalInterface
interface DspWriteVerifier {
	void require(Operation operation, DspChannel channel, String label);
}
