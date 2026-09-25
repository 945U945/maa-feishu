#!/usr/bin/env bash
# 一键推送到 GitHub：https://github.com/945U945/maa-feishu
#
# 前置条件（二选一）：
#   A. 在 GitHub 上先创建好仓库 945U945/maa-feishu（不要勾选 README/gitignore，
#      否则首次推送会因历史不一致被拒）
#   B. 用 gh CLI 创建：gh repo create maa-feishu --private --source=. --push
#
# 然后准备好 Token（GitHub → Settings → Developer settings →
# Personal access tokens → Fine-grained tokens，授予该仓库 Contents: Read and write），运行本脚本。

set -euo pipefail
cd "$(dirname "$0")"

REPO_URL="https://github.com/945U945/maa-feishu.git"
PROXY="http://127.0.0.1:2927"

echo "==> 仓库：$REPO_URL"

# ── 取 Token ────────────────────────────────────────────
if [ -n "${GITHUB_TOKEN:-}" ]; then
    TOKEN="$GITHUB_TOKEN"
    echo "    使用环境变量 GITHUB_TOKEN"
else
    read -rsp "请输入 GitHub Personal Access Token（输入不回显）: " TOKEN
    echo
fi

if [ -z "$TOKEN" ]; then
    echo "✗ Token 为空，退出。"
    exit 1
fi

# ── 先验证 Token 有效、且有该仓库写权限 ──────────────────
echo "==> 验证 Token"
HTTP=$(curl -sS -x "$PROXY" -o /tmp/_gh_check.json -w '%{http_code}' \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/945U945/maa-feishu" || echo 000)

case "$HTTP" in
    200)
        echo "    ✓ 仓库存在，Token 可访问"
        ;;
    404)
        echo "    ✗ 仓库不存在，或 Token 无权限访问它。"
        echo "      请先在 GitHub 上创建 https://github.com/945U945/maa-feishu"
        exit 1
        ;;
    401)
        echo "    ✗ Token 无效或已过期。"
        exit 1
        ;;
    *)
        echo "    ✗ 校验失败（HTTP $HTTP）。"; exit 1
        ;;
esac

# ── 配置远端（带 Token，仅存于本次命令，不写入 git config）──
echo "==> 推送 main 分支持续"
git -c http.proxy="$PROXY" -c https.proxy="$PROXY" \
    push "https://945U945:${TOKEN}@github.com/945U945/maa-feishu.git" main

echo
echo "✅ 推送完成"
echo
echo "下一步：触发云端构建出 APK"
echo "   方式一（推荐，自动发版）："
echo "     git -c http.proxy=$PROXY tag v1.0.0"
echo "     git -c http.proxy=$PROXY -c https.proxy=$PROXY push https://945U945:\$TOKEN@github.com/945U945/maa-feishu.git v1.0.0"
echo "   方式二（手动）：到 GitHub 仓库 Actions 页 → Build Release APK → Run workflow"
