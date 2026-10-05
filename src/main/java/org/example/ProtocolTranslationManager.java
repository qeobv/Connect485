package org.example;

/**
 * 协议转换管理器：唯一翻译入口。
 * 内部自动清洗输入（去时间戳前缀、去空格），按功能码分发。
 *
 * 支持的功能码：
 *   0x64 → DeviceProtocolTranslator.translateFullPacket
 *   0x66 → DeviceProtocolTranslator.translatePartPacket
 *   0x03 → ModbusUtils.parseModbusFrame
 */
public class ProtocolTranslationManager {

    private static final ProtocolTranslationManager instance = new ProtocolTranslationManager();

    private ProtocolTranslationManager() {}

    public static ProtocolTranslationManager getInstance() {
        return instance;
    }

    /**
     * 翻译入口。入参支持：
     *   1. 纯 hex
     *   2. 带空格 hex
     *   3. 带时间戳前缀（发送/接收）
     *
     * 返回 null 表示「不需要翻译」或「无法翻译」：
     *   - 裸碎片（不含 "接收]:" / "发送]:"）
     *   - 长度不足 10 个 hex 字符
     *   - 0x64 / 0x66 的请求帧（byteCount == 0）
     */
    public String translate(String raw) {
        if (raw == null) return null;

        boolean isCompleteFrame = raw.contains("接收]:") || raw.contains("发送]:");
        if (!isCompleteFrame) return null;

        String pureHex = extractPureHex(raw);
        if (pureHex == null || pureHex.length() < 10) return null;

        String funcCode = pureHex.substring(2, 4).toUpperCase();

        if ("64".equals(funcCode) || "66".equals(funcCode)) {
            int byteCount = Integer.parseInt(pureHex.substring(4, 6), 16);
            if (byteCount == 0) return null;
        }

        switch (funcCode) {
            case "64": return translateFull(pureHex);
            case "66": return translatePart(pureHex);
            case "03": return ModbusUtils.parseModbusFrame(hexToBytes(pureHex));
            default:   return null;   // ★ 不返回「不支持」
        }
    }

    /** 把任意格式输入清洗成大写纯 hex；无法提取时返回 null */
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