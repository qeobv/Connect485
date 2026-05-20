package org.example;

/**
 * CRC16校验工具类
 * 提供CRC16校验值的计算和验证功能
 */
public class Crc16Util {
    /**
     * 计算给定数据的CRC16校验值
     * @param data 待计算的数据字节数组
     * @param offset 数据起始位置
     * @param length 数据长度
     * @return 计算得到的CRC16校验值
     */
    public static int calculateCRC16(byte[] data, int offset, int length) {
        int crc = 0xFFFF; // 初始化CRC值为0xFFFF，这是CRC16算法的初始值
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF); // 将当前字节与CRC值进行异或操作，将数据字节整合到CRC计算中
            for (int j = 0; j < 8; j++) { // 对每个字节的8位进行处理，这是CRC16算法的核心循环
                if ((crc & 0x0001) != 0) { crc >>= 1; crc ^= 0xA001; }
                else { crc >>= 1; }
            }
        }
        return crc;
    }

/**
 * 验证数据的CRC16校验值是否正确
 * @param data 包含数据和CRC校验值的字节数组
 * @param offset 数据起始位置偏移量
 * @param length 数据总长度（包含CRC校验值）
 * @return 如果校验值正确返回true，否则返回false
 */
    public static boolean verifyCRC16(byte[] data, int offset, int length) {
        // 如果数据长度小于2字节（CRC校验值本身长度），直接返回false
        if (length < 2) return false;
        // 计算实际数据长度（总长度减去CRC校验值的2个字节）
        int payloadLength = length - 2;
        // 计算数据的CRC16校验值
        int calculatedCrc = calculateCRC16(data, offset, payloadLength);
        // 从数据中提取接收到的CRC校验值（最后2个字节）
        int receivedCrc = (data[offset + length - 1] & 0xFF) << 8 | (data[offset + length - 2] & 0xFF);
        // 比较计算得到的CRC校验值和接收到的CRC校验值是否一致
        return calculatedCrc == receivedCrc;
    }
}
