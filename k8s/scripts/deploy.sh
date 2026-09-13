#!/usr/bin/env bash
# AI-Workbench 离线部署（Linux 内网机器）
# 用法: 将 dist/ 目录解压到任意位置,执行 deploy.sh
# 前置: Docker Engine 运行中

set -e
DIST="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE_DIR="$DIST/images"
BIN_DIR="$DIST/bin"
PROJECT_DIR="$DIST/project"
CLUSTER_NAME="aiwb"
REGISTRY_NAME="kind-registry"
NODE_IP="172.21.0.200"
NAMESPACE="ai-workbench"

# --- 自定义路径 ---
# 用法: WORKSPACE_DATA=/data/my-workspaces bash deploy.sh
WORKSPACE_DATA="${WORKSPACE_DATA:-}"

log() { echo -e "\033[36m=== $1 ===\033[0m"; }

# --- PATH ---
export PATH="$BIN_DIR:$PATH"

# --- 1. 安装二进制 ---
log "安装二进制"
cp "$BIN_DIR/kind" /usr/local/bin/kind && chmod +x /usr/local/bin/kind
cp "$BIN_DIR/kubectl" /usr/local/bin/kubectl && chmod +x /usr/local/bin/kubectl
echo "kind: $(kind version)"
echo "kubectl: $(kubectl version --client 2>&1 | head -1)"

# --- 2. 加载镜像 ---
log "加载 Docker 镜像"
for tar in "$IMAGE_DIR"/*.tar; do
    echo "  加载 $(basename "$tar") ..."
    docker load -i "$tar" 2>&1 | tail -1
done

# --- 3. kind 集群 ---
log "创建 kind 集群"
if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
    echo "集群已存在,跳过"
else
    kind create cluster --name "$CLUSTER_NAME" --config "$PROJECT_DIR/k8s/scripts/kind-config.yaml" 2>&1 | tail -3
fi
kubectl config use-context "kind-${CLUSTER_NAME}"

# --- 4. registry ---
log "启动 registry"
if ! docker ps -a --filter "name=$REGISTRY_NAME" --format '{{.Names}}' | grep -q "$REGISTRY_NAME"; then
    docker run -d --name "$REGISTRY_NAME" --restart unless-stopped -p 127.0.0.1:5000:5000 registry:2 >/dev/null
    echo "registry 已启动"
else
    docker ps --filter "name=$REGISTRY_NAME" --format '{{.Names}}' | grep -q "$REGISTRY_NAME" || docker start "$REGISTRY_NAME" >/dev/null
    echo "registry 已存在"
fi
# 接入网络（幂等）
for net in kind aiwb-agents; do
    docker network inspect "$net" --format '{{range .Containers}}{{.Name}} {{end}}' 2>/dev/null | grep -q "$REGISTRY_NAME" || \
        docker network connect "$net" "$REGISTRY_NAME" 2>/dev/null || true
done

# --- 5. 修复节点代理 ---
log "修复节点代理"
NO_PROXY="fc00:f853:ccd:e793::/64,172.18.0.0/16,127.0.0.1,localhost,::1,10.96.0.0/16,10.244.0.0/16,aiwb-control-plane,.svc,.svc.cluster,.svc.cluster.local,kind-registry,kind-registry:5000"
CONTENT="[Manager]
DefaultEnvironment=\"HTTP_PROXY=http://127.0.0.1:7892\" \"HTTPS_PROXY=http://127.0.0.1:7892\" \"NO_PROXY=$NO_PROXY\""
echo "$CONTENT" | docker exec -i "$CLUSTER_NAME-control-plane" tee /etc/systemd/system.conf.d/proxy-default-environment.conf >/dev/null
docker exec "$CLUSTER_NAME-control-plane" sh -c "systemctl daemon-reload && systemctl restart containerd"
sleep 8
echo "代理修复完成"

# --- 6. 固定节点 IP ---
log "固定节点 IP"
NODE_INFO=$(docker network inspect aiwb-agents --format '{{range .Containers}}{{.Name}}={{.IPv4Address}};{{end}}' 2>/dev/null)
if echo "$NODE_INFO" | grep -q "aiwb-control-plane=$NODE_IP"; then
    echo "已固定,跳过"
else
    docker network disconnect aiwb-agents aiwb-control-plane 2>/dev/null || true
    docker network connect --ip "$NODE_IP" aiwb-agents aiwb-control-plane
    echo "已固定到 $NODE_IP"
fi

# --- 7. 生成 kubeconfig ---
log "生成 kubeconfig"
RAW=$(kind get kubeconfig --name "$CLUSTER_NAME")
PORT=$(echo "$RAW" | grep -oP 'server: https://127\.0\.0\.1:\K\d+')
PATCHED=$(echo "$RAW" | sed "s|server: https://127.0.0.1:.*|server: https://host.docker.internal:$PORT|" | sed 's|certificate-authority-data:.*|insecure-skip-tls-verify: true|')
echo "$PATCHED" > "$PROJECT_DIR/k8s/scripts/kind-kubeconfig.yaml"
echo "端口=$PORT"

# --- 8. 推沙箱镜像 ---
log "推送沙箱镜像"
docker tag ai-workbench-sandbox:latest kind-registry:5000/aiwb-sandbox:1
docker push kind-registry:5000/aiwb-sandbox:1 2>&1 | tail -1

# --- 9. apply RBAC ---
log "apply 集群资源"
kubectl apply -f "$PROJECT_DIR/k8s/manifests/00-namespace.yaml"
kubectl apply -f "$PROJECT_DIR/k8s/manifests/21-agent-service.yaml"

# --- 10. 更新 compose env ---
log "更新 compose"
sed -i "s|AGENT_K8S_API_HOST:.*|AGENT_K8S_API_HOST: \"host.docker.internal:$PORT\"|" "$PROJECT_DIR/docker/docker-compose.yml"
# 工作区路径(Linux 生产)
if [ -n "$WORKSPACE_DATA" ]; then
    mkdir -p "$WORKSPACE_DATA/users"
    cat > "$PROJECT_DIR/docker/docker-compose.override.yml" << EOF
services:
  app:
    volumes:
      - ${WORKSPACE_DATA}:/workspace
  agent-service:
    environment:
      AGENT_SANDBOX_VOLUME_ROOT: ${WORKSPACE_DATA}/users
      AGENT_K8S_SANDBOX_VOLUME_HOST_PATH: ${WORKSPACE_DATA}
    volumes:
      - ${WORKSPACE_DATA}:/shared-ws
  postgres:
    volumes:
      - postgres-data:/var/lib/postgresql/data
  mysql:
    volumes:
      - mysql-data:/var/lib/mysql
volumes:
  postgres-data:
  mysql-data:
EOF
    echo "工作区: $WORKSPACE_DATA (override 已生成)"
fi

# --- 11. 启动服务 ---
log "启动 compose"
cd "$PROJECT_DIR"
docker compose -f docker/docker-compose.yml up -d 2>&1 | tail -5

# --- 12. 验证 ---
log "健康检查"
sleep 20
docker compose -f "$PROJECT_DIR/docker/docker-compose.yml" ps --format "table {{.Name}}\t{{.Status}}"
echo ""
echo "Pod:"
kubectl -n "$NAMESPACE" get pods 2>&1

echo -e "\n\033[32m=== 部署完成 ===访问 http://$(hostname -I | awk '{print $1}'):8080\033[0m"
