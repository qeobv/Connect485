package org.example;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Modbus 配置参数翻译器 (双层CRC校验版)
 */
public class DeviceProtocolTranslator {

    private static int[] basicData = null;
    private static int[] alarmData = null;
    private static int[] superData = null;

    // ================= 枚举映射定义 =================
    private static final Map<Integer, String> SIGN_TYPE_MAP = Map.of(
            0, "4-20mA", 1, "保持", 2, "点动", 3, "二线制常开",
            4, "二线制常闭", 5, "Modbus", 6, "Profibus", 7, "Hart", 8, "压差PID"
    );
    private static final Map<Integer, String> ACTION_TYPE_MAP = Map.of(
            0, "全开", 1, "全关", 2, "保持", 3, "指定位置", 4, "关闭"
    );
    private static final Map<Integer, String> ALARM_PORT_MAP = Map.of(
            0, "全开", 1, "全关", 2, "给定报警", 3, "关过力矩", 4, "开过力矩",
            5, "指定位置", 6, "综合", 7, "远方", 8, "就地", 9, "关闭"
    );
    private static final Map<Integer, String> TORQUE_UNIT_MAP = Map.of(
            0, "N", 1, "Nm", 2, "kN"
    );
    private static final Map<Integer, String> BAUD_RATE_MAP = Map.of(
            0, "1200", 1, "2400", 2, "4800", 3, "9600", 4, "14400",
            5, "19200", 6, "38400", 7, "56000", 8, "57600", 9, "115200"
    );

    // ================= 辅助方法 =================
    private static Integer safeGet(int[] w, int index) {
        return (index >= 0 && index < w.length) ? w[index] : null;
    }

    private static String safeGetEnum(int[] w, int index, Map<Integer, String> enumMap) {
        Integer val = safeGet(w, index);
        return val == null ? "[缺失]" : enumMap.getOrDefault(val, "未知(" + val + ")");
    }

    private static String formatDecimal(int raw, int divisor) {
        double val = raw * 1.0 / divisor;
        return (val == Math.floor(val)) ? String.valueOf((int) val) : String.format("%.1f", val);
    }
    // ================= 核心解析逻辑 =================

    public static String translateFullPacket(String hexData) {
        try {
            // 1. 清洗数据
            String cleanHex = hexData.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
            if (cleanHex.length() < 10) return "报文过短";

            // 2. 解析 Modbus 帧头 (01 64 8B)
            int slaveAddr = Integer.parseInt(cleanHex.substring(0, 2), 16);
            int funcCode = Integer.parseInt(cleanHex.substring(2, 4), 16);
            int byteCount = Integer.parseInt(cleanHex.substring(4, 6), 16);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("====== Modbus 帧头 ======\n"));
            sb.append(String.format("地址: %d, 功能码: %d, 声明数据长度: %d 字节\n", slaveAddr, funcCode, byteCount));

            // 3. 将全部数据存入总数组 (包含帧头 01 64 8B)
            int totalBytes = cleanHex.length() / 2;
            int[] allBytes = new int[totalBytes];
            for (int i = 0; i < totalBytes; i++) {
                allBytes[i] = Integer.parseInt(cleanHex.substring(i * 2, i * 2 + 2), 16);
            }

            // 4. Modbus 整包 CRC 校验 (校验除最后2字节外的所有数据)
            if (!verifyModbusCRC(allBytes)) {
                sb.append("⚠️ Modbus整包CRC校验失败！\n");
            } else {
                sb.append("✔️ Modbus整包CRC校验通过\n");
            }

            // 5. 提取应用层总数据区 (跳过前3个字节即6个字符)
            int appDataStart = 3; // 在 allBytes 中的索引
            int pointer = appDataStart;

            // 6. 顺序拆分到三个子数组，各自校验并解析
            // --- 第一部分：基础设置 (01 38) ---
            if (pointer + 1 < totalBytes && allBytes[pointer] == 1 && allBytes[pointer + 1] == 56) {
                sb.append("\n================ [基础设置] ================\n");
                basicData = new int[56];
                System.arraycopy(allBytes, pointer + 2, basicData, 0, 56);

                if (verifySubCRC(basicData)) {
                    translateBasicSettings(basicData, 0, sb);
                } else {
                    sb.append("⚠️ 基础设置CRC校验失败\n");
                }
                pointer += 58;
            }

            // --- 第二部分：告警设置 (02 0B) ---
            if (pointer + 1 < totalBytes && allBytes[pointer] == 2 && allBytes[pointer + 1] == 11) {
                sb.append("\n================ [告警设置] ================\n");
                alarmData = new int[11];
                System.arraycopy(allBytes, pointer + 2, alarmData, 0, 11);

                if (verifySubCRC(alarmData)) {
                    translateAlarmSettings(alarmData, 0, sb);
                } else {
                    sb.append("⚠️ 告警设置CRC校验失败\n");
                }
                pointer += 13;
            }

            // --- 第三部分：高级设置 (03 42) ---
            if (pointer + 1 < totalBytes && allBytes[pointer] == 3 && allBytes[pointer + 1] == 66) {
                sb.append("\n================ [高级设置] ================\n");
                superData = new int[66];
                System.arraycopy(allBytes, pointer + 2, superData, 0, 66);

                if (verifySubCRC(superData)) {
                    translateSuperSettings(superData, 0, sb);
                } else {
                    sb.append("⚠️ 高级设置CRC校验失败\n");
                }
                pointer += 68;
            }

            if (pointer < totalBytes - 2) {
                sb.append(String.format("\n⚠️ 解析结束，但仍有 %d 字节未处理\n", totalBytes - 2 - pointer));
            }

            return sb.toString();

        } catch (Exception e) {
            e.printStackTrace();
            return "解析异常: " + e.getMessage();
        }
    }

    // ================= CRC 校验辅助方法 =================

    /**
     * Modbus 整包校验：校验除最后2字节CRC外的所有数据 (从 01 64 8B 一直到最后数据区结束)
     */
    private static boolean verifyModbusCRC(int[] data) {
        byte[] bytes = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            bytes[i] = (byte) (data[i] & 0xFF);
        }
        // 使用 Crc16Util.verifyCRC16，它会自动计算 length-2 的数据，并与最后2字节比对
        return Crc16Util.verifyCRC16(bytes, 0, bytes.length);
    }

    /**
     * 应用层子包校验：手动计算并比对 (解决高低字节顺序问题)
     */
    private static boolean verifySubCRC(int[] data) {
        if (data.length < 2) return false;
        int payloadLen = data.length - 2;
        // 1. 将 int[] �/$byte[] 数组
        byte[] bytes = new byte[payloadLen];
        for (int i = 0; i < payloadLen; i++) {
            bytes[i] = (byte) (data[i] & 0xFF);
        }

        // 2. 计算前 length-2 个字节的 CRC

        int calculatedCrc = Crc16Util.crc16(bytes, payloadLen);

        // 3. 从报文中提取接收到的 CRC (Modbus小端模式：低字节在前，高字节在后)
        // 倒数第2个字节是低字节，最后1个字节是高字节
        int receivedCrcL = data[data.length - 2] & 0xFF; // D6
        int receivedCrcH = data[data.length - 1] & 0xFF; // C2
        int receivedCrc = (receivedCrcL << 8) | receivedCrcH; // 0xC2D6

        // 4. 比对
        return calculatedCrc == receivedCrc;
    }


    // ================= 翻译方法=================
    private static void translateBasicSettings(int[] w, int offset, StringBuilder sb) {
        Map<String, String> map = new LinkedHashMap<>();

        // 基本设置参数
        map.put("中英文选择", w[offset+0] == 0 ? "中文" : "英文");
        map.put("开阀位置1", String.valueOf(w[offset+1]));
        map.put("开阀位置2", String.valueOf(w[offset+2]));
        map.put("开阀位置3", String.valueOf(w[offset+3]));
        map.put("开阀位置4", String.valueOf(w[offset+4]));
        map.put("关阀位置1", String.valueOf(w[offset+5]));
        map.put("关阀位置2", String.valueOf(w[offset+6]));
        map.put("关阀位置3", String.valueOf(w[offset+7]));
        map.put("关阀位置4", String.valueOf(w[offset+8]));
        map.put("输入4mA校准高字节", String.valueOf(w[offset+9]));
        map.put("输入4mA校准低字节", String.valueOf(w[offset+10]));
        map.put("输入20mA校准高字节", String.valueOf(w[offset+11]));
        map.put("输入20mA校准低字节", String.valueOf(w[offset+12]));
        map.put("输出4mA校准高字节", String.valueOf(w[offset+13]));
        map.put("输出4mA校准低字节", String.valueOf(w[offset+14]));
        map.put("输出20mA校准高字节", String.valueOf(w[offset+15]));
        map.put("输出20mA校准低字节", String.valueOf(w[offset+16]));
        map.put("死区调整", formatDecimal(w[offset+17], 1) + " S");
        map.put("远程信号类型", safeGetEnum(w, offset+18, SIGN_TYPE_MAP));
        map.put("信号故障类型", safeGetEnum(w, offset+19, ACTION_TYPE_MAP));
        map.put("信号故障指定位置1", String.valueOf(w[offset+20]));
        map.put("信号故障指定位置2", String.valueOf(w[offset+21]));
        map.put("断电动作类型", safeGetEnum(w, offset+22, ACTION_TYPE_MAP));
        map.put("断电指定位置1", String.valueOf(w[offset+23]));
        map.put("断电指定位置2", String.valueOf(w[offset+24]));
        map.put("断电延时时间高字节", String.valueOf(w[offset+25]));
        map.put("断电延时时间低字节", String.valueOf(w[offset+26]));
        map.put("ESD动作类型", safeGetEnum(w, offset+27, ACTION_TYPE_MAP));
        map.put("ESD指定位置1", String.valueOf(w[offset+28]));
        map.put("ESD指定位置2", String.valueOf(w[offset+29]));
        map.put("启动选项", w[offset+30] == 0 ? "本地控制" : "远程控制");
        map.put("正反作用", w[offset+31] == 0 ? "正作用" : "反作用");
        map.put("设备地址高字节", String.valueOf(w[offset+32]));
        map.put("设备地址低字节", String.valueOf(w[offset+33]));
        map.put("调试模式", w[offset+34] == 1 ? "开启" : "关闭");
        map.put("最大开速度", String.valueOf(w[offset+35]));
        map.put("最大关速度", String.valueOf(w[offset+36]));
        map.put("压力设定", formatDecimal(w[offset+37], 10) + " MPa");
        map.put("减速范围", String.valueOf(w[offset+38]) + " %");
        map.put("自动移动开关", w[offset+39] == 1 ? "开" : "关");
        map.put("自动移动周期", String.valueOf(w[offset+40]));
        map.put("自动移动时刻小时", String.valueOf(w[offset+41]));
        map.put("自动移动时刻分钟", String.valueOf(w[offset+42]));
        map.put("自动移动范围", String.valueOf(w[offset+43]) + " %");
        map.put("自动移动最长时间", String.valueOf(w[offset+44]) + " 分");
        map.put("压差PID_P", String.valueOf(w[offset+45]));
        map.put("压差PID_I", String.valueOf(w[offset+46]));
        map.put("压差PID_D", String.valueOf(w[offset+47]));
        map.put("保留参数1", String.valueOf(w[offset+48]));
        map.put("保留参数2", String.valueOf(w[offset+49]));
        map.put("保留参数3", String.valueOf(w[offset+50]));
        map.put("保留参数4", String.valueOf(w[offset+51]));
        map.put("保留参数5", String.valueOf(w[offset+52]));
        map.put("保留参数6", String.valueOf(w[offset+53]));
        map.put("基础参数CRC高字节", String.valueOf(w[offset+54]));
        map.put("基础参数CRC低字节", String.valueOf(w[offset+55]));

        map.forEach((k, v) -> sb.append(String.format("%-30s: %s\n", k, v)));
    }

    private static void translateAlarmSettings(int[] w, int offset, StringBuilder sb) {
        Map<String, String> map = new LinkedHashMap<>();

        // 告警设置参数
        map.put("报警开出端口1", safeGetEnum(w, offset+0, ALARM_PORT_MAP));
        map.put("报警开出端口2", safeGetEnum(w, offset+1, ALARM_PORT_MAP));
        map.put("报警开出端口3", safeGetEnum(w, offset+2, ALARM_PORT_MAP));
        map.put("报警开出端口4", safeGetEnum(w, offset+3, ALARM_PORT_MAP));
        map.put("报警开出端口5", safeGetEnum(w, offset+4, ALARM_PORT_MAP));
        map.put("报警开出端口6", safeGetEnum(w, offset+5, ALARM_PORT_MAP));
        map.put("报警指定位置1", String.valueOf(w[offset+6]));
        map.put("报警指定位置2", String.valueOf(w[offset+7]));
        map.put("报警范围", String.valueOf(w[offset+8]) + " %");
        map.put("高级参数CRC高字节", String.valueOf(w[offset+9]));
        map.put("高级参数CRC低字节", String.valueOf(w[offset+10]));

        map.forEach((k, v) -> sb.append(String.format("%-30s: %s\n", k, v)));
    }

    private static void translateSuperSettings(int[] w, int offset, StringBuilder sb) {
        Map<String, String> map = new LinkedHashMap<>();

        // 高级设置参数
        map.put("密码高字节", String.valueOf(w[offset+0]));
        map.put("密码低字节", String.valueOf(w[offset+1]));
        map.put("力矩单位", safeGetEnum(w, offset+2, TORQUE_UNIT_MAP));
        map.put("二级关过力矩1高字节", String.valueOf(w[offset+3]));
        map.put("二级关过力矩1低字节", String.valueOf(w[offset+4]));
        map.put("二级开过力矩1高字节", String.valueOf(w[offset+5]));
        map.put("二级开过力矩1低字节", String.valueOf(w[offset+6]));
        map.put("过力矩延时1高字节", String.valueOf(w[offset+7]));
        map.put("过力矩延时1低字节", String.valueOf(w[offset+8]));
        map.put("二级关过力矩2高字节", String.valueOf(w[offset+9]));
        map.put("二级关过力矩2低字节", String.valueOf(w[offset+10]));
        map.put("二级开过力矩2高字节", String.valueOf(w[offset+11]));
        map.put("二级开过力矩2低字节", String.valueOf(w[offset+12]));
        map.put("传动比(电机至电位器)高字节", String.valueOf(w[offset+13]));
        map.put("传动比(电机至电位器)低字节", String.valueOf(w[offset+14]));
        map.put("传动比(电机至阀门)高字节", String.valueOf(w[offset+15]));
        map.put("传动比(电机至阀门)低字节", String.valueOf(w[offset+16]));
        map.put("产品编号1", String.format("0x%02X", w[offset+17]));
        map.put("产品编号2", String.format("0x%02X", w[offset+18]));
        map.put("产品编号3", String.format("0x%02X", w[offset+19]));
        map.put("产品编号4", String.format("0x%02X", w[offset+20]));
        map.put("电位计保护误差", String.valueOf(w[offset+21]) + " %");
        map.put("力矩校准低点值高字节", String.valueOf(w[offset+22]));
        map.put("力矩校准低点值低字节", String.valueOf(w[offset+23]));
        map.put("力矩校准低点开阀值高字节", String.valueOf(w[offset+24]));
        map.put("力矩校准低点开阀值低字节", String.valueOf(w[offset+25]));
        map.put("力矩校准低点关阀值高字节", String.valueOf(w[offset+26]));
        map.put("力矩校准低点关阀值低字节", String.valueOf(w[offset+27]));
        map.put("力矩校准高点值高字节", String.valueOf(w[offset+28]));
        map.put("力矩校准高点值低字节", String.valueOf(w[offset+29]));
        map.put("力矩校准高点开阀值高字节", String.valueOf(w[offset+30]));
        map.put("力矩校准高点开阀值低字节", String.valueOf(w[offset+31]));
        map.put("力矩校准高点关阀值高字节", String.valueOf(w[offset+32]));
        map.put("力矩校准高点关阀值低字节", String.valueOf(w[offset+33]));
        map.put("速度环启动系数", String.valueOf(w[offset+34]));
        map.put("速度环最大电流AD值高字节", String.valueOf(w[offset+35]));
        map.put("速度环最大电流AD值低字节", String.valueOf(w[offset+36]));
        map.put("电流环P系数", String.valueOf(w[offset+37]));
        map.put("电流环调节范围高字节", String.valueOf(w[offset+38]));
        map.put("电流环调节范围低字节", String.valueOf(w[offset+39]));
        map.put("电流环加速度减档", String.valueOf(w[offset+40]));
        map.put("采样电阻值", String.valueOf(w[offset+41]) + " mΩ");
        map.put("采样放大系数高字节", String.valueOf(w[offset+42]));
        map.put("采样放大系数低字节", String.valueOf(w[offset+43]));
        map.put("极对数", String.valueOf(w[offset+44]));
        map.put("额定速度高字节", String.valueOf(w[offset+45]));
        map.put("额定速度低字节", String.valueOf(w[offset+46]));
        map.put("最小速度高字节", String.valueOf(w[offset+47]));
        map.put("最小速度低字节", String.valueOf(w[offset+48]));
        map.put("保护参数1", String.valueOf(w[offset+49]));
        map.put("保护参数2", String.valueOf(w[offset+50]));
        map.put("保护参数3", String.valueOf(w[offset+51]));
        map.put("保护参数4", String.valueOf(w[offset+52]));
        map.put("机体正反逻辑", w[offset+53] == 0 ? "正逻辑" : "反逻辑");
        map.put("数字接口波特率", safeGetEnum(w, offset+54, BAUD_RATE_MAP));
        map.put("通讯开关", w[offset+55] == 1 ? "打开" : "关闭");
        map.put("MODBUS校验方式", w[offset+56] == 0 ? "无校验" : (w[offset+56] == 1 ? "奇校验" : "偶校验"));
        map.put("MODBUS类型", w[offset+57] == 0 ? "Modbus-RTU" : "Modbus-TCP");
        map.put("过力矩重试使能", w[offset+58] == 1 ? "开启" : "关闭");
        map.put("过力矩重试次数", String.valueOf(w[offset+59]));
        map.put("按键保持", String.valueOf(w[offset+60]));
        map.put("保留参数1", String.valueOf(w[offset+61]));
        map.put("保留参数2", String.valueOf(w[offset+62]));
        map.put("保留参数3", String.valueOf(w[offset+63]));
        map.put("出厂参数CRC高字节", String.valueOf(w[offset+64]));
        map.put("出厂参数CRC低字节", String.valueOf(w[offset+65]));

        map.forEach((k, v) -> sb.append(String.format("%-30s: %s\n", k, v)));
    }

    /**
     * 将两个字节组合成一个16位整数
     * @param highByte 高字节
     * @param lowByte 低字节
     * @return 组合后的16位整数
     */
    private static int combineBytes(int highByte, int lowByte) {
        return ((highByte & 0xFF) << 8) | (lowByte & 0xFF);
    }

    /**
     * 将四个字节组合成一个32位整数
     * @param byte1 最高位字节
     * @param byte2 次高位字节
     * @param byte3 次低位字节
     * @param byte4 最低位字节
     * @return 组合后的32位整数
     */
    private static int combineBytes(int byte1, int byte2, int byte3, int byte4) {
        return ((byte1 & 0xFF) << 24) | ((byte2 & 0xFF) << 16) |
                ((byte3 & 0xFF) << 8) | (byte4 & 0xFF);
    }

    /**
     * 将两个字节组合成一个16位浮点数
     * @param highByte 高字节
     * @param lowByte 低字节
     * @return 组合后的16位浮点数
     */
    private static float combineToFloat16(int highByte, int lowByte) {
        // 16位浮点数需要特殊处理，这里只是示例
        // 实际实现取决于你的16位浮点数格式
        short bits = (short) (((highByte & 0xFF) << 8) | (lowByte & 0xFF));
        return Float.intBitsToFloat(bits & 0xFFFF);
    }

    /**
     * 将四个字节组合成一个32位浮点数
     * @param highByte1 高字节1
     * @param highByte2 高字节2
     * @param lowByte1 低字节1
     * @param lowByte2 低字节2
     * @return 组合后的32位浮点数
     */
    private static float combineToFloat32(int highByte1, int highByte2, int lowByte1, int lowByte2) {
        int bits = ((highByte1 & 0xFF) << 24) | ((highByte2 & 0xFF) << 16) |
                ((lowByte1 & 0xFF) << 8) | (lowByte2 & 0xFF);
        return Float.intBitsToFloat(bits);
    }


    public static void main(String[] args) {
        String hexData = "01 64 8B \n" +
                "01 38 \n" +
                "00 \n" +
                "00 7F DD A0 00 7F E3 18 02 C6 \n" +
                "00 04 02 DA 0E 1A 05 05 02 01 \n" +
                "F4 02 01 F4 07 D0 02 01 F4 01 \n" +
                "00 00 01 00 3C 3C 00 32 00 1E \n" +
                "00 00 0A 0A 0A 05 01 00 00 00 \n" +
                "00 00 00 D6 C2 \n" +
                "02 0B \n" +
                "00 \n" +
                "01 06 07 08 0F 01 F4 05 AD 93 \n" +
                "03 42 \n" +
                "00 00 01 00 C8 00 C8 05 DC 00 \n" +
                "64 00 64 05 56 07 37 01 7E 92\n" +
                "4B 14 00 00 00 0F 00 0F 00 C8 \n" +
                "00 37 00 37 0A 01 2C 04 00 C8 \n" +
                "03 1E 01 3A 03 0F A0 01 90 00\n" +
                "00 01 F3 01 03 01 00 00 00 01 \n" +
                "00 00 00 00 A2 05\n" +
                "11 2B";
        System.out.println(translateFullPacket(hexData));
    }
}
