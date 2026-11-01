package de.drremote.dsp408controller.core.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.drremote.dsp408controller.core.protocol.DspChannel;
import de.drremote.dsp408controller.core.protocol.DspProtocol;

class DspPayloadCommandRoutingTest {
	@Test
	void routesGainAndMuteThroughTheRepeatSafeWritePath() throws Exception {
		RecordingTransport transport = new RecordingTransport();
		DspLevelRoutingPayloadCommands commands = new DspLevelRoutingPayloadCommands(transport);

		commands.setGain(0, -12.0);
		commands.setMute(3, true);
		commands.setPhase(1, true);

		assertEquals(2, transport.reliablePayloads.size());
		assertArrayEquals(DspProtocol.buildGain(0, -12.0), transport.reliablePayloads.get(0));
		assertArrayEquals(DspProtocol.buildMute(3, true), transport.reliablePayloads.get(1));
		assertEquals(1, transport.payloads.size());
		assertArrayEquals(DspProtocol.buildPhase(1, true), transport.payloads.get(0));
	}

	@Test
	void routesLimiterReliablyWithoutChangingOtherDynamicsCommands() throws Exception {
		RecordingTransport transport = new RecordingTransport();
		DspDynamicsPayloadCommands commands = new DspDynamicsPayloadCommands(transport);

		commands.setLimiter(DspChannel.OUT_1, 10, 1_000, 0, 400);
		commands.setInputGate(0, -40.0, 100.0, 10.0, 1_000.0);

		assertEquals(1, transport.reliablePayloads.size());
		assertArrayEquals(
				DspProtocol.buildLimiter(DspChannel.OUT_1, 10, 1_000, 0, 400),
				transport.reliablePayloads.get(0)
		);
		assertEquals(1, transport.payloads.size());
		assertArrayEquals(
				DspProtocol.buildInputGate(0, -40.0, 100.0, 10.0, 1_000.0),
				transport.payloads.get(0)
		);
	}

	private static final class RecordingTransport implements DspPayloadTransport {
		private final List<byte[]> payloads = new ArrayList<>();
		private final List<byte[]> reliablePayloads = new ArrayList<>();

		@Override
		public boolean send(byte[] payload) {
			payloads.add(payload);
			return true;
		}

		@Override
		public void sendReliable(byte[] payload) {
			reliablePayloads.add(payload);
		}
	}
}
