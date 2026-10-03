package org.example;

/**
 * Modbus 通讯协议解析工具类
 *
 * 职责：
 *   1. 异常响应判断（funcCode > 0x80）
 *   2. 0x03 功能码的寄存器位翻译（依赖 lastRequestAddress）
 *
 * 注意：0x64 / 0x66 的翻译由 ProtocolTranslationManager 统一分发，
 *       本类不再处理。
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
     * 解析 Modbus RTU 帧。
     * 只处理异常码和 0x03 功能码；其它功能码返回 null。
     */
    public static String parseModbusFrame(byte[] frame) {
        if (frame == null || frame.length < 3) return "数据长度过短，无法解析";

        int funcCode = frame[1] & 0xFF;

        // 异常响应
        if (funcCode > 0x80) {
            int errCode = frame[2] & 0xFF;
            return "Modbus异常响应，错误码: 0x" + String.format("%02X", errCode);
        }

        // 0x03：寄存器位翻译
        if (funcCode == 0x03) {
            int byteCount = frame[2] & 0xFF;
            if (frame.length < 3 + byteCount) {
                return String.format("数据长度不完整：期望 %d 字节，实际 %d 字节",
                        3 + byteCount, frame.length);
            }

            int startAddress = lastRequestAddress;
            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < byteCount; i += 2) {
                int highWord = (frame[3 + i] & 0xFF) << 8;
                int lowWord = (frame[3 + i + 1] & 0xFF);
                int rawValue = highWord | lowWord;

                int currentAddress = startAddress + (i / 2);

                boolean hasBitDefinition = false;
                for (int bit = 0; bit < 16; bit++) {
                    String bitTranslation = ModbusProtocolMap.translate(currentAddress, bit, rawValue);
                    if (!bitTranslation.startsWith("未知寄存器")) {
                        sb.append("  - ").append(bitTranslation).append("\n");
                        hasBitDefinition = true;
                    }
                }

                if (!hasBitDefinition) {
                    String intTranslation = ModbusProtocolMap.translate(currentAddress, 0, rawValue);
                    sb.append("  - ").append(intTranslation).append("\n");
                }
            }
            return sb.toString().trim();
        }

        // 其它功能码不再处理
        return null;
    }

    /** 发送 0x03 请求时，记录起始地址，供响应解析用 */
    public static void parseSendDataToUpdateAddress(byte[] sendData) {
        if (sendData != null && sendData.length >= 6 && (sendData[1] & 0xFF) == 0x03) {
            int high = (sendData[2] & 0xFF) << 8;
            int low = sendData[3] & 0xFF;
            setLastRequestAddress(high | low);
        }
    }
}