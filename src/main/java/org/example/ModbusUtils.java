package org.example;

/**
 * Modbus 通讯协议解析工具类
 */
public class ModbusUtils {

    // 记录最后一次发送的读取请求的起始地址
    private static int lastRequestAddress = 6; // 默认首站地址6 (对应40006)

    /**
     * 更新最后请求的起始地址（在发送读取指令时调用）
     */
    public static void setLastRequestAddress(int address) {
        lastRequestAddress = address;
    }

    public static int getLastRequestAddress() {
        return lastRequestAddress;
    }

    /**
     * 解析 Modbus RTU 完整响应帧
     * @param frame 接收到的原始字节数组
     * @return 格式化后的翻译文本，如果非标准帧则返回 null
     */
    public static String parseModbusFrame(byte[] frame) {
        if (frame == null || frame.length < 5) return null;

        int funcCode = frame[1] & 0xFF;

        // 处理异常响应
        if (funcCode > 0x80) {
            int errCode = frame[2] & 0xFF;
            return "Modbus异常响应，错误码: 0x" + String.format("%02X", errCode);
        }

        // 处理读取保持寄存器(0x03)的正常响应
        if (funcCode == 0x03) {
            int byteCount = frame[2] & 0xFF;
            if (frame.length < 3 + byteCount + 2) return "数据长度不完整";

            int startAddress = lastRequestAddress;
            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < byteCount; i += 2) {
                if (3 + i + 1 < frame.length - 1) {
                    int highWord = (frame[3 + i] & 0xFF) << 8;
                    int lowWord = frame[3 + i + 1] & 0xFF;
                    int rawValue = highWord | lowWord;

                    int currentAddress = startAddress + (i / 2);
                    String translation = ModbusProtocolMap.translate(currentAddress, rawValue);
                    sb.append("  - ").append(translation).append("\n");
                }
            }
            return sb.toString().trim();
        }

        // 其他功能码暂不翻译
        return null;
    }

    /**
     * 解析发送的指令，提取起始地址并更新状态
     * @param sendData 发送的指令字节数组
     */
    public static void parseSendDataToUpdateAddress(byte[] sendData) {
        if (sendData != null && sendData.length >= 6 && (sendData[1] & 0xFF) == 0x03) {
            // Modbus 0x03 请求帧格式: [从站地址][0x03][起始地址高][起始地址低][数量高][数量低][CRC]
            int high = (sendData[2] & 0xFF) << 8;
            int low = sendData[3] & 0xFF;
            setLastRequestAddress(high | low);
        }
    }
}

