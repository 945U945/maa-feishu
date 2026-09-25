#!/usr/bin/env python3
"""
把 AppModule 里的 `single { X(args) } bind I::class` 改写为
`single<I> { X(args) }`。

背景：Koin 4.2 + Kotlin 2.x 下，`bind` 扩展对 lambda 返回值的类型推断
不稳定，会报 "receiver type mismatch"。改成显式泛型声明更可靠，
且不依赖 Koin 的 DSL 扩展导入。

注意：构造参数里可能嵌套括号（如 Provider(get(), get())），
所以不能用 [^)]* 贪婪匹配，需按括号配对解析。
"""

import re
import sys

PATH = "app/src/main/java/com/aliothmoon/maameow/koin/AppModule.kt"

src = open(PATH, encoding="utf-8").read()
out_lines = []
count = 0

# 行首形如： single { Xxx(arg1, arg2) } bind Yyy::class
line_pat = re.compile(r"^(\s*)single \{ (.+) \} bind (\w+)::class\s*$")

for line in src.splitlines():
    m = line_pat.match(line)
    if not m:
        out_lines.append(line)
        continue
    indent, expr, iface = m.group(1), m.group(2), m.group(3)
    out_lines.append(f"{indent}single<{iface}> {{ {expr} }}")
    count += 1

if count == 0:
    print("未匹配到任何 `bind` 用法，请检查文件内容。", file=sys.stderr)
    sys.exit(1)

open(PATH, "w", encoding="utf-8", newline="\n").write("\n".join(out_lines) + "\n")
print(f"✅ 已改写 {count} 处 bind 用法")
