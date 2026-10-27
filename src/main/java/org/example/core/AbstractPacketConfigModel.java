package org.example.core;

import org.example.Crc16Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 整包"读-改-写"式配置模型的通用引擎（0x64 读 / 0x65 写）。
 *
 * 帧结构（各系列仅长度不同，功能码与子包标记格式一致）：
 *   索引 0~2   : 从站地址 + 功能码 0x64 + 数据长度
 *   索引 3~4   : 子包标记 (01 <basicLen>) 基础设置
 *   基础设置数据区（含 2 字节子包 CRC）
 *   子包标记 (02 <alarmLen>) 继电器输出设置 + 数据区（含 CRC）
 *   子包标记 (03 <superLen>) 高级设置 + 数据区（含 CRC）
 *   最后 2 字节 : Modbus 整包 CRC
 *
 * 子类只需提供帧结构参数与字段定义表；编解码、CRC 重算、功能码切换由本类完成。
 */
public abstract class AbstractPacketConfigModel implements ConfigModel {

    // ================= 帧结构参数（子类提供） =================
    protected abstract int frameLength();          // 完整帧字节数（含整包 CRC）
    protected abstract int basicBase();            // 基础设置数据区起始偏移（通常 5）
    protected abstract int basicPayloadLen();      // 基础设置数据区长度（不含子包 CRC）
    protected abstract int basicCrcOffset();       // 基础设置子包 CRC 位置（高字节在前）
    protected abstract int alarmBase();
    protected abstract int alarmPayloadLen();
    protected abstract int alarmCrcOffset();
    protected abstract int superBase();
    protected abstract int superPayloadLen();
    protected abstract int superCrcOffset();

    /** 字段定义表（按显示顺序） */
    protected abstract List<ConfigFieldDef> fields();

    /** 最近一次成功 decode 的完整配置帧 */
    private volatile byte[] lastConfigFrame;

    // ================= 接口实现 =================
    @Override
    public boolean isConfigFrame(byte[] frame) {
        return frame != null && frame.length >= 3 && (frame[1] & 0xFF) == 0x64;
    }

    @Override
    public synchronized List<ConfigFieldValue> decode(byte[] frame) {
        if (!isConfigFrame(frame)) return null;
        if (frame.length < frameLength()) return null;

        byte[] snapshot = frame.clone();
        lastConfigFrame = snapshot;

        List<ConfigFieldValue> values = new ArrayList<>();
        for (ConfigFieldDef f : fields()) {
            long raw = readRaw(snapshot, f.offset, f.length);
            values.add(new ConfigFieldValue(f, formatValue(f, snapshot, raw), raw));
        }
        return values;
    }

    @Override
    public synchronized ConfigEdit encodeField(ConfigFieldDef field, String input) {
        if (field == null || input == null) return null;
        try {
            switch (field.type) {
                case BYTE: {
                    int v = parseInteger(stripUnit(input.trim(), field));
                    if (v < 0 || v > 255) return null;
                    return new ConfigEdit(field, new byte[]{(byte) v}, withUnit(String.valueOf(v), field));
                }
                case BYTES2: {
                    int v = parseInteger(stripUnit(input.trim(), field));
                    if (v < 0 || v > 0xFFFF) return null;
                    return new ConfigEdit(field, twoBytes(v), withUnit(String.valueOf(v), field));
                }
                case ENUM: {
                    String sel = input.trim();
                    for (Map.Entry<Integer, String> e : field.enumMap.entrySet()) {
                        if (e.getValue().equals(sel)) {
                            int code = e.getKey();
                            return new ConfigEdit(field, new byte[]{(byte) code}, sel);
                        }
                    }
                    // 也允许直接输入数字编码
                    int code = parseInteger(sel);
                    String desc = field.enumMap.get(code);
                    return desc == null ? null
                            : new ConfigEdit(field, new byte[]{(byte) code}, desc);
                }
                case SCALED_BYTE: {
                    double d = Double.parseDouble(stripUnit(input.trim(), field));
                    int raw = (int) Math.round(d * field.scale);
                    if (raw < 0 || raw > 255) return null;
                    return new ConfigEdit(field, new byte[]{(byte) raw},
                            withUnit(formatDecimal(raw, (int) field.scale), field));
                }
                case SCALED_BYTES2: {
                    double d = Double.parseDouble(stripUnit(input.trim(), field));
                    int raw = (int) Math.round(d * field.scale);
                    if (raw < 0 || raw > 0xFFFF) return null;
                    return new ConfigEdit(field, twoBytes(raw),
                            withUnit(formatDecimal(raw, (int) field.scale), field));
                }
                case CURRENT_A: {
                    String s = input.trim();
                    if (s.endsWith("A") || s.endsWith("a")) {
                        s = s.substring(0, s.length() - 1).trim();
                    }
                    String[] parts = s.split("\\.");
                    if (parts.length != 2) return null;
                    int intPart = Integer.parseInt(parts[0]);
                    int fracPart = Integer.parseInt(parts[1]);
                    if (intPart < 0 || fracPart < 0 || fracPart > 99) return null;
                    int raw = intPart * 100 + fracPart;
                    if (raw < 0 || raw > 0xFFFF) return null;
                    String display = String.format("%02d.%02dA", intPart, fracPart);
                    return new ConfigEdit(field, twoBytes(raw), display);
                }
                case SCALED_BYTE1: {
                    double d = Double.parseDouble(stripUnit(input.trim(), field));
                    int raw = (int) Math.round(d * field.scale);
                    if (raw < 0 || raw > 255) return null;
                    return new ConfigEdit(field, new byte[]{(byte) raw},
                            withUnit(String.format("%.1f", raw / field.scale), field));
                }
                case SCALED_FIXED3: {
                    double d = Double.parseDouble(stripUnit(input.trim(), field));
                    int raw = (int) Math.round(d * 1000);
                    if (raw < 0 || raw > 0xFFFF) return null;
                    return new ConfigEdit(field, twoBytes(raw),
                            withUnit(String.format("%.3f", raw / 1000.0), field));
                }
                case TIME_HM: {
                    String[] hm = input.trim().split(":");
                    if (hm.length != 2) return null;
                    int h = Integer.parseInt(hm[0]);
                    int m = Integer.parseInt(hm[1]);
                    if (h < 0 || h > 23 || m < 0 || m > 59) return null;
                    return new ConfigEdit(field, new byte[]{(byte) h, (byte) m},
                            String.format("%02d:%02d", h, m));
                }
                case POS4096: {
                    String[] qr = input.trim().split("\\.");
                    if (qr.length != 2) return null;
                    long q = Long.parseLong(qr[0]);
                    long r = Long.parseLong(qr[1]);
                    if (q < 0 || r < 0 || r >= 4096) return null;
                    long v = q * 4096 + r;
                    if (v > 0xFFFFFFFFL) return null;
                    return new ConfigEdit(field, fourBytes(v),
                            String.format("%04d.%04d", q, r));
                }
                case BIT: {
                    String sel = input.trim();
                    int code = -1;
                    for (Map.Entry<Integer, String> e : field.enumMap.entrySet()) {
                        if (e.getValue().equals(sel)) { code = e.getKey(); break; }
                    }
                    if (code < 0) code = parseInteger(sel);
                    if (code != 0 && code != 1) return null;
                    byte b = (byte) (code == 1 ? field.bitMask : 0);
                    String desc = field.enumMap.getOrDefault(code, String.valueOf(code));
                    return new ConfigEdit(field, new byte[]{b}, desc);
                }
                default:
                    return null;   // BYTES4 等不允许编辑
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public synchronized byte[] applyEdits(byte[] originalFrame, List<ConfigEdit> edits) {
        if (originalFrame == null || edits == null) return null;
        if (originalFrame.length < frameLength()) return null;

        // 输出帧不含整包 CRC：与现有上传逻辑一致，
        // 整包 CRC 由 SerialManager 在发送时统一追加。
        byte[] frame = new byte[frameLength() - 2];
        System.arraycopy(originalFrame, 0, frame, 0, frame.length);

        for (ConfigEdit edit : edits) {
            if (edit == null || edit.rawBytes == null) continue;
            if (!edit.field.editable) continue;
            if (edit.rawBytes.length != edit.field.length) continue;
            if (edit.field.type == ConfigFieldDef.FieldType.BIT) {
                // 位字段：只改掩码覆盖的位，保留同字节其他位
                int mask = edit.field.bitMask;
                frame[edit.field.offset] = (byte) ((frame[edit.field.offset] & ~mask)
                        | (edit.rawBytes[0] & mask));
            } else {
                System.arraycopy(edit.rawBytes, 0, frame, edit.field.offset, edit.rawBytes.length);
            }
        }

        // 1. 重算三个子包 CRC（高字节在前）
        writeSubCrc(frame, basicBase(), basicPayloadLen(), basicCrcOffset());
        writeSubCrc(frame, alarmBase(), alarmPayloadLen(), alarmCrcOffset());
        writeSubCrc(frame, superBase(), superPayloadLen(), superCrcOffset());

        // 2. 功能码 0x64 -> 0x65（写配置）
        frame[1] = 0x65;
        return frame;
    }

    @Override
    public byte[] getLastConfigFrame() {
        return lastConfigFrame == null ? null : lastConfigFrame.clone();
    }

    // ================= 内部工具 =================
    protected static long readRaw(byte[] frame, int offset, int length) {
        long v = 0;
        for (int i = 0; i < length; i++) {
            v = (v << 8) | (frame[offset + i] & 0xFF);
        }
        return v;
    }

    protected static byte[] twoBytes(int v) {
        return new byte[]{(byte) ((v >> 8) & 0xFF), (byte) (v & 0xFF)};
    }

    protected static byte[] fourBytes(long v) {
        return new byte[]{
                (byte) ((v >> 24) & 0xFF),
                (byte) ((v >> 16) & 0xFF),
                (byte) ((v >> 8) & 0xFF),
                (byte) (v & 0xFF)
        };
    }

    /** 整数不带小数，否则保留 1 位 */
    protected static String formatDecimal(int raw, int divisor) {
        double val = raw * 1.0 / divisor;
        return (val == Math.floor(val)) ? String.valueOf((int) val) : String.format("%.1f", val);
    }

    protected static int parseInteger(String s) {
        return Integer.parseInt(s);
    }

    /** 按类型格式化显示文本 */
    protected static String formatValue(ConfigFieldDef f, byte[] frame, long raw) {
        switch (f.type) {
            case BYTE:
            case BYTES2:
            case BYTES4:
                return withUnit(String.valueOf(raw), f);
            case ENUM: {
                String desc = f.enumMap.get((int) raw);
                return desc != null ? desc : "未知(" + raw + ")";
            }
            case SCALED_BYTE:
            case SCALED_BYTES2:
                return withUnit(formatDecimal((int) raw, (int) f.scale), f);
            case SCALED_BYTE1:
                return withUnit(String.format("%.1f", raw / f.scale), f);
            case TIME_HM: {
                int h = (int) (raw >> 8) & 0xFF;
                int m = (int) raw & 0xFF;
                return String.format("%02d:%02d", h, m);
            }
            case POS4096:
                return String.format("%04d.%04d", raw / 4096, raw % 4096);
            case BIT: {
                boolean on = (frame[f.offset] & f.bitMask) != 0;
                String desc = f.enumMap.get(on ? 1 : 0);
                return desc != null ? desc : (on ? "开启" : "关闭");
            }
            case CURRENT_A:
                return String.format("%02d.%02dA", (int) raw / 100, (int) raw % 100);
            case SCALED_FIXED3:
                return withUnit(String.format("%.3f", raw / 1000.0), f);
            default:
                return String.valueOf(raw);
        }
    }

    /** 数值后附加单位（无单位则原样返回） */
    private static String withUnit(String value, ConfigFieldDef f) {
        return (f.unit == null || f.unit.isEmpty()) ? value : value + f.unit;
    }

    /** 输入文本去掉末尾的单位后缀（编辑时用户可带单位输入，如 "1.5S"） */
    private static String stripUnit(String s, ConfigFieldDef f) {
        if (f.unit != null && !f.unit.isEmpty() && s.endsWith(f.unit)) {
            return s.substring(0, s.length() - f.unit.length()).trim();
        }
        return s;
    }

    /** 子包 CRC 重算并写回（高字节在前），payloadLen 为数据区长度（不含 CRC 两字节） */
    protected static void writeSubCrc(byte[] frame, int base, int payloadLen, int crcOffset) {
        byte[] payload = new byte[payloadLen];
        System.arraycopy(frame, base, payload, 0, payloadLen);
        int crc = Crc16Util.crc16(payload, payloadLen);
        frame[crcOffset] = (byte) ((crc >> 8) & 0xFF);
        frame[crcOffset + 1] = (byte) (crc & 0xFF);
    }
}
