#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
配置 GitHub Actions 的签名相关 secrets，让 CI 能产出已签名的 Release APK。

## 为什么需要这个脚本

GitHub 的 secrets 写入接口要求用仓库公钥做 **libsodium sealed box** 加密，
不能明文 PUT。用 curl 很难做（要装 sodium CLI），用 Python 几行就够。

## 关键细节（踩过的坑）

1. **必须用 `SealedBox`，不是 `SecretBox`**。
   `nacl.secret` 是共享密钥对称加密，接口完全不同 ——
   用错会得到 `TypeError`，且报错信息不指向真正的误解。
2. **加密后要 base64 编码**再放进 `encrypted_value`。
3. 写 secret 需要 token 有 **`repo`** scope（写 Actions secrets 属于仓库写权限），
   `admin:repo_hook` 之类的 scope 都不够。
4. 值里的换行要保留（KEYSTORE_BASE64 是长 base64 串，本身就无换行，
   但 keystore 密码等若含特殊字符也要原样传）。

## 用法

    export GITHUB_TOKEN=ghp_xxx
    python scripts/setup_ci_secrets.py

可选：`DRY_RUN=1` 只检查 keystore 与参数，不实际写入。
"""

import base64
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = os.environ.get("GITHUB_REPO", "945U945/maa-feishu")
API = f"https://api.github.com/repos/{REPO}/actions/secrets"
ROOT = Path(__file__).resolve().parent.parent

# ── 与本地签名配置保持一致 ──
#
# 这些值必须和 local.properties / CI workflow 里的 KEYSTORE_PATH 三方对齐，
# 否则会出现「keystore 还原了但 gradle 找不到」的经典问题。
KEYSTORE_FILE = ROOT.parent / "keystore" / "feishu-checkin.jks"
SECRETS = {
    "KEYSTORE_PATH": "release.jks",       # 相对仓库根，与 workflow 还原位置一致
    "KEYSTORE_PASSWORD": "FeishuCheckIn2026",
    "KEY_ALIAS": "feishu-checkin",
    "KEY_PASSWORD": "FeishuCheckIn2026",
}


def request(method: str, url: str, token: str, payload=None):
    body = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("User-Agent", "feishu-checkin-setup")
    if body:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        return e.code, {"message": e.read().decode(errors="replace")[:400]}


def encrypt(public_key_b64: str, secret_value: str) -> str:
    """用仓库公钥做 SealedBox 加密，返回 base64 后的密文"""
    from nacl.public import PublicKey, SealedBox

    pk = PublicKey(base64.b64decode(public_key_b64))
    sealed = SealedBox(pk)
    encrypted = sealed.encrypt(secret_value.encode("utf-8"))
    return base64.b64encode(encrypted).decode()


def main() -> int:
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not token:
        print("✗ 未设置 GITHUB_TOKEN", file=sys.stderr)
        return 2

    dry = os.environ.get("DRY_RUN") == "1"

    # ── 校验 keystore 存在（这是最关键的一项）──
    if not KEYSTORE_FILE.exists():
        print(f"✗ 找不到 keystore: {KEYSTORE_FILE}", file=sys.stderr)
        print("  请确认路径，KEYSTORE_BASE64 无法生成", file=sys.stderr)
        return 3
    raw = KEYSTORE_FILE.read_bytes()
    b64 = base64.b64encode(raw).decode()
    print(f"✓ keystore: {KEYSTORE_FILE.name} ({len(raw)} 字节 → base64 {len(b64)} 字符)")

    payload_secrets = dict(SECRETS)
    payload_secrets["KEYSTORE_BASE64"] = b64

    print(f"✓ 待写入 {len(payload_secrets)} 个 secret:")
    for k, v in payload_secrets.items():
        shown = v if len(v) <= 24 else v[:16] + f"...({len(v)} 字符)"
        print(f"    {k:<20} = {shown}")

    if dry:
        print("\n[DRY_RUN] 未实际写入")
        return 0

    # ── 取公钥 ──
    status, pk_data = request("GET", f"{API}/public-key", token)
    if status != 200:
        print(f"✗ 获取公钥失败 ({status}): {pk_data.get('message', '')}", file=sys.stderr)
        print("  常见原因：token 缺少 repo scope", file=sys.stderr)
        return 4
    key_id = pk_data["key_id"]
    print(f"\n✓ 仓库公钥 key_id: {key_id[:16]}...")

    # ── 逐个写入 ──
    ok = 0
    for name, value in payload_secrets.items():
        enc = encrypt(pk_data["key"], value)
        status, data = request(
            "PUT", f"{API}/{name}", token,
            {"encrypted_value": enc, "key_id": key_id},
        )
        if status in (201, 204):
            print(f"  ✓ {name}")
            ok += 1
        else:
            print(f"  ✗ {name} → HTTP {status}: {data.get('message', '')[:200]}", file=sys.stderr)

    print()
    print("=" * 60)
    print(f"完成：{ok}/{len(payload_secrets)} 个 secret 已写入")
    print(f"查看：https://github.com/{REPO}/settings/secrets/actions")
    print("=" * 60)
    return 0 if ok == len(payload_secrets) else 1


if __name__ == "__main__":
    sys.exit(main())
