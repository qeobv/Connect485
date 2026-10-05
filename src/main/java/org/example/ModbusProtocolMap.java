package org.example;

import java.util.HashMap;
import java.util.Map;

public class ModbusProtocolMap {

    // ================= 枚举映射 =================
    public enum ControlMode {
        LOCAL(0, "就地"),
        CURRENT_4_20MA(1, "4-20mA"),
        LEVEL(2, "电平型"),
        PULSE(3, "脉冲型"),
        TWO_WIRE_NO(4, "二线制常开"),
        TWO_WIRE_NC(5, "二线制常关"),
        MODBUS(6, "Modbus"),
        PROFIBUS(7, "Profibus"),
        HART(8, "Hart"),
        PID(9, "压差PID");

        private final int code;
        private final String desc;
        private static final Map<Integer, ControlMode> MAP = new HashMap<>();
        static { for (ControlMode e : values()) MAP.put(e.code, e); }

        ControlMode(int code, String desc) { this.code = code; this.desc = desc; }
        public static String getDesc(int code) {
            ControlMode e = MAP.get(code);
            return e != null ? e.desc : "未知控制方式(" + code + ")";
        }
    }

    public enum TorqueUnit {
        N(0, "N"), NM(1, "Nm"), KN(2, "kN");
        private final int code;
        private final String desc;
        private static final Map<Integer, TorqueUnit> MAP = new HashMap<>();
        static { for (TorqueUnit e : values()) MAP.put(e.code, e); }
        TorqueUnit(int code, String desc) { this.code = code; this.desc = desc; }
        public static String getDesc(int code) {
            TorqueUnit e = MAP.get(code);
            return e != null ? e.desc : "未知力矩单位(" + code + ")";
        }
    }

    public enum RemoteSignalType {
        CURRENT_4_20MA(1, "4-20mA"),
        LEVEL(2, "电平型"),
        PULSE(3, "脉冲型"),
        TWO_WIRE_NO(4, "二线制常开"),
        TWO_WIRE_NC(5, "二线制常关"),
        MODBUS(6, "Modbus"),
        PROFIBUS(7, "Profibus"),
        HART(8, "Hart"),
        PID(9, "压差PID");

        private final int code;
        private final String desc;
        private static final Map<Integer, RemoteSignalType> MAP = new HashMap<>();
        static { for (RemoteSignalType e : values()) MAP.put(e.code, e); }

        RemoteSignalType(int code, String desc) { this.code = code; this.desc = desc; }
        public static String getDesc(int code) {
            RemoteSignalType e = MAP.get(code);
            return e != null ? e.desc : "未知信号类型(" + code + ")";
        }
    }

    // ================= 寄存器解析规则 =================
    public enum RegType { INT, BIT, ENUM_INT }

    public static class RegisterDef {
        public final int address;
        public final int bitPos;
        public final String name;
        public final String unit;
        public final RegType type;
        public final double minRaw;
        public final double maxRaw;
        public final Enum<?> enumMap;

        public RegisterDef(int address, int bitPos, String name, String unit, RegType type, double minRaw, double maxRaw, Enum<?> enumMap) {
            this.address = address; this.bitPos = bitPos; this.name = name; this.unit = unit; this.type = type;
            this.minRaw = minRaw; this.maxRaw = maxRaw; this.enumMap = enumMap;
        }
    }

    private static final Map<Integer, RegisterDef> REGISTERS = new HashMap<>();

    static {
        // --- 模拟量输入 ---
        addReg(5,  "阀门当前开度值", "‰",  RegType.INT, 0, 1000, null);
        addReg(6,  "读取当前控制方式", "",  RegType.ENUM_INT, 0, 9, ControlMode.MODBUS);
        addReg(7,  "读取阀门给定开度", "‰",  RegType.INT, 0, 1000, null);
        addReg(8,  "阀门实际力矩值",   "", RegType.INT, 0, 60000, null);
        addReg(9,  "阀门力矩单位", "", RegType.ENUM_INT, 0, 2, TorqueUnit.N);
        addReg(10, "电机转速", "rpm", RegType.INT, 0, 6000, null);   // ★ 新增

        // --- 模拟量输出（写） ---
        addReg(11, "设定阀门位置",   "‰",  RegType.INT, 0, 1000, null);      // ★ 改成 11
        addReg(12, "设定远程信号类型", "",  RegType.ENUM_INT, 1, 9, RemoteSignalType.MODBUS);  // ★ 改成 12

        // --- 压力表（写） ---
        addReg(100, "进压表数据写执行器", "Kpa", RegType.INT, 0, 60000, null);  // ★ 新增
        addReg(101, "回压表数据写执行器", "Kpa", RegType.INT, 0, 60000, null);  // ★ 新增
        addReg(102, "压差PID目标压差设置", "Kpa", RegType.INT, 0, 25000, null); // ★ 新增

        // --- 数字量输入 (40001) ---
        addBitReg(0, 0, "阀开到位");
        addBitReg(0, 1, "阀关到位");
        addBitReg(0, 2, "阀到指定位置");

        // --- 数字量输入 (40002) ---
        addBitReg(1, 0, "阀门正在开");
        addBitReg(1, 1, "阀门正在关");
        addBitReg(1, 2, "阀门停止");

        // --- 数字量输入 (40003) ---
        addBitReg(2, 0, "告警预留0");
        addBitReg(2, 1, "PID异常");
        addBitReg(2, 2, "电机过热");
        addBitReg(2, 3, "告警预留3");
        addBitReg(2, 4, "告警预留4");
        addBitReg(2, 5, "自检失败");
        addBitReg(2, 6, "电机驱动器异常");
        addBitReg(2, 7, "电机hall异常");
        addBitReg(2, 8, "位置传感器通信异常");
        addBitReg(2, 9, "4-20mA断线");
        addBitReg(2, 10,"电机堵转");
        addBitReg(2, 11,"电机拒动");
        addBitReg(2, 12,"电机反向运行");
        addBitReg(2, 13,"开过力矩");
        addBitReg(2, 14,"关过力矩");
        addBitReg(2, 15,"传动异常");

        // --- 数字量输出 (40004) ---
        addBitReg(3, 0, "阀门全开[控制]");
        addBitReg(3, 1, "阀门全关[控制]");
        addBitReg(3, 2, "阀门停止[控制]");

        // --- 数字量输出 (40005) ---
        addBitReg(4, 0, "故障告警复归[控制]");
    }

    private static int generateKey(int address, int bitPos) {
        return (address << 4) | bitPos;
    }

    private static void addReg(int addr, String name, String unit, RegType type, double min, double max, Enum<?> em) {
        int key = generateKey(addr, 0);
        REGISTERS.put(key, new RegisterDef(addr, -1, name, unit, type, min, max, em));
    }

    private static void addBitReg(int addr, int bit, String name) {
        int key = generateKey(addr, bit);
        REGISTERS.put(key, new RegisterDef(addr, bit, name, "N/A", RegType.BIT, 0, 1, null));
    }

    public static String translate(int modbusAddress, int bitPos, int rawValue) {
        int key = generateKey(modbusAddress, bitPos);
        RegisterDef reg = REGISTERS.get(key);

        if (reg == null) {
            return String.format("未知寄存器(地址:%d, 位:%d, 值:0x%04X)", modbusAddress, bitPos, rawValue);
        }

        if (reg.enumMap instanceof TorqueUnit) return String.format("%s: %s", reg.name, TorqueUnit.getDesc(rawValue));

        switch (reg.type) {
            case INT:
                return String.format("%s: %d %s", reg.name, rawValue, reg.unit);

            case ENUM_INT:
                if (reg.enumMap instanceof ControlMode) return String.format("%s: %s", reg.name, ControlMode.getDesc(rawValue));
                if (reg.enumMap instanceof RemoteSignalType) return String.format("%s: %s", reg.name, RemoteSignalType.getDesc(rawValue));
                return String.format("%s: %d", reg.name, rawValue);

            case BIT:
                boolean isSet = ((rawValue >> reg.bitPos) & 0x01) == 1;
                return String.format("%s: %s", reg.name, isSet ? "✔ 是" : "✖ 否");

            default:
                return String.format("%s: %d", reg.name, rawValue);
        }
    }
}