# -*- coding: utf-8 -*-
"""验证 MTA 0x65 写帧的字节数与三层 CRC"""
import os

OUT = os.path.join(os.environ.get("TEMP", r"C:\Windows\Temp"), "mta_write_verify.txt")
lines = []

def crc16_modbus(data):
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc & 0xFFFF

def crc16_sub(data):
    table = []
    for i in range(256):
        c = i
        for _ in range(8):
            c = (c >> 1) ^ 0xA001 if c & 1 else c >> 1
        table.append(c & 0xFFFF)
    res = 0
    for b in data:
        res = table[(b ^ (res & 0xFF)) & 0xFF] ^ (res >> 8)
    return res & 0xFFFF

def check(name, hexstr):
    hx = [int(x, 16) for x in hexstr.split()]
    lines.append("== %s: 总字节数=%d" % (name, len(hx)))
    if len(hx) < 135:
        lines.append("   帧长不足 135！")
        return
    lines.append("   功能码=0x%02X, byteCount=%d" % (hx[1], hx[2]))
    # 整包 CRC（最后2字节，低字节在前）
    recv = (hx[-1] << 8) | hx[-2]
    calc = crc16_modbus(hx[:-2])
    lines.append("   整包CRC: 帧内=%04X 计算=%04X %s" % (recv, calc, "OK" if recv == calc else "FAIL"))
    # 子包 CRC（高字节在前）：basic 数据 5..44，CRC 45..46；alarm 49..65，CRC 66..67；super 70..130，CRC 131..132
    def sub(base, plen, croff, label):
        payload = hx[base:base+plen]
        res = crc16_sub(payload)
        fw = (hx[croff] << 8) | hx[croff+1]
        lines.append("   %s 子包CRC: 帧内=%04X 计算=%04X %s" % (label, fw, res, "OK" if fw == res else "FAIL"))
    sub(5, 40, 45, "基础")
    sub(49, 17, 66, "报警")
    sub(70, 61, 131, "高级")
    lines.append("   子包标记: %02X %02X / %02X %02X / %02X %02X" % (
        hx[3], hx[4], hx[47], hx[48], hx[68], hx[69]))
    # 关键字段
    lines.append("   w0 中英文=%d, w28 关阀方向=%d, w31 调试模式=%d" % (hx[5], hx[33], hx[36]))
    lines.append("   w24-25 保护投退=%02X %02X, w51 力矩校准开关=%d" % (hx[94], hx[95], hx[121]))
    lines.append("")

check("0x65写帧(14:57:56)",
      "01 65 82 01 2A 01 00 74 F8 94 00 75 23 B0 02 9E 0D 48 02 9E 0D 16 00 00 10 02 02 01 F4 00 00 00 00 00 00 01 00 00 00 00 00 00 00 00 00 27 8A 02 13 00 06 02 0E 0E 0E 0E 0F 0E 0F 00 0F 01 F4 0F 00 00 C0 C6 03 3F 00 00 01 00 00 28 00 64 01 90 00 37 00 37 05 DC 00 C9 32 C8 10 00 1E 0F 33 C0 50 00 01 2E 22 19 00 00 00 00 02 01 02 00 00 00 01 00 02 00 01 00 03 00 04 00 00 01 00 00 00 00 00 00 00 43 CA 1D 8A")

check("0x65写帧(15:00:44)",
      "01 65 82 01 2A 00 00 74 F8 94 00 75 23 B0 02 9E 0D 48 02 9E 0D 16 00 00 10 02 02 01 F4 00 00 00 00 00 00 01 00 00 01 00 00 00 00 00 00 E1 27 02 13 00 06 02 0E 0E 0E 0E 0F 0E 0F 00 0F 01 F4 0F 00 00 C0 C6 03 3F 00 00 01 00 00 28 00 64 01 90 00 37 00 37 05 DC 00 C9 32 C8 10 00 1E 0F 33 C0 50 00 01 2E 22 19 00 00 00 00 02 01 02 00 00 00 01 00 02 00 01 00 03 00 04 00 00 01 00 00 00 00 00 00 00 43 CA 34 F1")

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
print("written:", OUT)
