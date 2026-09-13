# AI-Workbench 离线包制作（需要联网机器执行）
# 用法: powershell -ExecutionPolicy Bypass -File offline-build.ps1
# 产出: dist/ 目录（压缩后拷贝到内网）

$ErrorActionPreference = "Continue"
$Root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$Dist = Join-Path $Root "dist"
$ImageDir = Join-Path $Dist "images"
$BinDir = Join-Path $Dist "bin"

Write-Host "`n=== AI-Workbench 离线包制作 ===" -ForegroundColor Cyan

# ---------- 清理重建 ----------
if (Test-Path $Dist) { Remove-Item $Dist -Recurse -Force }
New-Item -ItemType Directory -Path $ImageDir -Force | Out-Null
New-Item -ItemType Directory -Path $BinDir -Force | Out-Null

# ---------- 1. 下载二进制 ----------
Write-Host "`n--- 下载 kind.exe ---"
$kindVer = "v0.33.0"
$kindUrl = "https://github.com/kubernetes-sigs/kind/releases/download/$kindVer/kind-windows-amd64"
Invoke-WebRequest -Uri $kindUrl -OutFile (Join-Path $BinDir "kind.exe") -UseBasicParsing
Write-Host "kind.exe 已下载"

Write-Host "`n--- 下载 kubectl.exe ---"
$kubectlVer = "v1.36.1"
$kubectlUrl = "https://dl.k8s.io/release/$kubectlVer/bin/windows/amd64/kubectl.exe"
Invoke-WebRequest -Uri $kubectlUrl -OutFile (Join-Path $BinDir "kubectl.exe") -UseBasicParsing
Write-Host "kubectl.exe 已下载"

# ---------- 2. 拉取并保存所有 Docker 镜像 ----------
$images = @(
    # 基础服务
    "docker.m.daocloud.io/postgres:16-alpine",
    "docker.m.daocloud.io/mysql:8.4",
    # registry
    "registry:2",
    # Dockerfile 构建阶段（buildx 缓存用）
    "docker.m.daocloud.io/node:22-bookworm-slim",
    "docker.m.daocloud.io/maven:3.9-eclipse-temurin-21",
    "docker.m.daocloud.io/python:3.13-slim",
    # kind 节点
    "kindest/node:v1.36.1"
)

Write-Host "`n--- 拉取 Docker 镜像 ---"
foreach ($img in $images) {
    Write-Host "  拉取 $img ..."
    docker pull $img 2>&1 | Select-Object -Last 1
}

# ---------- 3. 构建本地镜像并保存 ----------
Write-Host "`n--- 构建本地镜像 ---"
Push-Location $Root

# agent-service
Write-Host "  构建 ai-workbench-agent-service ..."
docker compose -f docker/docker-compose.yml build agent-service 2>&1 | Select-Object -Last 2

# app
Write-Host "  构建 ai-workbench-app ..."
docker compose -f docker/docker-compose.yml build app 2>&1 | Select-Object -Last 2

# sandbox
if (Test-Path "agent-service\Dockerfile.sandbox") {
    Write-Host "  构建 ai-workbench-sandbox ..."
    docker build -f agent-service/Dockerfile.sandbox -t ai-workbench-sandbox:latest . 2>&1 | Select-Object -Last 2
}

Pop-Location

# ---------- 4. 保存镜像为 tar ----------
Write-Host "`n--- 保存镜像为 tar ---"
$localImages = @(
    "ai-workbench-app:latest",
    "ai-workbench-agent-service:latest",
    "ai-workbench-sandbox:latest"
)
$allImages = $images + $localImages

foreach ($img in $allImages) {
    $safeName = $img -replace "[/:]", "_"
    $tarPath = Join-Path $ImageDir "$safeName.tar"
    Write-Host "  保存 $img → $safeName.tar"
    docker save $img -o $tarPath 2>&1 | Out-Null
}

# ---------- 5. 打包 Python 依赖（pip download） ----------
Write-Host "`n--- 下载 Python 依赖 ---"
$pyDir = Join-Path $Dist "python-packages"
New-Item -ItemType Directory -Path $pyDir -Force | Out-Null
pip download -r "$Root\agent-service\requirements.txt" -d $pyDir --only-binary=:all: --python-version 3.13 --platform manylinux2014_x86_64 2>&1 | Select-Object -Last 3
# 备注: 纯 python 包也下载（无 binary 的）
pip download -r "$Root\agent-service\requirements.txt" -d $pyDir --no-deps 2>&1 | Select-Object -Last 3

# ---------- 6. 打包 npm 依赖 ----------
Write-Host "`n--- 下载 npm 依赖 ---"
$npmDir = Join-Path $Dist "npm-packages"
New-Item -ItemType Directory -Path $npmDir -Force | Out-Null
Push-Location $npmDir
npm pack @playwright/mcp@latest 2>&1 | Select-Object -Last 1
Pop-Location

# ---------- 7. 复制项目文件 ----------
Write-Host "`n--- 复制项目文件 ---"
$projectDir = Join-Path $Dist "project"
New-Item -ItemType Directory -Path $projectDir -Force | Out-Null

# 只复制部署必需的文件
$copyDirs = @("agent-service", "docker", "k8s", "backend\src", "frontend\src")
foreach ($d in $copyDirs) {
    $src = Join-Path $Root $d
    $dst = Join-Path $projectDir $d
    if (Test-Path $src) {
        Copy-Item $src $dst -Recurse -Force 2>&1 | Out-Null
        Write-Host "  复制 $d"
    }
}
# 复制构建文件
Copy-Item (Join-Path $Root "docker\Dockerfile") (Join-Path $projectDir "docker\Dockerfile") -Force
Copy-Item (Join-Path $Root "docker\Dockerfile.sandbox") (Join-Path $projectDir "agent-service\Dockerfile.sandbox") -Force -ErrorAction SilentlyContinue
Copy-Item (Join-Path $Root "docker\.env.example") (Join-Path $projectDir "docker\.env") -Force

# ---------- 8. 复制离线部署脚本 ----------
Copy-Item (Join-Path $PSScriptRoot "offline-deploy.ps1") (Join-Path $Dist "deploy.ps1") -Force

# ---------- 9. 生成清单 ----------
Write-Host "`n--- 生成清单 ---"
$manifest = @"
# AI-Workbench 离线包清单
# 制作时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
# 用法: 将 dist/ 目录整体拷贝到内网机器,执行 deploy.ps1

## 二进制
- kind.exe (kind $kindVer)
- kubectl.exe (kubectl $kubectlVer)

## Docker 镜像 (images/*.tar)
$($allImages | ForEach-Object { "- $_" } | Out-String)

## Python 依赖 (python-packages/)
- requirements.txt 全部包(wheel + sdist)

## npm 依赖 (npm-packages/)
- @playwright/mcp

## 项目文件 (project/)
- agent-service/ (sandbox_k8s.py, config.py, Dockerfile.sandbox 等)
- docker/ (Dockerfile, docker-compose.yml, nginx.conf 等)
- k8s/ (manifests/, scripts/)
- backend/src/ (Java 源码)
- frontend/src/ (前端源码)
"@
[IO.File]::WriteAllText((Join-Path $Dist "README.md"), $manifest, (New-Object Text.UTF8Encoding($false)))

# ---------- 10. 压缩 ----------
Write-Host "`n--- 压缩 dist ---"
$zipPath = Join-Path $Root "ai-workbench-offline.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
Compress-Archive -Path $Dist -DestinationPath $zipPath -Force
$zipSize = [math]::Round((Get-Item $zipPath).Length / 1MB, 1)
Write-Host "已生成: $zipPath ($zipSize MB)" -ForegroundColor Green
Write-Host "`n=== 制作完成 ===将 $zipPath 拷贝到内网机器解压后执行 deploy.ps1" -ForegroundColor Green
