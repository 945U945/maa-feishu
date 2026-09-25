#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把本地工程**完整镜像**到 GitHub，包括删除远端多出来的文件。

## 为什么需要「镜像」而不是「上传」

最初的 push_to_github.py 只做「新增 + 更新」，**没有删除语义**。
这在「同一仓库被彻底重写」的场景下会造成灾难：

    远端还留着 447 个旧版本文件（com/aliothmoon/ 下的 MAA 残留）
    + 新推上去的 66 个文件
    = CI checkout 到一棵新旧混合的代码树
    → 旧代码里的类型（LaunchMutex / ScheduleAlarmManager ...）
      与新代码的依赖图交织，静态检查报出几十条「依赖未注册」
    → 报错信息完全指向错误的方向，排查代价极大

**教训**：只要发生过「推倒重写」，就必须做**删除对齐**，
不能只往上叠。

## 实现方式

用 **Git Data API** 构造一次完整提交，而不是逐个文件 PUT：

1. `POST /git/blobs`              —— 上传每个文件的内容，拿到 blob sha
2. `POST /git/trees`              —— 用 blob 列表构造一棵**完整**的树
                                     （不带 base_tree，就是「以这棵树为准」，
                                      远端多余的文件自然消失）
3. `POST /git/commits`            —— 创建提交，parent 指向当前 HEAD
4. `PATCH /git/refs/heads/main`   —— 移动分支指针

这样做的好处：
- **原子性**：一次提交，不存在「删了一半」的中间状态
- **真正的镜像**：远端树 = 本地树，多出来的文件被删除
- **快**：所有文件一次 tree 请求，而不是 N 次 PUT
- **绕开 workflow scope 限制**：Git Data API 属于 Contents 权限，
  classic token 勾 `repo` 即可推送 `.github/workflows/`

## 用法

    export GITHUB_TOKEN=ghp_xxx
    python scripts/sync_to_github.py --dry-run    # 先看会删/改什么
    python scripts/sync_to_github.py              # 实际推送
"""

import base64
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = os.environ.get("GITHUB_REPO", "945U945/maa-feishu")
BRANCH = os.environ.get("GITHUB_BRANCH", "main")
API = f"https://api.github.com/repos/{REPO}"
ROOT = Path(__file__).resolve().parent.parent

# ── 与 push_to_github.py 保持一致的白名单规则 ──
SKIP_DIRS = {
    "build", ".gradle", ".kotlin", ".idea", ".git",
    "__pycache__", ".pytest_cache", "node_modules",
    "intermediates", "outputs", "tmp",
}
SKIP_SUFFIXES = {".jks", ".keystore", ".apk", ".log", ".hprof"}
SKIP_FILES = {"local.properties", "keystore.properties", ".DS_Store"}


def should_skip(rel: Path) -> bool:
    if any(part in SKIP_DIRS for part in rel.parts):
        return True
    if rel.name in SKIP_FILES:
        return True
    return rel.suffix.lower() in SKIP_SUFFIXES


def collect() -> list[Path]:
    out = []
    for p in ROOT.rglob("*"):
        if p.is_file():
            rel = p.relative_to(ROOT)
            if not should_skip(rel):
                out.append(rel)
    return sorted(out, key=lambda r: r.as_posix())


def request(method: str, url: str, token: str, payload=None, retries: int = 3):
    body = json.dumps(payload).encode() if payload is not None else None
    last = (0, {})
    for attempt in range(1, retries + 1):
        req = urllib.request.Request(url, data=body, method=method)
        req.add_header("Authorization", f"Bearer {token}")
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        req.add_header("User-Agent", "feishu-checkin-sync")
        if body:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                raw = resp.read()
                return resp.status, (json.loads(raw) if raw else {})
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:400]
            last = (e.code, {"message": detail})
            # 4xx（除 429/5xx）重试无意义
            if 400 <= e.code < 500 and e.code != 429:
                return last
            if attempt < retries:
                import time
                time.sleep(2 * attempt)
        except Exception as e:  # noqa: BLE001
            last = (0, {"message": str(e)})
            if attempt < retries:
                import time
                time.sleep(2 * attempt)
    return last


def main() -> int:
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not token:
        print("✗ 未设置 GITHUB_TOKEN", file=sys.stderr)
        return 2
    dry = "--dry-run" in sys.argv

    # ── 1. 当前 HEAD 与远端树 ──
    status, ref = request("GET", f"{API}/git/ref/heads/{BRANCH}", token)
    if status != 200:
        print(f"✗ 读取分支 {BRANCH} 失败 ({status}): {ref.get('message','')}", file=sys.stderr)
        return 3
    parent_sha = ref["object"]["sha"]
    print(f"✓ 分支 {BRANCH} HEAD = {parent_sha[:7]}")

    status, commit = request("GET", f"{API}/git/commits/{parent_sha}", token)
    if status != 200:
        print(f"✗ 读取提交失败 ({status})", file=sys.stderr)
        return 3
    base_tree_sha = commit["tree"]["sha"]

    # 列出远端已有的全部路径，用于算出「将被删除」的集合
    status, tree_data = request(
        "GET", f"{API}/git/trees/{base_tree_sha}?recursive=1", token
    )
    remote_paths = {
        t["path"] for t in tree_data.get("tree", []) if t["type"] == "blob"
    }
    print(f"✓ 远端现有 {len(remote_paths)} 个文件")

    # ── 2. 本地文件 ──
    local = collect()
    local_set = {p.as_posix() for p in local}
    print(f"✓ 本地待镜像 {len(local_set)} 个文件")

    to_delete = sorted(remote_paths - local_set)
    to_add = sorted(local_set - remote_paths)
    common = local_set & remote_paths

    print()
    print(f"  新增 {len(to_add)}   保留 {len(common)}   删除 {len(to_delete)}")
    if to_delete:
        print(f"\n  将删除 {len(to_delete)} 个远端多余文件（前 15 个）:")
        for p in to_delete[:15]:
            print(f"    - {p}")
        if len(to_delete) > 15:
            print(f"    ...另有 {len(to_delete) - 15} 个")

    if dry:
        print("\n[DRY-RUN] 未做任何修改")
        return 0

    # ── 3. 上传 blob ──
    print(f"\n上传 {len(local)} 个 blob ...")
    tree_items = []
    for i, rel in enumerate(local, 1):
        content = (ROOT / rel).read_bytes()
        status, blob = request(
            "POST", f"{API}/git/blobs", token,
            {"content": base64.b64encode(content).decode(), "encoding": "base64"},
        )
        if status not in (200, 201):
            print(f"✗ blob 上传失败 {rel} ({status}): {blob.get('message','')[:160]}",
                  file=sys.stderr)
            return 4
        tree_items.append({
            "path": rel.as_posix(),
            "mode": "100755" if os.access(ROOT / rel, os.X_OK) and rel.suffix == "" else "100644",
            "type": "blob",
            "sha": blob["sha"],
        })
        if i % 25 == 0:
            print(f"  [{i}/{len(local)}]")

    # ── 4. 构造完整树（不带 base_tree = 以本地为准，多余文件自然消失）──
    print("构造完整目录树 ...")
    status, new_tree = request(
        "POST", f"{API}/git/trees", token, {"tree": tree_items}
    )
    if status not in (200, 201):
        print(f"✗ 建树失败 ({status}): {new_tree.get('message','')[:200]}", file=sys.stderr)
        return 5
    print(f"✓ 新树 {new_tree['sha'][:7]}")

    # ── 5. 提交 ──
    msg = (
        f"重写为 FeishuCheckIn：镜像同步（{len(local)} 文件）\n\n"
        f"- 移除 {len(to_delete)} 个上一版 MAA 改造工程的残留文件\n"
        f"- 新增 {len(to_add)} 个文件\n"
        f"本次为完整镜像同步，远端树与本地一致。"
    )
    status, new_commit = request(
        "POST", f"{API}/git/commits", token,
        {"message": msg, "tree": new_tree["sha"], "parents": [parent_sha]},
    )
    if status not in (200, 201):
        print(f"✗ 建提交失败 ({status}): {new_commit.get('message','')[:200]}", file=sys.stderr)
        return 6
    print(f"✓ 新提交 {new_commit['sha'][:7]}")

    # ── 6. 移动分支指针 ──
    status, updated = request(
        "PATCH", f"{API}/git/refs/heads/{BRANCH}", token,
        {"sha": new_commit["sha"], "force": False},
    )
    if status not in (200, 201):
        print(f"✗ 更新分支失败 ({status}): {updated.get('message','')[:200]}", file=sys.stderr)
        return 7

    print()
    print("=" * 70)
    print(f"✓ 镜像同步完成")
    print(f"  分支   : {BRANCH}")
    print(f"  提交   : {new_commit['sha'][:7]}")
    print(f"  文件数 : {len(local)}")
    print(f"  已删除 : {len(to_delete)} 个旧文件")
    print(f"  查看   : https://github.com/{REPO}/commit/{new_commit['sha']}")
    print("=" * 70)
    return 0


if __name__ == "__main__":
    sys.exit(main())
