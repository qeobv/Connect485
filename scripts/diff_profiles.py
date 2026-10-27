# -*- coding: utf-8 -*-
"""对比两个系列 profile json，输出差异报告"""
import json
import os
import sys

a = json.load(open(sys.argv[1], encoding="utf-8"))
b = json.load(open(sys.argv[2], encoding="utf-8"))
out = os.path.join(os.environ.get("TEMP", r"C:\Windows\Temp"), "profile_diff.txt")
lines = []

def key(reg):
    return (reg["addr"], reg.get("bit", -1))

def desc(d):
    s = "%s%s" % (d["name"], "[bit%d]" % d["bit"] if "bit" in d else "@%d" % d["addr"])
    return s

lines.append("对比: %s (%s)  vs  %s (%s)" % (a["id"], a["displayName"], b["id"], b["displayName"]))
lines.append("=" * 70)

# 汇总
a_items = {(d["addr"], d.get("bit", -1)): d for d in a["registers"] + a["bits"]}
b_items = {(d["addr"], d.get("bit", -1)): d for d in b["registers"] + b["bits"]}
lines.append("条目数: %s=%d, %s=%d" % (a["id"], len(a_items), b["id"], len(b_items)))

only_a = sorted(set(a_items) - set(b_items))
only_b = sorted(set(b_items) - set(a_items))
common = sorted(set(a_items) & set(b_items))

lines.append("")
lines.append("--- 仅 %s 有 (%d 条) ---" % (a["id"], len(only_a)))
for k in only_a:
    lines.append("  %s" % desc(a_items[k]))
lines.append("")
lines.append("--- 仅 %s 有 (%d 条) ---" % (b["id"], len(only_b)))
for k in only_b:
    lines.append("  %s" % desc(b_items[k]))

# 同名不同义
lines.append("")
lines.append("--- 共同条目但内容不同 ---")
for k in common:
    x, y = a_items[k], b_items[k]
    if x.get("name") != y.get("name"):
        lines.append("  %s/%d: %s → %s" % (a["id"], k[0], x["name"], y["name"]))
    for field in ("unit", "min", "max", "access", "enum"):
        if x.get(field) != y.get(field):
            lines.append("  %s/%d %s: %s=%r → %s=%r" % (a["id"], k[0], field, a["id"], x.get(field), b["id"], y.get(field)))

# 枚举差异
lines.append("")
lines.append("--- 枚举差异 ---")
for name in set(a.get("enums", {})) | set(b.get("enums", {})):
    ea, eb = a.get("enums", {}).get(name), b.get("enums", {}).get(name)
    if ea != eb:
        lines.append("  [%s]" % name)
        for code in sorted(set(ea or {}) | set(eb or {})):
            va, vb = (ea or {}).get(code), (eb or {}).get(code)
            if va != vb:
                lines.append("    %s: %s=%r → %s=%r" % (code, a["id"], va, b["id"], vb))

# 示例报文（仅 B 有）
lines.append("")
lines.append("--- %s 点表自带的示例轮询报文 ---" % b["id"])
for d in b["registers"] + b["bits"]:
    if "sample" in d:
        lines.append("  %-24s %s" % (desc(d), d["sample"]))

with open(out, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
print("written:", out)
