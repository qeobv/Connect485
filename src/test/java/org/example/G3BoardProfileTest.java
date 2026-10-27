package org.example;

import org.example.core.ControlAction;
import org.example.core.DeviceState;
import org.example.g3.G3BoardProfile;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * G3 系列档案回归测试：
 * 验证 G3BoardProfile 的行为与原硬编码逻辑一致（轮询命令、控制命令、响应解析、帧识别）。
 */
public class G3BoardProfileTest {

    private final G3BoardProfile profile = new G3BoardProfile();

    private static byte[] hex(String s) {
        String clean = s.replace(" ", "");
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

    @Test
    public void testBuildPollCommand() {
        // 原硬编码: 01 03 00 01 00 0A（地址 40002，10 个寄存器）
        assertEquals("01030001000A", hexOf(profile.buildPollCommand(1)));
        assertEquals("02030001000A", hexOf(profile.buildPollCommand(2)));
    }

    @Test
    public void testBuildControlCommands() {
        // 原硬编码: 0x06 写 40004(0x0003) = 1/2/4, 40005(0x0004) = 1
        assertEquals("010600030001", hexOf(profile.buildControlCommand(1, ControlAction.OPEN_VALVE, 0)));
        assertEquals("010600030002", hexOf(profile.buildControlCommand(1, ControlAction.CLOSE_VALVE, 0)));
        assertEquals("010600030004", hexOf(profile.buildControlCommand(1, ControlAction.STOP_VALVE, 0)));
        assertEquals("010600040001", hexOf(profile.buildControlCommand(1, ControlAction.RESET_ALARM, 0)));

        // 给定开度: 0x06 写 40012(0x000B)，raw = percent*10；控制方式: 0x06 写 40013(0x000C)
        assertEquals("0206000B0352", hexOf(profile.buildControlCommand(2, ControlAction.SET_OPEN_RATIO, 850)));
        assertEquals("0106000C0006", hexOf(profile.buildControlCommand(1, ControlAction.SET_CONTROL_MODE, 6)));
    }

    @Test
    public void testControlModeMapping() {
        assertEquals(9, profile.controlModeValue("压差PID"));
        assertEquals(6, profile.controlModeValue("Modbus"));
        assertEquals(6, profile.controlModeValue("未知选项"));  // 默认 6
    }

    @Test
    public void testParsePollResponse() {
        // 模拟 G3 轮询响应: 01 03 14 + 20 字节数据（40002~40011）
        // 40002=0x0001(正在开) 40003=0 40004=0 40005=0
        // 40006=850‰ 40007=6(Modbus) 40008=500‰ 40009=1200 40010=1(Nm) 40011=3000rpm
        byte[] frame = hex("01 03 14 00 01 00 00 00 00 00 00 03 52 00 06 01 F4 04 B0 00 01 0B B8 00 00");

        DeviceState state = profile.parsePollResponse(frame);
        assertNotNull(state);
        assertEquals(85.0, state.openRatio, 0.001);
        assertEquals(50.0, state.setRatio, 0.001);
        assertEquals("1200 Nm", state.torqueText);
        assertEquals("3000 rpm", state.speedText);
        assertEquals("Modbus", state.controlModeText);
        assertEquals("正在开", state.valveStateText);
        assertEquals(DeviceState.ValveState.OPENING, state.valveState);
    }

    @Test
    public void testParsePollResponseValveStopped() {
        // 40002=0（停止）
        byte[] frame = hex("01 03 14 00 00 00 00 00 00 00 00 03 52 00 06 01 F4 04 B0 00 01 0B B8 00 00");
        DeviceState state = profile.parsePollResponse(frame);
        assertNotNull(state);
        assertEquals("已停止", state.valveStateText);
        assertEquals(DeviceState.ValveState.STOPPED, state.valveState);
    }

    @Test
    public void testParsePollResponseRejectsNon03() {
        // 0x64 配置帧不应被当作轮询响应
        byte[] frame = hex("01 64 8B 01 38 00 00");
        assertNull(profile.parsePollResponse(frame));
        // 长度不足
        assertNull(profile.parsePollResponse(hex("01 03 02 00 00")));
    }

    @Test
    public void testIsPollingFrame() {
        assertTrue(profile.isPollingFrame("[12:00:00.000 发送]: 01 03 00 01 00 0A D5 CA"));
        assertTrue(profile.isPollingFrame("[12:00:00.000 接收]: 01 03 14 00 01"));
        assertFalse(profile.isPollingFrame("[12:00:00.000 接收]: 01 03 0A 00 00"));   // byteCount=0x0A 非轮询
        assertFalse(profile.isPollingFrame("[12:00:00.000 接收]: 01 64 8B 01 38"));   // 0x64
        assertFalse(profile.isPollingFrame("无前缀文本"));
    }

    @Test
    public void testReadConfigAndHistoryCommands() {
        assertEquals("016400000000", hexOf(profile.buildReadConfigCommand(1)));
        assertEquals("016600000000", hexOf(profile.buildHistoryInitCommand(1)));
    }
}
