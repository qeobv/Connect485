# -*- coding: utf-8 -*-
"""
通用 MODBUS IO 点表 xlsx -> profiles/<id>.json 转换器

用法:
    python xlsx2profile.py <xlsx路径> <输出json路径> --id <id> --name <显示名> --source <来源说明>

特性:
  - 自动定位表头行（含"MODBUS地址"）与各列位置；
  - 自动解析备注列中的枚举文本（如 "0 就地，1 4-20mA,..."）；
  - 支持位定义（40001/0）与示例报文列（第 11 列，如 01 03 00 05 00 04 D5 CA）；
  - 输出为纯数据 profile JSON，供 Java 端 classpath 加载。
"""
import sys
import json
import re
import argparse
import openpyxl


def parse_enums(remark):
    """把 '0 就地，1 4-20mA,2电平型,...' 解析为 {code: desc}，不足 2 项返回 None"""
    if not remark:
        return None
    parts = re.split(r"[，,\n]", remark)
    enums = {}
    for p in parts:
        p = p.strip()
        m = re.match(r"^(\d+)\s*(.+)$", p)
        if m:
            enums[m.group(1)] = m.group(2).strip()
    return enums if len(enums) >= 2 else None


def merged_value(rows, row, col, default=""):
    """合并单元格取值：向上找同列最近的非空行"""
    v = rows[row][col].strip()
    if v:
        return v
    for r in range(row - 1, 0, -1):
        v = rows[r][col].strip()
        if v:
            return v
    return default


ENUM_NAME_BY_ADDR = {"40007": "control_mode", "40010": "torque_unit", "40013": "remote_signal"}


def convert(xlsx_path, out_path, profile_id, display_name, source):
    wb = openpyxl.load_workbook(xlsx_path, data_only=True)
    ws = wb[wb.sheetnames[0]]
    rows = [[("" if v is None else str(v)) for v in r]
            for r in ws.iter_rows(min_row=1, max_row=ws.max_row, values_only=True)]

    # 1. 定位表头行
    header_row = None
    for i, row in enumerate(rows):
        if any("MODBUS地址" in c for c in row):
            header_row = i
            break
    if header_row is None:
        raise SystemExit("未找到表头行（含 MODBUS地址）")

    # 2. 列映射
    header = rows[header_row]

    def col(text):
        for j, c in enumerate(header):
            if text in c:
                return j
        return None

    c_type = col("参数类型")
    c_name = col("参数名称")
    c_unit = col("单位")
    c_maddr = col("MODBUS地址")
    c_range = col("实际量程")
    c_dtype = col("数据类型")
    c_remark = col("备注")
    # 示例报文列：点表若有第 11 列（表头可能为空）
    c_sample = 10 if len(header) > 10 else None

    registers = []
    bits = []
    enum_catalog = {}

    # 3. 数据行
    for i in range(header_row + 1, len(rows)):
        cat = merged_value(rows, i, c_type)          # 参数类型（合并列，向上继承）
        name = rows[i][c_name].strip()               # 参数名称（每行独立）
        addr_text = rows[i][c_maddr].strip()         # MODBUS地址
        if not name or not addr_text:
            continue
        range_text = rows[i][c_range].strip()
        dtype = rows[i][c_dtype].strip()
        unit = rows[i][c_unit].strip()
        remark = rows[i][c_remark].strip()
        sample = rows[i][c_sample].strip() if c_sample is not None else ""

        # 单位补充: 部分点表把 Kpa 写在备注列
        if not unit and remark.strip().upper() == "KPA":
            unit = "Kpa"

        # 访问属性
        if dtype == "位":
            access = "WO" if ("输出" in cat) else "RO"
        else:
            access = "RW" if ("写" in name or "输出" in cat) else "RO"

        # 量程
        lo = hi = None
        if range_text:
            m = re.match(r"^\s*(\d+)\s*-\s*(\d+)\s*$", range_text)
            if m:
                lo, hi = int(m.group(1)), int(m.group(2))

        # 枚举: 从备注文本自动解析，按地址命名
        enum_name = None
        parsed = parse_enums(remark)
        if parsed:
            enum_name = ENUM_NAME_BY_ADDR.get(addr_text, "enum_" + addr_text.replace("/", "_"))
            enum_catalog.setdefault(enum_name, parsed)

        def add_sample(d):
            if sample:
                d["sample"] = "".join(sample.split()).upper()

        if "/" in addr_text:  # 位定义: 40001/0
            base, bit = addr_text.split("/")
            d = {
                "addr": int(base) - 40001, "bit": int(bit), "name": name,
                "group": cat, "access": access,
            }
            add_sample(d)
            bits.append(d)
        else:  # 寄存器: 40006 -> addr 5
            d = {"addr": int(addr_text) - 40001, "name": name, "group": cat, "access": access}
            if unit:
                d["unit"] = unit
            if lo is not None:
                d["min"] = lo
            if hi is not None:
                d["max"] = hi
            if enum_name:
                d["enum"] = enum_name
            if remark:
                d["remark"] = remark
            add_sample(d)
            registers.append(d)

    profile = {
        "id": profile_id,
        "displayName": display_name,
        "protocol": "modbus-rtu",
        "source": source,
        "registers": registers,
        "bits": bits,
        "enums": enum_catalog,
    }

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(profile, f, ensure_ascii=False, indent=2)
    print("written:", out_path)
    print("registers:", len(registers), "bits:", len(bits), "enums:", list(enum_catalog))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("xlsx")
    ap.add_argument("out")
    ap.add_argument("--id", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--source", default="")
    a = ap.parse_args()
    convert(a.xlsx, a.out, a.id, a.name, a.source)
