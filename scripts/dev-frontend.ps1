# 前端快速迭代:宿主机构建 → 直接覆盖容器内静态资源。
# 免去 "docker compose up -d --build app"(镜像内会再构建一次前端 + 重启容器)。
#
# 用法(PowerShell):
#   .\scripts\dev-frontend.ps1              # 构建并覆盖(推荐)
#   .\scripts\dev-frontend.ps1 -Clean       # 先清空容器内 html 再覆盖(避免残留旧 hash 资源)
#
# 完成后浏览器 Ctrl+F5 强制刷新(nginx 已对 index.html/静态资源设 no-cache)。
param([switch]$Clean)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$cid = "docker-app-1"
$dist = Join-Path $root "frontend\dist"

Write-Host "==> 宿主机构建前端(npm run build)"
Push-Location (Join-Path $root "frontend")
try { npm run build } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw "前端构建失败,未覆盖容器" }
if (-not (Test-Path $dist)) { throw "未找到 $dist" }

if ($Clean) {
    Write-Host "==> 清空容器内静态资源"
    docker exec $cid sh -lc "rm -rf /usr/share/nginx/html/*" | Out-Null
}

Write-Host "==> 覆盖容器静态资源(docker cp,免重建镜像/免重启容器)"
# 注意:用 "dist/." 形式复制内容(而非目录本身)
docker cp "$($dist -replace '\\','/')./." "${cid}:/usr/share/nginx/html/"
if ($LASTEXITCODE -ne 0) { throw "docker cp 失败" }

$code = (curl.exe -s -o NUL -w "%{http_code}" "http://localhost:8080/")
Write-Host "==> 完成。首页 HTTP $code —— 浏览器 Ctrl+F5 强制刷新即可看到新版本。"
