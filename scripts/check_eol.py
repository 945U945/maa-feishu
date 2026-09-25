#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
字节级检测关键文件的行尾 —— **不要用 grep 判断行尾**。

## 为什么必须用字节级

在 Windows 的 Git Bash 里，`grep -c $'\r' file` 会做**文本模式转换**，
把纯 LF 文件也报告成含 CR，结果完全错误。

真实案例：一个已在磁盘上确认为纯 LF（CRLF=0，185 个 LF）的 `gradlew`，
被 `grep -c $'\r'` 报成「185 个 CR 行」，
让人误以为转换失败并反复重做 —— 白白浪费大量时间。

**唯一可信的判据是直接数字节。**

## 用法

    python scripts/check_eol.py                 # 检查当前目录
    python scripts/check_eol.py path/to/repo    # 检查指定目录
    python scripts/check_eol.py --fix           # 顺便就地转换

退出码：
    0 —— 全部符合预期
    1 —— 有文件行尾不符合预期（CI 里可用作守门）
"""

import sys
from pathlib import Path

# 必须 LF 的扩展名
LF_REQUIRED = {
    ".sh", ".bash", ".kt", ".kts", ".gradle", ".java", ".py",
    ".properties", ".toml", ".yml", ".yaml", ".xml", ".json",
    ".pro", ".md", ".txt", ".aidl",
}
# 无扩展名但必须 LF
LF_FORCED_NAMES = {"gradlew", ".gitattributes", ".gitignore", ".editorconfig"}

# 必须 CRLF（Windows 批处理解析器对 LF 支持不佳）
CRLF_REQUIRED_NAMES = {"gradlew.bat", ".bat", ".cmd"}

# 二进制：一律跳过，绝不做行尾转换
BINARY_SUFFIXES = {
    ".jar", ".jks", ".keystore", ".apk", ".png", ".jpg", ".jpeg",
    ".webp", ".gif", ".ttf", ".otf", ".so", ".dex", ".zip",
}

# 不扫描的目录
SKIP_DIRS = {
    ".git", ".gradle", ".kotlin", ".idea", "build",
    "__pycache__", ".pytest_cache", "node_modules",
}


def classify(rel: Path):
    """返回该文件期望的行尾类型: 'lf' | 'crlf' | None（不关心）"""
    if rel.suffix.lower() in BINARY_SUFFIXES:
        return None
    if rel.name in CRLF_REQUIRED_NAMES or rel.suffix.lower() in {".bat", ".cmd"}:
        return "crlf"
    if rel.suffix.lower() in LF_REQUIRED or rel.name in LF_FORCED_NAMES:
        return "lf"
    return None


def scan(root: Path):
    ok, bad = [], []
    for p in sorted(root.rglob("*")):
        if not p.is_file():
            continue
        rel = p.relative_to(root)
        if any(part in SKIP_DIRS for part in rel.parts):
            continue
        want = classify(rel)
        if want is None:
            continue

        data = p.read_bytes()
        crlf = data.count(b"\r\n")
        bare_cr = data.count(b"\r") - crlf
        lf = data.count(b"\n") - crlf

        if want == "lf":
            # 期望纯 LF：无 CRLF、无孤立 CR
            good = (crlf == 0 and bare_cr == 0)
        else:
            # 期望 CRLF：不应有「纯 LF 行」（总数不等于 CRLF 数）
            good = (lf == 0 and bare_cr == 0)

        record = (rel.as_posix(), want, crlf, bare_cr, lf, p)
        (ok if good else bad).append(record)
    return ok, bad


def fix(records):
    changed = []
    for rel, want, crlf, bare_cr, lf, path in records:
        data = path.read_bytes()
        if want == "lf":
            new = data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
        else:
            # 先统一成 LF，再统一成 CRLF，避免出现 \r\r\n
            tmp = data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
            new = tmp.replace(b"\n", b"\r\n")
        if new != data:
            path.write_bytes(new)
            changed.append((rel, want, len(data), len(new)))
    return changed


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    do_fix = "--fix" in sys.argv
    root = Path(args[0]).resolve() if args else Path.cwd()

    if not root.is_dir():
        print(f"✗ 目录不存在: {root}", file=sys.stderr)
        return 2

    print(f"扫描: {root}")
    print(f"（用字节计数判断，不依赖 grep —— grep 在 Windows 上会误报）")
    print()

    ok, bad = scan(root)

    if do_fix and bad:
        print(f"转换 {len(bad)} 个不符合预期的文件 ...")
        changed = fix(bad)
        for rel, want, before, after in changed:
            print(f"  ~ {rel}  -> {want.upper()}  ({before} -> {after} bytes)")
        print()
        # 转换后重新扫描
        ok, bad = scan(root)

    if ok:
        print(f"✓ 符合预期: {len(ok)} 个")
        # 只列出关键文件，避免刷屏
        key = [r for r in ok if r[0] in
               ("gradlew", ".gitattributes", "gradlew.bat")]
        for rel, want, crlf, bare_cr, lf, _ in key:
            print(f"    {rel:20s} {want.upper():4s}  CRLF={crlf} bareCR={bare_cr} LF={lf}")

    if bad:
        print()
        print(f"✗ 不符合预期: {len(bad)} 个")
        for rel, want, crlf, bare_cr, lf, _ in bad:
            print(f"    {rel}")
            print(f"      期望 {want.upper()}，实际 CRLF={crlf} bareCR={bare_cr} LF={lf}")
            if want == "lf" and crlf:
                print(f"      → CRLF 会让 shebang 变成 '#!/usr/bin/env sh\\r'，")
                print(f"        在 Linux 上 exit 127（command not found）")
        print()
        print("提示：加 --fix 可就地转换")
        return 1

    print()
    print("✓ 所有关键文件行尾正确")
    return 0


if __name__ == "__main__":
    sys.exit(main())
