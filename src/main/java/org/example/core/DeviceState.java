package org.example.core;

/**
 * 轮询响应解析后的 UI 显示模型。
 *
 * 由 BoardProfile.parsePollResponse 填充，Controller 只负责显示，
 * 不同系列的显示差异（单位、语义、有无转速等）全部在 profile 实现内处理。
 */
public class DeviceState {

    public enum ValveState { UNKNOWN, OPENING, CLOSING, STOPPED }

    public final double openRatio;        // 当前开度 %
    public final double setRatio;         // 给定开度 %
    public final String torqueText;       // 力矩/电流文本（含单位），如 "1200 Nm"、"350 mA"
    public final String speedText;        // 转速文本；null = 该系列无转速
    public final String controlModeText;  // 控制方式显示名
    public final String valveStateText;   // 阀门状态显示名
    public final ValveState valveState;   // 供"阀门是否停止"判断用

    public DeviceState(double openRatio, double setRatio,
                       String torqueText, String speedText,
                       String controlModeText, String valveStateText,
                       ValveState valveState) {
        this.openRatio = openRatio;
        this.setRatio = setRatio;
        this.torqueText = torqueText;
        this.speedText = speedText;
        this.controlModeText = controlModeText;
        this.valveStateText = valveStateText;
        this.valveState = valveState;
    }
}
