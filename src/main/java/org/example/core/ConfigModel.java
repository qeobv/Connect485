package org.example.core;

import java.util.List;

/**
 * 系列配置模型：支持"读-改-写"式单参数修改。
 *
 * 流程：
 *   1. decode(读配置响应帧) → 结构化字段值列表（界面表格显示）
 *   2. 用户编辑某字段 → encodeField 校验并编码成字节
 *   3. applyEdits(原始帧, 编辑列表) → 新的写配置帧（含 CRC 重算、功能码切换）
 *   4. 写回后重新 decode 设备回读帧刷新界面
 */
public interface ConfigModel {

    /** 判断帧是否为本系列的配置响应帧 */
    boolean isConfigFrame(byte[] frame);

    /**
     * 解析配置帧为字段值列表；null = 不是配置帧或帧不完整。
     * 成功后内部保存最近一次完整帧（供 applyEdits 使用）。
     */
    List<ConfigFieldValue> decode(byte[] frame);

    /**
     * 校验并编码一次字段编辑；返回 null 表示输入非法。
     * displayText 为规范化后的显示文本。
     */
    ConfigEdit encodeField(ConfigFieldDef field, String input);

    /**
     * 把编辑应用到原始配置帧，重算子包 CRC，返回可直接交给 SerialManager 发送的
     * 写配置帧（功能码已切换为写；不含整包 CRC，由 SerialManager 统一追加）。
     * edits 为空时仅重算子包 CRC 并切换功能码（用于整包上传等场景）。
     * null = 原始帧为空。
     */
    byte[] applyEdits(byte[] originalFrame, List<ConfigEdit> edits);

    /** 最近一次成功 decode 的完整配置帧；null = 尚未读到 */
    byte[] getLastConfigFrame();
}
