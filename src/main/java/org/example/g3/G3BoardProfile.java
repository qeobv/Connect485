package org.example.g3;

import org.example.ProtocolTranslationManager;
import org.example.core.BoardProfile;
import org.example.core.ConfigModel;
import org.example.core.ControlAction;
import org.example.core.DeviceState;

import java.util.List;

/**
 * G3 系列板卡档案。
 *
 * 本类把原散落在各 Controller 中的 G3 硬编码收拢：
 *   - 轮询命令（0x03 读 40002~40011，共 10 个寄存器）
 *   - 轮询响应解析（原 SerialUIController.parseAndUpdate 的偏移逻辑）
 *   - 控制命令（0x06 写 40004/40005/40012/40013）
 *   - 配置/历史命令（0x64 / 0x66）
 *   - 调试窗口轮询帧识别
 *
 * 翻译沿用现有 ProtocolTranslationManager（0x03/0x64/0x66），行为不变。
 */
public class G3BoardProfile implements BoardProfile {

    private static final List<String> CONTROL_MODE_OPTIONS = List.of(
            "4-20mA", "电平型", "脉冲型", "二线制常开", "二线制常关",
            "Modbus", "Profibus", "Hart", "压差PID");

    @Override
    public String id() {
        return "g3";
    }

    @Override
    public String displayName() {
        return "G3 系列";
    }

    // ==========================================
    //  轮询：0x03 读 40002~40011（10 个寄存器）
    // ==========================================
    @Override
    public byte[] buildPollCommand(int deviceAddr) {
        return new byte[]{
                (byte) (deviceAddr & 0xFF),
                0x03,
                0x00, 0x01,      // 起始地址 40002
                0x00, 0x0A       // 10 个寄存器
        };
    }

    // ==========================================
    //  轮询响应解析（原 SerialUIController.parseAndUpdate）
    // ==========================================
    @Override
    public DeviceState parsePollResponse(byte[] data) {
        if (data == null || data.length < 3) return null;

        int funcCode = data[1] & 0xFF;
        if (funcCode != 0x03) return null;

        int byteCount = data[2] & 0xFF;
        if (data.length < 3 + byteCount) return null;
        if (byteCount < 20) return null;   // G3: 10 个寄存器 = 20 字节

        // 40002：状态位
        int statusReg = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);

        // 40006：当前开度（数据区偏移 11）
        int openRatio   = ((data[11] & 0xFF) << 8) | (data[12] & 0xFF);
        // 40007：控制方式
        int controlMode = ((data[13] & 0xFF) << 8) | (data[14] & 0xFF);
        // 40008：给定开度
        int setRatio    = ((data[15] & 0xFF) << 8) | (data[16] & 0xFF);
        // 40009：力矩
        int torque      = ((data[17] & 0xFF) << 8) | (data[18] & 0xFF);
        // 40010：力矩单位
        int torqueUnit  = ((data[19] & 0xFF) << 8) | (data[20] & 0xFF);
        // 40011：转速
        int speed       = ((data[21] & 0xFF) << 8) | (data[22] & 0xFF);

        // 用 40002 的位判断状态
        boolean opening = ((statusReg >> 0) & 1) == 1;
        boolean closing = ((statusReg >> 1) & 1) == 1;
        DeviceState.ValveState valveState = opening ? DeviceState.ValveState.OPENING
                : (closing ? DeviceState.ValveState.CLOSING : DeviceState.ValveState.STOPPED);

        String unit = switch (torqueUnit) {
            case 0 -> "N";
            case 1 -> "Nm";
            case 2 -> "kN";
            default -> "";
        };

        return new DeviceState(
                openRatio / 10.0,
                setRatio / 10.0,
                torque + " " + unit,
                speed + " rpm",
                controlModeName(controlMode),
                valveStateName(valveState),
                valveState);
    }

    private String controlModeName(int mode) {
        return switch (mode) {
            case 0 -> "就地";
            case 1 -> "4-20mA";
            case 2 -> "电平型";
            case 3 -> "脉冲型";
            case 4 -> "二线制常开";
            case 5 -> "二线制常关";
            case 6 -> "Modbus";
            case 7 -> "Profibus";
            case 8 -> "Hart";
            case 9 -> "压差PID";
            default -> "未知";
        };
    }

    private String valveStateName(DeviceState.ValveState state) {
        return switch (state) {
            case STOPPED -> "已停止";
            case OPENING -> "正在开";
            case CLOSING -> "正在关";
            default -> "--";
        };
    }

    // ==========================================
    //  控制命令（0x06 写单寄存器）
    // ==========================================
    @Override
    public byte[] buildControlCommand(int deviceAddr, ControlAction action, int value) {
        int regAddr;
        int regValue;
        switch (action) {
            case OPEN_VALVE     -> { regAddr = 0x0003; regValue = 0x0001; }
            case CLOSE_VALVE    -> { regAddr = 0x0003; regValue = 0x0002; }
            case STOP_VALVE     -> { regAddr = 0x0003; regValue = 0x0004; }
            case RESET_ALARM    -> { regAddr = 0x0004; regValue = 0x0001; }
            case SET_OPEN_RATIO -> { regAddr = 0x000B; regValue = value; }
            case SET_CONTROL_MODE -> { regAddr = 0x000C; regValue = value; }
            default -> throw new IllegalArgumentException("G3 不支持的控制动作: " + action);
        }
        return new byte[]{
                (byte) (deviceAddr & 0xFF),
                0x06,
                (byte) ((regAddr >> 8) & 0xFF),
                (byte) (regAddr & 0xFF),
                (byte) ((regValue >> 8) & 0xFF),
                (byte) (regValue & 0xFF)
        };
    }

    @Override
    public List<String> controlModeOptions() {
        return CONTROL_MODE_OPTIONS;
    }

    @Override
    public int controlModeValue(String option) {
        return switch (option) {
            case "4-20mA" -> 1;
            case "电平型" -> 2;
            case "脉冲型" -> 3;
            case "二线制常开" -> 4;
            case "二线制常关" -> 5;
            case "Modbus" -> 6;
            case "Profibus" -> 7;
            case "Hart" -> 8;
            case "压差PID" -> 9;
            default -> 6;
        };
    }

    // ==========================================
    //  参数页 / 历史页命令
    // ==========================================
    private final G3ConfigModel configModel = new G3ConfigModel();

    @Override
    public ConfigModel getConfigModel() {
        return configModel;
    }

    @Override
    public byte[] buildReadConfigCommand(int deviceAddr) {
        return new byte[]{
                (byte) (deviceAddr & 0xFF),
                0x64,
                0x00, 0x00, 0x00, 0x00
        };
    }

    @Override
    public byte[] buildHistoryInitCommand(int deviceAddr) {
        return new byte[]{
                (byte) (deviceAddr & 0xFF),
                0x66,
                0x00, 0x00, 0x00, 0x00
        };
    }

    // ==========================================
    //  翻译与轮询帧识别
    // ==========================================
    @Override
    public String translate(String rawHexText) {
        return ProtocolTranslationManager.getInstance().translate(rawHexText);
    }

    @Override
    public boolean isPollingFrame(String rawHex) {
        if (rawHex == null) return false;
        int idx = rawHex.indexOf("]:");
        if (idx < 0) return false;
        String hex = rawHex.substring(idx + 2)
                .replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        if (hex.length() < 4) return false;

        // 功能码必须是 0x03
        if (!"03".equals(hex.substring(2, 4))) return false;

        // 发送帧：xx 03 00 01 00 0A（起始地址 40002，10 个寄存器）
        if (hex.length() >= 12 && hex.substring(4, 10).equals("000100")) return true;

        // 接收帧：xx 03 14 ...（byteCount = 0x14 = 20）
        if (hex.length() >= 6 && hex.substring(4, 6).equals("14")) return true;

        return false;
    }
}
