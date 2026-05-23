package org.example;

import java.util.HashMap;
import java.util.Map;

/**
 * Modbus 通讯协议映射表
 */
public class ModbusProtocolMap {

    // ================= 枚举映射（用于状态量和控制方式） =================
    public enum ControlMode {
        LOCAL(0, "就地"), CURRENT_4_20MA(1, "4-20mA"), LEVEL(2, "电平型"),
        PULSE(3, "脉冲型"), TWO_WIRE_NO(4, "二线制常开"), TWO_WIRE_NC(5, "二线制常关"),
        MODBUS(6, "Modbus");

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

    public enum RemoteSignalType {
        CURRENT_4_20MA(1, "4-20mA"), LEVEL(2, "电平型"), PULSE(3, "脉冲型"),
        TWO_WIRE_NO(4, "二线制常开"), TWO_WIRE_NC(5, "二线制常关"),
        SWITCH_LEVEL_REG(6, "开关电平+调节"), SWITCH_PULSE_REG(7, "开关脉冲+调节"),
        MODBUS(8, "Modbus");

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

    // ================= 寄存器解析规则定义 =================
    public enum RegType { INT, BIT, ENUM_INT }

    public static class RegisterDef {
        public final int address;   // Modbus地址 (如 40006 传 6)
        public final String name;   // 参数名称
        public final String unit;   // 单位
        public final RegType type;  // 数据类型
        public final double minRaw; // 量程下限
        public final double maxRaw; // 量程上限
        public final int bitPos;    // 位偏移 (仅Bit类型有效, -1表示无效)
        public final Enum<?> enumMap; // 枚举映射 (仅ENUM_INT类型有效)

        public RegisterDef(int address, String name, String unit, RegType type, double minRaw, double maxRaw, int bitPos, Enum<?> enumMap) {
            this.address = address; this.name = name; this.unit = unit; this.type = type;
            this.minRaw = minRaw; this.maxRaw = maxRaw; this.bitPos = bitPos; this.enumMap = enumMap;
        }
    }

    // 全局寄存器字典：Key为 Modbus地址
    private static final Map<Integer, RegisterDef> REGISTERS = new HashMap<>();

    static {
        // --- 模拟量输入 ---
        addReg(6,  "阀门当前开度值", "‰",  RegType.INT, 0, 1000, -1, null);
        addReg(7,  "读取当前控制方式", "",  RegType.ENUM_INT, 0, 9, -1, ControlMode.MODBUS); // 传入任意枚举作为标记
        addReg(8,  "读取阀门给定开度", "‰",  RegType.INT, 0, 1000, -1, null);
        addReg(9,  "电机工作电流",   "mA", RegType.INT, 0, 60000, -1, null);

        // --- 模拟量输出 ---
        addReg(12, "设定阀门位置",   "‰",  RegType.INT, 1, 1000, -1, null);
        addReg(13, "设定远程信号类型", "",  RegType.ENUM_INT, 1, 9, -1, RemoteSignalType.MODBUS);

        // --- 数字量输入 (位操作) ---
        addBitReg(1, 0, "阀开到位");
        addBitReg(1, 1, "阀关到位");
        addBitReg(1, 2, "阀到指定位置");
        addBitReg(2, 0, "阀门正在开");
        addBitReg(2, 1, "阀门正在关");
        addBitReg(2, 2, "阀门停止");
        addBitReg(3, 1, "相序异常[故障]");
        addBitReg(3, 2, "4-20mA无输入[故障]");
        addBitReg(3, 3, "C相断线[故障]");
        addBitReg(3, 4, "电机过温[故障]");
        addBitReg(3, 5, "位置传感器异常[故障]");
        addBitReg(3, 8, "开关堵转[故障]");
        addBitReg(3, 9, "开关拒动[故障]");
        addBitReg(3, 10,"开阀过力矩[故障]");
        addBitReg(3, 11,"关阀过力矩[故障]");
        addBitReg(3, 12,"随动异常[故障]");
        addBitReg(3, 13,"反向运行[故障]");

        // --- 数字量输出 (位操作) ---
        addBitReg(4, 0, "阀门全开[控制]");
        addBitReg(4, 1, "阀门全关[控制]");
        addBitReg(4, 2, "阀门停止[控制]");
        addBitReg(5, 0, "故障告警复归[控制]");
    }

    private static void addReg(int addr, String name, String unit, RegType type, double min, double max, int bit, Enum<?> em) {
        REGISTERS.put(addr, new RegisterDef(addr, name, unit, type, min, max, bit, em));
    }

    private static void addBitReg(int addr, int bit, String name) {
        REGISTERS.put(addr, new RegisterDef(addr, name, "N/A", RegType.BIT, 0, 1, bit, null));
    }

    /**
     * 核心翻译方法：将读取到的寄存器值翻译为中文
     * @param modbusAddress Modbus 地址 (如 40006 传 6)
     * @param rawValue     原始寄存器值 (16位无符号，Java用int表示 0-65535)
     * @return 翻译后的字符串
     */
    public static String translate(int modbusAddress, int rawValue) {
        RegisterDef reg = REGISTERS.get(modbusAddress);
        if (reg == null) {
            return String.format("未知寄存器(地址:%d, 值:0x%04X)", modbusAddress, rawValue);
        }

        switch (reg.type) {
            case INT:
                // 量程换算逻辑 (如果需要换算成实际物理量，可在此处理)
                // 目前按原始量程显示，如需换算百分比可修改此处
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
