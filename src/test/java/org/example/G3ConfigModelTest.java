package org.example;

import org.example.core.ConfigEdit;
import org.example.core.ConfigFieldDef;
import org.example.core.ConfigFieldValue;
import org.example.core.ConfigModel;
import org.example.g3.G3ConfigModel;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * G3 配置模型回归测试：
 *  - decode：解析 0x64 帧为结构化字段
 *  - encodeField：校验与编码
 *  - applyEdits：改字节 + 重算三层 CRC + 功能码 0x64→0x65
 *  - 完整往返：decode 全部字段 → 重新编码 → 字节与原帧一致
 */
public class G3ConfigModelTest {

    private final G3ConfigModel model = new G3ConfigModel();

    private static byte[] hex(String s) {
        String clean = s.replace(" ", "").replace("\n", "");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hexOf(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    /** 合成帧：所有枚举字段取合法值，保证 decode→encode 可完整往返（子包 CRC 已填充为合法值） */
    private static final byte[] SYNTHETIC_FRAME = fillSubCrcs(hex(
            "01 64 8B 01 38 " +
            "00 " +                                     // w0 中文
            "00 7F DD A0 " +                             // w1-4 开阀位置
            "00 7F E3 18 " +                             // w5-8 关阀位置
            "01 00 01 00 01 00 01 00 " +                 // w9-16 校准值
            "05 " +                                      // w17 死区 0.5S
            "05 " +                                      // w18 信号类型 Modbus
            "01 " +                                      // w19 信号故障类型 全关
            "02 00 " +                                   // w20-21 51.2%
            "01 " +                                      // w22 断电动作 全关
            "02 00 " +                                   // w23-24
            "00 C8 " +                                   // w25-26 200ms
            "01 " +                                      // w27 ESD 全关
            "02 00 " +                                   // w28-29
            "00 " +                                      // w30 本地控制
            "00 " +                                      // w31 顺时针
            "00 01 " +                                   // w32-33 设备地址
            "01 " +                                      // w34 力矩校准开启
            "32 32 " +                                   // w35-36 速度 50%
            "05 " +                                      // w37 压力 0.5
            "05 " +                                      // w38 减速 0.5%
            "01 " +                                      // w39 自检开
            "05 " +                                      // w40 自检周期
            "08 1E " +                                   // w41-42 08:30
            "05 " +                                      // w43 自检范围 0.5%
            "05 " +                                      // w44 自检最大时间 5S
            "05 01 00 " +                                // w45-47 PID
            "00 00 00 00 00 00 " +                       // w48-53 保留
            "00 00 " +                                   // w54-55 子包CRC(占位)
            "02 0B " +
            "00 01 02 03 04 05 " +                       // 继电器1-6
            "01 F4 " +                                   // 报警指定位置 50.0%
            "05 " +                                      // 反馈区间 0.5%
            "00 00 " +                                   // 子包CRC(占位)
            "03 42 " +
            "00 00 " +                                   // 密码
            "01 " +                                      // 力矩单位 Nm
            "00 00 00 00 " +                             // w3-6
            "05 DC " +                                   // 过力矩延时 1500ms
            "00 64 00 64 " +                             // 关/开过力矩 100%
            "05 56 07 37 " +                             // 传动比
            "01 7E 92 4B " +                             // 产品编号
            "05 " +                                      // 传感器保护精度 0.5度
            "00 00 00 00 00 00 00 00 00 00 00 00 " +     // 空载/负载校准与电流
            "05 " +                                      // 启动速度
            "00 C8 " +                                   // 设定电流
            "05 " +                                      // 电流环系数
            "00 C8 " +                                   // 电流环范围
            "05 " +                                      // 加速分频
            "0A " +                                      // 采样电阻 10mΩ
            "01 90 " +                                   // 采样放大系数 4
            "02 " +                                      // 电机极对数
            "01 3A 03 0F " +                             // 额定/最小速度
            "00 00 00 00 " +                             // 保护投退
            "00 " +                                      // 电机关阀方向 顺时针
            "03 " +                                      // 波特率 9600
            "01 " +                                      // 公司信息开启
            "00 " +                                      // 无校验
            "00 " +                                      // RTU
            "01 " +                                      // 过力矩重试开启
            "03 " +                                      // 重试次数 3
            "01 " +                                      // 按键保持开启
            "00 00 00 00 00 " +                          // w61-65 保留
            "00 00 " +                                   // 子包CRC(占位)
            "00 00"));                                   // 整包CRC(占位)

    /** 填充三个子包 CRC（高字节在前），使合成帧成为合法帧 */
    private static byte[] fillSubCrcs(byte[] frame) {
        fillSubCrc(frame, 5, 54, 59);
        fillSubCrc(frame, 63, 9, 72);
        fillSubCrc(frame, 76, 64, 140);
        return frame;
    }

    private static void fillSubCrc(byte[] frame, int base, int payloadLen, int crcOffset) {
        byte[] payload = new byte[payloadLen];
        System.arraycopy(frame, base, payload, 0, payloadLen);
        int crc = Crc16Util.crc16(payload, payloadLen);
        frame[crcOffset] = (byte) ((crc >> 8) & 0xFF);
        frame[crcOffset + 1] = (byte) (crc & 0xFF);
    }

    @Test
    public void testDecodeSyntheticFrame() {
        List<ConfigFieldValue> values = model.decode(SYNTHETIC_FRAME);
        assertNotNull(values);
        assertEquals(76, values.size());

        ConfigFieldValue openPos = findByValue(values, "开阀位置");
        // 00 7F DD A0 = 8379808 = 2045*4096 + 3488
        assertEquals("2045.3488", openPos.displayText);

        ConfigFieldValue deadZone = findByValue(values, "死区范围设置");
        assertEquals("0.5S", deadZone.displayText);

        ConfigFieldValue signalType = findByValue(values, "信号类型");
        assertEquals("Modbus", signalType.displayText);

        ConfigFieldValue selfTestTime = findByValue(values, "自检时刻");
        assertEquals("08:30", selfTestTime.displayText);

        ConfigFieldValue torqueUnit = findByValue(values, "力矩单位");
        assertEquals("Nm", torqueUnit.displayText);

        ConfigFieldValue sampleAmp = findByValue(values, "采样放大系数");
        assertEquals("4", sampleAmp.displayText);

        // 保护投退拆分后的位字段（合成帧 w49-52 全 0 → 全部关闭）
        assertEquals("关闭", findByValue(values, "电机拒动保护").displayText);
        assertEquals("关闭", findByValue(values, "传感器保护").displayText);
        assertEquals("关闭", findByValue(values, "过温保护").displayText);
    }

    @Test
    public void testFullRoundTrip() {
        List<ConfigFieldValue> values = model.decode(SYNTHETIC_FRAME);
        assertNotNull(values);

        // 每个可编辑字段：用显示文本重新编码，字节必须与原帧一致
        List<ConfigEdit> edits = new ArrayList<>();
        for (ConfigFieldValue v : values) {
            if (!v.def.editable) continue;
            ConfigEdit edit = model.encodeField(v.def, v.displayText);
            assertNotNull("字段无法重新编码: " + v.def.name + " 显示=" + v.displayText, edit);
            byte[] raw = readRaw(SYNTHETIC_FRAME, v.def.offset, v.def.length);
            if (v.def.type == ConfigFieldDef.FieldType.BIT) {
                // 位字段只比较掩码覆盖的位
                assertEquals("位字段不一致: " + v.def.name,
                        raw[0] & v.def.bitMask, edit.rawBytes[0] & v.def.bitMask);
            } else {
                assertArrayEquals("字段字节不一致: " + v.def.name,
                        raw, edit.rawBytes);
            }
        }
    }

    @Test
    public void testApplyEditsRoundTripEqualsOriginal() {
        List<ConfigFieldValue> values = model.decode(SYNTHETIC_FRAME);
        List<ConfigEdit> edits = new ArrayList<>();
        for (ConfigFieldValue v : values) {
            if (!v.def.editable) continue;
            ConfigEdit edit = model.encodeField(v.def, v.displayText);
            if (edit != null) edits.add(edit);
        }

        byte[] out = model.applyEdits(SYNTHETIC_FRAME, edits);
        assertNotNull(out);
        assertEquals(G3ConfigModel.FRAME_LENGTH - 2, out.length);   // 不含整包 CRC
        assertEquals(0x65, out[1] & 0xFF);                          // 功能码切为写

        // 除功能码字节外，其余字节与原帧完全一致
        for (int i = 0; i < out.length; i++) {
            if (i == 1) continue;
            assertEquals("字节 " + i + " 不一致", SYNTHETIC_FRAME[i], out[i]);
        }
    }

    @Test
    public void testApplyEditRecalculatesCrc() {
        List<ConfigFieldValue> values = model.decode(SYNTHETIC_FRAME);
        ConfigFieldValue deadZone = findByValue(values, "死区范围设置");

        ConfigEdit edit = model.encodeField(deadZone.def, "1.5");   // 0.5 -> 1.5
        assertNotNull(edit);
        List<ConfigEdit> edits = new ArrayList<>();
        edits.add(edit);

        byte[] out = model.applyEdits(SYNTHETIC_FRAME, edits);
        assertNotNull(out);

        // 修改的字节落在死区字段（帧内偏移 5+17=22）
        assertEquals(0x0F, out[22] & 0xFF);

        // 基础设置子包 CRC 重算验证：帧内 5..58 为数据区，59..60 为子包 CRC（高字节在前）
        byte[] basicPayload = new byte[54];
        System.arraycopy(out, 5, basicPayload, 0, 54);
        int crc = Crc16Util.crc16(basicPayload, 54);
        assertEquals((crc >> 8) & 0xFF, out[59] & 0xFF);
        assertEquals(crc & 0xFF, out[60] & 0xFF);

        // 模拟 SerialManager 追加整包 CRC 后校验
        byte[] withCrc = new byte[out.length + 2];
        System.arraycopy(out, 0, withCrc, 0, out.length);
        int wholeCrc = Crc16Util.calculateCRC16(out, 0, out.length);
        withCrc[withCrc.length - 2] = (byte) (wholeCrc & 0xFF);
        withCrc[withCrc.length - 1] = (byte) ((wholeCrc >> 8) & 0xFF);
        assertTrue(Crc16Util.verifyCRC16(withCrc, 0, withCrc.length));
    }

    @Test
    public void testEncodeFieldValidation() {
        ConfigFieldValue deadZone = findByValue(model.decode(SYNTHETIC_FRAME), "死区范围设置");
        assertNull(model.encodeField(deadZone.def, "abc"));
        assertNull(model.encodeField(deadZone.def, "300"));     // 超出 0~25.5

        ConfigFieldValue time = findByValue(model.decode(SYNTHETIC_FRAME), "自检时刻");
        assertNull(model.encodeField(time.def, "25:00"));        // 时超范围
        assertNull(model.encodeField(time.def, "08:60"));        // 分超范围
        assertNotNull(model.encodeField(time.def, "23:59"));

        ConfigFieldValue pos = findByValue(model.decode(SYNTHETIC_FRAME), "开阀位置");
        assertNull(model.encodeField(pos.def, "abc"));
        assertNull(model.encodeField(pos.def, "1.5000"));        // 余数 >= 4096
        assertNotNull(model.encodeField(pos.def, "1.4095"));

        // 位字段
        ConfigFieldValue sensor = findByValue(model.decode(SYNTHETIC_FRAME), "传感器保护");
        assertNotNull(model.encodeField(sensor.def, "开启"));
        assertNull(model.encodeField(sensor.def, "xyz"));
        assertNull(model.encodeField(sensor.def, "2"));

        // 只读字段不可编辑
        ConfigFieldValue product = findByValue(model.decode(SYNTHETIC_FRAME), "产品编号");
        assertNull(model.encodeField(product.def, "12345"));
    }

    @Test
    public void testBitFieldEdit() {
        List<ConfigFieldValue> values = model.decode(SYNTHETIC_FRAME);
        ConfigFieldDef overTemp = findByValue(values, "过温保护").def;

        // 开启：bit 23 → w50（帧内 SUPER_BASE+50 = 126）最高位置 1
        ConfigEdit on = model.encodeField(overTemp, "开启");
        assertNotNull(on);
        assertEquals(0x80, on.rawBytes[0] & 0xFF);

        byte[] out = model.applyEdits(SYNTHETIC_FRAME, new ArrayList<>(List.of(on)));
        assertNotNull(out);
        assertEquals(0x80, out[126] & 0xFF);
        // 同字节其他位与相邻保护字段不受影响
        assertEquals(0, out[126] & 0x7F);
        assertEquals(0, out[127] & 0x02);   // 传感器保护 bit9 仍 0
        assertEquals(0, out[128] & 0xC0);   // 电机拒动保护 bit6-7 仍 0

        // 电机拒动保护（合并开关）：开启时 bit6+bit7 同时置 1
        ConfigEdit motorNo = model.encodeField(findByValue(values, "电机拒动保护").def, "开启");
        byte[] outMotor = model.applyEdits(SYNTHETIC_FRAME, new ArrayList<>(List.of(motorNo)));
        assertEquals(0xC0, outMotor[128] & 0xFF);

        // 关闭：恢复为 0
        ConfigEdit off = model.encodeField(overTemp, "关闭");
        byte[] outOff = model.applyEdits(SYNTHETIC_FRAME, new ArrayList<>(List.of(off)));
        assertEquals(0, outOff[126] & 0xFF);

        // 高级子包 CRC 重算验证（帧内 76..139 数据区，140..141 子包 CRC，高字节在前）
        byte[] payload = new byte[64];
        System.arraycopy(out, 76, payload, 0, 64);
        int crc = Crc16Util.crc16(payload, 64);
        assertEquals((crc >> 8) & 0xFF, out[140] & 0xFF);
        assertEquals(crc & 0xFF, out[141] & 0xFF);
    }

    @Test
    public void testRejectsNonConfigFrames() {
        assertNull(model.decode(hex("01 03 14 00 01")));
        assertNull(model.decode(hex("01 64 8B")));              // 长度不足
        assertTrue(model.isConfigFrame(hex("01 64 8B 00 00")));
    }

    @Test
    public void testRealSampleFrameDecode() {
        // DeviceProtocolTranslator 注释掉的真实样例帧
        byte[] frame = hex(
                "01 64 8B 01 38 00 00 7F DD A0 00 7F E3 18 02 C6 00 04 02 DA 0E 1A 05 05 02 01 " +
                "F4 02 01 F4 07 D0 02 01 F4 01 00 00 01 00 3C 3C 00 32 00 1E 00 00 0A 0A 0A 05 01 00 00 00 " +
                "00 00 00 D6 C2 02 0B 00 01 06 07 08 0F 01 F4 05 AD 93 03 42 00 00 01 00 C8 00 C8 05 DC 00 " +
                "64 00 64 05 56 07 37 01 7E 92 4B 14 00 00 00 0F 00 0F 00 C8 00 37 00 37 0A 01 2C 04 00 C8 " +
                "03 1E 01 3A 03 0F A0 01 90 00 00 01 F3 01 03 01 00 00 00 01 00 00 00 00 A2 05 11 2B");
        assertEquals(144, frame.length);

        List<ConfigFieldValue> values = model.decode(frame);
        assertNotNull(values);
        // 开阀位置 00 7F DD A0 = 8379808 -> 2045.3488
        assertEquals("2045.3488", findByValue(values, "开阀位置").displayText);
        // 信号类型 w18=05 -> Modbus
        assertEquals("Modbus", findByValue(values, "信号类型").displayText);
    }

    // ================= 工具 =================
    private static ConfigFieldValue findByValue(List<ConfigFieldValue> values, String name) {
        for (ConfigFieldValue v : values) {
            if (v.def.name.equals(name)) return v;
        }
        throw new AssertionError("找不到字段: " + name);
    }

    private static byte[] readRaw(byte[] frame, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(frame, offset, out, 0, length);
        return out;
    }
}
