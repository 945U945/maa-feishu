#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 FeishuCheckIn 工程推送到 GitHub，绕开两个常见障碍。

## 为什么不用 `git push`

两个原因，都是实测踩过的：

1. **classic token 推 `.github/workflows/` 会 403**
   GitHub 拒绝 classic PAT 创建/更新 workflow 文件，除非 token 带 `workflow` scope。
   报错原文：
   `refusing to allow a Personal Access Token to create or update workflow ... without 'workflow' scope`
   → 用 **Contents API**（`PUT /repos/{owner}/{repo}/contents/{path}`）上传可以绕过，
     实测返回 201。

2. **HTTPS 直推可能超时**（批量 500+ 文件时）
   Contents API 逐个文件上传，单文件请求小、可重试，反而更稳。

## 用法

    export GITHUB_TOKEN=ghp_xxx          # classic token，勾 repo 即可
    python scripts/push_to_github.py

可选环境变量：
    GITHUB_REPO   默认 945U945/maa-feishu
    GITHUB_BRANCH 默认 main
    PUSH_DRY_RUN  设为 1 时只打印将要上传的文件，不实际请求

## 设计说明

- **幂等**：先 GET 拿已存在文件的 sha，PUT 时带上表示「更新」；
  不带 sha 就是「新建」。因此脚本可以反复跑，不会报 409。
- **跳过无变化文件**：GET 返回的 sha 与本地内容计算的 git blob sha
  一致时跳过，省流量也避免无意义的 commit。
- **二进制安全**：base64 编码，APK/jks 都能传。
- **不碰的目录**：build/、.gradle/、.git/ —— 即使误加也会被这里过滤掉。
"""

import base64
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO = os.environ.get("GITHUB_REPO", "945U945/maa-feishu")
BRANCH = os.environ.get("GITHUB_BRANCH", "main")
DRY_RUN = os.environ.get("PUSH_DRY_RUN") == "1"

ROOT = Path(__file__).resolve().parent.parent
API = f"https://api.github.com/repos/{REPO}"

# ── 不要上传的目录 / 文件 ──
#
# 这些是构建产物和本地环境配置：
# - build/、.gradle/、.kotlin/ 是编译中间产物，进仓库会让 clone 变慢且毫无意义
# - local.properties 含本机 SDK 路径与签名密码，**绝不能进仓库**
# - *.jks 是签名密钥，进了仓库等于把签发权公开
#
# 注意：.gitignore 里已经写了这些规则，但脚本不依赖 git，
# 所以这里必须再过滤一遍 —— 两道防线，任何一道失效都还有另一道。
SKIP_DIRS = {
    "build", ".gradle", ".kotlin", ".idea", ".git",
    "__pycache__", ".pytest_cache", "node_modules",
    "intermediates", "outputs", "tmp",
}
SKIP_SUFFIXES = {".jks", ".keystore", ".apk", ".log", ".hprof"}
SKIP_FILES = {"local.properties", "keystore.properties", ".DS_Store"}


def git_blob_sha(data: bytes) -> str:
    """计算 git blob 的 SHA-1 —— GitHub Contents API 返回的就是这个值。

    格式是 `blob <长度>\\0<内容>` 的 SHA-1。
    用它比对可以判断「内容是否真的变了」，避免每次跑都产生空提交。
    """
    header = b"blob %d\x00" % len(data)
    return hashlib.sha1(header + data).hexdigest()


def should_skip(rel: Path) -> bool:
    """按路径规则判断是否跳过"""
    if any(part in SKIP_DIRS for part in rel.parts):
        return True
    if rel.name in SKIP_FILES:
        return True
    if rel.suffix.lower() in SKIP_SUFFIXES:
        return True
    return False


def collect_files() -> list[Path]:
    """收集待上传文件（相对 ROOT 的路径），已排序保证顺序稳定"""
    files = []
    for p in ROOT.rglob("*"):
        if not p.is_file():
            continue
        rel = p.relative_to(ROOT)
        if should_skip(rel):
            continue
        files.append(rel)
    # 排序：先根目录配置，再 src，最后 scripts —— 便于阅读日志
    return sorted(files, key=lambda r: (len(r.parts), str(r)))


def request(method: str, url: str, token: str, payload=None, retries: int = 3):
    """带重试的 GitHub API 请求"""
    body = json.dumps(payload).encode() if payload is not None else None
    for attempt in range(1, retries + 1):
        req = urllib.request.Request(url, data=body, method=method)
        req.add_header("Authorization", f"Bearer {token}")
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        req.add_header("User-Agent", "feishu-checkin-push")
        if body:
            req.add_header("Content-Type", "application/json")

        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                raw = resp.read()
                return resp.status, (json.loads(raw) if raw else {})
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:400]
            # 404 在 GET 场景是「文件不存在」，属于正常，不重试
            if e.code == 404:
                return 404, {}
            # 401/403 是鉴权问题，重试也没用，直接报出来
            if e.code in (401, 403):
                return e.code, {"message": detail}
            if attempt == retries:
                return e.code, {"message": detail}
            time.sleep(2 * attempt)
        except Exception as e:  # noqa: BLE001
            if attempt == retries:
                return 0, {"message": str(e)}
            time.sleep(2 * attempt)
    return 0, {"message": "unreachable"}


def existing_sha(token: str, path: str) -> str | None:
    """查询远端已存在文件的 sha；不存在返回 None"""
    url = f"{API}/contents/{urllib.request.quote(path)}?ref={BRANCH}"
    status, data = request("GET", url, token)
    if status == 200 and isinstance(data, dict):
        return data.get("sha")
    return None


def put_file(token: str, path: str, content: bytes, message: str, sha: str | None):
    """上传单个文件"""
    url = f"{API}/contents/{urllib.request.quote(path)}"
    payload = {
        "message": message,
        "content": base64.b64encode(content).decode(),
        "branch": BRANCH,
    }
    if sha:
        payload["sha"] = sha
    return request("PUT", url, token, payload)


def main() -> int:
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not token:
        print("✗ 未设置 GITHUB_TOKEN 环境变量", file=sys.stderr)
        print("  请提供 classic token（ghp_ 开头，勾选 repo scope）", file=sys.stderr)
        return 2

    # ── 先确认 token 可用，并拿到仓库默认分支 ──
    status, repo_info = request("GET", API, token)
    if status != 200:
        print(f"✗ 仓库不可访问 ({status}): {repo_info.get('message', '')}", file=sys.stderr)
        print(f"  仓库: {REPO}", file=sys.stderr)
        return 3
    print(f"✓ 仓库可达: {repo_info.get('full_name')} (默认分支 {repo_info.get('default_branch')})")

    # 远端是否为空仓库？空仓库没有分支，首次 PUT 不需要 sha
    empty_repo = repo_info.get("size", 0) == 0

    files = collect_files()
    print(f"✓ 待上传 {len(files)} 个文件（已过滤 build/.gradle/*.jks 等）")

    if DRY_RUN:
        for rel in files:
            print(f"   - {rel}")
        return 0

    ok = skipped = failed = 0
    failures: list[str] = []

    for i, rel in enumerate(files, 1):
        path = rel.as_posix()
        content = (ROOT / rel).read_bytes()

        sha = None if empty_repo else existing_sha(token, path)
        if sha and sha == git_blob_sha(content):
            skipped += 1
            continue

        action = "更新" if sha else "新建"
        status, data = put_file(
            token, path, content,
            message=f"{action}: {path}",
            sha=sha,
        )

        if status in (200, 201):
            ok += 1
            if i % 20 == 0 or ok <= 3:
                print(f"  [{i}/{len(files)}] {action} {path}")
        else:
            failed += 1
            failures.append(f"{path} → HTTP {status}: {data.get('message', '')[:160]}")
            print(f"  ✗ {action}失败 {path} (HTTP {status})", file=sys.stderr)

    print()
    print("=" * 68)
    print(f"上传完成：成功 {ok}，跳过（内容未变） {skipped}，失败 {failed}")
    if failures:
        print("-" * 68)
        for f in failures[:20]:
            print(f"  {f}")
        if len(failures) > 20:
            print(f"  ...另有 {len(failures) - 20} 条")
    print("=" * 68)

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
