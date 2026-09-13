# AI-Workbench 离线部署（内网机器执行）
# 用法: 将 dist/ 目录解压到任意位置,执行 deploy.ps1
# 前置: Docker Desktop 运行中

$ErrorActionPreference = "Continue"
$Dist = Split-Path $PSScriptRoot -Parent
$ImageDir = Join-Path $Dist "images"
$BinDir = Join-Path $Dist "bin"
$ProjectDir = Join-Path $Dist "project"
$ClusterName = "aiwb"
$RegistryName = "kind-registry"
$NodeIP = "172.21.0.200"
$Namespace = "ai-workbench"

Write-Host "`n=== AI-Workbench 离线部署 ===" -ForegroundColor Cyan

# --- PATH 补全 ---
$env:Path += ";$BinDir"

# --- 1. 安装二进制 ---
Write-Host "`n--- 安装二进制 ---"
$binTarget = "$env:LOCALAPPDATA\Microsoft\WinGet\Links"
New-Item -ItemType Directory -Path $binTarget -Force | Out-Null
Copy-Item (Join-Path $BinDir "kind.exe") "$binTarget\kind.exe" -Force
Copy-Item (Join-Path $BinDir "kubectl.exe") "$binTarget\kubectl.exe" -Force
Write-Host "kind.exe → $binTarget"
Write-Host "kubectl.exe → $binTarget"

# --- 2. 加载镜像 ---
Write-Host "`n--- 加载 Docker 镜像 ---"
$tars = Get-ChildItem $ImageDir -Filter "*.tar"
foreach ($tar in $tars) {
    Write-Host "  加载 $($tar.Name) ..."
    docker load -i $tar.FullName 2>&1 | Select-Object -Last 1
}

# --- 3. kind 集群 ---
Write-Host "`n--- 创建 kind 集群 ---"
$exists = kind get clusters 2>&1 | Select-String "^$ClusterName$"
if ($exists) {
    Write-Host "集群已存在,跳过"
} else {
    # kind 节点镜像已通过 docker load 加载,docker daemon 里有 kindest/node
    kind create cluster --name $ClusterName --config "$ProjectDir\k8s\scripts\kind-config.yaml" 2>&1 | Select-Object -Last 3
}
kubectl config use-context "kind-$ClusterName"

# --- 4. registry ---
Write-Host "`n--- 启动 registry ---"
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

# --- 5. 修复节点代理 ---
Write-Host "`n--- 修复节点代理 ---"
$noProxy = "fc00:f853:ccd:e793::/64,172.18.0.0/16,127.0.0.1,localhost,::1,10.96.0.0/16,10.244.0.0/16,aiwb-control-plane,.svc,.svc.cluster,.svc.cluster.local,kind-registry,kind-registry:5000"
$content = "[Manager]`nDefaultEnvironment=`"HTTP_PROXY=http://127.0.0.1:7892`" `"HTTPS_PROXY=http://127.0.0.1:7892`" `"NO_PROXY=$noProxy`" "
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($content))
docker exec $ClusterName-control-plane sh -c "echo $b64 | base64 -d > /etc/systemd/system.conf.d/proxy-default-environment.conf" 2>&1 | Out-Null
docker exec $ClusterName-control-plane sh -c "systemctl daemon-reload && systemctl restart containerd" 2>&1 | Out-Null
Start-Sleep -Seconds 8
Write-Host "代理修复完成"

# --- 6. 固定节点 IP ---
Write-Host "`n--- 固定节点 IP ---"
$nodeInfo = docker network inspect aiwb-agents --format '{{range .Containers}}{{.Name}}={{.IPv4Address}};{{end}}' 2>&1
if ($nodeInfo -match "aiwb-control-plane=$NodeIP") {
    Write-Host "已固定,跳过"
} else {
    docker network disconnect aiwb-agents aiwb-control-plane 2>&1 | Out-Null
    docker network connect --ip $NodeIP aiwb-agents aiwb-control-plane 2>&1 | Out-Null
    Write-Host "已固定到 $NodeIP"
}

# --- 7. 生成 kubeconfig ---
Write-Host "`n--- 生成 kubeconfig ---"
$raw = kind get kubeconfig --name $ClusterName
$port = [regex]::Match($raw, "server: https://127\.0\.0\.1:(\d+)").Groups[1].Value
$patched = $raw -replace "server: https://127\.0\.0\.1:\d+", "server: https://host.docker.internal:$port"
$patched = ($patched -replace "(\s+)certificate-authority-data:.*", '$1insecure-skip-tls-verify: true')
[IO.File]::WriteAllText("$ProjectDir\k8s\scripts\kind-kubeconfig.yaml", $patched, (New-Object Text.UTF8Encoding($false)))
Write-Host "端口=$port"

# --- 8. 推沙箱镜像到 registry ---
Write-Host "`n--- 推送沙箱镜像 ---"
docker tag ai-workbench-sandbox:latest kind-registry:5000/aiwb-sandbox:1 2>&1
docker push kind-registry:5000/aiwb-sandbox:1 2>&1 | Select-Object -Last 1

# --- 9. apply RBAC ---
Write-Host "`n--- apply 集群资源 ---"
kubectl apply -f "$ProjectDir\k8s\manifests\00-namespace.yaml" 2>&1
kubectl apply -f "$ProjectDir\k8s\manifests\21-agent-service.yaml" 2>&1

# --- 10. 更新 compose env 端口 ---
Write-Host "`n--- 更新 compose ---"
$composePath = "$ProjectDir\docker\docker-compose.yml"
$compose = Get-Content $composePath -Raw
$compose = $compose -replace "AGENT_K8S_API_HOST:.*", "AGENT_K8S_API_HOST: `"https://host.docker.internal:$port`""
[IO.File]::WriteAllText($composePath, $compose, (New-Object Text.UTF8Encoding($false)))

# --- 11. 启动服务 ---
Write-Host "`n--- 启动 compose ---"
Push-Location $ProjectDir
docker compose -f docker\docker-compose.yml up -d 2>&1 | Select-Object -Last 5
Pop-Location

# --- 12. 验证 ---
Write-Host "`n--- 健康检查 ---"
Start-Sleep -Seconds 20
docker compose -f "$ProjectDir\docker\docker-compose.yml" ps --format "table {{.Name}}\t{{.Status}}" 2>&1
Write-Host "`nPod:"
kubectl -n $Namespace get pods 2>&1

Write-Host "`n=== 部署完成 ===访问 http://localhost:8080" -ForegroundColor Green
