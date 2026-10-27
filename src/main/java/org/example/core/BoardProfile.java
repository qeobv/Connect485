package org.example.core;

import java.util.List;

/**
 * 板卡系列档案（精简版）：一个系列 = 一套随系列变化的通信行为。
 *
 * 数据差异（寄存器/位/枚举定义）由 resources/profiles/<id>.json 承担（后续接入），
 * 行为差异（轮询命令、响应解析、控制命令、翻译、轮询帧识别）由实现类承担。
 *
 * 所有方法的 deviceAddr 为 0~255 的从站地址，返回的命令帧均不含 CRC
 * （CRC 由 SerialManager 统一追加）。
 */
public interface BoardProfile {

    /** 系列唯一标识，如 "g3" */
    String id();

    /** 系列显示名，如 "G3 系列" */
    String displayName();

    /** 主界面"电机转速"栏是否显示（无转速寄存器的系列返回 false 隐藏该栏） */
    default boolean hasSpeed() {
        return true;
    }

    /** 主界面轮询命令（读寄存器） */
    byte[] buildPollCommand(int deviceAddr);

    /**
     * 解析轮询响应帧，返回 UI 显示模型。
     * 返回 null 表示该帧不是本系列的轮询响应（不更新界面）。
     */
    DeviceState parsePollResponse(byte[] frame);

    /** 控制命令构造；value 语义见 ControlAction */
    byte[] buildControlCommand(int deviceAddr, ControlAction action, int value);

    /** 主界面"控制方式"下拉选项（显示名列表） */
    List<String> controlModeOptions();

    /** 控制方式下拉选项 -> 设备编码 */
    int controlModeValue(String option);

    /** 参数页读配置命令；null = 该系列不支持配置读取 */
    byte[] buildReadConfigCommand(int deviceAddr);

    /**
     * 配置模型（读-改-写式单参数修改）；null = 该系列不支持配置编辑。
     */
    default ConfigModel getConfigModel() {
        return null;
    }

    /** 历史页初始化查询命令；null = 该系列不支持历史记录 */
    byte[] buildHistoryInitCommand(int deviceAddr);

    /**
     * 翻译原始报文文本（可含时间戳前缀与空格，与调试窗口输入一致）。
     * 返回 null 表示不翻译（原样显示）。
     */
    String translate(String rawHexText);

    /** 调试窗口"隐藏轮询"：判断文本帧是否为该系列的轮询帧 */
    boolean isPollingFrame(String rawHexText);
}
