#!/usr/bin/env python3
"""扫描 Kotlin/XML 源码中对 R.string.* 的引用，校验其是否在 values/strings.xml 中定义。

构建期的 aapt / I18nVerifyPlugin 会因缺失字符串失败，本脚本提前在本地把问题揪出来。
用法：
    python scripts/check_strings.py app/src/main
"""
import re
import sys
from pathlib import Path


def collect_defined(res_dir: Path) -> set[str]:
    defined: set[str] = set()
    strings_xml = res_dir / "values" / "strings.xml"
    if not strings_xml.exists():
        print(f"找不到 {strings_xml}")
        return defined
    text = strings_xml.read_text(encoding="utf-8")
    for m in re.finditer(r'<(?:string|plurals|string-array)\s+name="([^"]+)"', text):
        defined.add(m.group(1))
    return defined


def collect_referenced(roots: list[Path]) -> dict[str, list[str]]:
    refs: dict[str, list[str]] = {}
    pattern = re.compile(r'R\.(?:string|plurals)\.([A-Za-z_][A-Za-z0-9_]*)')
    at_string = re.compile(r'@string/([A-Za-z_][A-Za-z0-9_]*)')
    # res/raw/keep.xml 里的 tools:keep 用通配符，不是真实引用
    keep_xml = re.compile(r'R\.(?:string|plurals)\.([A-Za-z_][A-Za-z0-9_]*)$')
    for root in roots:
        for path in list(root.rglob("*.kt")) + list(root.rglob("*.java")):
            try:
                text = path.read_text(encoding="utf-8")
            except Exception:
                continue
            for m in pattern.finditer(text):
                refs.setdefault(m.group(1), []).append(str(path))
        for path in list(root.rglob("*.xml")):
            if path.name == "keep.xml" and path.parent.name == "raw":
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except Exception:
                continue
            for m in at_string.finditer(text):
                refs.setdefault(m.group(1), []).append(str(path))
    return refs


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    main_dir = Path(sys.argv[1])
    res_dir = main_dir / "res"
    defined = collect_defined(res_dir)
    print(f"values/strings.xml 中定义的字符串: {len(defined)}")

    search_roots = [main_dir]
    # build-logic / ksp 生成的注解里也可能出现 R.string.*
    project_root = main_dir.parent.parent.parent
    for extra in ("build-logic", "ksp-processor", "annotation-api", "hidden-api"):
        p = project_root / extra
        if p.exists():
            search_roots.append(p)

    refs = collect_referenced(search_roots)
    print(f"源码中引用的字符串键: {len(refs)}")

    missing = {k: v for k, v in refs.items() if k not in defined}
    if missing:
        print("=" * 70)
        print(f"❌ 缺失 {len(missing)} 个字符串键：")
        for k in sorted(missing):
            files = sorted(set(missing[k]))
            print(f"  R.string.{k}")
            for f in files[:3]:
                print(f"      {f}")
        return 1

    print("✅ 全部字符串引用均可解析")
    return 0


if __name__ == "__main__":
    sys.exit(main())
