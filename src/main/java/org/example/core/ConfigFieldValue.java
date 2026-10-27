package org.example.core;

/**
 * 配置字段的当前值（decode 结果）：定义 + 显示文本 + 原始值。
 */
public class ConfigFieldValue {

    public final ConfigFieldDef def;
    public final String displayText;   // 表格"值"列显示文本
    public final long rawValue;        // 原始值（1/2/4 字节按大端组合）

    public ConfigFieldValue(ConfigFieldDef def, String displayText, long rawValue) {
        this.def = def;
        this.displayText = displayText;
        this.rawValue = rawValue;
    }
}
