package org.example;

/**
 * 协议转换管理器：唯一翻译入口。
 * 内部自动清洗输入（去时间戳前缀、去空格），按功能码分发。
 *
 * 支持的功能码：
 *   0x64 → DeviceProtocolTranslator.translateFullPacket
 *   0x66 → DeviceProtocolTranslator.translatePartPacket
 *   0x03 → ModbusUtils.parseModbusFrame（寄存器位翻译）
 */
public class ProtocolTranslationManager {

    private static final ProtocolTranslationManager instance = new ProtocolTranslationManager();

    private ProtocolTranslationManager() {}

    public static ProtocolTranslationManager getInstance() {
        return instance;
    }

    /**
     * 翻译入口。入参支持：
     *   1. 纯 hex：           "016400000000"
     *   2. 带空格 hex：       "01 64 00 00 00 00"
     *   3. 带时间戳前缀：     "[12:34:56.789 发送]: 01 64 00 00 00 00"
     *   4. 带时间戳前缀+空格："[12:34:56.789 接收]: 01 64 00 00 00 00"
     */
    public String translate(String raw) {
        String pureHex = extractPureHex(raw);
        if (pureHex == null || pureHex.length() < 4) {
            return "数据格式错误";
        }

        String funcCode = pureHex.substring(2, 4).toUpperCase();

        switch (funcCode) {
            case "64":
                return translateFull(pureHex);
            case "66":
                return translatePart(pureHex);
            case "03":
                return ModbusUtils.parseModbusFrame(hexToBytes(pureHex));
            default:
                return "不支持的功能码: " + funcCode;
        }
    }

    /** 把任意格式的输入清洗成大写纯 hex；无法提取时返回 null */
    public String extractPureHex(String raw) {
        if (raw == null) return null;

        int colonIdx = raw.indexOf("]:");
        String body = (colonIdx >= 0) ? raw.substring(colonIdx + 2) : raw;

        String hex = body.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        return hex.isEmpty() ? null : hex;
    }

    public String translateFull(String hexData) {
        return DeviceProtocolTranslator.translateFullPacket(hexData);
    }

    public String translatePart(String hexData) {
        return DeviceProtocolTranslator.translatePartPacket(hexData);
    }

    /** hex 字符串 → byte[] */
    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}