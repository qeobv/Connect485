package org.example;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Modbus 配置参数翻译器 (双层CRC校验版)
 */
public class DeviceProtocolTranslator {

    private static int[] basicData = null;
    private static int[] alarmData = null;
    private static int[] superData = null;
    private static int[] partData = null;

    // ================= 枚举映射定义 =================
    private static final Map<Integer, String> SIGN_TYPE_MAP = Map.of(
            0, "4-20mA", 1, "保持", 2, "点动", 3, "二线制常开",
            4, "二线制常闭", 5, "Modbus", 6, "Profibus", 7, "Hart", 8, "压差PID"
    );
    private static final Map<Integer, String> ACTION_TYPE_MAP = Map.of(
            0, "全开", 1, "全关", 2, "保持", 3, "指定位置", 4, "关闭"
    );
    private static final Map<Integer, String> ALARM_PORT_MAP = Map.ofEntries(
            Map.entry(0, "全开"),
            Map.entry(1, "全关"),
            Map.entry(2, "给定信号故障"),
            Map.entry(3, "开过力矩"),
            Map.entry(4, "关过力矩"),
            Map.entry(5, "指定位置"),
            Map.entry(6, "综合报警"),
            Map.entry(7, "远方"),
            Map.entry(8, "就地"),
            Map.entry(9,"主电源故障"),
            Map.entry(10, "开关过力矩"),
            Map.entry(11, "正在开"),
            Map.entry(12, "正在关"),
            Map.entry(13, "正在开关"),
            Map.entry(14, "上电指示"),
            Map.entry(15, "禁止")
    );

    private static final Map<Integer, String> TORQUE_UNIT_MAP = Map.of(
            0, "N", 1, "Nm", 2, "kN"
    );
    private static final Map<Integer, String> BAUD_RATE_MAP = Map.of(
            0, "1200", 1, "2400", 2, "4800", 3, "9600", 4, "14400",
            5, "19200", 6, "38400", 7, "56000", 8, "57600", 9, "115200"
    );

    // ================= 报警类型映射表 =================
    private static final String[][] ALARM_MASS_DATA64 = {
            {"开阀堵转  ", "OPEN LR "},	    //10 0x0b
            {"关阀堵转  ", "CLOS LR "},	    //10 0x0b
            {"开过力矩1 ", "OP OVT1 "},	    //1  0x08
            {"关过力矩1 ", "CL OVT1 "},	    //1  0x08
            {"开过力矩  ", "OP OVT  "},	    //2  0x07
            {"关过力矩  ", "CL OVT  "},	    //2  0x07
            {"开阀拒动  ", "OPEN NC "},   	  //3
            {"关阀拒动  ", "CLOS NC "},   	  //3
            {"反向运行  ", "REVERSE "},	     //15 0x0c
            {"传动异常  ", "Not Fall"},	     //15 0x0c
            {"自检失败  ", "SC FAIL "},	     //15 0x0c
            {"调压失败  ", "PID FAIL"},	     //15 0x0c
            {"预留13    ", "RESERV13"},	     //15 0x0c
            {"预留14    ", "RESERV14"},	     //15 0x0c
            {"预留15    ", "RESERV15"},	     //15 0x0c
            {"预留16    ", "RESERV16"},	     //15 0x0c
            {"信号断线  ", "NO Input"},   	//3  0x05
            {"驱动保护  ", "VFO ERR "},   	//3  0x05
            {"电压越限  ", "VOL HIGH"},   	//3  0x05
            {"电源断线  ", "MPWR LOS"},	     //15 0x0c
            {"霍尔故障  ", "HALL ERR"},	     //15 0x0c
            {"编码器故障", "POS UNOB"},	    //7  POSITON UNABLE TO OBTAIN
            {"电容异常  ", "CAP ERR "},	     //15 0x0c
            {"电机过温  ", "OV TEMP "},	     //15 0x0c
            {"存储异常  ", "EE ERR  "},     //5 0x0e
            {"压表1异常 ", "PM1 ERR "},      //16 0x0f
            {"压表2异常 ", "PM2 ERR "},      //16 0x0f
            {"预留28    ", "RESERV28"},      //16 0x0f
            {"预留29    ", "RESERV29"},      //16 0x0f
            {"预留30    ", "RESERV30"},      //16 0x0f
            {"预留31    ", "RESERV31"},      //16 0x0f
            {"未知错误  ", "Unk ERR "},      //16 0x0f
    };

    /**
     * 安全获取数组中指定索引的元素值
     * @param w 目标数组
     * @param index 要获取的元素索引
     * @return 如果索引在数组范围内，返回对应索引的元素值；否则返回null
     */
    private static Integer safeGet(int[] w, int index) {
        // 使用三元运算符判断索引是否在数组范围内
        // 如果索引在0到数组长度-1之间，返回对应元素值
        // 否则返回null
        return (index >= 0 && index < w.length) ? w[index] : null;
    }

    private static String safeGetEnum(int[] w, int index, Map<Integer, String> enumMap) {
        // 安全地从数组中获取指定索引的值，避免数组越界
        Integer val = safeGet(w, index);
        // 如果值为null，返回"[缺失]"；否则尝试从枚举映射表中获取对应值，如果不存在则返回"未知(值)"
        return val == null ? "[缺失]" : enumMap.getOrDefault(val, "未知(" + val + ")");
    }

    /**
     * 格式化小数数值，根据数值是否为整数返回不同格式的字符串
     * @param raw 原始数值，将被转换为double类型
     * @param divisor 除数，用于计算最终的小数值
     * @return 如果计算结果是整数，返回整数形式的字符串；否则返回保留一位小数的字符串
     */
    private static String formatDecimal(int raw, int divisor) {
        // 将原始值转换为double类型并除以除数，得到小数值
        double val = raw * 1.0 / divisor;
        // 判断值是否等于其向下取整的值，如果是则说明是整数
        // 使用三元运算符：如果是整数，转换为int类型后转为字符串；否则格式化为保留一位小数的字符串
        return (val == Math.floor(val)) ? String.valueOf((int) val) : String.format("%.1f", val);
    }

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
    public static String translatePartPacket(String hexData) {
        try{
            String cleanHex = hexData.replaceAll("[^0-9A-Fa-f]", "").toUpperCase();
            if (cleanHex.length() < 10) return "无历史记录";
            else {
                int slaveAddr = Integer.parseInt(cleanHex.substring(0, 2), 16);
                int funcCode = Integer.parseInt(cleanHex.substring(2, 4), 16);
                int byteCount = Integer.parseInt(cleanHex.substring(4, 6), 16);

                int partCount= Integer.parseInt(cleanHex.substring(6, 8),16);
                int partNumber= Integer.parseInt(cleanHex.substring(8, 10),16);

                StringBuilder sb = new StringBuilder();
                sb.append(String.format("====== Modbus 帧头 ======\n"));
                sb.append(String.format("地址: %d, 功能码: %d, 声明数据长度: %d 字节\n", slaveAddr, funcCode, byteCount));
                sb.append(String.format("数据总条数：%d， 当前第%d条",partCount,partNumber + 1));
                // 3. 将全部数据存入总数组 (包含帧头 01 66 8B)
                int totalBytes = cleanHex.length() / 2;
                int[] allBytes = new int[totalBytes];
                for (int i = 0; i < totalBytes; i++) {
                    allBytes[i] = Integer.parseInt(cleanHex.substring(i * 2, i * 2 + 2), 16);
                }
                // 4. Modbus 整包 CRC 校验 (校验除最后2字节外的所有数据)
                if (!verifyModbusCRC(allBytes)) {
                    sb.append("⚠️ ModbusCRC校验失败！\n");
                    return sb.toString();
                } else {
                    sb.append("✔️ ModbusCRC校验通过\n");
                    partData = new int[allBytes.length-7];
                    int dataStart = 5;
                    System.arraycopy(allBytes,dataStart,partData,0,allBytes.length-7);
                    translatePartSettings(partData, sb);
                    return sb.toString();
                }
            }
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
        int divisor1 = combineBytes(w[offset+1], w[offset+2], w[offset+3], w[offset+4]) / 4096;
        int remainder1 = combineBytes(w[offset+1], w[offset+2], w[offset+3], w[offset+4]) % 4096;
        int divisor2 = combineBytes(w[offset+5], w[offset+6], w[offset+7], w[offset+8]) / 4096;
        int remainder2 = combineBytes(w[offset+5], w[offset+6], w[offset+7], w[offset+8]) % 4096;
        Map<String, String> map = new LinkedHashMap<>();
        // 基本设置参数
        map.put("中英文选择", w[offset+0] == 0 ? "中文" : "英文");
        //    map.put("开阀位置1", String.valueOf(w[offset+1]));
        //    map.put("开阀位置2", String.valueOf(w[offset+2]));
        //    map.put("开阀位置3", String.valueOf(w[offset+3]));
        //    map.put("开阀位置4", String.valueOf(w[offset+4]));
        map.put("开阀位置",String.format("%04d.%04d", divisor1, remainder1));
        //  map.put("关阀位置1", String.valueOf(w[offset+5]));
        //  map.put("关阀位置2", String.valueOf(w[offset+6]));
        //   map.put("关阀位置3", String.valueOf(w[offset+7]));
        //    map.put("关阀位置4", String.valueOf(w[offset+8]));
        map.put("关阀位置",String.format("%04d.%04d", divisor2, remainder2));
        //    map.put("输入4mA校准高字节", String.valueOf(w[offset+9]));
        //    map.put("输入4mA校准低字节", String.valueOf(w[offset+10]));
        map.put("输入4mA校准", String.valueOf(combineBytes(w[offset + 9],w[offset + 10])));
        //    map.put("输入20mA校准高字节", String.valueOf(w[offset+11]));
        //    map.put("输入20mA校准低字节", String.valueOf(w[offset+12]));
        map.put("输入20mA校准", String.valueOf(combineBytes(w[offset + 11],w[offset + 12])));
        //    map.put("输出4mA校准高字节", String.valueOf(w[offset+13]));
        //    map.put("输出4mA校准低字节", String.valueOf(w[offset+14]));
        map.put("输出4mA校准", String.valueOf(combineBytes(w[offset + 13],w[offset + 14])));
        //    map.put("输出20mA校准高字节", String.valueOf(w[offset+15]));
        //    map.put("输出20mA校准低字节", String.valueOf(w[offset+16]));
        map.put("输出20mA校准", String.valueOf(combineBytes(w[offset + 15],w[offset + 16])));
        map.put("死区调整", formatDecimal(w[offset+17], 10) + " S");
        map.put("远程信号类型", safeGetEnum(w, offset+18, SIGN_TYPE_MAP));
        map.put("信号故障类型", safeGetEnum(w, offset+19, ACTION_TYPE_MAP));
        //   map.put("信号故障指定位置1", String.valueOf(w[offset+20]));
        //   map.put("信号故障指定位置2", String.valueOf(w[offset+21]));
        map.put("信号故障指定位置", String.format("%.1f", combineBytes(w[offset + 20],w[offset + 21]) / 10.0) + "%");
        map.put("断电动作类型", safeGetEnum(w, offset+22, ACTION_TYPE_MAP));
        //   map.put("断电指定位置1", String.valueOf(w[offset+23]));
        //   map.put("断电指定位置2", String.valueOf(w[offset+24]));
        map.put("断电指定位置", String.format("%.1f", combineBytes(w[offset + 23],w[offset + 24]) / 10.0) + "%");
        //    map.put("断电延时时间高字节", String.valueOf(w[offset+25]));
        //   map.put("断电延时时间低字节", String.valueOf(w[offset+26]));
        map.put("断电延时时间", String.valueOf(combineBytes(w[offset+25],w[offset+26])+" ms"));
        map.put("ESD动作类型", safeGetEnum(w, offset+27, ACTION_TYPE_MAP));
        //    map.put("ESD指定位置1", String.valueOf(w[offset+28]));
        //    map.put("ESD指定位置2", String.valueOf(w[offset+29]));
        map.put("ESD指定位置", String.format("%.1f", combineBytes(w[offset + 28],w[offset + 29]) / 10.0) + "%");
        map.put("启动选项", w[offset+30] == 0 ? "本地控制" : "远程控制");
        map.put("正反作用", w[offset+31] == 0 ? "正作用" : "反作用");
        //   map.put("设备地址高字节", String.valueOf(w[offset+32]));
        //   map.put("设备地址低字节", String.valueOf(w[offset+33]));
        map.put("设备地址", String.valueOf(combineBytes(w[offset + 32],w[offset + 33])));
        map.put("调试模式", w[offset+34] == 1 ? "开启" : "关闭");
        map.put("最大开速度", String.valueOf(w[offset+35]+ " %"));
        map.put("最大关速度", String.valueOf(w[offset+36]+ " %"));
        map.put("压力设定", formatDecimal(w[offset+37], 10) + " MPa");
        map.put("减速范围", formatDecimal(w[offset+38], 10) + " %");
        map.put("自动移动开关", w[offset+39] == 1 ? "开" : "关");
        map.put("自动移动周期", String.valueOf(w[offset+40]));
        map.put("自动移动时刻小时", String.valueOf(w[offset+41]));
        map.put("自动移动时刻分钟", String.valueOf(w[offset+42]));
        map.put("自动移动范围", formatDecimal(w[offset+43], 10) + " %");
        map.put("自动移动最长时间", String.valueOf(w[offset+44] + "S"));
        map.put("压差PID_P", String.valueOf(w[offset+45] / 10.0));
        map.put("压差PID_I", String.valueOf(w[offset+46] / 10.0));
        map.put("压差PID_D", String.valueOf(w[offset+47] / 10.0));
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
        //    map.put("报警指定位置1", String.valueOf(w[offset+6]));
        //    map.put("报警指定位置2", String.valueOf(w[offset+7]));
        map.put("报警指定位置", String.format("%.1f", combineBytes(w[offset + 6],w[offset + 7]) / 10.0) + "%");
        map.put("报警范围", String.valueOf(w[offset+8] / 10.0) + " %");
        map.put("高级参数CRC高字节", String.valueOf(w[offset+9]));
        map.put("高级参数CRC低字节", String.valueOf(w[offset+10]));

        map.forEach((k, v) -> sb.append(String.format("%-30s: %s\n", k, v)));
    }

    private static void translateSuperSettings(int[] w, int offset, StringBuilder sb) {
        Map<String, String> map = new LinkedHashMap<>();

        // 高级设置参数
        //    map.put("密码高字节", String.valueOf(w[offset+0]));
        //    map.put("密码低字节", String.valueOf(w[offset+1]));
        map.put("密码", String.valueOf(combineBytes(w[offset + 0],w[offset + 1])));
        map.put("力矩单位", safeGetEnum(w, offset+2, TORQUE_UNIT_MAP));
        //    map.put("二级关过力矩1高字节", String.valueOf(w[offset+3]));
        //    map.put("二级关过力矩1低字节", String.valueOf(w[offset+4]));
        map.put("二级关过力矩1", String.valueOf(combineBytes(w[offset + 3],w[offset + 4])));
        //    map.put("二级开过力矩1高字节", String.valueOf(w[offset+5]));
        //     map.put("二级开过力矩1低字节", String.valueOf(w[offset+6]));
        map.put("二级开过力矩1", String.valueOf(combineBytes(w[offset + 5],w[offset + 6])));
        //    map.put("过力矩延时1高字节", String.valueOf(w[offset+7]));
        //    map.put("过力矩延时1低字节", String.valueOf(w[offset+8]));
        map.put("过力矩延时", String.valueOf(combineBytes(w[offset + 7],w[offset + 8]) + " ms"));
        //   map.put("二级关过力矩2高字节", String.valueOf(w[offset+9]));
        //    map.put("二级关过力矩2低字节", String.valueOf(w[offset+10]));
        map.put("二级关过力矩2", String.valueOf(combineBytes(w[offset + 9],w[offset + 10])));
        //    map.put("二级开过力矩2高字节", String.valueOf(w[offset+11]));
        //    map.put("二级开过力矩2低字节", String.valueOf(w[offset+12]));
        map.put("二级开过力矩2", String.valueOf(combineBytes(w[offset + 11],w[offset + 12])));
        //    map.put("传动比(电机至电位器)高字节", String.valueOf(w[offset+13]));
        //    map.put("传动比(电机至电位器)低字节", String.valueOf(w[offset+14]));
        map.put("传动比(电机至电位器)", String.valueOf(combineBytes(w[offset + 13],w[offset + 14])));
        //    map.put("传动比(电机至阀门)高字节", String.valueOf(w[offset+15]));
        //   map.put("传动比(电机至阀门)低字节", String.valueOf(w[offset+16]));
        map.put("传动比(电机至阀门)", String.valueOf(combineBytes(w[offset + 15],w[offset + 16])));
        //   map.put("产品编号1", String.valueOf(w[offset+17]));
        //   map.put("产品编号2", String.valueOf(w[offset+18]));
        //    map.put("产品编号3", String.valueOf(w[offset+19]));
        //     map.put("产品编号4", String.valueOf(w[offset+20]));
        map.put("产品编号",String.valueOf(combineBytes(w[offset+17],w[offset+18],w[offset+19],w[offset+20])));
        map.put("电位计保护误差", String.valueOf(w[offset+21]) + " %");
        //    map.put("力矩校准低点值高字节", String.valueOf(w[offset+22]));
        //    map.put("力矩校准低点值低字节", String.valueOf(w[offset+23]));
        map.put("力矩校准低点值", String.valueOf(combineBytes(w[offset + 22],w[offset + 23])));
        //    map.put("力矩校准低点开阀值高字节", String.valueOf(w[offset+24]));
        //    map.put("力矩校准低点开阀值低字节", String.valueOf(w[offset+25]));
        map.put("力矩校准低点开阀值", String.valueOf(combineBytes(w[offset + 24],w[offset + 25])));
        //    map.put("力矩校准低点关阀值高字节", String.valueOf(w[offset+26]));
        //    map.put("力矩校准低点关阀值低字节", String.valueOf(w[offset+27]));
        map.put("力矩校准低点关阀值", String.valueOf(combineBytes(w[offset + 26],w[offset + 27])));
        //    map.put("力矩校准高点值高字节", String.valueOf(w[offset+28]));
        //   map.put("力矩校准高点值低字节", String.valueOf(w[offset+29]));
        map.put("力矩校准高点值", String.valueOf(combineBytes(w[offset + 28],w[offset + 29])));
        //   map.put("力矩校准高点开阀值高字节", String.valueOf(w[offset+30]));
        //    map.put("力矩校准高点开阀值低字节", String.valueOf(w[offset+31]));
        map.put("力矩校准高点开阀值", String.valueOf(combineBytes(w[offset + 30],w[offset + 31])));
        //   map.put("力矩校准高点关阀值高字节", String.valueOf(w[offset+32]));
        //    map.put("力矩校准高点关阀值低字节", String.valueOf(w[offset+33]));
        map.put("力矩校准高点关阀值", String.valueOf(combineBytes(w[offset + 32],w[offset + 33])));
        map.put("速度环启动系数", String.valueOf(w[offset+34]));
        //   map.put("速度环最大电流AD值高字节", String.valueOf(w[offset+35]));
        //   map.put("速度环最大电流AD值低字节", String.valueOf(w[offset+36]));
        map.put("速度环最大电流AD值", String.valueOf(combineBytes(w[offset + 35],w[offset + 36])));
        map.put("电流环P系数", String.valueOf(w[offset+37]));
        //    map.put("电流环调节范围高字节", String.valueOf(w[offset+38]));
        //   map.put("电流环调节范围低字节", String.valueOf(w[offset+39]));
        map.put("电流环调节范围", String.valueOf(combineBytes(w[offset + 38],w[offset + 39])));
        map.put("电流环加速度减档", String.valueOf(w[offset+40]));
        map.put("采样电阻值", String.valueOf(w[offset+41]) + " mΩ");
        //   map.put("采样放大系数高字节", String.valueOf(w[offset+42]));
        //    map.put("采样放大系数低字节", String.valueOf(w[offset+43]));
        map.put("采样放大系数", String.valueOf(combineBytes(w[offset + 42],w[offset + 43])/100.00));
        map.put("极对数", String.valueOf(w[offset+44]));
        //   map.put("额定速度高字节", String.valueOf(w[offset+45]));
        //   map.put("额定速度低字节", String.valueOf(w[offset+46]));
        map.put("额定速度", String.valueOf(combineBytes(w[offset + 45],w[offset + 46])));
        //   map.put("最小速度高字节", String.valueOf(w[offset+47]));
        //   map.put("最小速度低字节", String.valueOf(w[offset+48]));
        map.put("最小速度", String.valueOf(combineBytes(w[offset + 47],w[offset + 48])));
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
    private static void translatePartSettings(int[] w, StringBuilder sb) {
        Map<String, String> map = new LinkedHashMap<>();

        // 解析报警类型和时间戳
        int alarmType = safeGet(w, 0) - 1;
        //事件代码
        int eventCode = combineBytes(safeGet(w, 1), safeGet(w, 2),
                safeGet(w, 3), safeGet(w, 4));
        int year = safeGet(w, 5);
        int month = safeGet(w, 6);
        int day = safeGet(w, 7);
        int hour = safeGet(w, 8);
        int minute = safeGet(w, 9);
        int second = safeGet(w, 10);

        // 解析位置和编码器值
        int position = combineBytes(safeGet(w, 11), safeGet(w, 12));
        int encoderValue = combineBytes(safeGet(w, 13), safeGet(w, 14),
                safeGet(w, 15), safeGet(w, 16));

        // 解析力矩和霍尔值
        int torque = combineBytes(safeGet(w, 17), safeGet(w, 18));
        int hallValue = combineBytes(safeGet(w, 19), safeGet(w, 20),
                safeGet(w, 21), safeGet(w, 22));

        // 解析设定参数
        int closeTorque = combineBytes(safeGet(w, 23), safeGet(w, 24));
        int closePosition = combineBytes(safeGet(w, 25), safeGet(w, 26),
                safeGet(w, 27), safeGet(w, 28));

        int openTorque = combineBytes(safeGet(w, 29), safeGet(w, 30));
        int openPosition = combineBytes(safeGet(w, 31), safeGet(w, 32),
                safeGet(w, 33), safeGet(w, 34));

        // 添加到映射表
        map.put("报警类型", alarmType >= 0 && alarmType < 32 ?
                ALARM_MASS_DATA64[alarmType][0] : "未知报警");
        map.put("事件代码",String.valueOf(eventCode));
        map.put("时间", String.format("%04d-%02d-%02d %02d:%02d:%02d",
                year, month, day, hour, minute, second));
        map.put("位置", String.format("%d.%d%%", position/10, position%10));
        map.put("编码器值", String.format("%04d-%04d", encoderValue/4096, encoderValue%4096));
        map.put("力矩", String.valueOf(torque));
        map.put("霍尔值", String.format("%010d", hallValue));
        map.put("设定关阀力矩", String.valueOf(closeTorque));
        map.put("设定关阀位置", String.format("%04d-%04d", closePosition/4096, closePosition%4096));
        map.put("设定开阀力矩", String.valueOf(openTorque));
        map.put("设定开阀位置", String.format("%04d-%04d", openPosition/4096, openPosition%4096));

        // 输出格式化的数据
        sb.append("\n================ [报警记录] ================\n");
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
    public static String formatRemainder(int number, int divisor) {
        double remainder = (double) number % divisor;
        return String.format("%f", remainder);
    }
    //public static String divisionOperation()


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
         // 假设00 7F DD A0存储在两个字节的数组中
         //byte[] bytes = {0x00, 0x7F, (byte) 0xDD, (byte)0xA0};
         System.out.print(combineBytes(0,127,221,160));

     }
    /*public static void main(String[] args) {
        String hexData ="01 66 25 \n" +
                "20\n" +
                "00\n" +
                "15 00 00 00 07 1A 09 05 0C 11 \n" +
                "23 01 90 00 00 03 20 00 00 00 \n" +
                "00 00 00 00 C8 00 7F E3 18 00 \n" +
                "C8 00 7F DD A0\n" +
                "72 C3";
        System.out.println(translatePartPacket(hexData));
    }*/
}