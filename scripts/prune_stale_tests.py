#!/usr/bin/env python3
"""找出引用了「已删除工程符号」的测试文件，并（可选）删除。

原理：测试文件 import 的 com.aliothmoon.maameow.* 符号，若在主源码树中
找不到对应文件/符号定义，则该测试必然编译失败，应当移除。

用法：
    python scripts/prune_stale_tests.py            # 仅报告
    python scripts/prune_stale_tests.py --delete   # 执行删除
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN_SRC = ROOT / "app/src/main/java"
TEST_SRC = ROOT / "app/src/test/java"
ANDROID_TEST_SRC = ROOT / "app/src/androidTest/java"

# KSP / AIDL 生成物与顶层常量白名单
GENERATED = {
    "BuildConfig", "R", "AppSettingsSchema",
    "ITouchEventCallback", "ILogcatService", "RemoteService",
    "PermissionGrantRequest", "PermissionStateInfo",
}


def build_main_symbol_index() -> tuple[set[str], set[str]]:
    """返回 (全限定类名集合, 顶层声明名集合)"""
    fqcn: set[str] = set()
    for path in MAIN_SRC.rglob("*.kt"):
        text = path.read_text(encoding="utf-8", errors="ignore")
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        if not pkg:
            continue
        base = pkg.group(1)
        # 顶层 class / object / interface / enum / fun / val / const val
        for m in re.finditer(
            r"^(?:@\w+(?:\([^)]*\))?\s*)*"
            r"(?:public |internal |private |abstract |open |sealed |data |value |annotation )*"
            r"(class|object|interface|enum class|fun|val|const val|typealias)\s+"
            r"([A-Za-z_][\w]*)",
            text, re.M,
        ):
            fqcn.add(f"{base}.{m.group(2)}")
        # 嵌套 object 里的常量：粗略扫 `object X {` 与 `companion object`
        for m in re.finditer(r'^\s*(?:const )?val ([A-Z_][A-Z0-9_]*)\b', text, re.M):
            fqcn.add(f"{base}.{m.group(1)}")
    for path in MAIN_SRC.rglob("*.java"):
        text = path.read_text(encoding="utf-8", errors="ignore")
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        if not pkg:
            continue
        base = pkg.group(1)
        for m in re.finditer(
            r"^(?:public |final |abstract |static )*(?:class|interface|enum)\s+([A-Za-z_]\w*)",
            text, re.M,
        ):
            fqcn.add(f"{base}.{m.group(1)}")
    return fqcn, set()


def stale_imports(text: str, index: set[str]) -> list[str]:
    bad: list[str] = []
    for m in re.finditer(r"^import\s+(com\.aliothmoon\.maameow\.[\w.]+)", text, re.M):
        full = m.group(1)
        leaf = full.split(".")[-1]
        if leaf in GENERATED:
            continue
        # 逐级回退：com.a.b.C.D -> 尝试 com.a.b.C 视作嵌套类
        parts = full.split(".")
        ok = False
        for cut in range(len(parts), 3, -1):
            if ".".join(parts[:cut]) in index:
                ok = True
                break
        if not ok:
            bad.append(full)
    return bad


def main() -> int:
    delete = "--delete" in sys.argv
    index, _ = build_main_symbol_index()
    print(f"主源码树符号索引: {len(index)} 条")

    stale_files: list[tuple[Path, list[str]]] = []
    for src in (TEST_SRC, ANDROID_TEST_SRC):
        if not src.exists():
            continue
        for path in src.rglob("*.kt"):
            bad = stale_imports(path.read_text(encoding="utf-8", errors="ignore"), index)
            if bad:
                stale_files.append((path, bad))

    if not stale_files:
        print("✅ 没有发现引用已删除符号的测试文件")
        return 0

    print(f"\n发现 {len(stale_files)} 个失效测试文件：")
    for path, bad in stale_files:
        print(f"  {path.relative_to(ROOT)}")
        for b in bad[:3]:
            print(f"      {b}")

    if delete:
        for path, _ in stale_files:
            path.unlink()
        print(f"\n已删除 {len(stale_files)} 个文件")
    else:
        print("\n（加 --delete 执行删除）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
