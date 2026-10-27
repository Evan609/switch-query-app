#!/bin/bash
# 交换机查询APP授权服务 —— 每日备份
# 备份内容：数据库（用户/令牌/审计）、配置（含签名密钥）、证书
# 保留最近 30 份

set -euo pipefail

BASE="/opt/switch-auth"
DEST="$BASE/backup"
KEEP=30

[ -d "$BASE" ] || { echo "目录不存在：$BASE"; exit 1; }
mkdir -p "$DEST"

TS=$(date +%Y%m%d-%H%M%S)
NAME="switch-auth-$TS.tar.gz"

# SQLite 建议用 .backup 保证一致性，避免直接拷 WAL 文件
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

if [ -f "$BASE/data/app.db" ]; then
  python3.11 - "$BASE/data/app.db" "$TMP/app.db" <<'PY'
import sqlite3, sys
src, dst = sys.argv[1], sys.argv[2]
c = sqlite3.connect(src)
d = sqlite3.connect(dst)
c.backup(d)
d.close(); c.close()
PY
fi

cp -f "$BASE/config.json" "$TMP/config.json" 2>/dev/null || true
mkdir -p "$TMP/cert"
cp -f "$BASE/cert/server.crt" "$BASE/cert/server.key" "$TMP/cert/" 2>/dev/null || true

tar czf "$DEST/$NAME" -C "$TMP" .
chmod 600 "$DEST/$NAME"

# 清理旧备份
ls -1t "$DEST"/switch-auth-*.tar.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f

echo "[$(date '+%F %T')] backup ok: $DEST/$NAME"
