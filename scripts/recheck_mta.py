# -*- coding: utf-8 -*-
"""精确重新验证 15:05:46 写帧"""
import os

OUT = os.path.join(os.environ.get("TEMP", r"C:\Windows\Temp"), "mta_recheck.txt")
lines = []

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

def crc16_modbus(data):
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc & 0xFFFF

# 15:05:46 写帧（从用户消息逐词复制）
write_hex = ("01 65 82 01 2A 00 00 74 F8 94 00 75 23 B0 02 9E 0D 48 02 9E 0D 16 "
             "00 00 10 02 02 01 F4 00 00 00 00 00 00 01 00 00 00 00 00 00 00 00 00 E1 27 "
             "02 13 00 06 02 0E 0E 0E 0E 0F 0E 0F 00 0F 01 F4 0F 00 00 C0 C6 "
             "03 3F 00 00 01 00 00 28 00 64 01 90 00 37 00 37 05 DC 00 C9 32 C8 10 00 1E "
             "0F 33 C0 50 00 01 2E 22 19 00 00 00 00 02 01 02 00 00 00 01 00 02 00 01 00 03 00 04 00 00 01 00 00 00 00 00 00 00 43 CA 11 5D")
hx = [int(x, 16) for x in write_hex.split()]
lines.append("写帧字节数=%d（期望135）" % len(hx))
if len(hx) >= 135:
    # basic 数据 = hx[5:45]（40字节），CRC = hx[45:47]
    basic_payload = hx[5:45]
    calc = crc16_sub(basic_payload)
    fw = (hx[45] << 8) | hx[46]
    lines.append("基础数据区(40B): %s" % " ".join("%02X" % b for b in basic_payload))
    lines.append("基础CRC: 帧内=%04X 计算=%04X %s" % (fw, calc, "OK" if fw == calc else "FAIL"))
    # 回读帧（15:05:49）的基础 CRC 对照
    read_hex = ("01 64 82 01 2A 01 00 74 F8 94 00 75 23 B0 02 9E 0D 48 02 9E 0D 16 "
                "00 00 10 02 02 01 F4 00 00 00 00 00 00 01 00 00 00 00 00 00 00 00 00 27 8A")
    rhx = [int(x, 16) for x in read_hex.split()]
    rcalc = crc16_sub(rhx[5:45])
    rfw = (rhx[45] << 8) | rhx[46]
    lines.append("回读帧基础CRC: 帧内=%04X 计算=%04X %s" % (rfw, rcalc, "OK" if rfw == rcalc else "FAIL"))
    lines.append("写帧 w0=%d, 回读 w0=%d" % (hx[5], rhx[5]))

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
print("written:", OUT)
