#!/usr/bin/env bash
# AI-Workbench 离线包制作（需要联网机器执行）
# 用法: bash offline-build.sh
# 产出: dist/ 目录 → 压缩为 ai-workbench-offline.tar.gz

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DIST="$ROOT/dist"
IMAGE_DIR="$DIST/images"
BIN_DIR="$DIST/bin"

echo -e "\033[36m=== AI-Workbench 离线包制作 ===\033[0m"

# ---------- 清理重建 ----------
rm -rf "$DIST"
mkdir -p "$IMAGE_DIR" "$BIN_DIR"

# ---------- 1. 下载二进制（Linux amd64） ----------
echo -e "\n--- 下载 kind ---"
KIND_VER="v0.33.0"
curl -sL "https://github.com/kubernetes-sigs/kind/releases/download/${KIND_VER}/kind-linux-amd64" -o "$BIN_DIR/kind"
chmod +x "$BIN_DIR/kind"
echo "kind: $($BIN_DIR/kind version)"

echo -e "\n--- 下载 kubectl ---"
KUBECTL_VER="v1.36.1"
curl -sL "https://dl.k8s.io/release/${KUBECTL_VER}/bin/linux/amd64/kubectl" -o "$BIN_DIR/kubectl"
chmod +x "$BIN_DIR/kubectl"
echo "kubectl: $($BIN_DIR/kubectl version --client 2>&1 | head -1)"

# ---------- 2. 拉取 Docker 镜像 ----------
IMAGES=(
    "docker.m.daocloud.io/postgres:16-alpine"
    "docker.m.daocloud.io/mysql:8.4"
    "registry:2"
    "docker.m.daocloud.io/node:22-bookworm-slim"
    "docker.m.daocloud.io/maven:3.9-eclipse-temurin-21"
    "docker.m.daocloud.io/python:3.13-slim"
    "kindest/node:v1.36.1"
)

echo -e "\n--- 拉取 Docker 镜像 ---"
for img in "${IMAGES[@]}"; do
    echo "  拉取 $img ..."
    docker pull "$img" 2>&1 | tail -1
done

# ---------- 3. 构建本地镜像 ----------
echo -e "\n--- 构建本地镜像 ---"
cd "$ROOT"
docker compose -f docker/docker-compose.yml build agent-service 2>&1 | tail -2
docker compose -f docker/docker-compose.yml build app 2>&1 | tail -2
if [ -f agent-service/Dockerfile.sandbox ]; then
    docker build -f agent-service/Dockerfile.sandbox -t ai-workbench-sandbox:latest . 2>&1 | tail -2
fi

# ---------- 4. 保存镜像 ----------
echo -e "\n--- 保存镜像 ---"
LOCAL_IMAGES=(
    "ai-workbench-app:latest"
    "ai-workbench-agent-service:latest"
    "ai-workbench-sandbox:latest"
)
ALL_IMAGES=("${IMAGES[@]}" "${LOCAL_IMAGES[@]}")

for img in "${ALL_IMAGES[@]}"; do
    SAFE_NAME=$(echo "$img" | tr '/:' '__')
    echo "  保存 $img → ${SAFE_NAME}.tar"
    docker save "$img" -o "$IMAGE_DIR/${SAFE_NAME}.tar"
done

# ---------- 5. 打包 Python 依赖 ----------
echo -e "\n--- 下载 Python 依赖 ---"
PY_DIR="$DIST/python-packages"
mkdir -p "$PY_DIR"
pip download -r "$ROOT/agent-service/requirements.txt" -d "$PY_DIR" --only-binary=:all: 2>&1 | tail -3 || true
pip download -r "$ROOT/agent-service/requirements.txt" -d "$PY_DIR" --no-deps 2>&1 | tail -3 || true

# ---------- 6. 打包 npm 依赖 ----------
echo -e "\n--- 下载 npm 依赖 ---"
NPM_DIR="$DIST/npm-packages"
mkdir -p "$NPM_DIR"
cd "$NPM_DIR"
npm pack @playwright/mcp@latest 2>&1 | tail -1
cd "$ROOT"

# ---------- 7. 复制项目文件 ----------
echo -e "\n--- 复制项目文件 ---"
PROJECT_DIR="$DIST/project"
mkdir -p "$PROJECT_DIR"
for d in agent-service docker k8s backend/src frontend/src; do
    [ -d "$ROOT/$d" ] && cp -r "$ROOT/$d" "$PROJECT_DIR/$d" && echo "  复制 $d"
done
# 构建文件
cp "$ROOT/docker/Dockerfile" "$PROJECT_DIR/docker/Dockerfile"
cp "$ROOT/agent-service/Dockerfile.sandbox" "$PROJECT_DIR/agent-service/Dockerfile.sandbox" 2>/dev/null || true
cp "$ROOT/docker/.env.example" "$PROJECT_DIR/docker/.env" 2>/dev/null || true

# ---------- 8. 复制部署脚本 ----------
cp "$SCRIPT_DIR/deploy.sh" "$DIST/deploy.sh"
chmod +x "$DIST/deploy.sh"

# ---------- 9. 生成清单 ----------
cat > "$DIST/README.md" << EOF
# AI-Workbench 离线包清单
# 制作时间: $(date '+%Y-%m-%d %H:%M:%S')

## 二进制 (bin/)
- kind (linux/amd64, $KIND_VER)
- kubectl (linux/amd64, $KUBECTL_VER)

## Docker 镜像 (images/*.tar)
$(printf -- '- %s\n' "${ALL_IMAGES[@]}")

## Python 依赖 (python-packages/)
- requirements.txt 全部包

## npm 依赖 (npm-packages/)
- @playwright/mcp

## 项目文件 (project/)
- agent-service/ docker/ k8s/ backend/src/ frontend/src/

## 部署
1. 将整个 dist/ 拷贝到内网 Linux 机器
2. 执行: sudo bash deploy.sh
EOF

# ---------- 10. 压缩 ----------
echo -e "\n--- 压缩 ---"
TAR_PATH="$ROOT/ai-workbench-offline.tar.gz"
tar czf "$TAR_PATH" -C "$ROOT" dist
SIZE=$(du -h "$TAR_PATH" | cut -f1)
echo -e "\n\033[32m=== 制作完成 ===\033[0m"
echo "文件: $TAR_PATH ($SIZE)"
echo "部署: 解压后执行 sudo bash dist/deploy.sh"
