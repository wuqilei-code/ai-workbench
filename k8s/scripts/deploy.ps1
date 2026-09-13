# AI-Workbench 混合架构一键部署
# 用法: powershell -ExecutionPolicy Bypass -File deploy.ps1
# 前置: Docker Desktop 运行中

$ErrorActionPreference = "Continue"
$Root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$ComposeFile = Join-Path $Root "docker\docker-compose.yml"
$ClusterName = "aiwb"
$RegistryName = "kind-registry"
$NodeIP = "172.21.0.200"
$Namespace = "ai-workbench"

Write-Host "`n=== AI-Workbench 混合架构部署 ===" -ForegroundColor Cyan

# --- PATH 补全（winget 装的 kind/kubectl） ---
$env:Path += ";$env:LOCALAPPDATA\Microsoft\WinGet\Links"

# --- 依赖检查 ---
Write-Host "`n--- 依赖检查 ---"
docker version --format '{{.Server.Version}}' 2>$null | ForEach-Object { Write-Host "docker: $_" }
kind version 2>$null | ForEach-Object { Write-Host "kind: $_" }
kubectl version --client 2>$null | Select-Object -First 1 | ForEach-Object { Write-Host "kubectl: $_" }

# --- 1. 构建镜像 ---
Write-Host "`n--- 构建镜像 ---"
Push-Location $Root
docker compose -f $ComposeFile build app agent-service 2>&1 | Select-Object -Last 2
if (Test-Path "agent-service\Dockerfile.sandbox") {
    docker build -f agent-service/Dockerfile.sandbox -t ai-workbench-sandbox:latest . 2>&1 | Select-Object -Last 2
}
Pop-Location

# --- 2. kind 集群 ---
Write-Host "`n--- kind 集群 ---"
$exists = kind get clusters 2>&1 | Select-String "^$ClusterName$"
if ($exists) {
    Write-Host "集群 $ClusterName 已存在,跳过"
} else {
    kind create cluster --name $ClusterName --config "$Root\k8s\scripts\kind-config.yaml" 2>&1 | Select-Object -Last 3
}
kubectl config use-context "kind-$ClusterName"

# --- 3. registry ---
Write-Host "`n--- registry ---"
$reg = docker ps -a --filter "name=$RegistryName" --format "{{.Names}}"
if (-not $reg) {
    docker run -d --name $RegistryName --restart unless-stopped -p 127.0.0.1:5000:5000 registry:2 2>&1 | Out-Null
    Write-Host "registry 已启动"
} else {
    $running = docker ps --filter "name=$RegistryName" --format "{{.Names}}"
    if (-not $running) { docker start $RegistryName | Out-Null }
    Write-Host "registry 已存在"
}
$nets = docker network inspect kind --format '{{range .Containers}}{{.Name}} {{end}}' 2>&1
if ($nets -notmatch $RegistryName) { docker network connect kind $RegistryName 2>&1 | Out-Null }
$nets = docker network inspect aiwb-agents --format '{{range .Containers}}{{.Name}} {{end}}' 2>&1
if ($nets -notmatch $RegistryName) { docker network connect aiwb-agents $RegistryName 2>&1 | Out-Null }

# --- 4. 修复节点代理 ---
Write-Host "`n--- 修复节点代理 ---"
powershell -ExecutionPolicy Bypass -File "$Root\k8s\scripts\fix-node-registry.ps1"

# --- 5. 固定节点 IP ---
Write-Host "`n--- 固定节点 IP ---"
$nodeInfo = docker network inspect aiwb-agents --format '{{range .Containers}}{{.Name}}={{.IPv4Address}};{{end}}' 2>&1
if ($nodeInfo -match "aiwb-control-plane=$NodeIP") {
    Write-Host "节点已固定到 $NodeIP,跳过"
} else {
    docker network disconnect aiwb-agents aiwb-control-plane 2>&1 | Out-Null
    docker network connect --ip $NodeIP aiwb-agents aiwb-control-plane 2>&1 | Out-Null
    Write-Host "节点已固定到 $NodeIP"
}

# --- 6. 生成 kubeconfig ---
Write-Host "`n--- 生成 kubeconfig ---"
$raw = kind get kubeconfig --name $ClusterName
$port = [regex]::Match($raw, "server: https://127\.0\.0\.1:(\d+)").Groups[1].Value
$patched = $raw -replace "server: https://127\.0\.0\.1:\d+", "server: https://host.docker.internal:$port"
$patched = ($patched -replace "(\s+)certificate-authority-data:.*", '$1insecure-skip-tls-verify: true')
$kubePath = "$Root\k8s\scripts\kind-kubeconfig.yaml"
[IO.File]::WriteAllText($kubePath, $patched, (New-Object Text.UTF8Encoding($false)))
Write-Host "kubeconfig 端口=$port"

# --- 7. 推沙箱镜像 ---
Write-Host "`n--- 推送沙箱镜像 ---"
docker tag ai-workbench-sandbox:latest kind-registry:5000/aiwb-sandbox:1 2>&1
docker push kind-registry:5000/aiwb-sandbox:1 2>&1 | Select-Object -Last 2

# --- 8. apply RBAC ---
Write-Host "`n--- apply 集群资源 ---"
kubectl apply -f "$Root\k8s\manifests\00-namespace.yaml" 2>&1
kubectl apply -f "$Root\k8s\manifests\21-agent-service.yaml" 2>&1

# --- 9. 更新 compose env 端口 ---
Write-Host "`n--- 更新 compose env ---"
$compose = Get-Content $ComposeFile -Raw
$compose = $compose -replace "AGENT_K8S_API_HOST:.*", "AGENT_K8S_API_HOST: `"https://host.docker.internal:$port`""
[IO.File]::WriteAllText($ComposeFile, $compose, (New-Object Text.UTF8Encoding($false)))
Write-Host "AGENT_K8S_API_HOST = https://host.docker.internal:$port"

# --- 10. 启动 ---
Write-Host "`n--- docker compose up ---"
docker compose -f $ComposeFile up -d 2>&1 | Select-Object -Last 5

# --- 11. 验证 ---
Write-Host "`n--- 健康检查 ---"
Start-Sleep -Seconds 20
docker compose -f $ComposeFile ps --format "table {{.Name}}\t{{.Status}}" 2>&1
Write-Host "`nPod:"
kubectl -n $Namespace get pods 2>&1

Write-Host "`n=== 部署完成 ===访问 http://localhost:8080" -ForegroundColor Green
