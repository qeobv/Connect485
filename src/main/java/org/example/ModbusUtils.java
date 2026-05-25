package org.example;

/**
 * Modbus 通讯协议解析工具类
 */
public class ModbusUtils {

    private static int lastRequestAddress = 6;

    public static void setLastRequestAddress(int address) {
        lastRequestAddress = address;
    }

    public static int getLastRequestAddress() {
        return lastRequestAddress;
    }

    /**
     * 解析 Modbus RTU 完整响应帧
     */
    public static String parseModbusFrame(byte[] frame) {
        if (frame == null || frame.length < 3) return "数据长度过短，无法解析";

        int funcCode = frame[1] & 0xFF;

        if (funcCode > 0x80) {
            int errCode = frame[2] & 0xFF;
            return "Modbus异常响应，错误码: 0x" + String.format("%02X", errCode);
        }

        if (funcCode == 0x03) {
            int byteCount = frame[2] & 0xFF;
            if (frame.length < 3 + byteCount) {
                return String.format("数据长度不完整：期望 %d 字节，实际 %d 字节", 3 + byteCount, frame.length);
            }

            int startAddress = lastRequestAddress;
            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < byteCount; i += 2) {
                int highWord = (frame[3 + i] & 0xFF) << 8;
                int lowWord = frame[3 + i + 1] & 0xFF;
                int rawValue = highWord | lowWord;

                int currentAddress = startAddress + (i / 2);

                // === 核心修改：针对每个寄存器，遍历其16个位进行翻译 ===
                boolean hasBitDefinition = false;
                for (int bit = 0; bit < 16; bit++) {
                    // 尝试用 地址+位偏移 去字典里查找
                    String bitTranslation = ModbusProtocolMap.translate(currentAddress, bit, rawValue);
                    // 如果返回的不是"未知寄存器"，说明字典里有这个位的定义
                    if (!bitTranslation.startsWith("未知寄存器")) {
                        sb.append("  - ").append(bitTranslation).append("\n");
                        hasBitDefinition = true;
                    }
                }

                // 如果该地址没有位定义，则按整体数值(ENUM/INT)翻译一次
                if (!hasBitDefinition) {
                    String intTranslation = ModbusProtocolMap.translate(currentAddress, 0, rawValue);
                    sb.append("  - ").append(intTranslation).append("\n");
                }
            }
            return sb.toString().trim();
        }

        return null;
    }

    public static void parseSendDataToUpdateAddress(byte[] sendData) {
        if (sendData != null && sendData.length >= 6 && (sendData[1] & 0xFF) == 0x03) {
            int high = (sendData[2] & 0xFF) << 8;
            int low = sendData[3] & 0xFF;
            setLastRequestAddress(high | low);
        }
    }
}
