package de.drremote.dsp408controller.core.net;

import de.drremote.dsp408controller.core.protocol.DspProtocol;

/** Pure command classification and response-correlation rules for one DSP transaction. */
final class DspCommandPolicy {
	private static final long DEFAULT_RESPONSE_TIMEOUT_MS = 500L;
	private static final long READ_RESPONSE_TIMEOUT_MS = 1_000L;
	private static final long METER_RESPONSE_TIMEOUT_MS = 150L;
	private static final long FIR_GENERATOR_RESPONSE_TIMEOUT_MS = 3_000L;
	private static final long FIR_TRANSFER_RESPONSE_TIMEOUT_MS = 1_000L;
	private static final long PRESET_RECALL_RESPONSE_TIMEOUT_MS = 6_000L;
	// Store may remain silent for approximately eight seconds while writing flash.
	private static final long PRESET_STORE_RESPONSE_TIMEOUT_MS = 10_000L;

	private DspCommandPolicy() {
	}

	static DspCommandQueue.Priority priorityOf(byte[] payload) {
		if (DspProtocol.isRuntimeMeterRequest(payload)) {
			return DspCommandQueue.Priority.METER;
		}
		int command = commandByte(payload);
		if (command == 0x35 || command == 0x3F) {
			return DspCommandQueue.Priority.SAFETY;
		}
		if (command == 0x27) {
			return DspCommandQueue.Priority.READ;
		}
		if (command == 0x12) {
			return DspCommandQueue.Priority.KEEPALIVE;
		}
		return DspCommandQueue.Priority.CONTROL;
	}

	static String coalesceKey(byte[] payload) {
		if (DspProtocol.isRuntimeMeterRequest(payload)) {
			return "meter";
		}
		int command = commandByte(payload);
		if (command == 0x12) {
			return "keepalive";
		}
		if (command == 0x27 && payload.length > 4) {
			return "block:" + (payload[4] & 0xFF);
		}
		if ((command == 0x34
				|| command == 0x35
				|| command == 0x36
				|| command == 0x38
				|| command == 0x3F)
				&& payload.length > 4) {
			return "command:" + command + ":channel:" + (payload[4] & 0xFF);
		}
		return null;
	}

	static int commandByte(byte[] payload) {
		return payload != null && payload.length > 3 ? payload[3] & 0xFF : -1;
	}

	static long responseTimeoutMs(byte[] request) {
		if (isIdempotentReadRequest(request)) return READ_RESPONSE_TIMEOUT_MS;
		return switch (commandByte(request)) {
			case 0x20 -> PRESET_RECALL_RESPONSE_TIMEOUT_MS;
			case 0x21 -> PRESET_STORE_RESPONSE_TIMEOUT_MS;
			case 0x4B -> FIR_GENERATOR_RESPONSE_TIMEOUT_MS;
			case 0x4E, 0x4F -> FIR_TRANSFER_RESPONSE_TIMEOUT_MS;
			case 0x40 -> METER_RESPONSE_TIMEOUT_MS;
			default -> DEFAULT_RESPONSE_TIMEOUT_MS;
		};
	}

	static boolean responseMatches(byte[] request, byte[] response) {
		if (request == null || response == null) {
			return false;
		}
		int requestCommand = commandByte(request);
		int responseCommand = commandByte(response);
		return switch (requestCommand) {
			case 0x10, 0x13, 0x14, 0x22, 0x2C, 0x40, 0x56 ->
					responseCommand == requestCommand;
			case 0x27 -> responseCommand == 0x24 && selectorMatches(request, response);
			case 0x29 -> responseCommand == 0x29 && selectorMatches(request, response);
			default -> DspProtocol.isAck(response);
		};
	}

	static boolean expectsAck(byte[] request) {
		return switch (commandByte(request)) {
			case 0x10, 0x13, 0x14, 0x22, 0x27, 0x29, 0x2C, 0x40, 0x56 -> false;
			default -> true;
		};
	}

	static boolean isIdempotentReadRequest(byte[] request) {
		return switch (commandByte(request)) {
			case 0x13, 0x14, 0x22, 0x27, 0x29, 0x2C, 0x56 -> true;
			default -> false;
		};
	}

	private static boolean selectorMatches(byte[] request, byte[] response) {
		return request.length <= 4
				|| response.length > 4 && request[4] == response[4];
	}
}
