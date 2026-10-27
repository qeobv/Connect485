package org.example.s1z;

import org.example.core.BoardProfile;
import org.example.core.ConfigModel;
import org.example.core.ControlAction;
import org.example.core.DeviceState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MTA（S1-Z 多回转）系列板卡档案。
 *
 * 与 G3 的差异：
 *   - 轮询为 4 段循环（点表示例报文）：40006×4 / 40001 / 40002 / 40003
 *   - 40009 是力矩千分比（电流检测），显示转百分比
 *   - 无转速寄存器，speedText 为 null
 *   - 控制方式枚举按点表：40007 读（0 就地/1-6），40013 写（1-8）
 *   - 配置模型为 S1ZConfigModel（42/19/63 布局）
 */
public class S1ZBoardProfile implements BoardProfile {

    // 40007（读取当前控制方式）枚举（点表：0 就地，1 4-20mA ... 6 modbus）
    private static final Map<Integer, String> CONTROL_MODE_NAMES = Map.of(
            0, "就地", 1, "4-20mA", 2, "电平型", 3, "脉冲型",
            4, "二线制常开", 5, "二线制常关", 6, "Modbus");

    // 40013（设定远程信号类型）枚举（点表：1 4-20mA ... 8 modbus）
    private static final Map<String, Integer> REMOTE_SIGNAL_VALUES = new LinkedHashMap<>();
    static {
        REMOTE_SIGNAL_VALUES.put("4-20mA", 1);
        REMOTE_SIGNAL_VALUES.put("电平型", 2);
        REMOTE_SIGNAL_VALUES.put("脉冲型", 3);
        REMOTE_SIGNAL_VALUES.put("二线制常开", 4);
        REMOTE_SIGNAL_VALUES.put("二线制常关", 5);
        REMOTE_SIGNAL_VALUES.put("开关电平+调节", 6);
        REMOTE_SIGNAL_VALUES.put("开关脉冲+调节", 7);
        REMOTE_SIGNAL_VALUES.put("Modbus", 8);
    }

    private static final List<String> CONTROL_MODE_OPTIONS =
            new ArrayList<>(REMOTE_SIGNAL_VALUES.keySet());

    private final S1ZConfigModel configModel = new S1ZConfigModel();

    // ================= 分段轮询状态累积 =================
    private int pollPhase = 0;
    private volatile double openRatio = 0;
    private volatile double setRatio = 0;
    private volatile String controlModeText = "--";
    private volatile String torqueText = "--";
    private volatile DeviceState.ValveState valveState = DeviceState.ValveState.UNKNOWN;

    @Override
    public String id() {
        return "mta";
    }

    @Override
    public String displayName() {
        return "S1-Z 系列（多回转）";
    }

    /** MTA 无转速寄存器：主界面隐藏"电机转速"栏 */
    @Override
    public boolean hasSpeed() {
        return false;
    }

    // ==========================================
    //  轮询：4 段循环（40006×4 / 40001 / 40002 / 40003）
    // ==========================================
    @Override
    public synchronized byte[] buildPollCommand(int deviceAddr) {
        byte addr = (byte) (deviceAddr & 0xFF);
        pollPhase = (pollPhase + 1) % 4;
        return switch (pollPhase) {
            case 0 -> new byte[]{addr, 0x03, 0x00, 0x05, 0x00, 0x04};  // 40006~40009
            case 1 -> new byte[]{addr, 0x03, 0x00, 0x00, 0x00, 0x01};  // 40001
            case 2 -> new byte[]{addr, 0x03, 0x00, 0x01, 0x00, 0x01};  // 40002
            default -> new byte[]{addr, 0x03, 0x00, 0x02, 0x00, 0x01}; // 40003
        };
    }

    @Override
    public synchronized DeviceState parsePollResponse(byte[] data) {
        if (data == null || data.length < 3) return null;
        int funcCode = data[1] & 0xFF;
        if (funcCode != 0x03) return null;
        int byteCount = data[2] & 0xFF;
        if (data.length < 3 + byteCount) return null;

        if (byteCount == 8) {
            // 段0：40006 开度 / 40007 控制方式 / 40008 给定开度 / 40009 力矩（电流检测，千分比→百分比）
            int openRaw = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);
            int modeRaw = ((data[5] & 0xFF) << 8) | (data[6] & 0xFF);
            int setRaw  = ((data[7] & 0xFF) << 8) | (data[8] & 0xFF);
            int torqueRaw = ((data[9] & 0xFF) << 8) | (data[10] & 0xFF);
            openRatio = openRaw / 10.0;
            setRatio = setRaw / 10.0;
            controlModeText = CONTROL_MODE_NAMES.getOrDefault(modeRaw, "未知(" + modeRaw + ")");
            // 力矩千分比 → 百分比（453 → 45.3%）
            double torquePercent = torqueRaw / 10.0;
            torqueText = (torquePercent == Math.floor(torquePercent))
                    ? String.valueOf((int) torquePercent) + "%"
                    : String.format("%.1f%%", torquePercent);
        } else if (byteCount == 2) {
            int regValue = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);
            // 段2：40002 运行状态（bit0 正在开 / bit1 正在关）
            // 段1/段3 不更新界面状态（到位/告警暂不显示）
            if (pollPhase == 2) {
                boolean opening = (regValue & 0x01) != 0;
                boolean closing = (regValue & 0x02) != 0;
                valveState = opening ? DeviceState.ValveState.OPENING
                        : (closing ? DeviceState.ValveState.CLOSING : DeviceState.ValveState.STOPPED);
            }
        }

        return new DeviceState(
                openRatio,
                setRatio,
                torqueText,
                null,   // MTA 无转速
                controlModeText,
                valveStateName(valveState),
                valveState);
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
    //  控制命令（与 G3 相同：0x06 写 40004/40005/40012/40013）
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
            default -> throw new IllegalArgumentException("S1-Z 不支持的控制动作: " + action);
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
        return REMOTE_SIGNAL_VALUES.getOrDefault(option, 8);   // 默认 Modbus(8)
    }

    // ==========================================
    //  参数页 / 历史页命令（与 G3 相同：0x64 / 0x66）
    // ==========================================
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
        if (rawHexText == null) return null;
        boolean isSend = rawHexText.contains("发送]:");
        boolean isRecv = rawHexText.contains("接收]:");
        if (!isSend && !isRecv) return null;
        int idx = rawHexText.indexOf("]:");
        String hex = rawHexText.substring(idx + 2).replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        if (hex.length() < 4) return null;

        String func = hex.substring(2, 4);
        if ("64".equals(func) && isRecv) {
            return translateConfig(hex);
        }
        if ("03".equals(func)) {
            return translatePoll(hex, isSend);
        }
        if ("66".equals(func) && isRecv) {
            return translateHistory(hex);
        }
        return null;
    }

    /** MTA 历史记录告警类型表（固件 ALARM_MASS_DATA64，17 条） */
    private static final String[] ALARM_NAMES = {
            "未知错误", "相序未知", "无信号", "C相断线", "过温保护", "位置异常",
            "关阀拒动", "开阀拒动", "关阀堵转", "开阀堵转", "关过力矩", "开过力矩",
            "关未随动", "开未随动", "反向运行", "参数异常", "未知错误"
    };

    /** 0x66 历史记录帧翻译（记录布局与 G3 相同，35 字节） */
    private String translateHistory(String hex) {
        byte[] data = hexToBytes(hex);
        if (data.length < 8) return null;
        int byteCount = data[2] & 0xFF;
        if (data.length < 3 + byteCount) return null;
        int partCount = data[3] & 0xFF;
        int partNumber = data[4] & 0xFF;

        int[] w = new int[data.length - 7];   // 去掉 头3 + partCount/partNumber + CRC2
        for (int i = 5; i < data.length - 2; i++) {
            w[i - 5] = data[i] & 0xFF;
        }
        if (w.length < 35) return null;

        int alarmType = w[0] - 1;
        long eventCode = ((long) w[1] << 24) | ((long) w[2] << 16) | ((long) w[3] << 8) | w[4];
        int year = w[5], month = w[6], day = w[7], hour = w[8], minute = w[9], second = w[10];
        int position = (w[11] << 8) | w[12];
        long encoderValue = ((long) w[13] << 24) | ((long) w[14] << 16) | ((long) w[15] << 8) | w[16];
        int torque = (w[17] << 8) | w[18];
        long hallValue = ((long) w[19] << 24) | ((long) w[20] << 16) | ((long) w[21] << 8) | w[22];
        int closeTorque = (w[23] << 8) | w[24];
        long closePosition = ((long) w[25] << 24) | ((long) w[26] << 16) | ((long) w[27] << 8) | w[28];
        int openTorque = (w[29] << 8) | w[30];
        long openPosition = ((long) w[31] << 24) | ((long) w[32] << 16) | ((long) w[33] << 8) | w[34];

        String alarmName = (alarmType >= 0 && alarmType < ALARM_NAMES.length)
                ? ALARM_NAMES[alarmType] : "未知报警(" + alarmType + ")";
        // 位置千分比 -> 百分比
        double posPercent = position / 10.0;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("数据总条数：%d， 当前第%d条\n", partCount, partNumber + 1));
        sb.append("================ [报警记录] ================\n");
        sb.append(String.format("%-24s: %s\n", "报警类型", alarmName));
        sb.append(String.format("%-24s: %d\n", "事件代码", eventCode));
        sb.append(String.format("%-24s: %04d-%02d-%02d %02d:%02d:%02d\n",
                "时间", 2000 + year, month, day, hour, minute, second));
        sb.append(String.format("%-24s: %.1f%%\n", "位置", posPercent));
        sb.append(String.format("%-24s: %04d-%04d\n", "编码器值", encoderValue / 4096, encoderValue % 4096));
        sb.append(String.format("%-24s: %d\n", "力矩", torque));
        sb.append(String.format("%-24s: %010d\n", "霍尔值", hallValue));
        sb.append(String.format("%-24s: %d%%\n", "设定关阀力矩", closeTorque));
        sb.append(String.format("%-24s: %04d-%04d\n", "设定关阀位置", closePosition / 4096, closePosition % 4096));
        sb.append(String.format("%-24s: %d%%\n", "设定开阀力矩", openTorque));
        sb.append(String.format("%-24s: %04d-%04d\n", "设定开阀位置", openPosition / 4096, openPosition % 4096));
        return sb.toString().trim();
    }

    /** 0x64 配置帧结构化翻译 */
    private String translateConfig(String hex) {
        byte[] bytes = hexToBytes(hex);
        var values = configModel.decode(bytes);
        if (values == null) return null;
        StringBuilder sb = new StringBuilder("====== Modbus 帧头 ======\n");
        sb.append("地址: ").append(bytes[0] & 0xFF)
                .append(", 功能码: ").append(bytes[1] & 0xFF)
                .append(", 声明数据长度: ").append(bytes[2] & 0xFF).append(" 字节\n");
        String currentCategory = "";
        for (var v : values) {
            if (!v.def.category.equals(currentCategory)) {
                currentCategory = v.def.category;
                sb.append("\n====== ").append(currentCategory).append(" ======\n");
            }
            sb.append(String.format("%-30s: %s\n", v.def.name, v.displayText));
        }
        return sb.toString().trim();
    }

    /** 0x03 轮询帧翻译（按点表寄存器/位定义） */
    private String translatePoll(String hex, boolean isSend) {
        byte[] data = hexToBytes(hex);
        if (isSend) {
            if (data.length < 6) return null;
            int start = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
            int count = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
            return String.format("读寄存器 %d~%d（%d 个）", 40001 + start, 40001 + start + count - 1, count);
        }
        if (data.length < 3) return null;
        int byteCount = data[2] & 0xFF;

        if (byteCount == 8 && data.length >= 11) {
            // 段0：40006 开度 / 40007 控制方式 / 40008 给定开度 / 40009 力矩
            int open = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);
            int mode = ((data[5] & 0xFF) << 8) | (data[6] & 0xFF);
            int set  = ((data[7] & 0xFF) << 8) | (data[8] & 0xFF);
            int torq = ((data[9] & 0xFF) << 8) | (data[10] & 0xFF);
            StringBuilder sb = new StringBuilder("读 40006~40009:\n");
            sb.append(String.format("  - 阀门当前开度值: %d ‰ (%.1f%%)\n", open, open / 10.0));
            sb.append(String.format("  - 读取当前控制方式: %s\n",
                    CONTROL_MODE_NAMES.getOrDefault(mode, "未知(" + mode + ")")));
            sb.append(String.format("  - 读取阀门给定开度: %d ‰ (%.1f%%)\n", set, set / 10.0));
            double tp = torq / 10.0;
            String tpText = (tp == Math.floor(tp))
                    ? String.valueOf((int) tp) + "%"
                    : String.format("%.1f%%", tp);
            sb.append(String.format("  - 力矩(电流检测): %d ‰ (%s)", torq, tpText));
            return sb.toString();
        }
        if (byteCount == 2 && data.length >= 5) {
            int reg = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);
            switch (pollPhase) {
                case 1:
                    return String.format("读 40001 到位状态: 开到位=%s, 关到位=%s, 到指定位置=%s",
                            bit(reg, 0), bit(reg, 1), bit(reg, 2));
                case 2:
                    return String.format("读 40002 运行状态: 正在开=%s, 正在关=%s, 停止=%s",
                            bit(reg, 0), bit(reg, 1), bit(reg, 2));
                case 3:
                    return translateAlarm(reg);
                default:
                    return String.format("读 40001~40003: 值=%d", reg);
            }
        }
        return null;
    }

    private String bit(int reg, int pos) {
        return ((reg >> pos) & 1) == 1 ? "是" : "否";
    }

    /** 40003 告警位翻译（点表位定义） */
    private String translateAlarm(int reg) {
        List<String> alarms = new ArrayList<>();
        if ((reg & 0x0002) != 0) alarms.add("相序异常");
        if ((reg & 0x0004) != 0) alarms.add("4-20mA无输入");
        if ((reg & 0x0008) != 0) alarms.add("C相断线");
        if ((reg & 0x0010) != 0) alarms.add("电机过温");
        if ((reg & 0x0020) != 0) alarms.add("位置传感器异常");
        if ((reg & 0x0100) != 0) alarms.add("开关堵转");
        if ((reg & 0x0200) != 0) alarms.add("开关拒动");
        if ((reg & 0x0400) != 0) alarms.add("开阀过力矩");
        if ((reg & 0x0800) != 0) alarms.add("关阀过力矩");
        if ((reg & 0x1000) != 0) alarms.add("随动异常");
        if ((reg & 0x2000) != 0) alarms.add("反向运行");
        return alarms.isEmpty() ? "读 40003 告警: 无告警" : "读 40003 告警: " + String.join(", ", alarms);
    }

    @Override
    public boolean isPollingFrame(String rawHex) {
        if (rawHex == null) return false;
        int idx = rawHex.indexOf("]:");
        if (idx < 0) return false;
        String hex = rawHex.substring(idx + 2)
                .replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
        if (hex.length() < 8) return false;
        if (!"03".equals(hex.substring(2, 4))) return false;

        boolean isSend = rawHex.contains("发送]:");
        if (isSend) {
            // 发送帧：xx 03 00 05 00 04 / 00 00 00 01 / 00 01 00 01 / 00 02 00 01
            String cmd = hex.substring(4, 12);
            return cmd.equals("00050004") || cmd.equals("00000001")
                    || cmd.equals("00010001") || cmd.equals("00020001");
        }
        // 接收帧：byteCount 为 8（段0）或 2（段1~3）
        String bc = hex.substring(4, 6);
        return bc.equals("08") || bc.equals("02");
    }

    private byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }
}
