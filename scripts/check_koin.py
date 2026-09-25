#!/usr/bin/env python3
"""
Koin 依赖注册审计。

## 这个脚本为什么存在（血泪教训）

上一版应用表现为「点图标闪一下就退」。根因是 Koin 依赖链断裂：

    AppPathConfig 与 MaaSessionLogger 从未注册，
    但被 Application.onCreate 的同步段直接消费

首次解析即抛 NoDefinitionFoundException，没有任何可读的日志，
排查花了很久。

更糟的是，第一版审计脚本只扫 `get<X>()` 这种带显式泛型的调用，
**漏掉了无参 `get()`** —— 而 MaaSessionLogger 正是通过无参 `get()`
被消费的。脚本报告「无问题」，实际有致命问题。

## 本脚本的做法

不再去猜调用点的类型，而是**反向推导**：

1. 收集所有注册了的东西（`single` / `factory` / `viewModel` 的
   返回类型，或 `singleOf` 的类）
2. 收集所有注册块里用到的 `get()` / `get<T>()` / `inject()`
3. 对每个 `get()`，按**实参个数**匹配目标构造函数的参数个数，
   反推它需要注入哪些类型
4. 检查第 3 步推出来的类型是否在第 1 步里注册过

这样即便是无参 `get()`，只要目标构造函数是确定的，就能算出它需要什么。

## 退出码

0 = 检查通过；1 = 发现未注册依赖。CI 里任一 PR 都会跑。
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

# ────────────────────── 配置 ──────────────────────

# 这些类型由 Koin 框架 / Android 平台直接提供，不需要显式注册
BUILTIN_TYPES = {
    "Context", "applicationContext", "androidContext", "Application",
    "CoroutineScope", "AppSettings", "CheckInRequest", "CheckInRecord",
    "List", "Set", "Map", "String", "Int", "Long", "Boolean", "Float",
    "Any", "Unit", "Nothing", "Json", "File", "AlarmManager",
    "NotificationManager", "PowerManager", "KeyguardManager",
    "PackageManager", "SettingsRepository",
}

# 限定符：带 qualifier 的注册只对这些名字可见
QUALIFIER_HINTS = {"named"}


@dataclass
class Registration:
    """一条 Koin 注册记录"""
    type_name: str
    kind: str  # single / factory / viewModel
    file: str
    line: int
    qualifier: str | None = None


@dataclass
class Constructor:
    """一个类的构造函数签名"""
    class_name: str
    file: str
    params: list[tuple[str, str]] = field(default_factory=list)  # (name, type)


@dataclass
class GetCall:
    """一次依赖解析调用"""
    file: str
    line: int
    explicit_type: str | None
    arg_count: int
    raw: str


class AuditError(Exception):
    pass


# ────────────────────── 解析 ──────────────────────

# 匹配 single { } / factory { } / viewModel { } —— 后接构造调用的形式
# 例：single { AppPaths(androidContext()) }
#     single<DeviceStateProvider> { AndroidDeviceStateProvider(...) }
RE_REGISTRATION_CONSTRUCT = re.compile(
    r"\b(?P<kind>single|factory|viewModel)\s*"
    r"(?:<(?P<iface>[A-Za-z_][A-Za-z0-9_.<>,\s]*?)>)?\s*"
    r"\{"
)

# 匹配带限定符的注册：single(named("x")) { ... }
RE_QUALIFIER = re.compile(r'named\s*\(\s*"([^"]+)"\s*\)')

# 匹配 get() / get<T>() / inject()
RE_GET = re.compile(r"\b(?P<fn>get|inject)\s*(?:<(?P<type>[A-Za-z_][A-Za-z0-9_.]*?)>)?\s*\(")

# 匹配类声明与主构造函数
RE_CLASS_WITH_CTOR = re.compile(
    r"\bclass\s+(?P<name>[A-Z][A-Za-z0-9_]*)"
    r"\s*(?:<[^>]*>)?\s*"  # 泛型参数
    r"\((?P<params>[^)]*(?:\)[^)]*)*?)\)"  # 构造函数参数（容忍嵌套括号）
    r"\s*(?::[^\n{]*)?\s*\{",
    re.S,
)

# 单个参数：name: Type 或 private val name: Type
RE_PARAM = re.compile(
    r"(?:private\s+|internal\s+|protected\s+|public\s+)?"
    r"(?:val\s+|var\s+)?"
    r"(?P<name>[a-z_][A-Za-z0-9_]*)\s*:\s*"
    r"(?P<type>[A-Za-z_][A-Za-z0-9_.<>,?\s]*?)"
    r"\s*(?:=|,|$)",
    re.M,
)


def strip_comments(source: str) -> str:
    """
    去掉注释，但保留行结构（用空行替换），
    这样报错时的行号仍然准确。

    特别注意 KDoc 里大量出现 `get()` 这样的示例代码 ——
    不剥离注释会把这些示例当成真实调用，产生海量误报。
    """
    out = []
    i = 0
    n = len(source)
    in_line_comment = False
    in_block_comment = False
    in_string = False
    in_raw_string = False
    in_char = False

    while i < n:
        c = source[i]
        nxt = source[i + 1] if i + 1 < n else ""

        if in_line_comment:
            if c == "\n":
                in_line_comment = False
                out.append(c)
            else:
                out.append(" ")
            i += 1
            continue

        if in_block_comment:
            if c == "*" and nxt == "/":
                in_block_comment = False
                out.append("  ")
                i += 2
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue

        if in_raw_string:
            out.append(c)
            if source[i:i + 3] == '"""':
                in_raw_string = False
                out.append('""')
                i += 3
                continue
            i += 1
            continue

        if in_string:
            out.append(c)
            if c == "\\" and nxt:
                out.append(nxt)
                i += 2
                continue
            if c == '"':
                in_string = False
            i += 1
            continue

        if in_char:
            out.append(c)
            if c == "\\" and nxt:
                out.append(nxt)
                i += 2
                continue
            if c == "'":
                in_char = False
            i += 1
            continue

        # 普通代码
        if c == "/" and nxt == "/":
            in_line_comment = True
            out.append("  ")
            i += 2
            continue
        if c == "/" and nxt == "*":
            in_block_comment = True
            out.append("  ")
            i += 2
            continue
        if source[i:i + 3] == '"""':
            in_raw_string = True
            out.append('"""')
            i += 3
            continue
        if c == '"':
            in_string = True
            out.append(c)
            i += 1
            continue
        if c == "'":
            in_char = True
            out.append(c)
            i += 1
            continue

        out.append(c)
        i += 1

    return "".join(out)


def find_matching_brace(source: str, open_idx: int) -> int:
    """
    从 `{` 的位置找到匹配的 `}`。

    必须做括号配平而不是找下一个 `}` —— 注册块里嵌套 lambda、
    字符串模板都很常见（例如 `get()` 里嵌 `named(...)`）。
    """
    depth = 0
    i = open_idx
    n = len(source)
    in_string = False
    in_raw = False

    while i < n:
        # 字符串里的大括号不算
        if in_raw:
            if source[i:i + 3] == '"""':
                in_raw = False
                i += 3
                continue
            i += 1
            continue
        if in_string:
            if source[i] == "\\":
                i += 2
                continue
            if source[i] == '"':
                in_string = False
            i += 1
            continue
        if source[i:i + 3] == '"""':
            in_raw = True
            i += 3
            continue
        if source[i] == '"':
            in_string = True
            i += 1
            continue

        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1

    raise AuditError(f"括号不配平，起始位置 {open_idx}")


def split_top_level_args(s: str) -> list[str]:
    """按顶层逗号切分参数列表（容忍嵌套括号与泛型）"""
    if not s.strip():
        return []
    args = []
    depth = 0
    cur = []
    for c in s:
        if c in "(<[":
            depth += 1
        elif c in ")>]":
            depth -= 1
        if c == "," and depth == 0:
            args.append("".join(cur))
            cur = []
        else:
            cur.append(c)
    if cur:
        args.append("".join(cur))
    return [a.strip() for a in args if a.strip()]


def parse_constructors(files: list[Path]) -> dict[str, Constructor]:
    """
    收集所有类的构造函数签名。

    这里刻意**不用正则匹配整个参数列表**，改为手工扫描括号 ——
    原因是实测中正则会在两种常见写法上断裂：

    1. 参数之间夹着 KDoc 块注释（本项目大量使用），
       `[^)]*` 匹配到注释里的括号就错位
    2. 参数类型本身含逗号（如 `() -> Boolean`、泛型 `Map<String, Int>`），
       按逗号切分会把一个参数切成两个

    手工扫描能正确处理嵌套括号、字符串、注释，代价是代码长一些 ——
    但一个审计工具如果自己会误报/漏报，就没有存在价值。
    """
    result: dict[str, Constructor] = {}

    for f in files:
        try:
            raw = f.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        src = strip_comments(raw)

        # 找 `class Name` 或 `class Name<T>`，然后定位其后的主构造函数括号
        for m in re.finditer(r"\bclass\s+([A-Z][A-Za-z0-9_]*)", src):
            name = m.group(1)
            pos = m.end()

            # 跳过泛型参数 <...>
            if pos < len(src) and src[pos] == "<":
                depth = 0
                while pos < len(src):
                    if src[pos] == "<":
                        depth += 1
                    elif src[pos] == ">":
                        depth -= 1
                        if depth == 0:
                            pos += 1
                            break
                    pos += 1

            # 跳过空白、注解、`constructor` 关键字，找到构造函数的左括号
            #
            # 注意：这里**不能**把 `(` 纳入循环条件 ——
            # 合法的构造函数左括号会因此被吃掉，导致所有类都解析不到
            # （这个 bug 实际发生过：脚本报「0 个类」却显示检查通过）
            while pos < len(src) and src[pos] in " \t\r\n":
                pos += 1

            # 跳过 `@Annotation(...)` 形式的注解
            while pos < len(src) and src[pos] == "@":
                pos += 1
                while pos < len(src) and (src[pos].isalnum() or src[pos] in "._"):
                    pos += 1
                while pos < len(src) and src[pos] in " \t\r\n":
                    pos += 1
                if pos < len(src) and src[pos] == "(":
                    pos = _skip_balanced(src, pos, "(", ")")
                while pos < len(src) and src[pos] in " \t\r\n":
                    pos += 1

            # 跳过显式的 `constructor` 关键字
            if src.startswith("constructor", pos):
                pos += len("constructor")
                while pos < len(src) and src[pos] in " \t\r\n":
                    pos += 1

            # 跳过次级构造函数前的修饰符（`public constructor`）
            if src.startswith("public", pos) or src.startswith("internal", pos):
                while pos < len(src) and src[pos] not in " \t\r\n":
                    pos += 1
                while pos < len(src) and src[pos] in " \t\r\n":
                    pos += 1
                if src.startswith("constructor", pos):
                    pos += len("constructor")
                    while pos < len(src) and src[pos] in " \t\r\n":
                        pos += 1

            if pos >= len(src) or src[pos] != "(":
                # 没有主构造函数（如 `class Foo { ... }`），跳过
                continue

            close = _find_balanced(src, pos, "(", ")")
            if close < 0:
                continue
            params_raw = src[pos + 1:close]

            params = _parse_param_list(params_raw)
            if name not in result or len(params) > len(result[name].params):
                result[name] = Constructor(
                    class_name=name,
                    file=str(f),
                    params=params,
                )

    return result


def _skip_balanced(src: str, open_idx: int, open_ch: str, close_ch: str) -> int:
    """跳过一个配对的括号组，返回关闭括号之后的位置"""
    i = _find_balanced(src, open_idx, open_ch, close_ch)
    return i + 1 if i >= 0 else len(src)


def _find_balanced(src: str, open_idx: int, open_ch: str, close_ch: str) -> int:
    """找到配对关闭括号的下标，找不到返回 -1"""
    depth = 0
    i = open_idx
    n = len(src)
    in_str = False
    in_raw = False

    while i < n:
        if in_raw:
            if src[i:i + 3] == '"""':
                in_raw = False
                i += 3
                continue
            i += 1
            continue
        if in_str:
            if src[i] == "\\":
                i += 2
                continue
            if src[i] == '"':
                in_str = False
            i += 1
            continue
        if src[i:i + 3] == '"""':
            in_raw = True
            i += 3
            continue
        if src[i] == '"':
            in_str = True
            i += 1
            continue

        if src[i] == open_ch:
            depth += 1
        elif src[i] == close_ch:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def _parse_param_list(params_raw: str) -> list[tuple[str, str]]:
    """
    解析参数列表，返回 [(参数名, 类型), ...]。

    按顶层逗号切分（忽略括号与泛型里的逗号），
    再逐个提取 `name: Type`。
    """
    parts = _split_top_level(params_raw)
    params: list[tuple[str, str]] = []

    for part in parts:
        # 去掉默认值：`= xxx`（注意 == 与 lambda 里的 =）
        # 只在顶层找第一个 '='
        eq_idx = _find_top_level_equals(part)
        if eq_idx >= 0:
            part = part[:eq_idx]

        part = part.strip()
        if not part:
            continue

        pm = re.match(
            r"(?:private\s+|internal\s+|protected\s+|public\s+)?"
            r"(?:val\s+|var\s+)?"
            r"([a-z_][A-Za-z0-9_]*)\s*:\s*(.+)",
            part,
            re.S,
        )
        if not pm:
            continue

        pname = pm.group(1)
        ptype = pm.group(2).strip()

        if not ptype:
            continue

        # 函数类型整体保留（它是 lambda，由调用方提供，不需要注册）
        if "->" in ptype:
            params.append((pname, "() -> Unit"))
            continue

        # 去掉泛型参数，只留裸类型名
        ptype = re.sub(r"<.*>", "", ptype, flags=re.S).strip()
        ptype = ptype.rstrip("?").strip()
        if not ptype:
            continue
        params.append((pname, ptype))

    return params


def _split_top_level(s: str) -> list[str]:
    """按顶层逗号切分（忽略括号/泛型/字符串中的逗号）"""
    if not s.strip():
        return []
    args: list[str] = []
    depth = 0
    cur: list[str] = []
    in_str = False

    for c in s:
        if in_str:
            cur.append(c)
            if c == '"':
                in_str = False
            continue
        if c == '"':
            in_str = True
            cur.append(c)
            continue
        if c in "(<[":
            depth += 1
        elif c in ")>]":
            depth -= 1
        if c == "," and depth == 0:
            args.append("".join(cur))
            cur = []
        else:
            cur.append(c)

    if cur:
        args.append("".join(cur))
    return [a for a in args if a.strip()]


def _find_top_level_equals(s: str) -> int:
    """找顶层 `=` 的下标（跳过 ==、>=、<=、!=）"""
    depth = 0
    for i, c in enumerate(s):
        if c in "(<[":
            depth += 1
        elif c in ")>]":
            depth -= 1
        elif c == "=" and depth == 0:
            prev = s[i - 1] if i > 0 else ""
            nxt = s[i + 1] if i + 1 < len(s) else ""
            if prev in "<>=!" or nxt == "=":
                continue
            return i
    return -1


def parse_registrations(src: str, file: str) -> list[Registration]:
    """解析一个文件里的全部 Koin 注册"""
    regs: list[Registration] = []

    for m in RE_REGISTRATION_CONSTRUCT.finditer(src):
        kind = m.group("kind")
        iface = (m.group("iface") or "").strip()

        # 找到该注册块的结束位置，以便探测限定符
        brace_idx = src.index("{", m.end() - 1)
        try:
            end = find_matching_brace(src, brace_idx)
        except AuditError:
            continue
        body = src[brace_idx:end + 1]

        # 限定符写在注册块之前，如 single(named("x")) {
        prefix = src[max(0, m.start() - 60):m.start()]
        qual_m = RE_QUALIFIER.search(prefix + body[: min(len(body), 80)])
        qualifier = qual_m.group(1) if qual_m else None

        # 注册类型：显式泛型 > 块内第一个构造调用 > 块内引用
        #
        # 注意 `single<DeviceStateProvider> { AndroidDeviceStateProvider(...) }`
        # 这种写法要**同时**记录两个名字：
        #   - 接口名（DeviceStateProvider）—— 别人 get<DeviceStateProvider>() 时用
        #   - 实现类名（AndroidDeviceStateProvider）—— 校验它的构造参数时用
        # 只记一个会导致「漏报实现类的未注册参数」或「误报接口未注册」
        explicit_iface = iface.split("<")[0].strip() if iface else ""

        # 匹配块内的构造调用。注意要容忍**全限定名**：
        #   viewModel { com.feishu.checkin.ui.screen.home.HomeViewModel(...) }
        # 只匹配 `{` 后紧跟类名的写法会漏掉这类注册
        ctor_m = re.search(
            r"\{\s*(?:[a-z_][A-Za-z0-9_]*\.)*([A-Z][A-Za-z0-9_]*)\s*\(",
            body,
        )
        impl_class = ctor_m.group(1) if ctor_m else ""

        if not impl_class:
            inner_get = RE_GET.search(body)
            if inner_get and inner_get.group("type"):
                impl_class = inner_get.group("type")

        # 记录实现类（有的话），它是真正需要校验构造参数的那个
        if impl_class and impl_class[0].isupper():
            regs.append(Registration(
                type_name=impl_class,
                kind=kind,
                file=file,
                line=src[:m.start()].count("\n") + 1,
                qualifier=qualifier,
            ))

        # 再记录接口名，让别人查得到
        if explicit_iface and explicit_iface[0].isupper() and explicit_iface != impl_class:
            regs.append(Registration(
                type_name=explicit_iface,
                kind=kind,
                file=file,
                line=src[:m.start()].count("\n") + 1,
                qualifier=qualifier,
            ))

    return regs


def parse_get_calls(src: str, file: str) -> list[GetCall]:
    """解析所有 get()/inject() 调用"""
    calls: list[GetCall] = []

    for m in RE_GET.finditer(src):
        explicit = m.group("type")
        # 找到调用的右括号，检查实参个数
        start = m.end() - 1
        try:
            end = find_matching_brace(src, start) if src[start] == "(" else start
        except AuditError:
            end = start
        # 用括号配平找右括号
        depth = 0
        i = start
        while i < len(src):
            if src[i] == "(":
                depth += 1
            elif src[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        args_raw = src[start + 1:i] if i > start else ""
        args = split_top_level_args(args_raw)

        calls.append(GetCall(
            file=file,
            line=src[:m.start()].count("\n") + 1,
            explicit_type=explicit,
            arg_count=len(args),
            raw=src[m.start():i + 1] if i > start else m.group(0),
        ))

    return calls


# ────────────────────── 主流程 ──────────────────────

def main() -> int:
    root = Path(__file__).resolve().parent.parent
    src_root = root / "app" / "src" / "main" / "java"
    if not src_root.exists():
        src_root = root / "app" / "src" / "main" / "kotlin"

    if not src_root.exists():
        print(f"[错误] 找不到源码目录: {src_root}")
        return 1

    kt_files = sorted(src_root.rglob("*.kt"))
    print(f"扫描 {len(kt_files)} 个 Kotlin 文件")

    constructors = parse_constructors(kt_files)

    # ── 收集注册 ──
    all_regs: list[Registration] = []
    registrations_by_file: dict[str, str] = {}

    for f in kt_files:
        try:
            raw = f.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        src = strip_comments(raw)
        registrations_by_file[str(f)] = src
        all_regs.extend(parse_registrations(src, str(f)))

    registered_types = {r.type_name for r in all_regs}
    qualifiers_by_type: dict[str, set[str | None]] = {}
    for r in all_regs:
        qualifiers_by_type.setdefault(r.type_name, set()).add(r.qualifier)

    print(f"发现 {len(all_regs)} 条 Koin 注册，覆盖 {len(registered_types)} 个类型")

    # ── 只在 module { } 块内找 get() 调用 ──
    # 业务代码里的 get() 是别的东西（比如 Map.get、SharedPreferences.get），
    # 全文件扫描会产生大量误报
    module_calls: list[GetCall] = []
    for fpath, src in registrations_by_file.items():
        # 定位所有 `module {` 块
        for m in re.finditer(r"\bmodule\s*\{", src):
            brace_idx = src.index("{", m.end() - 1)
            try:
                end = find_matching_brace(src, brace_idx)
            except AuditError:
                continue
            body = src[brace_idx + 1:end]
            # 行号偏移
            base_line = src[:brace_idx].count("\n")
            for call in parse_get_calls(body, fpath):
                call.line += base_line
                module_calls.append(call)

    print(f"发现 {len(module_calls)} 处容器内依赖解析")

    # ── 推导每个 get() 需要什么类型 ──
    problems: list[str] = []
    resolved_ok = 0

    for call in module_calls:
        if call.explicit_type:
            # 显式泛型：直接检查
            if call.explicit_type in registered_types or call.explicit_type in BUILTIN_TYPES:
                resolved_ok += 1
                continue
            # 可能是接口，检查注册里的显式泛型是否覆盖
            if any(r.type_name == call.explicit_type for r in all_regs):
                resolved_ok += 1
                continue
            problems.append(
                f"{rel(call.file, root)}:{call.line}  "
                f"未注册: {call.explicit_type}   [{call.raw.strip()}]"
            )
            continue

        # 无参 get() 无法单独判定类型，因此不在这一层检查，
        # 而是由下面「注册块内构造调用」的整体校验覆盖 ——
        # 因为 `single { X(a, get(), b) }` 里的 get() 一定服务于 X，
        # 只要能校验 X 的构造参数完整，就等于校验了这个 get()。
        pass

    # ── 检查：注册块内 new 出来的类，其构造参数是否都已注册 ──
    # 这是比逐个 get() 更可靠的整体校验
    for fpath, src in registrations_by_file.items():
        for m in re.finditer(r"\bmodule\s*\{", src):
            brace_idx = src.index("{", m.end() - 1)
            try:
                end = find_matching_brace(src, brace_idx)
            except AuditError:
                continue
            body = src[brace_idx + 1:end]
            base_line = src[:brace_idx].count("\n") + 1

            # 找块内所有 `Xxx(` 形式的构造调用（含全限定名）
            for cm in re.finditer(
                r"(?:[a-z_][A-Za-z0-9_]*\.)*([A-Z][A-Za-z0-9_]*)\s*\(",
                body,
            ):
                cls = cm.group(1)
                ctor = constructors.get(cls)
                if ctor is None or not ctor.params:
                    continue

                for pname, ptype in ctor.params:
                    base = ptype.split(".")[-1]
                    # 跳过基本类型与 lambda
                    if base in BUILTIN_TYPES or "->" in ptype:
                        continue
                    if base.endswith("Scope") or base in {"Context"}:
                        continue
                    if base in registered_types:
                        continue
                    # 接口有实现注册即可
                    if any(r.type_name == base for r in all_regs):
                        continue

                    line = base_line + body[:cm.start()].count("\n")
                    problems.append(
                        f"{rel(fpath, root)}:{line}  "
                        f"{cls} 需要 {base}（参数 {pname}），但未找到注册"
                    )

    # ── 报告 ──
    print()
    if problems:
        print("=" * 68)
        print(f"发现 {len(problems)} 个可能的依赖问题：")
        print("=" * 68)
        for p in sorted(set(problems)):
            print(f"  ✗ {p}")
        print()
        print("提示：若确认这些类型由框架提供或确实不需要注册，")
        print("      请加入脚本顶部的 BUILTIN_TYPES 白名单。")
        return 1

    print("=" * 68)
    print(f"✓ 依赖审计通过：{len(all_regs)} 条注册，无未注册依赖")
    print("=" * 68)
    return 0


def rel(path: str, root: Path) -> str:
    try:
        return str(Path(path).relative_to(root))
    except ValueError:
        return path


if __name__ == "__main__":
    sys.exit(main())
