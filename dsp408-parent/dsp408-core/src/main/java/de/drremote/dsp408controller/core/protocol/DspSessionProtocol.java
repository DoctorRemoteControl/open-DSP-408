package de.drremote.dsp408controller.core.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Builds and decodes connection, discovery, block-read, preset and login payloads. */
final class DspSessionProtocol {
	private static final byte CMD_DELAY_UNIT = 0x15;
	private static final byte CMD_PRESET_LOAD = 0x20;
	private static final byte CMD_PRESET_STORE = 0x21;
	private static final byte CMD_PRESET_NAME = 0x26;
	private static final byte CMD_READ_PRESET_NAME = 0x29;
	private static final byte CMD_HANDSHAKE_INIT = 0x10;
	private static final byte CMD_HANDSHAKE_ACK = 0x13;
	private static final byte CMD_STARTUP_STATUS = 0x14;
	private static final byte CMD_STARTUP_ZERO_BLOCK = 0x22;
	private static final byte CMD_SYSTEM_INFO = 0x2C;
	private static final byte CMD_KEEPALIVE = 0x12;
	private static final byte CMD_LOGIN = 0x2D;
	private static final byte CMD_RUNTIME_METERS = 0x40;
	private static final byte CMD_READ_FIR_STARTUP_WINDOW = 0x56;
	private static final byte CMD_READ_PARAMETER_BLOCK = 0x27;

	private DspSessionProtocol() {
	}

	static byte[] handshakeInit() {
		return new byte[]{0x00, 0x01, 0x01, CMD_HANDSHAKE_INIT};
	}

	static byte[] handshakeAck() {
		return new byte[]{0x00, 0x01, 0x01, CMD_HANDSHAKE_ACK};
	}

	static byte[] systemInfoRequest() {
		return new byte[]{0x00, 0x01, 0x01, CMD_SYSTEM_INFO};
	}

	static byte[] startupZeroBlockRequest() {
		return new byte[]{0x00, 0x01, 0x01, CMD_STARTUP_ZERO_BLOCK};
	}

	static byte[] startupStatusRequest() {
		return new byte[]{0x00, 0x01, 0x01, CMD_STARTUP_STATUS};
	}

	static byte[] readFirStartupWindow(int offset) {
		if (offset < 0 || offset > 0xFFFF) {
			throw new IllegalArgumentException("FIR startup offset must be in range 0..65535");
		}
		return new byte[]{
				0x00,
				0x01,
				0x04,
				CMD_READ_FIR_STARTUP_WINDOW,
				0x00,
				(byte) (offset & 0xFF),
				(byte) ((offset >>> 8) & 0xFF)
		};
	}

	static byte[] keepAlive() {
		return new byte[]{0x00, 0x01, 0x01, CMD_KEEPALIVE};
	}

	static boolean isKeepAliveRequest(byte[] payload) {
		return payload != null
				&& payload.length == 4
				&& (payload[3] & 0xFF) == CMD_KEEPALIVE;
	}

	static byte[] runtimeMeterRequest() {
		return new byte[]{0x00, 0x01, 0x01, CMD_RUNTIME_METERS};
	}

	static boolean isRuntimeMeterRequest(byte[] payload) {
		return payload != null
				&& payload.length == 4
				&& (payload[0] & 0xFF) == 0x00
				&& (payload[1] & 0xFF) == 0x01
				&& (payload[2] & 0xFF) == 0x01
				&& (payload[3] & 0xFF) == (CMD_RUNTIME_METERS & 0xFF);
	}

	static boolean isHandshakeInitReply(byte[] payload) {
		return isReplyFor(payload, CMD_HANDSHAKE_INIT);
	}

	static boolean isHandshakeAckReply(byte[] payload) {
		return isReplyFor(payload, CMD_HANDSHAKE_ACK);
	}

	static boolean isSystemInfoReply(byte[] payload) {
		return isReplyFor(payload, CMD_SYSTEM_INFO);
	}

	static boolean isRuntimeMeterResponse(byte[] payload) {
		return payload != null
				&& payload.length >= 42
				&& (payload[0] & 0xFF) == 0x01
				&& (payload[3] & 0xFF) == (CMD_RUNTIME_METERS & 0xFF);
	}

	static boolean isBlockResponse(byte[] payload) {
		return payload != null
				&& payload.length >= 5
				&& (payload[3] & 0xFF) == 0x24;
	}

	static int blockResponseIndex(byte[] payload) {
		if (!isBlockResponse(payload)) {
			throw new IllegalArgumentException("Not a block response");
		}
		return payload[4] & 0xFF;
	}

	static byte[] blockResponseData(byte[] payload) {
		if (!isBlockResponse(payload)) {
			throw new IllegalArgumentException("Not a block response");
		}
		return Arrays.copyOfRange(payload, 5, payload.length);
	}

	static byte[] readParameterBlock(int blockIndex) {
		if (blockIndex < 0x00 || blockIndex > 0x1F) {
			throw new IllegalArgumentException("Block index must be in range 0x00..0x1F");
		}
		return new byte[]{0x00, 0x01, 0x02, CMD_READ_PARAMETER_BLOCK, (byte) blockIndex};
	}

	static byte[] readPresetName(int presetIndex) {
		checkPresetIndex(presetIndex);
		return new byte[]{0x00, 0x01, 0x02, CMD_READ_PRESET_NAME, (byte) presetIndex};
	}

	static byte[] loadPreset(int presetIndex) {
		checkPresetIndex(presetIndex);
		return new byte[]{0x00, 0x01, 0x02, CMD_PRESET_LOAD, (byte) presetIndex};
	}

	static byte[] loadPreset(String slotLabel) {
		return loadPreset(parsePresetSlot(slotLabel));
	}

	static byte[] storePreset(int presetIndex) {
		checkUserPresetIndex(presetIndex);
		return new byte[]{0x00, 0x01, 0x02, CMD_PRESET_STORE, (byte) presetIndex};
	}

	static byte[] storePreset(String slotLabel) {
		return storePreset(parsePresetSlot(slotLabel));
	}

	static byte[] buildPresetName(String name) {
		byte[] ascii = presetNameBytes(name);
		byte[] payload = new byte[18];
		payload[0] = 0x00;
		payload[1] = 0x01;
		payload[2] = 0x0F;
		payload[3] = CMD_PRESET_NAME;
		Arrays.fill(payload, 4, payload.length, (byte) 0x20);
		System.arraycopy(ascii, 0, payload, 4, ascii.length);
		return payload;
	}

	static boolean isPresetNameResponse(byte[] payload) {
		return payload != null
				&& payload.length >= 5
				&& (payload[0] & 0xFF) == 0x01
				&& (payload[3] & 0xFF) == (CMD_READ_PRESET_NAME & 0xFF);
	}

	static int presetNameResponseIndex(byte[] payload) {
		if (!isPresetNameResponse(payload)) {
			throw new IllegalArgumentException("Not a preset-name response");
		}
		return payload[4] & 0xFF;
	}

	static String presetNameResponseName(byte[] payload) {
		if (!isPresetNameResponse(payload)) {
			throw new IllegalArgumentException("Not a preset-name response");
		}
		int end = payload.length;
		while (end > 5 && (payload[end - 1] == 0x00 || payload[end - 1] == 0x20)) {
			end--;
		}
		return new String(payload, 5, end - 5, StandardCharsets.US_ASCII);
	}

	static int parsePresetSlot(String slotLabel) {
		if (slotLabel == null || slotLabel.isBlank()) {
			throw new IllegalArgumentException("Preset slot is required");
		}

		String normalized = slotLabel.trim().toUpperCase(Locale.ROOT);
		if ("F00".equals(normalized)) {
			return 0;
		}
		if (normalized.matches("U\\d{2}")) {
			int userIndex = Integer.parseInt(normalized.substring(1));
			if (userIndex < 1 || userIndex > 19) {
				throw new IllegalArgumentException("User preset must be U01..U19");
			}
			return userIndex;
		}
		throw new IllegalArgumentException("Preset slot must be F00 or U01..U19");
	}

	static byte[] login(String pin) {
		if (pin == null || !pin.matches("\\d{4}")) {
			throw new IllegalArgumentException("PIN must be exactly 4 digits");
		}

		byte[] ascii = pin.getBytes(StandardCharsets.US_ASCII);
		byte[] payload = new byte[5 + ascii.length];
		payload[0] = 0x00;
		payload[1] = 0x01;
		payload[2] = 0x06;
		payload[3] = CMD_LOGIN;
		payload[4] = 0x00;
		System.arraycopy(ascii, 0, payload, 5, ascii.length);
		return payload;
	}

	static byte[] buildDelayUnit(int unitRaw) {
		checkDelayUnit(unitRaw);
		return new byte[]{0x00, 0x01, 0x02, CMD_DELAY_UNIT, (byte) unitRaw};
	}

	static byte[] buildDelayUnit(String unit) {
		return buildDelayUnit(parseDelayUnit(unit));
	}

	private static boolean isReplyFor(byte[] payload, int command) {
		return payload != null
				&& payload.length >= 4
				&& (payload[3] & 0xFF) == (command & 0xFF);
	}

	private static int parseDelayUnit(String unit) {
		if (unit == null || unit.isBlank()) {
			throw new IllegalArgumentException("Delay unit is required");
		}
		return switch (normalize(unit)) {
			case "ms" -> 0;
			case "m", "meter", "meters" -> 1;
			case "ft", "feet" -> 2;
			default -> throw new IllegalArgumentException("Delay unit must be ms|m|ft");
		};
	}

	private static String normalize(String value) {
		return value.trim()
				.toLowerCase(Locale.ROOT)
				.replace("_", "")
				.replace("-", "")
				.replace(" ", "");
	}

	private static void checkPresetIndex(int presetIndex) {
		if (presetIndex < 0 || presetIndex > 19) {
			throw new IllegalArgumentException("Preset index must be in range 0..19");
		}
	}

	private static void checkUserPresetIndex(int presetIndex) {
		if (presetIndex < 1 || presetIndex > 19) {
			throw new IllegalArgumentException("Writable preset index must be in range 1..19");
		}
	}

	private static byte[] presetNameBytes(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Preset name is required");
		}
		String trimmed = name.trim();
		if (trimmed.length() > 14) {
			throw new IllegalArgumentException("Preset name must contain at most 14 ASCII characters");
		}
		for (int index = 0; index < trimmed.length(); index++) {
			char value = trimmed.charAt(index);
			if (value < 0x20 || value > 0x7E) {
				throw new IllegalArgumentException("Preset name must contain printable ASCII characters only");
			}
		}
		return trimmed.getBytes(StandardCharsets.US_ASCII);
	}

	private static void checkDelayUnit(int unitRaw) {
		if (unitRaw < 0 || unitRaw > 2) {
			throw new IllegalArgumentException("Delay unit must be 0=ms, 1=m, 2=ft");
		}
	}
}
