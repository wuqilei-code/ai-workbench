#!/usr/bin/env bash
# 从 kind admin.kubeconfig 生成 compose 侧 kubeconfig
set -e
CLUSTER="${1:-aiwb}"
OUT="$(dirname "$0")/kind-kubeconfig.yaml"

RAW=$(kind get kubeconfig --name "$CLUSTER")
PORT=$(echo "$RAW" | grep -oP 'server: https://127\.0\.0\.1:\K\d+')

echo "apiserver 端口 = $PORT"
PATCHED=$(echo "$RAW" | \
    sed "s|server: https://127.0.0.1:.*|server: https://host.docker.internal:$PORT|" | \
    sed 's|certificate-authority-data:.*|insecure-skip-tls-verify: true|')
echo "$PATCHED" > "$OUT"
echo "已生成 $OUT"
