# -*- coding: utf-8 -*-
"""读取配置导出 CSV，输出 UTF-8 文本"""
import os

src = r"E:\JavaProject\导入导出测试\config_20261007_114228.csv"
out = os.path.join(os.environ.get("TEMP", r"C:\Windows\Temp"), "config_csv.txt")

with open(src, "rb") as f:
    raw = f.read()

for enc in ("utf-8-sig", "gbk", "gb18030", "utf-16"):
    try:
        text = raw.decode(enc)
        break
    except UnicodeDecodeError:
        continue
else:
    text = raw.decode("utf-8", errors="replace")

with open(out, "w", encoding="utf-8") as f:
    f.write(text)
print("written:", out, "lines:", text.count("\n"))
