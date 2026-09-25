#!/usr/bin/env python3
"""
通过 GitHub Git Data API 同步本地 HEAD 到远端（无需 git push）。

为什么需要这个脚本：
    Git for Windows 的 schannel 后端在部分代理环境下与 git push 不兼容
    （curl 能通但 git push 固定超时）。本脚本改用 HTTP API 完成等价操作。

设计要点：
    不走「本地领先远端的 commit 范围」——远端经过 API 提交后 sha 与本地不同，
    git 无法计算范围。改为直接比对「本地 HEAD 的完整文件树」与
    「远端当前树的文件内容」，只上传内容有差异的文件。

用法：
    GITHUB_TOKEN=xxx python push_commit_via_api.py [--repo owner/name] [--branch main]
"""

import argparse
import base64
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

PROXY = os.environ.get("HTTPS_PROXY_OVERRIDE", "http://127.0.0.1:7897")


def api(method, url, token, data=None, retries=4):
    """调用 GitHub API，带指数退避重试。

    经本地代理时偶发 RemoteDisconnected（连接被中途掐断），
    重试即可恢复，无需人工干预。
    """
    body = json.dumps(data).encode() if data is not None else None
    last_err = None
    for attempt in range(retries):
        req = urllib.request.Request(url, data=body, method=method)
        req.add_header("Authorization", f"token {token}")
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("User-Agent", "push-commit-via-api")
        if body:
            req.add_header("Content-Type", "application/json")

        handler = urllib.request.ProxyHandler({"http": PROXY, "https": PROXY})
        opener = urllib.request.build_opener(handler)
        try:
            with opener.open(req, timeout=180) as resp:
                return json.loads(resp.read())
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")
            # 4xx 是请求本身的问题，重试无意义
            if 400 <= e.code < 500:
                print(f"  ✗ HTTP {e.code} {method} {url}\n    {detail[:500]}", file=sys.stderr)
                raise
            last_err = e
            print(f"  ! HTTP {e.code}，第 {attempt + 1} 次重试…", file=sys.stderr)
        except Exception as e:  # 网络层异常（连接断开、超时等）
            last_err = e
            print(f"  ! {type(e).__name__}，第 {attempt + 1} 次重试…", file=sys.stderr)

        time.sleep(2 ** attempt)

    raise RuntimeError(f"API 调用连续 {retries} 次失败：{method} {url}") from last_err


def git(*args, check=True, binary=False):
    r = subprocess.run(["git", *args], capture_output=True, check=check)
    return r.stdout if binary else r.stdout.decode().strip()


def git_blob_sha(content: bytes) -> str:
    """计算 git blob sha1：'blob <len>\\0' + content。用于与远端 sha 比对。"""
    header = f"blob {len(content)}\0".encode()
    return hashlib.sha1(header + content).hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default="945U945/maa-feishu")
    ap.add_argument("--branch", default="main")
    ap.add_argument("--message", default=None, help="自定义提交信息")
    args = ap.parse_args()

    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        sys.exit("错误：请设置环境变量 GITHUB_TOKEN")

    base = f"https://api.github.com/repos/{args.repo}"

    # ── 1. 远端当前状态 ──
    ref = api("GET", f"{base}/git/ref/heads/{args.branch}", token)
    remote_head = ref["object"]["sha"]
    remote_commit = api("GET", f"{base}/git/commits/{remote_head}", token)
    remote_tree = api(
        "GET", f"{base}/git/trees/{remote_commit['tree']['sha']}?recursive=1", token
    )
    # 远端现有文件 path -> blob sha
    remote_files = {
        item["path"]: item["sha"]
        for item in remote_tree.get("tree", [])
        if item["type"] == "blob"
    }
    print(f"远端 {args.branch} HEAD: {remote_head[:8]}  文件数: {len(remote_files)}")

    # ── 2. 本地 HEAD 的完整文件列表 ──
    local_paths = git("ls-tree", "-r", "--name-only", "HEAD").splitlines()
    print(f"本地 HEAD 文件数: {len(local_paths)}")

    # ── 3. 逐文件比对，收集变更 ──
    tree_items = []
    added, modified, deleted = [], [], []

    for path in local_paths:
        content = git("show", f"HEAD:{path}", binary=True)
        local_sha = git_blob_sha(content)
        if path not in remote_files:
            added.append(path)
        elif remote_files[path] != local_sha:
            modified.append(path)
        else:
            # 远端已有相同内容，直接复用其 sha，无需上传
            continue

        # 内容有差异：上传 blob（GitHub 会按内容去重，
        # 即使与本地算出的 sha 相同也需先创建，否则建树报 not a valid blob）
        blob = api(
            "POST",
            f"{base}/git/blobs",
            token,
            {"content": base64.b64encode(content).decode(), "encoding": "base64"},
        )
        tree_items.append(
            {"path": path, "mode": "100644", "type": "blob", "sha": blob["sha"]}
        )

    for path in remote_files:
        if path not in local_paths:
            deleted.append(path)
            tree_items.append(
                {"path": path, "mode": "100644", "type": "blob", "sha": None}
            )

    print(f"\n新增 {len(added)} / 修改 {len(modified)} / 删除 {len(deleted)}")
    for p in added:
        print(f"  + {p}")
    for p in modified:
        print(f"  M {p}")
    for p in deleted:
        print(f"  - {p}")

    if not tree_items:
        print("\n✅ 已是最新，无需提交。")
        return

    # ── 4. 建树 + 提交 + 更新引用 ──
    tree = api(
        "POST",
        f"{base}/git/trees",
        token,
        {"base_tree": remote_commit["tree"]["sha"], "tree": tree_items},
    )
    print(f"\n新树: {tree['sha'][:8]}")

    message = args.message or git("log", "-1", "--format=%B", "HEAD")
    commit = api(
        "POST",
        f"{base}/git/commits",
        token,
        {"message": message, "tree": tree["sha"], "parents": [remote_head]},
    )
    api("PATCH", f"{base}/git/refs/heads/{args.branch}", token, {"sha": commit["sha"]})

    print(f"✅ {args.branch} 已更新 -> {commit['sha'][:8]}")
    print(f"   https://github.com/{args.repo}/commit/{commit['sha']}")


if __name__ == "__main__":
    main()
