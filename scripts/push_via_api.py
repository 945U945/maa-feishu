#!/usr/bin/env python3
"""
通过 GitHub Git Data API 批量提交本地变更。

为什么需要这个脚本：
    Git for Windows 的 schannel 后端在部分代理环境下会与 git push 不兼容
    （curl 能通但 git push 固定超时）。此时可改用 HTTP API 完成提交，
    功能等价于 push，且能正确处理文件新增 / 修改 / 删除。

用法：
    GITHUB_TOKEN=xxx python push_via_api.py [--repo owner/name] [--branch main]
"""

import argparse
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

PROXY = os.environ.get("HTTPS_PROXY_OVERRIDE", "http://127.0.0.1:7897")


def api(method, url, token, data=None, raw=False):
    """调用 GitHub API。raw=True 时返回字节，否则返回解析后的 JSON。"""
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", f"token {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "push-via-api")
    if body:
        req.add_header("Content-Type", "application/json")

    # 走本地代理，避免直连不通
    handler = urllib.request.ProxyHandler({"http": PROXY, "https": PROXY})
    opener = urllib.request.build_opener(handler)
    try:
        with opener.open(req, timeout=90) as resp:
            payload = resp.read()
            return payload if raw else json.loads(payload)
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        print(f"  ✗ HTTP {e.code} {method} {url}\n    {detail[:400]}", file=sys.stderr)
        raise


def git(*args):
    return subprocess.run(
        ["git", *args], capture_output=True, text=True, check=True
    ).stdout.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default="945U945/maa-feishu")
    ap.add_argument("--branch", default="main")
    args = ap.parse_args()

    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        sys.exit("错误：请设置环境变量 GITHUB_TOKEN")

    base = f"https://api.github.com/repos/{args.repo}"

    # ── 1. 读取本地变更 ──
    # 以 HEAD^ 为基准取 diff，避免依赖远端状态
    changed = {
        line[3:].strip(): line[:2].strip()
        for line in git("status", "--porcelain").splitlines()
        if line.strip()
    }
    if not changed:
        print("没有需要提交的变更。")
        return

    print(f"待提交 {len(changed)} 个文件：")
    for path, status in sorted(changed.items()):
        print(f"  [{status}] {path}")

    # ── 2. 取当前分支 HEAD 与基线树 ──
    ref = api("GET", f"{base}/git/ref/heads/{args.branch}", token)
    parent_sha = ref["object"]["sha"]
    parent_commit = api("GET", f"{base}/git/commits/{parent_sha}", token)
    base_tree = parent_commit["tree"]["sha"]
    print(f"\n远端 HEAD: {parent_sha[:8]}  基线树: {base_tree[:8]}")

    # ── 3. 为每个变更文件建 blob ──
    tree_items = []
    for path, status in sorted(changed.items()):
        if status == "D":
            # 删除：树中该项 sha 置 null 即表示移除
            tree_items.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
            print(f"  · 删除 {path}")
            continue

        with open(path, "rb") as f:
            content = f.read()
        blob = api(
            "POST",
            f"{base}/git/blobs",
            token,
            {"content": base64.b64encode(content).decode(), "encoding": "base64"},
        )
        tree_items.append(
            {"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]}
        )
        print(f"  · 上传 {path} ({len(content)} bytes)")

    # ── 4. 建树 + 提交 ──
    tree = api("POST", f"{base}/git/trees", token, {"base_tree": base_tree, "tree": tree_items})

    message = os.environ.get(
        "COMMIT_MESSAGE",
        "fix: 修复首次云端构建暴露的编译错误",
    )
    commit = api(
        "POST",
        f"{base}/git/commits",
        token,
        {"message": message, "tree": tree["sha"], "parents": [parent_sha]},
    )
    print(f"\n新提交: {commit['sha'][:8]}")

    # ── 5. 更新分支引用 ──
    api("PATCH", f"{base}/git/refs/heads/{args.branch}", token, {"sha": commit["sha"]})
    print(f"✅ 已更新 {args.branch} -> {commit['sha'][:8]}")
    print(f"   https://github.com/{args.repo}/commit/{commit['sha']}")


if __name__ == "__main__":
    main()
