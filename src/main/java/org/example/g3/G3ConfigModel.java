package org.example.g3;

import org.example.core.AbstractPacketConfigModel;
import org.example.core.ConfigFieldDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * G3 系列 0x64 配置模型：支持"读-改-写"式单参数修改。
 *
 * 帧结构（共 144 字节）：
 *   索引 0~2   : 从站地址 + 功能码 0x64 + 数据长度
 *   索引 3~4   : 子包标记 (01 38) 基础设置
 *   索引 5~60  : basicData[0..55]（56 字节，其中 [54..55] 为子包 CRC）
 *   索引 61~62 : 子包标记 (02 0B) 继电器输出设置
 *   索引 63~73 : alarmData[0..10]（11 字节，其中 [9..10] 为子包 CRC）
 *   索引 74~75 : 子包标记 (03 42) 高级设置
 *   索引 76~141: superData[0..65]（66 字节，其中 [64..65] 为子包 CRC）
 *   索引 142~143: Modbus 整包 CRC
 *
 * CRC 规则：
 *   子包 CRC：高字节在前（frame[off] = crc>>8, frame[off+1] = crc&0xFF），用 Crc16Util.crc16
 *   整包 CRC：低字节在前（标准 Modbus），用 Crc16Util.calculateCRC16
 * 写配置帧功能码为 0x65，帧结构相同。
 */
public class G3ConfigModel extends AbstractPacketConfigModel {

    public static final int FRAME_LENGTH = 144;

    // 子包数据区在完整帧内的偏移
    private static final int BASIC_BASE = 5;    // basicData[i] -> frame[5+i]
    private static final int ALARM_BASE = 63;   // alarmData[i] -> frame[63+i]
    private static final int SUPER_BASE = 76;   // superData[i] -> frame[76+i]

    // 各子包 CRC 在完整帧内的位置（高字节在前）
    private static final int BASIC_CRC = 59;    // basicData[54..55]
    private static final int ALARM_CRC = 72;    // alarmData[9..10]
    private static final int SUPER_CRC = 140;   // superData[64..65]

    // ================= 枚举映射（与 DeviceProtocolTranslator 一致） =================
    private static final Map<Integer, String> LANG = Map.of(0, "中文", 1, "英文");
    private static final Map<Integer, String> SIGN_TYPE = Map.of(
            0, "4-20mA", 1, "电平信号", 2, "脉冲信号", 3, "二线制常开",
            4, "二线制常关", 5, "Modbus", 6, "Profibus", 7, "Hart", 8, "压差PID");
    private static final Map<Integer, String> ACTION_TYPE = Map.of(
            0, "全开", 1, "全关", 2, "保持", 3, "指定位置", 4, "关闭");
    private static final Map<Integer, String> STARTUP = Map.of(0, "本地控制", 1, "远程控制");
    private static final Map<Integer, String> MOTOR_DIR = Map.of(0, "顺时针关阀", 1, "逆时针关阀");
    private static final Map<Integer, String> ON_OFF = Map.of(0, "关闭", 1, "开启");
    private static final Map<Integer, String> SELFTEST_SW = Map.of(0, "关", 1, "开");
    private static final Map<Integer, String> TORQUE_UNIT = Map.of(0, "N", 1, "Nm", 2, "kN");
    private static final Map<Integer, String> BAUD_RATE = Map.of(
            0, "1200", 1, "2400", 2, "4800", 3, "9600", 4, "14400",
            5, "19200", 6, "38400", 7, "56000", 8, "57600", 9, "115200");
    private static final Map<Integer, String> PARITY = Map.of(0, "无校验", 1, "奇校验", 2, "偶校验");
    private static final Map<Integer, String> MODBUS_TYPE = Map.of(0, "Modbus-RTU", 1, "Modbus-TCP");

    private static final Map<Integer, String> ALARM_PORT = new LinkedHashMap<>();
    static {
        ALARM_PORT.put(0, "全开");
        ALARM_PORT.put(1, "全关");
        ALARM_PORT.put(2, "给定信号故障");
        ALARM_PORT.put(3, "开过力矩");
        ALARM_PORT.put(4, "关过力矩");
        ALARM_PORT.put(5, "指定位置");
        ALARM_PORT.put(6, "综合报警");
        ALARM_PORT.put(7, "远方");
        ALARM_PORT.put(8, "就地");
        ALARM_PORT.put(9, "主电源故障");
        ALARM_PORT.put(10, "开关过力矩");
        ALARM_PORT.put(11, "正在开");
        ALARM_PORT.put(12, "正在关");
        ALARM_PORT.put(13, "正在开关");
        ALARM_PORT.put(14, "上电指示");
        ALARM_PORT.put(15, "禁止");
    }

    // ================= 字段定义表 =================
    private static final List<ConfigFieldDef> FIELDS = new ArrayList<>();

    private static void reg(String name, String cat, int offset, int len,
                            ConfigFieldDef.FieldType type, double scale, String unit,
                            Map<Integer, String> enums, boolean editable) {
        FIELDS.add(new ConfigFieldDef(name, cat, offset, len, type, scale, unit, enums, editable, 0xFF));
    }

    /** 位字段：1 字节内按位掩码读写（enumMap 用 0/1 -> 关闭/开启） */
    private static void regBit(String name, String cat, int offset, int bitMask,
                               Map<Integer, String> enums, boolean editable) {
        FIELDS.add(new ConfigFieldDef(name, cat, offset, 1, ConfigFieldDef.FieldType.BIT,
                0, null, enums, editable, bitMask));
    }

    static {
        String basic = "基础设置";
        // --- 基础设置（basicData，帧内偏移 5+i） ---
        reg("中英文选择", basic, BASIC_BASE + 0, 1, ConfigFieldDef.FieldType.ENUM, 0, null, LANG, true);
        reg("开阀位置", basic, BASIC_BASE + 1, 4, ConfigFieldDef.FieldType.POS4096, 0, null, null, true);
        reg("关阀位置", basic, BASIC_BASE + 5, 4, ConfigFieldDef.FieldType.POS4096, 0, null, null, true);
        reg("输入4mA校准", basic, BASIC_BASE + 9, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("输入20mA校准", basic, BASIC_BASE + 11, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("输出4mA校准", basic, BASIC_BASE + 13, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("输出20mA校准", basic, BASIC_BASE + 15, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("死区范围设置", basic, BASIC_BASE + 17, 1, ConfigFieldDef.FieldType.SCALED_BYTE, 10, "S", null, true);
        reg("信号类型", basic, BASIC_BASE + 18, 1, ConfigFieldDef.FieldType.ENUM, 0, null, SIGN_TYPE, true);
        reg("信号故障类型", basic, BASIC_BASE + 19, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ACTION_TYPE, true);
        reg("信号故障指定位置", basic, BASIC_BASE + 20, 2, ConfigFieldDef.FieldType.SCALED_BYTES2, 10, "%", null, true);
        reg("断电动作类型", basic, BASIC_BASE + 22, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ACTION_TYPE, true);
        reg("断电指定位置", basic, BASIC_BASE + 23, 2, ConfigFieldDef.FieldType.SCALED_BYTES2, 10, "%", null, true);
        reg("断电延时时间", basic, BASIC_BASE + 25, 2, ConfigFieldDef.FieldType.BYTES2, 0, "ms", null, true);
        reg("ESD动作类型", basic, BASIC_BASE + 27, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ACTION_TYPE, true);
        reg("ESD指定位置", basic, BASIC_BASE + 28, 2, ConfigFieldDef.FieldType.SCALED_BYTES2, 10, "%", null, true);
        reg("启动选项", basic, BASIC_BASE + 30, 1, ConfigFieldDef.FieldType.ENUM, 0, null, STARTUP, true);
        reg("电机关阀方向", basic, BASIC_BASE + 31, 1, ConfigFieldDef.FieldType.ENUM, 0, null, MOTOR_DIR, true);
        reg("设备地址", basic, BASIC_BASE + 32, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, false);
        reg("最大开速度", basic, BASIC_BASE + 35, 1, ConfigFieldDef.FieldType.BYTE, 0, "%", null, true);
        reg("最大关速度", basic, BASIC_BASE + 36, 1, ConfigFieldDef.FieldType.BYTE, 0, "%", null, true);
        reg("压力设定", basic, BASIC_BASE + 37, 1, ConfigFieldDef.FieldType.SCALED_BYTE, 10, "MPa", null, true);
        reg("减速范围", basic, BASIC_BASE + 38, 1, ConfigFieldDef.FieldType.SCALED_BYTE, 10, "%", null, true);
        reg("自检开关", basic, BASIC_BASE + 39, 1, ConfigFieldDef.FieldType.ENUM, 0, null, SELFTEST_SW, true);
        reg("自检周期", basic, BASIC_BASE + 40, 1, ConfigFieldDef.FieldType.BYTE, 0, null, null, true);
        reg("自检时刻", basic, BASIC_BASE + 41, 2, ConfigFieldDef.FieldType.TIME_HM, 0, null, null, true);
        reg("自检范围", basic, BASIC_BASE + 43, 1, ConfigFieldDef.FieldType.SCALED_BYTE, 10, "%", null, true);
        reg("自检最大时间", basic, BASIC_BASE + 44, 1, ConfigFieldDef.FieldType.BYTE, 0, "S", null, true);
        reg("压差PID_P", basic, BASIC_BASE + 45, 1, ConfigFieldDef.FieldType.SCALED_BYTE, 10, null, null, true);
        reg("压差PID_I", basic, BASIC_BASE + 46, 1, ConfigFieldDef.FieldType.SCALED_BYTE, 10, null, null, true);
        reg("压差PID_D", basic, BASIC_BASE + 47, 1, ConfigFieldDef.FieldType.SCALED_BYTE, 10, null, null, true);

        String alarm = "继电器输出设置";
        // --- 继电器输出设置（alarmData，帧内偏移 63+i） ---
        reg("继电器输出1", alarm, ALARM_BASE + 0, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ALARM_PORT, true);
        reg("继电器输出2", alarm, ALARM_BASE + 1, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ALARM_PORT, true);
        reg("继电器输出3", alarm, ALARM_BASE + 2, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ALARM_PORT, true);
        reg("继电器输出4", alarm, ALARM_BASE + 3, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ALARM_PORT, true);
        reg("继电器输出5", alarm, ALARM_BASE + 4, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ALARM_PORT, true);
        reg("继电器输出6", alarm, ALARM_BASE + 5, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ALARM_PORT, true);
        reg("报警指定位置", alarm, ALARM_BASE + 6, 2, ConfigFieldDef.FieldType.SCALED_BYTES2, 10, "%", null, true);
        reg("反馈区间设置", alarm, ALARM_BASE + 8, 1, ConfigFieldDef.FieldType.SCALED_BYTE, 10, "%", null, true);

        String sup = "高级设置";
        // --- 高级设置（superData，帧内偏移 76+i） ---
        reg("密码设置", sup, SUPER_BASE + 0, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, false);
        reg("力矩单位", sup, SUPER_BASE + 2, 1, ConfigFieldDef.FieldType.ENUM, 0, null, TORQUE_UNIT, true);
        reg("过力矩延时", sup, SUPER_BASE + 7, 2, ConfigFieldDef.FieldType.BYTES2, 0, "ms", null, true);
        reg("关过力矩值", sup, SUPER_BASE + 9, 2, ConfigFieldDef.FieldType.BYTES2, 0, "%", null, true);
        reg("开过力矩值", sup, SUPER_BASE + 11, 2, ConfigFieldDef.FieldType.BYTES2, 0, "%", null, true);
        reg("位置传动比", sup, SUPER_BASE + 13, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("机械传动比", sup, SUPER_BASE + 15, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("产品编号", sup, SUPER_BASE + 17, 4, ConfigFieldDef.FieldType.BYTES4, 0, null, null, false);
        // 力矩校准开关：数据显示在高级设置（与原翻译一致），数据字节在基础设置 w34
        reg("力矩校准开关", sup, BASIC_BASE + 34, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ON_OFF, true);
        reg("传感器保护精度", sup, SUPER_BASE + 21, 1, ConfigFieldDef.FieldType.SCALED_BYTE, 10, "度", null, true);
        reg("空载校准值", sup, SUPER_BASE + 22, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("空载开电流值", sup, SUPER_BASE + 24, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("空载关电流值", sup, SUPER_BASE + 26, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("负载校准值", sup, SUPER_BASE + 28, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("负载开电流值", sup, SUPER_BASE + 30, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("负载关电流值", sup, SUPER_BASE + 32, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("启动速度", sup, SUPER_BASE + 34, 1, ConfigFieldDef.FieldType.BYTE, 0, null, null, true);
        reg("设定电流值", sup, SUPER_BASE + 35, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("电流环系数", sup, SUPER_BASE + 37, 1, ConfigFieldDef.FieldType.BYTE, 0, null, null, true);
        reg("电流环范围", sup, SUPER_BASE + 38, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("加速分频系数", sup, SUPER_BASE + 40, 1, ConfigFieldDef.FieldType.BYTE, 0, null, null, true);
        reg("采样电阻值", sup, SUPER_BASE + 41, 1, ConfigFieldDef.FieldType.BYTE, 0, "mΩ", null, true);
        reg("采样放大系数", sup, SUPER_BASE + 42, 2, ConfigFieldDef.FieldType.SCALED_BYTES2, 100, null, null, true);
        reg("电机极对数", sup, SUPER_BASE + 44, 1, ConfigFieldDef.FieldType.BYTE, 0, null, null, true);
        reg("额定速度", sup, SUPER_BASE + 45, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("最小速度", sup, SUPER_BASE + 47, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        // 保护投退（MENU_SUP_PROTEND1~4，w49~w52 位域，1=投用 0=退出）。
        // 位定义（固件 Fault_Bits 布局）：
        //   bit 0  开电机堵转     bit 1  关电机堵转     bit 4  开过力矩2     bit 5  关过力矩2
        //   bit 6  开阀拒动       bit 7  关阀拒动       bit 8  电机反向运行   bit 9  电位计随动异常
        //   bit 10 自检失败       bit 11 PID失败        bit 16 4-20mA无输入  bit 17 VFO故障
        //   bit 18 电压越限       bit 19 断电运行       bit 20 霍尔异常     bit 21 编码器通信异常
        //   bit 22 电容异常       bit 23 过温报警       bit 24 EEPROM错误   bit 25/26 PM1/2
        //   bit 31 未知错误
        // 当前开放的三个可编辑保护项（均为开启/关闭）：
        //   电机拒动保护：bit 6~7（开阀拒动 OMNOI + 关阀拒动 CMNOI 合并，任一为 1 即开启）
        regBit("电机拒动保护", sup, SUPER_BASE + 52, 0xC0, ON_OFF, true);
        regBit("传感器保护", sup, SUPER_BASE + 51, 0x02, ON_OFF, true);     // bit 9  DWJSD 电位计随动异常
        regBit("过温保护", sup, SUPER_BASE + 50, 0x80, ON_OFF, true);       // bit 23 OVERT 过温报警
        reg("电机关阀方向", sup, SUPER_BASE + 53, 1, ConfigFieldDef.FieldType.ENUM, 0, null, MOTOR_DIR, true);
        reg("数字接口波特率", sup, SUPER_BASE + 54, 1, ConfigFieldDef.FieldType.ENUM, 0, null, BAUD_RATE, true);
        reg("公司信息", sup, SUPER_BASE + 55, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ON_OFF, true);
        reg("MODBUS校验方式", sup, SUPER_BASE + 56, 1, ConfigFieldDef.FieldType.ENUM, 0, null, PARITY, true);
        reg("MODBUS类型", sup, SUPER_BASE + 57, 1, ConfigFieldDef.FieldType.ENUM, 0, null, MODBUS_TYPE, true);
        reg("过力矩重试开关", sup, SUPER_BASE + 58, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ON_OFF, true);
        reg("过力矩重试次数", sup, SUPER_BASE + 59, 1, ConfigFieldDef.FieldType.BYTE, 0, null, null, true);
        reg("按键保持", sup, SUPER_BASE + 60, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ON_OFF, true);
    }

    // ================= 帧结构参数 =================
    @Override protected int frameLength()      { return FRAME_LENGTH; }
    @Override protected int basicBase()        { return BASIC_BASE; }
    @Override protected int basicPayloadLen()  { return 54; }
    @Override protected int basicCrcOffset()   { return BASIC_CRC; }
    @Override protected int alarmBase()        { return ALARM_BASE; }
    @Override protected int alarmPayloadLen()  { return 9; }
    @Override protected int alarmCrcOffset()   { return ALARM_CRC; }
    @Override protected int superBase()        { return SUPER_BASE; }
    @Override protected int superPayloadLen()  { return 64; }
    @Override protected int superCrcOffset()   { return SUPER_CRC; }
    @Override protected List<ConfigFieldDef> fields() { return FIELDS; }
}
