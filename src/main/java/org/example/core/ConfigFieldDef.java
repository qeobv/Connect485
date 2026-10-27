package org.example.core;

import java.util.Map;

/**
 * 配置字段定义：描述 0x64 配置帧中一个可显示/可编辑的字段。
 *
 * offset 为该字段在完整 0x64 帧（含帧头、子包标记）内的绝对字节偏移；
 * length 为占用的字节数（1/2/4）。
 */
public class ConfigFieldDef {

    /** 字段编码类型 */
    public enum FieldType {
        BYTE,               // 1 字节无符号整数
        BYTES2,             // 2 字节大端无符号整数
        BYTES4,             // 4 字节大端无符号整数
        ENUM,               // 1 字节枚举（enumMap 提供 编码->显示名）
        SCALED_BYTE,        // 1 字节，显示 = 值 / scale（scale=10 即一位小数）
        SCALED_BYTES2,      // 2 字节大端，显示 = 值 / scale
        TIME_HM,            // 2 字节：时 + 分
        POS4096,            // 4 字节：显示为 "商.余数"（4096 进制小数）
        BIT,                // 位字段：1 字节内按 bitMask 掩码读写（enumMap 0/1）
        CURRENT_A,          // 2 字节大端，值/100，显示 XX.XXA（如 100 -> 01.00A）
        SCALED_FIXED3,      // 2 字节大端，值/1000，固定三位小数（如 13000 -> 13.000）
        SCALED_BYTE1        // 1 字节，值/scale，固定一位小数（如 50/10 -> 5.0）
    }

    public final String name;      // 参数名
    public final String category;  // 类别（基础设置/继电器输出设置/高级设置）
    public final int offset;       // 帧内绝对字节偏移
    public final int length;       // 1/2/4
    public final FieldType type;
    public final double scale;     // SCALED_* 用
    public final String unit;      // 显示单位（可空）
    public final Map<Integer, String> enumMap;  // ENUM/BIT 用（编码->显示名）
    public final boolean editable; // 是否允许修改
    public final int bitMask;      // BIT 类型的位掩码（如 0xC0、0x02、0x80）；非 BIT 为 0xFF

    public ConfigFieldDef(String name, String category, int offset, int length,
                          FieldType type, double scale, String unit,
                          Map<Integer, String> enumMap, boolean editable,
                          int bitMask) {
        this.name = name;
        this.category = category;
        this.offset = offset;
        this.length = length;
        this.type = type;
        this.scale = scale;
        this.unit = unit;
        this.enumMap = enumMap;
        this.editable = editable;
        this.bitMask = bitMask;
    }
}
