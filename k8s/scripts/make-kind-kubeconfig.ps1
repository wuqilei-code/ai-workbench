# 从 kind admin.kubeconfig 生成 compose 侧 kubeconfig(供 agent-service 使用)。
# 生成后需同步 docker-compose.yml 的 AGENT_K8S_API_HOST 端口。
# 用法: powershell -ExecutionPolicy Bypass -File make-kind-kubeconfig.ps1

$ErrorActionPreference = "Stop"
$cluster = if ($env:AIWB_CLUSTER) { $env:AIWB_CLUSTER } else { "aiwb" }
$out = Join-Path $PSScriptRoot "kind-kubeconfig.yaml"

Write-Host "==> kind get kubeconfig (cluster=$cluster)..."
$raw = kind get kubeconfig --name $cluster
if (-not $raw) { Write-Error "未能获取 kind kubeconfig;确认集群 $cluster 存在"; exit 1 }

# server 端口:kind 让 kubeconfig 里是 127.0.0.1:<random> → 换 host.docker.internal,并跳过 TLS 校验
$port = [regex]::Match($raw, "server: https://127\.0\.0\.1:(\d+)").Groups[1].Value
if (-not $port) { Write-Error "端口解析失败"; exit 1 }
$portForCompose = Read-Host "apiserver 宿主端口 = $port,回车确认(可改)"
if ($portForCompose) { $port = $portForCompose }

$patched = $raw -replace "server: https://127\.0\.0\.1:\d+", "server: https://host.docker.internal:$port"
$patched = ($patched -replace "(\s+)certificate-authority-data:.*", '$1insecure-skip-tls-verify: true')

[IO.File]::WriteAllText($out, $patched, (New-Object Text.UTF8Encoding($false)))
Write-Host "==> 已生成 $out"
Write-Host "==> 提示:docker-compose.yml 的 AGENT_K8S_API_HOST 应为 https://host.docker.internal:$port";
Write-Host "==> 提示:docker-compose.yml 挂载 ../k8s/scripts/kind-kubeconfig.yaml:/kube/config:ro"
docker compose -f docker/docker-compose.yml config 2>$null | Select-String -Pattern "AGENT_K8S_API_HOST|kind-kubeconfig" | Select-Object -First 2