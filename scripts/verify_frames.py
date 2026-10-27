# -*- coding: utf-8 -*-
"""验证用户日志中的 0x64 响应帧与 0x65 写帧的字节数与 CRC"""
import os

OUT = os.path.join(os.environ.get("TEMP", r"C:\Windows\Temp"), "frame_verify.txt")
lines = []

def crc16_modbus(data):
    """标准 Modbus CRC16（低字节在前输出）"""
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            if crc & 1:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1
    return crc

def crc16_sub(data):
    """子包 CRC（Crc16Util.crc16 查表法等价：res=0 初始）"""
    res = 0
    for b in data:
        res = ((res & 0xFF) ^ b)  # 近似占位
    return 0

def check(name, hexstr):
    hx = [int(x, 16) for x in hexstr.split()]
    lines.append("== %s: 总字节数=%d" % (name, len(hx)))
    if len(hx) >= 5:
        func = hx[1]
        lines.append("   功能码=0x%02X, byteCount=%d, 期望帧长(3+bc+2)=%d" % (func, hx[2], 3 + hx[2] + 2))
        # 整包 CRC = 最后2字节（低字节在前）
        if len(hx) >= 2:
            recv = (hx[-1] << 8) | hx[-2]
            calc = crc16_modbus(hx[:-2])
            lines.append("   整包CRC: 帧内=%04X 计算=%04X %s" % (recv, calc, "OK" if recv == calc else "FAIL"))
            if len(hx) >= 144:
                # 子包 CRC：basicData=5..60 (54数据+2CRC), alarm=63..73 (9+2), super=76..141 (64+2)
                def subcrc(base, plen, croff, label):
                    payload = hx[base:base+plen]
                    # Crc16Util.crc16: res=0 初始的查表法
                    table = []
                    for i in range(256):
                        c = i
                        for _ in range(8):
                            c = (c >> 1) ^ 0xA001 if (c & 1) else (c >> 1)
                        table.append(c & 0xFFFF)
                    res = 0
                    for b in payload:
                        idx = (b ^ (res & 0xFF)) & 0xFF
                        res = table[idx] ^ (res >> 8)
                    res &= 0xFFFF
                    # 子包 CRC 高字节在前: frame[croff] = res>>8, frame[croff+1] = res&0xFF
                    fw = (hx[croff] << 8) | hx[croff+1]
                    lines.append("   %s 子包CRC: 帧内=%04X 计算=%04X %s (帧内高字节在前)" % (label, fw, res, "OK" if fw == res else "FAIL"))
                subcrc(5, 54, 59, "基础")
                subcrc(63, 9, 72, "继电器")
                subcrc(76, 64, 140, "高级")
    lines.append("")

# 08:42:06.034 接收的 0x64 响应
check("0x64响应(08:42:06)",
      "01 64 8B 01 38 01 00 75 47 98 00 75 0E FC 02 DA 0E 46 02 DE 0E 56 05 05 03 01 90 02 01 F4 07 D0 03 02 BC 01 00 00 01 01 64 64 00 1E 00 1E 00 00 0A 0A 0A 05 01 00 00 00 00 00 00 03 67 02 0B 00 01 06 07 08 0F 01 F4 05 AD 93 03 42 00 00 01 00 C8 00 C8 05 DC 00 64 00 64 00 37 00 33 04 D3 F6 A5 32 00 01 00 02 00 03 00 8C 00 28 00 28 3C 01 F4 08 00 96 03 32 01 3A 03 0A F0 00 C8 00 00 03 F3 00 03 01 03 04 07 52 07 AC 58 DB 87 E3 00 7E")

# 08:41:58.678 发送的 0x65 写帧
check("0x65写帧(08:41:58)",
      "01 65 8B 01 38 01 00 75 47 98 00 75 0E FC 02 DA 0E 46 02 DE 0E 56 05 05 03 01 90 02 01 F4 07 D0 03 02 BC 01 00 00 01 01 64 64 00 1E 00 1E 00 00 0A 0A 0A 05 01 00 00 00 00 00 00 03 67 02 0B 00 01 06 07 08 0F 01 F4 05 AD 93 03 42 00 00 01 00 C8 00 C8 05 DC 00 64 00 64 00 37 00 33 04 D3 F6 A5 32 00 01 00 02 00 03 00 8C 00 28 00 28 3C 01 F4 08 00 96 03 32 01 3A 03 0A F0 00 C8 00 00 03 F3 00 03 01 03 04 07 00 00 BC 88 6B 8E 06 9C 23")

# 08:43:11.444 发送的 0x65 写帧（第二次）
check("0x65写帧(08:43:11)",
      "01 65 8B 01 38 00 00 75 47 98 00 74 E0 00 02 DA 0E 46 02 DE 0E 56 05 05 03 01 90 02 01 F4 07 D0 03 02 BC 01 00 00 01 00 64 64 00 1E 00 1E 00 00 0A 0A 0A 05 01 00 00 00 00 00 00 1E D2 02 0B 00 01 06 07 08 0F 01 F4 05 AD 93 03 42 00 00 01 00 C8 00 C8 05 DC 00 64 00 64 00 37 00 33 04 D3 F6 A5 32 00 01 00 02 00 03 00 8C 00 28 00 28 3C 01 F4 08 00 96 03 32 01 3A 03 0A F0 00 C8 00 00 03 F3 00 03 01 03 04 07 52 07 AC 58 DB 87 E3 D0 E8")

# 0x64 读命令
check("0x64读命令", "01 64 00 00 00 00 70 02")

# 前导垃圾+响应混合
check("前导垃圾55 D0 68 + 响应开头", "55 D0 68 01 64 8B 01 38 01 00 75")

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
print("written:", OUT)
