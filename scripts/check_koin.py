#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Koin 依赖闭合性校验。

编译通过 ≠ 能跑。Koin 的缺失依赖只在**运行期**解析时才抛
NoDefinitionFoundException，静态编译器完全看不见。

本脚本做的事：
  1. 解析全工程 `class X(...)` 的主构造与 `constructor(...)` 次构造签名
  2. 解析 koin/ 下所有注册点（single / singleOf / factory / viewModelOf）
     以及每个注册点用到的实现类与实参
  3. 逐点比对：
     - 实参个数能否匹配某个构造重载
     - 匹配到的构造的参数类型是否都「可被 Koin 提供」
       （可被提供 = 该类型自身有注册，或属于 Android/框架内建类型）

用法：
    python scripts/check_koin.py app/src/main

退出码：0 全部通过；1 发现问题。

背景：飞书打卡助手（FeishuCheckIn）曾因 AppPathConfig / MaaSessionLogger
未注册，导致启动同步段 treeHolder.setup() 解析失败、应用启动即闪退。
"""
import io
import os
import re
import sys


# 这些类型由 Koin 的 androidContext() / 框架本身提供，无需显式注册
BUILTIN = {
    'Context', 'Application', 'Activity', 'CoroutineScope', 'DataStore',
    'OkHttpClient', 'KeyguardManager', 'PowerManager',
    'String', 'Boolean', 'Int', 'Long', 'Float', 'Double', 'Unit',
    'List', 'MutableList', 'Set', 'Map', 'Flow', 'StateFlow', 'SharedFlow',
    'File', 'Uri', 'Intent',
}


def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    return re.sub(r'//[^\n]*', '', text)


def match_paren(s, open_idx):
    """返回 s[open_idx] 对应的括号内内容（不含最外层括号）。"""
    depth = 0
    for j in range(open_idx, len(s)):
        if s[j] == '(':
            depth += 1
        elif s[j] == ')':
            depth -= 1
            if depth == 0:
                return s[open_idx + 1:j]
    return ''


def split_top_level(s):
    """按顶层逗号切分（忽略嵌套括号内的逗号）。"""
    parts, buf, depth = [], '', 0
    for ch in s:
        if ch in '(<[':
            depth += 1
        elif ch in ')>]':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(buf)
            buf = ''
        else:
            buf += ch
    if buf.strip():
        parts.append(buf)
    return parts


def param_types(param_str):
    """从形参列表文本提取每个参数的类型名（去掉包名与泛型）。"""
    types = []
    for p in split_top_level(param_str):
        m = re.search(r':\s*([A-Za-z_][\w.]*)\s*(?:<[^>]*>)?\s*(?:=|$)', p.strip())
        if m:
            types.append(m.group(1).split('.')[-1])
    return types


def collect_constructors(src_root):
    """类名 -> [(kind, [参数类型...]), ...]，kind 为 primary / secondary。"""
    klass = {}
    for dirpath, _, filenames in os.walk(src_root):
        for fn in filenames:
            if not fn.endswith('.kt') and not fn.endswith('.java'):
                continue
            path = os.path.join(dirpath, fn)
            code = strip_comments(open(path, encoding='utf-8', errors='replace').read())
            for m in re.finditer(r'\bclass\s+(\w+)\b', code):
                name = m.group(1)
                k = code.find('(', m.end())
                if k == -1 or k - m.end() > 100:
                    continue
                klass.setdefault(name, []).append(('primary', param_types(match_paren(code, k))))
                # 次构造函数
                tail = code[m.end():]
                for m2 in re.finditer(r'\bconstructor\s*\(', tail):
                    k2 = code.find('(', m.end() + m2.start())
                    klass[name].append(('secondary', param_types(match_paren(code, k2))))
    return klass


def collect_registrations(koin_dir):
    """返回已注册类型集合 与 注册点列表 [(impl, [实参文本...], 源文件)]。"""
    registered = set()
    sites = []
    for fn in sorted(os.listdir(koin_dir)):
        if not fn.endswith('.kt'):
            continue
        path = os.path.join(koin_dir, fn)
        code = strip_comments(open(path, encoding='utf-8', errors='replace').read())

        # singleOf(::X) / viewModelOf(::X) / factory(::X)
        for m in re.finditer(r'\b(?:singleOf|factoryOf|viewModelOf|workerOf|scopedOf)\s*\(\s*::\s*(\w+)', code):
            registered.add(m.group(1))

        # single<IFace> { ... }
        for m in re.finditer(r'\bsingle\s*<\s*([\w.]+)\s*>\s*\{', code):
            registered.add(m.group(1).split('.')[-1])

        # single { Impl(...) } / single<IFace> { Impl(...) }
        for m in re.finditer(r'\bsingle\s*(?:<\s*([\w.]+)\s*>)?\s*\{', code):
            iface = m.group(1)
            if iface:
                registered.add(iface.split('.')[-1])
            body = match_paren(code, code.find('{', m.end() - 1))
            m2 = re.search(r'\b([A-Z]\w+)\s*\(', body)
            if not m2:
                continue
            impl = m2.group(1)
            registered.add(impl)
            args = [a.strip() for a in split_top_level(
                match_paren(body, body.find('(', m2.end() - 1))) if a.strip()]
            sites.append((impl, args, fn))

    return registered, sites


def main():
    src_root = sys.argv[1] if len(sys.argv) > 1 else 'app/src/main'
    koin_dir = os.path.join(src_root, 'java', 'com', 'aliothmoon', 'maameow', 'koin')
    if not os.path.isdir(koin_dir):
        print('找不到 Koin 目录：%s' % koin_dir)
        return 1

    klass = collect_constructors(src_root)
    registered, sites = collect_registrations(koin_dir)
    registered |= BUILTIN

    print('扫描根目录: %s' % src_root)
    print('已知类定义: %d   Koin 已注册类型: %d   注册点: %d' % (len(klass), len(registered), len(sites)))
    print('=' * 70)

    problems = []
    for impl, args, where in sites:
        # 跳过具名参数里的 lambda（含 '->' 的实参不是类型依赖）
        count = len(args)
        overloads = klass.get(impl)
        if not overloads:
            continue
        cands = [o for o in overloads if len(o[1]) == count]
        if not cands:
            # 参数个数不匹配：可能是默认值或 lambda，交由编译器判断
            continue
        types = cands[0][1]
        missing = [t for t in types if t not in registered]
        if missing:
            problems.append((impl, missing, where, types))

    if problems:
        print('发现 %d 处可疑的依赖缺失：' % len(problems))
        seen = set()
        for impl, missing, where, types in problems:
            key = (impl, tuple(missing))
            if key in seen:
                continue
            seen.add(key)
            print('  x %-28s 构造=%s' % (impl, types))
            print('      缺少注册: %s   (注册于 %s)' % (missing, where))
        print()
        print('提示：若某类型确由 Koin 提供但未在本脚本识别范围内，')
        print('      可加入脚本顶部 BUILTIN 白名单。')
        return 1

    print('  未发现 Koin 依赖缺失')
    print()
    print('注意：本脚本为静态近似判断，最终以真机运行 / CI 构建为准。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
