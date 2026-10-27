package org.example.core;

/**
 * 一次字段编辑：用户输入经 ConfigModel.encodeField 校验编码后的结果。
 */
public class ConfigEdit {

    public final ConfigFieldDef field;
    public final byte[] rawBytes;      // 编码后的字节（长度 == field.length）
    public final String displayText;   // 规范化显示文本（写入后表格显示用）

    public ConfigEdit(ConfigFieldDef field, byte[] rawBytes, String displayText) {
        this.field = field;
        this.rawBytes = rawBytes;
        this.displayText = displayText;
    }
}
