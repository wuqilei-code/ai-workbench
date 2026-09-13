# 修复 kind 节点:NO_PROXY 加入 kind-registry,令 containerd 经集群内网拉镜像。
# kind 重建集群后节点 systemd 代理配置会重置,需重跑本脚本。
# 用法: powershell -ExecutionPolicy Bypass -File fix-node-registry.ps1

$ErrorActionPreference = "Stop"
$node = if ($env:AIWB_NODE) { $env:AIWB_NODE } else { "aiwb-control-plane" }
if (-not (docker ps --filter "name=$node" --format "{{.Names}}")) {
  Write-Error "kind 节点 $node 不在运行(docker ps),请先创建集群"; exit 1
}

Write-Host "==> 读取节点当前 DefaultEnvironment..."
docker exec $node sh -c "cat /etc/systemd/system.conf.d/proxy-default-environment.conf"

Write-Host "==> 注入 kind-registry 到 NO_PROXY(base64 传输避免转义)..."
$noProxy = "fc00:f853:ccd:e793::/64,172.18.0.0/16,127.0.0.1,localhost,::1,10.96.0.0/16,10.244.0.0/16,aiwb-control-plane,.svc,.svc.cluster,.svc.cluster.local,kind-registry,kind-registry:5000"
$content = "[Manager]`nDefaultEnvironment=`"HTTP_PROXY=http://127.0.0.1:7892`" `"HTTPS_PROXY=http://127.0.0.1:7892`" `"NO_PROXY=$noProxy`" "
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($content))
docker exec $node sh -c "echo $b64 | base64 -d > /etc/systemd/system.conf.d/proxy-default-environment.conf"

Write-Host "==> 重启 containerd..."
docker exec $node sh -c "systemctl daemon-reload && systemctl restart containerd"
Start-Sleep -Seconds 8

Write-Host "==> 验证镜像拉取(kind-registry:5000/aiwb-tools:alpine3.19)..."
docker exec $node sh -c "ctr --namespace k8s.io content fetch --all-platforms kind-registry:5000/aiwb-tools:alpine3.19 2>&1 | tail -2" | Select-Object -Last 3
Write-Host "OK"