#!/usr/bin/env python3
"""
静态依赖检查：扫描 Kotlin 源文件中的 import，检测指向并不存在的同一工程内符号。

用法：python check_deps.py <src_root>
"""
import os
import re
import sys
from collections import defaultdict

# 收集工程内所有已定义的顶层符号
DEF_PATTERNS = [
    re.compile(r'^\s*(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+|final\s+|data\s+|value\s+|annotation\s+|const\s+|override\s+)*'
               r'(?:class|interface|object|enum\s+class|fun|val|var|const\s+val|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)', re.M),
    # 顶层 interface（如 BootstrapRegistry）不带修饰符的写法
    re.compile(r'^\s*interface\s+([A-Za-z_][A-Za-z0-9_]*)', re.M),
    # 泛型函数：fun <T> Foo(...)
    re.compile(r'^\s*(?:public\s+|internal\s+|private\s+)?fun\s*<[^>]*>\s*([A-Za-z_][A-Za-z0-9_]*)', re.M),
    # 扩展函数：fun Type.name(...) / fun Type?.name(...)
    re.compile(r'^\s*(?:public\s+|internal\s+|private\s+|inline\s+|suspend\s+|@\w+\s*)*'
               r'fun\s+(?:[A-Za-z_][A-Za-z0-9_.]*[?]?\.)?([A-Za-z_][A-Za-z0-9_]*)\s*[(<]', re.M),
    # @Composable 标注的顶层函数（缩进 + 注解已在上一条覆盖，这里兜底多行注解）
    re.compile(r'^@\w+\s*$[\r\n]+(?:\s*@\w+\s*$[\r\n]+)*\s*(?:public\s+|internal\s+|private\s+)?fun\s+([A-Za-z_][A-Za-z0-9_]*)', re.M),
    # Java 顶层类型（third/ 下的反射包装类），含 record
    re.compile(r'^(?:public\s+|final\s+|abstract\s+|static\s+)*(?:class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)', re.M),
]

def collect_definitions(root):
    """返回 {(包路径, 符号名)} 集合，以及 符号名集合"""
    defs = set()
    files = []
    for dirpath, _, filenames in os.walk(root):
        for fn in filenames:
            if fn.endswith(('.kt', '.java')):
                files.append(os.path.join(dirpath, fn))

    for path in files:
        try:
            with open(path, encoding='utf-8', errors='ignore') as f:
                content = f.read()
        except Exception:
            continue

        # 包名
        pkg_m = re.search(r'^\s*package\s+([A-Za-z0-9_.]+)', content, re.M)
        pkg = pkg_m.group(1) if pkg_m else ''

        for pat in DEF_PATTERNS:
            for m in pat.finditer(content):
                defs.add((pkg, m.group(1)))

    return defs

def collect_imports(root):
    """返回 [(文件, 行号, import全名)]"""
    imports = []
    for dirpath, _, filenames in os.walk(root):
        for fn in filenames:
            if not fn.endswith(('.kt', '.java')):
                continue
            path = os.path.join(dirpath, fn)
            try:
                with open(path, encoding='utf-8', errors='ignore') as f:
                    for i, line in enumerate(f, 1):
                        s = line.strip()
                        if s.startswith('import ') and not s.startswith('import static'):
                            name = s[len('import '):].rstrip(';').strip()
                            if name.startswith('com.aliothmoon.maameow'):
                                imports.append((path, i, name))
            except Exception:
                continue
    return imports

def main():
    if len(sys.argv) < 2:
        print('usage: check_deps.py <src_root>')
        sys.exit(1)

    root = sys.argv[1]
    defs = collect_definitions(root)
    # 按 (包, 名) 与 全局名 建索引
    by_pkg_name = set(defs)
    names = {n for (_, n) in defs}

    # 生成类符号（AGP/AIDL/KSP 生成，源码树中不存在）
    GENERATED = {
        'BuildConfig', 'R', 'Manifest', 'BR', 'DataBinder', 'BuildConfigKt',
        'ITouchEventCallback', 'ILogcatService', 'RemoteService',
        'MaaCoreService', 'MaaCoreCallback',
        'PermissionGrantRequest', 'PermissionStateInfo',
        # @PrefSchema / @PrefKey 由 KSP 生成
        'AppSettingsSchema', 'FONT_SIZE_SCALE_AUTO',
    }

    imports = collect_imports(root)

    missing = []
    for path, lineno, full in imports:
        parts = full.split('.')
        symbol = parts[-1]
        pkg = '.'.join(parts[:-1])

        if symbol in GENERATED:
            continue

        # 通配 import（如 import a.b.*）跳过
        if symbol == '*':
            pkgdir = os.path.join(root, *pkg.split('.'))
            if not os.path.isdir(pkgdir):
                missing.append((path, lineno, full, 'PACKAGE'))
            continue

        # 情况1：符号是顶层定义，定义在 pkg 下
        if (pkg, symbol) in by_pkg_name:
            continue
        # 情况2：嵌套类 import Out.Inner —— Inner 定义在 Out 的文件里
        if '.' in pkg:
            parent_pkg, parent_sym = pkg.rsplit('.', 1)
            if (parent_pkg, parent_sym) in by_pkg_name:
                continue
        # 情况3：顶层函数/属性（无类容器），按名字全局匹配
        if symbol in names:
            continue
        # 情况3b：AIDL / Java 生成的嵌套伴生常量（如 Foo.Companion.BAR）
        if 'Companion' in parts or symbol.isupper():
            if any(p in names for p in parts[:-1]):
                continue
        # 情况3c：Kotlin companion/object 内的常量，源码树中以普通 val 出现
        if symbol.isupper() and any(
                n for n in names if n == symbol):
            continue
        # 情况4：同名文件存在（最宽松兜底）
        for ext in ('.kt', '.java'):
            cand = os.path.join(root, *pkg.split('.'), symbol + ext)
            if os.path.isfile(cand):
                break
        else:
            missing.append((path, lineno, full, 'SYMBOL'))

    # 汇总
    print(f'扫描文件根目录: {root}')
    print(f'工程内顶层定义符号数: {len(names)}')
    print(f'工程内 import 语句数: {len(imports)}')
    print('=' * 70)
    if not missing:
        print('✅ 未发现指向缺失符号的 import')
    else:
        print(f'❌ 发现 {len(missing)} 处可疑 import：\n')
        grouped = defaultdict(list)
        for path, lineno, full, kind in missing:
            grouped[full].append((path, lineno, kind))
        for full, locs in sorted(grouped.items()):
            print(f'  {full}')
            for path, lineno, kind in locs[:3]:
                rel = os.path.relpath(path, root)
                print(f'      {rel}:{lineno}  [{kind}]')
            if len(locs) > 3:
                print(f'      ... 另外 {len(locs) - 3} 处')
            print()

if __name__ == '__main__':
    main()
