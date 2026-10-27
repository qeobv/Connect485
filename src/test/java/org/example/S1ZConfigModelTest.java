package org.example;

import org.example.core.ConfigEdit;
import org.example.core.ConfigFieldDef;
import org.example.core.ConfigFieldValue;
import org.example.s1z.S1ZConfigModel;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * MTA（S1-Z）配置模型回归测试：
 * 帧结构 135 字节（basic 42 / alarm 19 / super 63，datarw.h 布局）。
 */
public class S1ZConfigModelTest {

    private final S1ZConfigModel model = new S1ZConfigModel();

    private static byte[] hex(String s) {
        String clean = s.replace(" ", "").replace("\n", "");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** 合成帧：枚举取合法值，子包 CRC 已填充 */
    private static final byte[] SYNTHETIC_FRAME = fillSubCrcs(hex(
            "01 64 82 01 2A " +                                     // 帧头 + 子包标记(01 2A=42)
            "00 " +                                                // w0 中文
            "00 7F DD A0 00 7F E3 18 " +                           // w1-8 开/关阀位置
            "01 00 01 00 01 00 01 00 " +                           // w9-16 校准
            "00 " +                                                // w17 输入正反作用 正作用
            "01 " +                                                // w18 输出正反作用 反作用
            "05 " +                                                // w19 死区 0.5S
            "07 " +                                                // w20 信号类型 Modbus(7)
            "01 " +                                                // w21 信号故障 全关
            "02 00 " +                                             // w22-23 51.2%
            "01 " +                                                // w24 ESD 全关
            "02 00 " +                                             // w25-26
            "00 " +                                                // w27 本地
            "00 " +                                                // w28 正作用
            "00 01 " +                                             // w29-30 地址
            "00 " +                                                // w31 调试模式 关闭
            "00 " +                                                // w32 自动移动 关
            "05 " +                                                // w33 周期
            "08 1E " +                                             // w34-35 08:30
            "05 " +                                                // w36 范围 0.5%
            "05 " +                                                // w37 最大时间
            "00 00 " +                                             // w38-39 保留
            "00 00 " +                                             // w40-41 子包CRC(占位)
            "02 13 " +                                             // 子包标记(02 13=19)
            "00 01 02 03 04 05 06 00 01 02 03 04 " +              // w0-11 继电器1-12
            "01 F4 " +                                             // w12-13 50.0%
            "05 " +                                                // w14 告警范围 5‰
            "00 00 " +                                             // w15-16 保留
            "00 00 " +                                             // w17-18 子包CRC(占位)
            "03 3F " +                                             // 子包标记(03 3F=63)
            "00 00 " +                                             // w0-1 密码
            "00 " +                                                // w2 力矩单位 N
            "00 " +                                                // w3 电源 380V
            "00 C8 00 C8 " +                                       // w4-7 一二级? 一级开/关过力矩
            "05 DC " +                                             // w8-9 延迟
            "05 56 07 37 " +                                       // w10-13 传动比
            "01 3A 01 2C " +                                       // w14-17 最大速度/额定电流
            "01 90 " +                                             // w18-19 采样放大 4
            "00 0F " +                                             // w20-21 电位器圈数
            "05 " +                                                // w22 保护误差
            "05 " +                                                // w23 停车位置 5‰
            "00 00 " +                                             // w24-25 保护投退
            "05 05 " +                                             // w26-27 刹车
            "01 7E 92 4B " +                                       // w28-31 产品编号
            "00 " +                                                // w32 机体正反逻辑
            "00 C8 " +                                             // w33-34 编码器分辨率
            "03 " +                                                // w35 编码器波特率 9600
            "00 " +                                                // w36 预留
            "01 " +                                                // w37 公司信息 开启
            "05 " +                                                // w38 电流变比
            "00 00 00 00 00 00 00 00 00 00 00 00 " +              // w39-50 力矩校准六项
            "00 " +                                                // w51 调试模式
            "01 " +                                                // w52 过力矩方式 电流检测
            "00 " +                                                // w53 校验 无
            "00 " +                                                // w54 类型 RTU
            "00 00 00 00 00 00 " +                                 // w55-60 保留
            "00 00 " +                                             // w61-62 子包CRC(占位)
            "00 00"));                                             // 整包CRC(占位)

    private static byte[] fillSubCrcs(byte[] frame) {
        fillSubCrc(frame, 5, 40, 45);
        fillSubCrc(frame, 49, 17, 66);
        fillSubCrc(frame, 70, 61, 131);
        return frame;
    }

    private static void fillSubCrc(byte[] frame, int base, int payloadLen, int crcOffset) {
        byte[] payload = new byte[payloadLen];
        System.arraycopy(frame, base, payload, 0, payloadLen);
        int crc = Crc16Util.crc16(payload, payloadLen);
        frame[crcOffset] = (byte) ((crc >> 8) & 0xFF);
        frame[crcOffset + 1] = (byte) (crc & 0xFF);
    }

    private static ConfigFieldValue find(List<ConfigFieldValue> values, String name) {
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

    @Test
    public void testDecodeSyntheticFrame() {
        assertEquals(S1ZConfigModel.FRAME_LENGTH, SYNTHETIC_FRAME.length);

        List<ConfigFieldValue> values = model.decode(SYNTHETIC_FRAME);
        assertNotNull(values);
        assertEquals(61, values.size());

        assertEquals("Modbus", find(values, "远程信号类型").displayText);
        assertEquals("380V", find(values, "电源类型").displayText);
        assertEquals("0.5%", find(values, "死区范围设置").displayText);
        assertEquals("0.5%", find(values, "反馈区间设置").displayText);
        assertEquals("0.5%", find(values, "停车位置").displayText);
        assertEquals("1500ms", find(values, "过力矩延迟时间").displayText);
        assertEquals("200%", find(values, "开过力矩系数").displayText);
        assertEquals("0.400", find(values, "采样放大系数").displayText);
        assertEquals("0.5S", find(values, "传感器保护时间").displayText);
        assertEquals("0S", find(values, "堵转时间").displayText);
        assertEquals("关闭", find(values, "力矩校准开关").displayText);
        assertEquals("2045.3488", find(values, "开阀位置").displayText);
        assertEquals("顺时针关阀", find(values, "关阀方向").displayText);
        assertEquals("4mA关阀", find(values, "输入正反作用").displayText);
        assertEquals("4mA开到位", find(values, "输出正反作用").displayText);
        assertEquals("常开", find(values, "停止常开常闭").displayText);
        assertEquals("常开", find(values, "ESD常开常闭").displayText);
        assertEquals("03.00A", find(values, "额定电流").displayText);
        assertEquals("报警07设置", find(values, "报警07设置").def.name);
        assertEquals("报警01设置", find(values, "报警01设置").def.name);

        // 保护投退拆分后的 5 个位字段（合成帧 w24-25 全 0 → 全部关闭）
        assertEquals("关闭", find(values, "过力矩保护").displayText);
        assertEquals("关闭", find(values, "传感器保护").displayText);
        assertEquals("关闭", find(values, "堵转保护").displayText);
        assertEquals("关闭", find(values, "拒动保护").displayText);
        assertEquals("关闭", find(values, "过温保护").displayText);
    }

    @Test
    public void testFullRoundTrip() {
        List<ConfigFieldValue> values = model.decode(SYNTHETIC_FRAME);
        assertNotNull(values);

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
                assertArrayEquals("字段字节不一致: " + v.def.name, raw, edit.rawBytes);
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
        assertEquals(S1ZConfigModel.FRAME_LENGTH - 2, out.length);   // 不含整包 CRC
        assertEquals(0x65, out[1] & 0xFF);                            // 功能码切为写

        for (int i = 0; i < out.length; i++) {
            if (i == 1) continue;
            assertEquals("字节 " + i + " 不一致", SYNTHETIC_FRAME[i], out[i]);
        }
    }

    @Test
    public void testApplyEditRecalculatesCrc() {
        List<ConfigFieldValue> values = model.decode(SYNTHETIC_FRAME);
        ConfigFieldValue deadZone = find(values, "死区范围设置");

        ConfigEdit edit = model.encodeField(deadZone.def, "1.5");   // 0.5 -> 1.5
        assertNotNull(edit);
        byte[] out = model.applyEdits(SYNTHETIC_FRAME, new ArrayList<>(List.of(edit)));
        assertNotNull(out);

        // 死区字段在帧内偏移 5+19=24
        assertEquals(0x0F, out[24] & 0xFF);

        // 基础子包 CRC 重算验证：数据区 5..44，CRC 45..46（高字节在前）
        byte[] payload = new byte[40];
        System.arraycopy(out, 5, payload, 0, 40);
        int crc = Crc16Util.crc16(payload, 40);
        assertEquals((crc >> 8) & 0xFF, out[45] & 0xFF);
        assertEquals(crc & 0xFF, out[46] & 0xFF);

        // 高级子包 CRC 重算验证（未被编辑也应保持合法）：数据区 70..130，CRC 131..132
        byte[] supPayload = new byte[61];
        System.arraycopy(out, 70, supPayload, 0, 61);
        int supCrc = Crc16Util.crc16(supPayload, 61);
        assertEquals((supCrc >> 8) & 0xFF, out[131] & 0xFF);
        assertEquals(supCrc & 0xFF, out[132] & 0xFF);

        // 模拟 SerialManager 追加整包 CRC 后校验
        byte[] withCrc = new byte[out.length + 2];
        System.arraycopy(out, 0, withCrc, 0, out.length);
        int wholeCrc = Crc16Util.calculateCRC16(out, 0, out.length);
        withCrc[withCrc.length - 2] = (byte) (wholeCrc & 0xFF);
        withCrc[withCrc.length - 1] = (byte) ((wholeCrc >> 8) & 0xFF);
        assertTrue(Crc16Util.verifyCRC16(withCrc, 0, withCrc.length));
    }

    @Test
    public void testRejectsWrongLength() {
        assertNull(model.decode(hex("01 64 82 01 2A")));
        assertNull(model.decode(hex("01 03 08 00 00")));
        assertTrue(model.isConfigFrame(hex("01 64 82 00 00")));
    }
}
