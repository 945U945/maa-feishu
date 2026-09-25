#!/usr/bin/env python3
"""
通过 GitHub Git Data API 推送本地 commit（无需 git push）。

为什么需要这个脚本：
    Git for Windows 的 schannel 后端在部分代理环境下与 git push 不兼容
    （curl 能通但 git push 固定超时）。本脚本改用 HTTP API 完成等价操作，
    能正确处理文件的新增 / 修改 / 删除。

用法：
    GITHUB_TOKEN=xxx python push_commit_via_api.py [--repo owner/name] [--branch main]

行为：
    找出本地领先远端的 commit，把每个 commit 的完整文件快照写入远端，
    逐条生成对应的远端提交，最后更新分支引用。
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


def api(method, url, token, data=None):
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", f"token {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "push-commit-via-api")
    if body:
        req.add_header("Content-Type", "application/json")

    handler = urllib.request.ProxyHandler({"http": PROXY, "https": PROXY})
    opener = urllib.request.build_opener(handler)
    try:
        with opener.open(req, timeout=120) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        print(f"  ✗ HTTP {e.code} {method} {url}\n    {detail[:500]}", file=sys.stderr)
        raise


def git(*args, check=True):
    return subprocess.run(
        ["git", *args], capture_output=True, text=True, check=check
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

    # ── 1. 远端当前 HEAD ──
    ref = api("GET", f"{base}/git/ref/heads/{args.branch}", token)
    remote_head = ref["object"]["sha"]
    print(f"远端 {args.branch} HEAD: {remote_head[:8]}")

    # ── 2. 找出领先的本地 commit（从旧到新）──
    # 先拿到远端 head 的对象，用 --not 排除远端可达的提交
    git("fetch", check=False)  # 忽略失败：可能无网络，提交对象本地已有
    log = git(
        "log",
        "--oneline",
        "--reverse",
        "--no-merges",
        f"{remote_head}..HEAD",
        check=False,
    )
    if not log:
        print("本地没有领先远端的提交。")
        return

    commits = [line.split(None, 1) for line in log.splitlines() if line.strip()]
    print(f"待推送 {len(commits)} 个提交：")
    for sha, subject in commits:
        print(f"  {sha[:8]} {subject}")

    # ── 3. 逐个提交同步 ──
    for sha, subject in commits:
        # 该 commit 的完整文件树（含该提交时的所有文件）
        files = git("ls-tree", "-r", "--name-only", sha).splitlines()

        # 与远端最新状态做差异：只上传内容有变化的文件
        # 用 git diff-tree 拿到本 commit 相对其父的变更集
        changed_raw = git(
            "diff-tree",
            "--no-commit-id",
            "--name-status",
            "-r",
            sha,
        ).splitlines()

        # 首个提交可能无父（--root），diff-tree 会列出全部文件
        if not changed_raw:
            changed_raw = [f"A\t{p}" for p in files]

        tree_items = []
        for line in changed_raw:
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            status, path = parts[0], parts[-1]

            if status.startswith("D"):
                tree_items.append(
                    {"path": path, "mode": "100644", "type": "blob", "sha": None}
                )
                print(f"    · 删除 {path}")
                continue

            try:
                content = subprocess.run(
                    ["git", "show", f"{sha}:{path}"],
                    capture_output=True,
                    check=True,
                ).stdout
            except subprocess.CalledProcessError:
                print(f"    ! 跳过无法读取的 {path}", file=sys.stderr)
                continue

            blob = api(
                "POST",
                f"{base}/git/blobs",
                token,
                {"content": base64.b64encode(content).decode(), "encoding": "base64"},
            )
            tree_items.append(
                {"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]}
            )
            print(f"    · 上传 {path} ({len(content)}B)")

        if not tree_items:
            print("    (无文件变更，跳过)")
            continue

        # 用远端当前树作为 base_tree，只叠加本次变更集，
        # 否则新建的树将只包含 tree_items 里的文件，其余文件会全部丢失。
        remote_commit = api("GET", f"{base}/git/commits/{remote_head}", token)
        base_tree = remote_commit["tree"]["sha"]

        tree = api(
            "POST",
            f"{base}/git/trees",
            token,
            {"base_tree": base_tree, "tree": tree_items},
        )

        # 用本地 commit 的完整 message
        message = git("log", "-1", "--format=%B", sha)

        new_commit = api(
            "POST",
            f"{base}/git/commits",
            token,
            {
                "message": message,
                "tree": tree["sha"],
                "parents": [remote_head] if remote_head else [],
            },
        )
        remote_head = new_commit["sha"]
        print(f"  ✓ {sha[:8]} -> 远端 {remote_head[:8]}")

    # ── 4. 更新分支引用 ──
    api("PATCH", f"{base}/git/refs/heads/{args.branch}", token, {"sha": remote_head})
    print(f"\n✅ {args.branch} 已更新到 {remote_head[:8]}")
    print(f"   https://github.com/{args.repo}/commit/{remote_head}")


if __name__ == "__main__":
    main()
