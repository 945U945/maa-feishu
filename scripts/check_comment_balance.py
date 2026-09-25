#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
扫描 Kotlin 源文件里块注释的配对情况，找出「多一个 /*」的文件。

## 为什么需要这个脚本

Kotlin 的块注释**可以嵌套**（这是与 Java / C 的重要区别）。
如果注释正文里出现了 `/*`（比如把路径写成 `plans/*.json`），
就会开启一层嵌套注释，把后面本来用于闭合外层注释的 `*/` 消耗掉，
最终整份文件在 EOF 处报 `Unclosed comment`。

危害不在于这一个错误，而在于**级联**：
该文件解析失败后，它定义的所有类型在别处全部变成
`Unresolved reference`，于是 1 个语法错误表现为 30 个分散的错误。
按错误数量排序去修是徒劳的，必须先找到真正出问题的文件。

## 用法

    python check_comment_balance.py [源码根目录 ...]

默认扫描 ./app/src（不存在则退回 .）。

## 输出

- 所有配对正常的文件：只在 --verbose 时列出
- 不配对的文件：**总是**列出来，并给出可疑行号
- 退出码：有问题返回 1，全部正常返回 0（可直接用于 CI 门禁）

## 设计取舍

不做完整的 Kotlin 词法分析 —— 那需要处理字符串模板里的嵌套、原始字符串、
字符字面量中的转义等一堆边界，成本远超收益。
这里只做「计数 + 定位可疑行」，因为：

1. 真实场景里 `/*` 误植的地方极少数会被字符串包住
2. 计数不配对本身就是强信号，足以把范围缩小到一个文件
3. 再配合「打印含 `/*` 且不属于注释起始的花样行」人工确认即可
"""

import re
import sys
from pathlib import Path

# 可疑模式：行内含 /* 但不像是注释起始
#
# 正常注释起始长这样：
#   /**            ← KDoc
#   /*             ← 普通块注释
#   /** ... */     ← 单行 KDoc
# 也就是说 /* 前面允许有空白，且 /* 之后通常是空白、*、或换行。
#
# 若 /* 前面紧邻非空白字符（如 `plans/*.json` 里的 s），
# 或在引号/反引号内部，就是高度可疑的误植。
SUSPECT = re.compile(r"(?<![*/\s])/\*")


def scan(path: Path) -> tuple[int, int, list[tuple[int, str]]]:
    """返回 (open_count, close_count, 可疑行列表)"""
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError as e:
        print(f"  ! 无法读取: {e}", file=sys.stderr)
        return 0, 0, []

    opens = text.count("/*")
    closes = text.count("*/")
    if opens == closes:
        return opens, closes, []

    suspects: list[tuple[int, str]] = []
    for i, line in enumerate(text.splitlines(), 1):
        if SUSPECT.search(line):
            suspects.append((i, line.strip()))
    return opens, closes, suspects


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    verbose = "-v" in sys.argv or "--verbose" in sys.argv

    roots = [Path(a) for a in args] if args else [Path("app/src")]
    roots = [r for r in roots if r.exists()] or [Path(".")]

    files: list[Path] = []
    for root in roots:
        files.extend(sorted(root.rglob("*.kt")))

    if not files:
        print("未找到 .kt 文件")
        return 0

    print(f"扫描 {len(files)} 个 Kotlin 文件（块注释配对检查）")
    print("-" * 68)

    bad: list[tuple[Path, int, int, list[tuple[int, str]]]] = []
    for f in files:
        o, c, susp = scan(f)
        if o != c:
            bad.append((f, o, c, susp))
        elif verbose:
            print(f"  ok  {o:>3} 对  {f}")

    if not bad:
        print()
        print("=" * 68)
        print(f"✓ 全部 {len(files)} 个文件的块注释均配对")
        print("=" * 68)
        return 0

    # ── 有问题：详细输出 ──
    print()
    for f, o, c, susp in bad:
        print(f"✗ {f}")
        print(f"    块注释: {o} 个 '/*' vs {c} 个 '*/' —— 相差 {o - c}")
        print(f"    含义: 有 {o - c} 个 '/*' 没有对应的闭合，"
              f"编译器会在文件末尾报 Unclosed comment")
        if susp:
            print("    可疑行（'/*' 前紧邻非空白字符，疑似误植）:")
            for lineno, text in susp[:10]:
                print(f"      L{lineno}: {text[:100]}")
        else:
            print("    未能自动定位可疑行 —— 请手工查找注释里的 glob 写法，")
            print("    如 *.json / **/*.kt 这类含 '/*' 的路径")
        print()

    print("=" * 68)
    print(f"✗ {len(bad)} 个文件的块注释不配对")
    print("  修复要点：注释正文里不要写含 '/*' 的 glob 路径，")
    print("           改写成文字描述（如「plans/ 目录下的 json 文件」）")
    print("=" * 68)
    return 1


if __name__ == "__main__":
    sys.exit(main())
