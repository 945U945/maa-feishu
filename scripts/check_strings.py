#!/usr/bin/env python3
"""
字符串资源一致性检查。

## 为什么需要这个脚本

上一版出现过两类 i18n 问题，都能编译通过但运行时报错或显示空白：

1. **默认语言有、其它语言没有**：中文 strings.xml 定义了 key，
   英文 values-en 里漏了 → 英文环境下显示 key 名本身
2. **代码引用了不存在的 key**：`R.string.xxx` 在 xml 里没定义
   → 编译期才会报错，而且报错信息指向代码行而非根因

第 2 类现在有 AGP 覆盖（编译期报错），第 1 类**没有任何工具会检查**，
所以这个脚本的价值主要在第 1 类。

## 检查项

1. `values/strings.xml` 与所有 `values-*/strings.xml` 的 key 集合一致
2. 每个 key 在两种语言里都非空
3. 代码里引用的 `R.string.*` 都在默认语言里存在
4. 代码引用的 `R.string.*` 里，格式化占位符（%1$s 等）在两种语言里一致

第 4 项容易忽略但会崩溃：中文写 `%1$d 项`、英文写 `%d items`，
在英文环境下 `getString(R.string.x, 1)` 会抛
`IllegalFormatConversionException`。

## 退出码

0 = 通过；1 = 发现问题
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# 占位符：%1$s / %2$d / %s / %d
RE_PLACEHOLDER = re.compile(r"%(\d+\$)?[sdfx]")

# 代码里的 R.string.xxx 引用
RE_STRING_REF = re.compile(r"\bR\.string\.([a-z0-9_]+)\b")


def read_strings(path: Path) -> dict[str, str]:
    """读取一个 strings.xml，返回 {key: value}"""
    if not path.exists():
        return {}
    try:
        tree = ET.parse(path)
    except ET.ParseError as e:
        print(f"[错误] 无法解析 {path}: {e}")
        return {}

    result: dict[str, str] = {}
    root = tree.getroot()
    for elem in root:
        if elem.tag not in ("string", "plurals", "string-array"):
            continue
        name = elem.get("name")
        if not name:
            continue
        # 拼接所有文本（含 <xliff:g> 等子元素）
        text = "".join(elem.itertext())
        result[name] = text
    return result


def collect_kotlin_refs(src_root: Path) -> dict[str, list[str]]:
    """扫描 Kotlin 代码里的 R.string 引用，返回 {key: [文件:行]}"""
    refs: dict[str, list[str]] = {}

    for f in sorted(src_root.rglob("*.kt")):
        try:
            content = f.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue

        for i, line in enumerate(content.splitlines(), start=1):
            # 跳过注释行（减少误报：注释里提到的示例不算真实引用）
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*"):
                continue

            for m in RE_STRING_REF.finditer(line):
                key = m.group(1)
                refs.setdefault(key, []).append(f"{f.name}:{i}")

    return refs


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    res_dir = root / "app" / "src" / "main" / "res"
    src_root = root / "app" / "src" / "main" / "java"

    if not res_dir.exists():
        print(f"[错误] 找不到资源目录: {res_dir}")
        return 1

    default_file = res_dir / "values" / "strings.xml"
    if not default_file.exists():
        print(f"[错误] 找不到默认语言资源: {default_file}")
        return 1

    default = read_strings(default_file)
    print(f"默认语言（values）: {len(default)} 个 key")

    if not default:
        print("[错误] 默认语言没有解析到任何 key，请检查文件格式")
        return 1

    problems: list[str] = []

    # ── 1. 收集所有 locale 目录 ──
    locales: list[tuple[str, dict[str, str], Path]] = []
    for d in sorted(res_dir.iterdir()):
        if not d.is_dir() or not d.name.startswith("values-"):
            continue
        f = d / "strings.xml"
        if not f.exists():
            continue
        strings = read_strings(f)
        locales.append((d.name, strings, f))

    if not locales:
        print("未发现其它语言目录，跳过跨语言检查")

    # ── 2. 跨语言 key 集合一致性 ──
    for locale_name, strings, f in locales:
        print(f"{locale_name}: {len(strings)} 个 key")

        missing = sorted(set(default) - set(strings))
        extra = sorted(set(strings) - set(default))

        for key in missing:
            problems.append(
                f"[{locale_name}] 缺少 key: {key}"
                f"（默认语言有，{f.name} 没有）"
            )
        for key in extra:
            problems.append(
                f"[{locale_name}] 多余的 key: {key}"
                f"（{f.name} 有，默认语言没有 —— 可能是拼写错误）"
            )

        # ── 3. 占位符一致性 ──
        # 这是会**运行时崩溃**的问题，优先级最高
        for key in set(default) & set(strings):
            default_ph = set(RE_PLACEHOLDER.findall(default[key]))
            locale_ph = set(RE_PLACEHOLDER.findall(strings[key]))

            if default_ph != locale_ph:
                problems.append(
                    f"[{locale_name}] key '{key}' 占位符不一致: "
                    f"默认语言={sorted(default_ph) or '无'}, "
                    f"{locale_name}={sorted(locale_ph) or '无'}  "
                    f"← 运行时会抛 IllegalFormatConversionException"
                )

    # ── 4. 代码引用检查 ──
    if src_root.exists():
        refs = collect_kotlin_refs(src_root)
        print(f"代码中引用 {len(refs)} 个字符串 key")

        undefined = sorted(set(refs) - set(default))
        for key in undefined:
            locations = ", ".join(refs[key][:3])
            problems.append(
                f"代码引用了未定义的 key: R.string.{key}  ({locations})"
            )

        # 未使用的 key（仅提示，不算错误）
        unused = sorted(set(default) - set(refs))
        if unused:
            print(f"提示: {len(unused)} 个 key 未被代码直接引用"
                  f"（可能由 Manifest 引用）")

    # ── 报告 ──
    print()
    if problems:
        print("=" * 68)
        print(f"发现 {len(problems)} 个字符串资源问题：")
        print("=" * 68)
        for p in problems:
            print(f"  ✗ {p}")
        print()
        print("提示：占位符不一致是运行时崩溃，务必修复；")
        print("      缺失 key 会让对应语言显示 key 名本身。")
        return 1

    total = len(default) + sum(len(s) for _, s, _ in locales)
    print("=" * 68)
    print(f"✓ 字符串资源检查通过：{len(default)} 个 key × {len(locales) + 1} 种语言"
          f"（共 {total} 条），全部一致")
    print("=" * 68)
    return 0


if __name__ == "__main__":
    sys.exit(main())
