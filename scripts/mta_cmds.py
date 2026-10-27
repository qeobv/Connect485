# -*- coding: utf-8 -*-
"""计算 MTA 控制指令的完整帧（含 CRC），对照点表验证"""
import os

OUT = os.path.join(os.environ.get("TEMP", r"C:\Windows\Temp"), "mta_cmds.txt")
lines = []

def crc16(data):
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc & 0xFFFF

def cmd(name, hexdata, point_def):
    data = [int(x, 16) for x in hexdata.split()]
    c = crc16(data)
    full = data + [c & 0xFF, (c >> 8) & 0xFF]
    lines.append("%-14s %s" % (name, " ".join("%02X" % b for b in full)))
    lines.append("%-14s 点表: %s" % ("", point_def))
    lines.append("")

cmd("开阀", "01 06 00 03 00 01", "40004/0 阀门全开（写），1=阀门全开控制")
cmd("关阀", "01 06 00 03 00 02", "40004/1 阀门全关（写），1=阀门全关控制")
cmd("停止", "01 06 00 03 00 04", "40004/2 阀门停止（写），1=阀门控制停止")
cmd("复归", "01 06 00 04 00 01", "40005/0 故障告警复归（写），所有故障复归")
cmd("给定开度50%", "01 06 00 0B 01 F4", "40012 设定阀门位置（写），0-1000‰，50% = 500 = 01F4")
cmd("控制方式Modbus", "01 06 00 0C 00 08", "40013 设定远程信号类型（写），8 = modbus")

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
print("written:", OUT)
