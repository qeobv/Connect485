package org.example.core;

/**
 * 阀门控制动作枚举。
 * buildControlCommand 的 value 参数语义：
 *   OPEN_VALVE / CLOSE_VALVE / STOP_VALVE / RESET_ALARM -> 忽略 value
 *   SET_OPEN_RATIO  -> value = 开度原始值（G3: 百分比 ×10）
 *   SET_CONTROL_MODE -> value = 控制方式编码
 */
public enum ControlAction {
    OPEN_VALVE,
    CLOSE_VALVE,
    STOP_VALVE,
    RESET_ALARM,
    SET_OPEN_RATIO,
    SET_CONTROL_MODE
}
