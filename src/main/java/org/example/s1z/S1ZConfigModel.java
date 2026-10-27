package org.example.s1z;

import org.example.core.AbstractPacketConfigModel;
import org.example.core.ConfigFieldDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MTA（S1-Z 多回转）系列 0x64 配置模型：支持"读-改-写"式单参数修改。
 *
 * 帧结构（共 135 字节，数据区长度按 datarw.h）：
 *   索引 0~2   : 从站地址 + 功能码 0x64 + 数据长度
 *   索引 3~4   : 子包标记 (01 2A) 基础设置
 *   索引 5~46  : basicData[0..41]（42 字节，其中 [40..41] 为子包 CRC）
 *   索引 47~48 : 子包标记 (02 13) 继电器输出设置
 *   索引 49~67 : alarmData[0..18]（19 字节，其中 [17..18] 为子包 CRC）
 *   索引 68~69 : 子包标记 (03 3F) 高级设置
 *   索引 70~132: superData[0..62]（63 字节，其中 [61..62] 为子包 CRC）
 *   索引 133~134: Modbus 整包 CRC
 *
 * 字段布局按 datarw.h（EEPROM_BASE_DATA_LEN=42 / ALM=19 / SUP=63）。
 */
public class S1ZConfigModel extends AbstractPacketConfigModel {

    public static final int FRAME_LENGTH = 135;

    private static final int BASIC_BASE = 5;
    private static final int ALARM_BASE = 49;
    private static final int SUPER_BASE = 70;

    private static final int BASIC_CRC = 45;    // basicData[40..41]
    private static final int ALARM_CRC = 66;    // alarmData[17..18]
    private static final int SUPER_CRC = 131;   // superData[61..62]

    // ================= 枚举映射（按 datarw.h 注释） =================
    private static final Map<Integer, String> LANG = Map.of(0, "中文", 1, "英文");
    private static final Map<Integer, String> PN_LOGIC1 = Map.of(0, "4mA关阀", 1, "4mA开阀");
    private static final Map<Integer, String> PN_LOGIC2 = Map.of(0, "4mA关到位", 1, "4mA开到位");
    private static final Map<Integer, String> PN_DIR = Map.of(0, "顺时针关阀", 1, "逆时针关阀");
    private static final Map<Integer, String> NO_NC = Map.of(0, "常开", 1, "常闭");
    private static final Map<Integer, String> SIGN_TYPE = Map.of(
            0, "4-20mA", 1, "电平型", 2, "脉冲型", 3, "二线制常开", 4, "二线制常关",
            5, "开关电平+调节", 6, "开关脉冲+调节", 7, "Modbus", 8, "Profibus", 9, "Hart");
    private static final Map<Integer, String> ACTION_TYPE = Map.of(
            0, "全开", 1, "全关", 2, "保持", 3, "指定位置", 4, "关闭");
    private static final Map<Integer, String> STARTUP = Map.of(0, "本地控制", 1, "远程控制");
    private static final Map<Integer, String> ON_OFF = Map.of(0, "关闭", 1, "开启");
    private static final Map<Integer, String> TORQUE_UNIT = Map.of(0, "N", 1, "Nm");   // MTA 仅 0/1
    private static final Map<Integer, String> PWR_TYPE = Map.of(0, "380V", 1, "220V", 2, "110V");
    private static final Map<Integer, String> GLJ_MODE = Map.of(0, "力矩传感器", 1, "电流检测力矩");
    private static final Map<Integer, String> MACH_LOGIC = Map.of(0, "顺时针关阀", 1, "逆时针关阀");
    private static final Map<Integer, String> BAUD_RATE = Map.of(
            0, "1200", 1, "2400", 2, "4800", 3, "9600", 4, "14400",
            5, "19200", 6, "38400", 7, "56000", 8, "57600", 9, "115200");
    private static final Map<Integer, String> PARITY = Map.of(0, "无校验", 1, "奇校验", 2, "偶校验");
    private static final Map<Integer, String> MODBUS_TYPE = Map.of(0, "Modbus-RTU", 1, "Modbus-TCP");

    private static final Map<Integer, String> ALARM_PORT = new LinkedHashMap<>();
    static {
        ALARM_PORT.put(0, "全开");
        ALARM_PORT.put(1, "全关");
        ALARM_PORT.put(2, "信号断线");
        ALARM_PORT.put(3, "开过力矩");
        ALARM_PORT.put(4, "关过力矩");
        ALARM_PORT.put(5, "指定位置");
        ALARM_PORT.put(6, "综合报警");
        ALARM_PORT.put(7, "远程");
        ALARM_PORT.put(8, "就地");
        ALARM_PORT.put(9, "开关过力矩");
        ALARM_PORT.put(10, "正在开");
        ALARM_PORT.put(11, "正在关");
        ALARM_PORT.put(12, "正在开关");
        ALARM_PORT.put(13, "停止");
        ALARM_PORT.put(14, "电源正常");
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
        // --- 基础设置（basicData，帧内偏移 5+i，共 42 字节） ---
        reg("中英文选择", basic, BASIC_BASE + 0, 1, ConfigFieldDef.FieldType.ENUM, 0, null, LANG, true);
        reg("关阀方向", basic, BASIC_BASE + 28, 1, ConfigFieldDef.FieldType.ENUM, 0, null, PN_DIR, true);
        reg("开阀位置", basic, BASIC_BASE + 1, 4, ConfigFieldDef.FieldType.POS4096, 0, null, null, true);
        reg("关阀位置", basic, BASIC_BASE + 5, 4, ConfigFieldDef.FieldType.POS4096, 0, null, null, true);
        reg("信号故障类型", basic, BASIC_BASE + 21, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ACTION_TYPE, true);
        reg("信号故障指定位置", basic, BASIC_BASE + 22, 2, ConfigFieldDef.FieldType.SCALED_BYTES2, 10, "%", null, true);
        reg("ESD动作类型", basic, BASIC_BASE + 24, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ACTION_TYPE, true);
        reg("ESD指定位置", basic, BASIC_BASE + 25, 2, ConfigFieldDef.FieldType.SCALED_BYTES2, 10, "%", null, true);
        reg("死区范围设置", basic, BASIC_BASE + 19, 1, ConfigFieldDef.FieldType.SCALED_BYTE, 10, "%", null, true);
        reg("远程信号类型", basic, BASIC_BASE + 20, 1, ConfigFieldDef.FieldType.ENUM, 0, null, SIGN_TYPE, true);
        reg("停止常开常闭", basic, BASIC_BASE + 38, 1, ConfigFieldDef.FieldType.ENUM, 0, null, NO_NC, true);
        reg("ESD常开常闭", basic, BASIC_BASE + 39, 1, ConfigFieldDef.FieldType.ENUM, 0, null, NO_NC, true);

        reg("输入4mA校准", basic, BASIC_BASE + 9, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("输入20mA校准", basic, BASIC_BASE + 11, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("输入正反作用", basic, BASIC_BASE + 17, 1, ConfigFieldDef.FieldType.ENUM, 0, null, PN_LOGIC1, true);
        reg("输出4mA校准", basic, BASIC_BASE + 13, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("输出20mA校准", basic, BASIC_BASE + 15, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("输出正反作用", basic, BASIC_BASE + 18, 1, ConfigFieldDef.FieldType.ENUM, 0, null, PN_LOGIC2, true);
        reg("设备地址", basic, BASIC_BASE + 29, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, false);

       // reg("启动选项", basic, BASIC_BASE + 27, 1, ConfigFieldDef.FieldType.ENUM, 0, null, STARTUP, true);
        // 调试模式(w31)、自动移动(w32~37) 按用户要求不再显示

        String alarm = "报警设置";
        // --- 报警设置（alarmData，帧内偏移 49+i，共 19 字节，12 路继电器） ---
        for (int i = 0; i < 12; i++) {
            reg(String.format("报警%02d设置", i + 1), alarm, ALARM_BASE + i, 1,
                    ConfigFieldDef.FieldType.ENUM, 0, null, ALARM_PORT, true);
        }
        reg("报警指定位置", alarm, ALARM_BASE + 12, 2, ConfigFieldDef.FieldType.SCALED_BYTES2, 10, "%", null, true);
        reg("反馈区间设置", alarm, ALARM_BASE + 14, 1, ConfigFieldDef.FieldType.SCALED_BYTE, 10, "%", null, true);

        String sup = "高级设置";
        // --- 高级设置（superData，帧内偏移 70+i，共 63 字节） ---
        regBit("过力矩保护", sup, SUPER_BASE + 24, 0x0C, ON_OFF, true);   // bit10~11 关/开过力矩
        reg("开过力矩系数", sup, SUPER_BASE + 4, 2, ConfigFieldDef.FieldType.BYTES2, 0, "%", null, true);
        reg("关过力矩系数", sup, SUPER_BASE + 6, 2, ConfigFieldDef.FieldType.BYTES2, 0, "%", null, true);
        reg("过力矩延迟时间", sup, SUPER_BASE + 8, 2, ConfigFieldDef.FieldType.BYTES2, 0, "ms", null, true);
        regBit("传感器保护", sup, SUPER_BASE + 24, 0x30, ON_OFF, true);   // bit12~13 关/开未随动（电位计随动异常）
        reg("传感器保护时间", sup, SUPER_BASE + 22, 1, ConfigFieldDef.FieldType.SCALED_BYTE1, 10, "S", null, true);   // MENU_SUP_DWJPROTTIME
        regBit("堵转保护", sup, SUPER_BASE + 24, 0x03, ON_OFF, true);     // bit8~9 关/开阀堵转
        reg("堵转时间", sup, SUPER_BASE + 36, 1, ConfigFieldDef.FieldType.BYTE, 0, "S", null, true);                    // MENU_SUP_ROTEDTIME
        regBit("拒动保护", sup, SUPER_BASE + 25, 0xC0, ON_OFF, true);     // bit6~7 关/开阀拒动
        regBit("过温保护", sup, SUPER_BASE + 25, 0x10, ON_OFF, true);     // bit4 过温
        reg("位置传动比", sup, SUPER_BASE + 12, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("机械传动比", sup, SUPER_BASE + 10, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("电机关阀方向", sup, SUPER_BASE + 32, 1, ConfigFieldDef.FieldType.ENUM, 0, null, MACH_LOGIC, true);
        reg("停车位置", sup, SUPER_BASE + 23, 1, ConfigFieldDef.FieldType.SCALED_BYTE, 10, "%", null, true);
        reg("刹车制动延时", sup, SUPER_BASE + 26, 1, ConfigFieldDef.FieldType.BYTE, 0, "ms", null, true);
        reg("刹车制动时间", sup, SUPER_BASE + 27, 1, ConfigFieldDef.FieldType.BYTE, 0, "ms", null, true);
        reg("电源类型", sup, SUPER_BASE + 3, 1, ConfigFieldDef.FieldType.ENUM, 0, null, PWR_TYPE, true);
        reg("额定电流", sup, SUPER_BASE + 16, 2, ConfigFieldDef.FieldType.CURRENT_A, 0, null, null, true);
        reg("采样放大系数", sup, SUPER_BASE + 18, 2, ConfigFieldDef.FieldType.SCALED_FIXED3, 0, null, null, true);
        reg("采样CT变化", sup, SUPER_BASE + 38, 1, ConfigFieldDef.FieldType.BYTE, 0, null, null, true);
        reg("力矩校准开关", sup, SUPER_BASE + 51, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ON_OFF, true);   // MENU_SUP_LJDEBUGMODE
       // reg("力矩校准低点值", sup, SUPER_BASE + 39, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("空载开电流值", sup, SUPER_BASE + 41, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("空载关电流值", sup, SUPER_BASE + 43, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
       // reg("力矩校准高点值", sup, SUPER_BASE + 45, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("负载开电流值", sup, SUPER_BASE + 47, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("负载关电流值", sup, SUPER_BASE + 49, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
        reg("产品编号", sup, SUPER_BASE + 28, 4, ConfigFieldDef.FieldType.BYTES4, 0, null, null, false);
        reg("密码设置", sup, SUPER_BASE + 0, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, false);
        reg("公司信息", sup, SUPER_BASE + 37, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ON_OFF, true);

     //   reg("力矩单位", sup, SUPER_BASE + 2, 1, ConfigFieldDef.FieldType.ENUM, 0, null, TORQUE_UNIT, true);
     //   reg("最大速度", sup, SUPER_BASE + 14, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
      //  reg("电位器圈数", sup, SUPER_BASE + 20, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
     //   reg("电位计保护误差", sup, SUPER_BASE + 22, 1, ConfigFieldDef.FieldType.BYTE, 0, null, null, true);
        // 保护投入退出（w24~w25 位域，1=投用 0=退出；位定义按故障代码-1）：
        //   bit 4  过温保护        bit 6  关阀拒动      bit 7  开阀拒动
        //   bit 8  关阀堵转        bit 9  开阀堵转      bit 10 关过力矩      bit 11 开过力矩
        //   bit 12 关未随动        bit 13 开未随动
        // 开放 5 个合并保护项（每对关/开合并为一个开关，任一为 1 即开启）


      //  reg("编码器分辨率", sup, SUPER_BASE + 33, 2, ConfigFieldDef.FieldType.BYTES2, 0, null, null, true);
      //  reg("编码器波特率", sup, SUPER_BASE + 35, 1, ConfigFieldDef.FieldType.ENUM, 0, null, BAUD_RATE, true);
     //   reg("调试模式", sup, SUPER_BASE + 51, 1, ConfigFieldDef.FieldType.ENUM, 0, null, ON_OFF, true);
     //   reg("过力矩方式", sup, SUPER_BASE + 52, 1, ConfigFieldDef.FieldType.ENUM, 0, null, GLJ_MODE, true);
     //   reg("MODBUS校验方式", sup, SUPER_BASE + 53, 1, ConfigFieldDef.FieldType.ENUM, 0, null, PARITY, true);
       // reg("MODBUS类型", sup, SUPER_BASE + 54, 1, ConfigFieldDef.FieldType.ENUM, 0, null, MODBUS_TYPE, true);
    }

    // ================= 帧结构参数 =================
    @Override protected int frameLength()      { return FRAME_LENGTH; }
    @Override protected int basicBase()        { return BASIC_BASE; }
    @Override protected int basicPayloadLen()  { return 40; }
    @Override protected int basicCrcOffset()   { return BASIC_CRC; }
    @Override protected int alarmBase()        { return ALARM_BASE; }
    @Override protected int alarmPayloadLen()  { return 17; }
    @Override protected int alarmCrcOffset()   { return ALARM_CRC; }
    @Override protected int superBase()        { return SUPER_BASE; }
    @Override protected int superPayloadLen()  { return 61; }
    @Override protected int superCrcOffset()   { return SUPER_CRC; }
    @Override protected List<ConfigFieldDef> fields() { return FIELDS; }
}
