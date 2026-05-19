package org.example;

public class Crc16Util {
    public static int calculateCRC16(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF);
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x0001) != 0) { crc >>= 1; crc ^= 0xA001; }
                else { crc >>= 1; }
            }
        }
        return crc;
    }

    public static boolean verifyCRC16(byte[] data, int offset, int length) {
        if (length < 2) return false;
        int payloadLength = length - 2;
        int calculatedCrc = calculateCRC16(data, offset, payloadLength);
        int receivedCrc = (data[offset + length - 1] & 0xFF) << 8 | (data[offset + length - 2] & 0xFF);
        return calculatedCrc == receivedCrc;
    }
}
