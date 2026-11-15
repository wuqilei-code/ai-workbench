# 前端快速迭代:宿主机构建 → 直接覆盖容器内静态资源。
# 免去 "docker compose up -d --build app"(镜像内会再构建一次前端 + 重启容器)。
#
# 用法(PowerShell):
#   .\scripts\dev-frontend.ps1              # 构建并覆盖(推荐)
#   .\scripts\dev-frontend.ps1 -Clean       # 先清空容器内 html 再覆盖(避免残留旧 hash 资源)
#
# 完成后浏览器 Ctrl+F5 强制刷新(nginx 已对 index.html/静态资源设 no-cache)。
param([switch]$Clean, [string]$Name)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root "frontend\dist"

# 容器名不写死:优先按 compose 服务名(labels)找,其次按镜像名兜底。
# 之前的 "docker-app-1" 在 compose 项目名为 ai-workbench 时并不存在,会导致最后的 docker cp 失败。
function Resolve-AppContainer {
    $composeDir = Join-Path $root "docker"
    if (Test-Path (Join-Path $composeDir "docker-compose.yml")) {
        Push-Location $composeDir
        try {
            $id = (& docker compose ps -q app 2>$null | Select-Object -First 1)
        } catch { $id = $null }
        finally { Pop-Location }
        if ($id) { return $id.Trim() }
    }
    $byLabel = (& docker ps --filter "label=com.docker.compose.service=app" --format "{{.ID}}" 2>$null | Select-Object -First 1)
    if ($byLabel) { return $byLabel.Trim() }
    $byImage = (& docker ps --filter "ancestor=ai-workbench-app" --format "{{.ID}}" 2>$null | Select-Object -First 1)
    if ($byImage) { return $byImage.Trim() }
    return $null
}

$cid = if ($Name) { $Name } else { Resolve-AppContainer }
if (-not $cid) {
    throw "未找到运行中的 app 容器(app)。请先 docker compose up -d app,或用 -Name <容器> 指定。"
}
Write-Host "==> 目标容器:$cid"

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
