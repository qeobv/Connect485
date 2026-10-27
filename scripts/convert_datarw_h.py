# -*- coding: utf-8 -*-
"""读取 GBK 编码的 datarw.h，转成 UTF-8 文本"""
import os

src = r"E:\JavaProject\导入导出测试\datarw.h"
out = os.path.join(os.environ.get("TEMP", r"C:\Windows\Temp"), "datarw_h_utf8.txt")

with open(src, "rb") as f:
    raw = f.read()

for enc in ("gbk", "gb18030", "utf-16"):
    try:
        text = raw.decode(enc)
        break
    except UnicodeDecodeError:
        continue
else:
    text = raw.decode("utf-8", errors="replace")

with open(out, "w", encoding="utf-8") as f:
    f.write(text)
print("written:", out, "chars:", len(text), "lines:", text.count("\n"))
