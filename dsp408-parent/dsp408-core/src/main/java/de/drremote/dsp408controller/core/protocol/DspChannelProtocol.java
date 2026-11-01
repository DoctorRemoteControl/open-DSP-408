package de.drremote.dsp408controller.core.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

import de.drremote.dsp408controller.core.codec.DspCodecRegistry;

/** Builds channel name, level, mute, phase, delay and matrix payloads. */
final class DspChannelProtocol {
	private static final byte CMD_SET_GAIN = 0x34;
	private static final byte CMD_SET_MUTE = 0x35;
	private static final byte CMD_SET_PHASE = 0x36;
	private static final byte CMD_SET_DELAY = 0x38;
	private static final byte CMD_SET_MATRIX_ROUTE = 0x3A;
	private static final byte CMD_SET_CHANNEL_NAME = 0x3D;
	private static final byte CMD_SET_MATRIX_CROSSPOINT_GAIN = 0x41;

	private DspChannelProtocol() {
	}

	static byte[] buildChannelName(DspChannel channel, String name) {
		if (channel == null) {
			throw new IllegalArgumentException("Channel is required");
		}
		if (name == null) {
			throw new IllegalArgumentException("Channel name is required");
		}

		byte[] ascii = name.getBytes(StandardCharsets.US_ASCII);
		if (ascii.length > 8) {
			throw new IllegalArgumentException("Channel name must be max 8 ASCII bytes");
		}
		for (byte value : ascii) {
			if ((value & 0x80) != 0) {
				throw new IllegalArgumentException("Channel name must be ASCII");
			}
		}

		byte[] payload = new byte[13];
		payload[0] = 0x00;
		payload[1] = 0x01;
		payload[2] = 0x0A;
		payload[3] = CMD_SET_CHANNEL_NAME;
		payload[4] = (byte) channel.index();
		System.arraycopy(ascii, 0, payload, 5, ascii.length);
		return payload;
	}

	static byte[] buildMute(int selector, boolean muted) {
		checkChannel(selector);
		return new byte[]{
				0x00,
				0x01,
				0x03,
				CMD_SET_MUTE,
				(byte) selector,
				(byte) (muted ? 0x01 : 0x00)
		};
	}

	static byte[] buildGain(int channelIndex, double db) {
		checkChannel(channelIndex);
		int raw = DspCodecRegistry.gain().doubleToRaw(db);
		return new byte[]{
				0x00,
				0x01,
				0x04,
				CMD_SET_GAIN,
				(byte) channelIndex,
				(byte) (raw & 0xFF),
				(byte) ((raw >>> 8) & 0xFF)
		};
	}

	static byte[] buildPhase(int channelIndex, boolean inverted) {
		checkChannel(channelIndex);
		return new byte[]{
				0x00,
				0x01,
				0x03,
				CMD_SET_PHASE,
				(byte) channelIndex,
				(byte) (inverted ? 0x01 : 0x00)
		};
	}

	static byte[] buildDelay(int channelIndex, double ms) {
		checkChannel(channelIndex);
		int raw = DspCodecRegistry.delayMs().doubleToRaw(ms);
		return new byte[]{
				0x00,
				0x01,
				0x04,
				CMD_SET_DELAY,
				(byte) channelIndex,
				(byte) (raw & 0xFF),
				(byte) ((raw >>> 8) & 0xFF)
		};
	}

	static byte[] buildMatrixRoute(DspChannel output, DspChannel input) {
		return buildMatrixRoutes(output, List.of(input));
	}

	static byte[] buildMatrixRoutes(DspChannel output, Collection<DspChannel> inputs) {
		checkOutputChannel(output);
		if (inputs == null) {
			throw new IllegalArgumentException("Matrix inputs are required");
		}

		int inputMask = 0;
		for (DspChannel input : inputs) {
			checkInputChannel(input);
			inputMask |= matrixInputMask(input);
		}

		return new byte[]{
				0x00,
				0x01,
				0x03,
				CMD_SET_MATRIX_ROUTE,
				(byte) output.index(),
				(byte) inputMask
		};
	}

	static byte[] buildMatrixCrosspointGain(DspChannel output, DspChannel input, double db) {
		checkOutputChannel(output);
		checkInputChannel(input);
		int raw = DspCodecRegistry.gain().doubleToRaw(Math.max(-60.0, Math.min(0.0, db)));
		return new byte[]{
				0x00,
				0x01,
				0x05,
				CMD_SET_MATRIX_CROSSPOINT_GAIN,
				(byte) output.index(),
				(byte) input.index(),
				(byte) (raw & 0xFF),
				(byte) ((raw >>> 8) & 0xFF)
		};
	}

	private static void checkChannel(int channelIndex) {
		if (channelIndex < 0 || channelIndex > 11) {
			throw new IllegalArgumentException("Channel must be in range 0..11");
		}
	}

	private static void checkOutputChannel(DspChannel channel) {
		if (channel == null || channel.index() < 4 || channel.index() > 11) {
			throw new IllegalArgumentException("Output channel required: Out1..Out8");
		}
	}

	private static void checkInputChannel(DspChannel channel) {
		if (channel == null || channel.index() < 0 || channel.index() > 3) {
			throw new IllegalArgumentException("Input channel required: InA..InD");
		}
	}

	private static int matrixInputMask(DspChannel input) {
		return switch (input) {
			case IN_A -> 0x01;
			case IN_B -> 0x02;
			case IN_C -> 0x04;
			case IN_D -> 0x08;
			default -> throw new IllegalArgumentException("Input channel required: InA..InD");
		};
	}
}
