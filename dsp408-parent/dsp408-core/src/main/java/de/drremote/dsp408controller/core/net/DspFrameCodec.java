package de.drremote.dsp408controller.core.net;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PushbackInputStream;

public final class DspFrameCodec {
    private static final int DLE = 0x10;
    private static final int STX = 0x02;
    private static final int ETX = 0x03;
    private static final int PAYLOAD_HEADER_LENGTH = 3;

    private DspFrameCodec() {
    }

    public static byte[] encode(byte[] payload) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(DLE);
        body.write(STX);
        body.writeBytes(payload);
        body.write(DLE);
        body.write(ETX);
        byte[] withoutChecksum = body.toByteArray();
        int checksum = xor(withoutChecksum);

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.writeBytes(withoutChecksum);
        frame.write(checksum & 0xFF);
        return frame.toByteArray();
    }

    public static DecodedFrame readFrame(PushbackInputStream in) throws IOException {
        findFrameStart(in);

        byte[] header = readExactly(in, PAYLOAD_HEADER_LENGTH, "payload header");
        int payloadLength = PAYLOAD_HEADER_LENGTH + (header[2] & 0xFF);
        byte[] payload = new byte[payloadLength];
        System.arraycopy(header, 0, payload, 0, header.length);
        if (payloadLength > header.length) {
            byte[] remainder = readExactly(in, payloadLength - header.length, "payload");
            System.arraycopy(remainder, 0, payload, header.length, remainder.length);
        }

        int trailerDle = readRequired(in, "frame trailer DLE");
        int trailerEtx = readRequired(in, "frame trailer ETX");
        if (trailerDle != DLE || trailerEtx != ETX) {
            throw new IOException(
                    "Invalid frame trailer: expected 10 03, got "
                            + String.format("%02X %02X", trailerDle, trailerEtx)
            );
        }
        int receivedChecksum = readRequired(in, "checksum byte");

        ByteArrayOutputStream withoutChecksum = new ByteArrayOutputStream();
        withoutChecksum.write(DLE);
        withoutChecksum.write(STX);
        withoutChecksum.writeBytes(payload);
        withoutChecksum.write(DLE);
        withoutChecksum.write(ETX);
        int calculated = xor(withoutChecksum.toByteArray());

        return new DecodedFrame(
                payload,
                calculated == receivedChecksum,
                calculated,
                receivedChecksum
        );
    }

    private static void findFrameStart(PushbackInputStream in) throws IOException {
        while (true) {
            int current = in.read();
            if (current < 0) {
                throw new EOFException("EOF before frame start");
            }
            if (current != DLE) {
                continue;
            }
            int next = readRequired(in, "byte after DLE");
            if (next == STX) {
                return;
            }
            in.unread(next);
        }
    }

    private static byte[] readExactly(PushbackInputStream in, int length, String part) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = in.read(data, offset, length - offset);
            if (count < 0) {
                throw new EOFException("EOF while reading " + part);
            }
            offset += count;
        }
        return data;
    }

    private static int readRequired(PushbackInputStream in, String part) throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new EOFException("EOF while reading " + part);
        }
        return value;
    }

    private static int xor(byte[] bytes) {
        int checksum = 0;
        for (byte value : bytes) {
            checksum ^= value & 0xFF;
        }
        return checksum;
    }

    public record DecodedFrame(
            byte[] payload,
            boolean checksumValid,
            int calculatedChecksum,
            int receivedChecksum
    ) {
    }
}
