#!/usr/bin/env bash
# 修复 kind 节点 NO_PROXY（每次集群重建后重做）
set -e
NODE="${1:-aiwb-control-plane}"

echo "==> 注入 kind-registry 到 NO_PROXY ..."
NO_PROXY="fc00:f853:ccd:e793::/64,172.18.0.0/16,127.0.0.1,localhost,::1,10.96.0.0/16,10.244.0.0/16,aiwb-control-plane,.svc,.svc.cluster,.svc.cluster.local,kind-registry,kind-registry:5000"

docker exec "$NODE" sh -c "mkdir -p /etc/systemd/system.conf.d"
cat <<EOF | docker exec -i "$NODE" tee /etc/systemd/system.conf.d/proxy-default-environment.conf >/dev/null
[Manager]
DefaultEnvironment="HTTP_PROXY=http://127.0.0.1:7892" "HTTPS_PROXY=http://127.0.0.1:7892" "NO_PROXY=$NO_PROXY"
EOF

echo "==> 重启 containerd ..."
docker exec "$NODE" sh -c "systemctl daemon-reload && systemctl restart containerd"
sleep 8
echo "OK"
