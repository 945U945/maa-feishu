#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
查看 GitHub Actions 工作流运行状态与失败步骤。

## 为什么需要这个脚本

CI 失败时，`gh` CLI 不一定装了，网页看又不方便自动化。
直接调 REST API 最省事，但有两个坑：

1. **日志要跟随重定向**：`/actions/jobs/{id}/logs` 返回 302，
   必须带 `-L`（urllib 默认会跟随，但 curl 不会）
2. **失败步骤定位**：`/actions/runs/{id}/jobs` 返回的 steps 里有
   `conclusion` 字段，能直接看出是哪个 step 挂了 ——
   比翻几千行日志快得多

## 用法

    export GITHUB_TOKEN=ghp_xxx
    python scripts/ci_status.py                 # 看最近 8 次运行
    python scripts/ci_status.py --run 12345     # 看某次运行的步骤详情
    python scripts/ci_status.py --log 12345     # 拉某次运行的完整日志
"""

import json
import os
import sys
import urllib.error
import urllib.request

REPO = os.environ.get("GITHUB_REPO", "945U945/maa-feishu")
BASE = f"https://api.github.com/repos/{REPO}"


def api(path: str, token: str):
    req = urllib.request.Request(f"{BASE}{path}")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "feishu-checkin-ci-status")
    try:
        with urllib.request.urlopen(req, timeout=45) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        return e.code, {"message": e.read().decode(errors="replace")[:300]}


def api_raw(path: str, token: str) -> bytes:
    """下载二进制/文本（用于日志）"""
    req = urllib.request.Request(f"{BASE}{path}")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "feishu-checkin-ci-status")
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        return f"HTTP {e.code}: {e.read().decode(errors='replace')[:300]}".encode()


def show_runs(token: str, limit: int = 8) -> None:
    status, data = api(f"/actions/runs?per_page={limit}", token)
    if status != 200:
        print(f"✗ 获取运行列表失败 ({status}): {data.get('message')}", file=sys.stderr)
        return
    print(f"{'工作流':<18} {'#':<5} {'状态':<12} {'结果':<11} {'提交':<9} 触发")
    print("-" * 78)
    for r in data.get("workflow_runs", []):
        name = r["name"][:17]
        concl = r.get("conclusion") or "-"
        # 用符号让成功/失败一眼可辨
        symbol = {"success": "✓", "failure": "✗", "cancelled": "⊘"}.get(concl, "·")
        print(
            f"{name:<18} {r['run_number']:<5} {r['status']:<12} "
            f"{symbol} {concl:<8} {r['head_sha'][:7]:<9} {r['event']}"
        )
    print()
    print("查看某次详情:  python scripts/ci_status.py --run <run_id>")
    print("拉取日志:      python scripts/ci_status.py --log <run_id>")


def show_run_detail(token: str, run_id: str) -> None:
    status, data = api(f"/actions/runs/{run_id}/jobs", token)
    if status != 200:
        print(f"✗ 获取 job 失败 ({status}): {data.get('message')}", file=sys.stderr)
        return
    for j in data.get("jobs", []):
        print(f"job: {j['name']}")
        print(f"     status={j['status']}  conclusion={j['conclusion']}  id={j['id']}")
        for s in j.get("steps", []):
            c = s.get("conclusion")
            mark = {"success": "✓", "failure": "✗", "skipped": "⊘"}.get(c, "·")
            print(f"     [{mark}] {s['name']}")
        print()


def show_log(token: str, run_id: str) -> None:
    """拉取该 run 下所有 job 的日志，只打印含错误关键字的行"""
    status, data = api(f"/actions/runs/{run_id}/jobs", token)
    if status != 200:
        print(f"✗ 获取 job 失败 ({status})", file=sys.stderr)
        return
    keys = ("error", "failed", "not found", "denied", "unable",
            "cannot", "exception", "e: ", "::error")
    for j in data.get("jobs", []):
        if j.get("conclusion") != "failure":
            continue
        print("=" * 78)
        print(f"job: {j['name']}  (id={j['id']})")
        print("=" * 78)
        blob = api_raw(f"/actions/jobs/{j['id']}/logs", token).decode(errors="replace")
        for line in blob.splitlines():
            low = line.lower()
            if any(k in low for k in keys):
                # 去掉时间戳前缀，让内容更醒目
                print("  " + line[-260:])


def main() -> int:
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not token:
        print("✗ 未设置 GITHUB_TOKEN", file=sys.stderr)
        return 2

    args = sys.argv[1:]
    if "--run" in args:
        show_run_detail(token, args[args.index("--run") + 1])
    elif "--log" in args:
        show_log(token, args[args.index("--log") + 1])
    else:
        show_runs(token)
    return 0


if __name__ == "__main__":
    sys.exit(main())
