# 生成 docker\.env:复制 .env.example 并填入随机密钥/密码,免去手工生成。
#
# 用法(在 docker 目录下):
#   powershell -ExecutionPolicy Bypass -File .\gen-env.ps1
#   powershell -ExecutionPolicy Bypass -File .\gen-env.ps1 -Force   # 覆盖已存在的 .env
#
# 生成后请按需补充 AGENT_MODEL / AGENT_MODEL_API_KEY,然后 docker compose up --build
param([switch]$Force)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$envPath = Join-Path $PSScriptRoot '.env'
$exPath = Join-Path $PSScriptRoot '.env.example'
if (-not (Test-Path $exPath)) { throw "找不到 .env.example" }
if ((Test-Path $envPath) -and -not $Force) {
  throw ".env 已存在;如需覆盖请加 -Force"
}

function New-Hex([int]$bytes) {
  -join (1..$bytes | ForEach-Object { '{0:x2}' -f (Get-Random -Minimum 0 -Maximum 256) })
}
function New-B64([int]$bytes) {
  $b = New-Object 'byte[]' $bytes
  (New-Object Random).NextBytes($b)
  [Convert]::ToBase64String($b)
}

$jwt = New-Hex 32
$internal = New-Hex 32
$aes = New-B64 32
$dbPass = New-Hex 16
$rootPass = New-Hex 16
$pgPass = New-Hex 16

# 以 UTF-8(无 BOM)读写,避免中文注释乱码
$c = [System.IO.File]::ReadAllText($exPath, [System.Text.Encoding]::UTF8)
$c = $c -replace '(?m)^JWT_SECRET=.*$', "JWT_SECRET=$jwt"
$c = $c -replace '(?m)^APP_AES_KEY=.*$', "APP_AES_KEY=$aes"
$c = $c -replace '(?m)^INTERNAL_TOKEN=.*$', "INTERNAL_TOKEN=$internal"
$c = $c -replace '(?m)^SPRING_DATASOURCE_PASSWORD=.*$', "SPRING_DATASOURCE_PASSWORD=$dbPass"
$c = $c -replace '(?m)^MYSQL_PASSWORD=.*$', "MYSQL_PASSWORD=$dbPass"
$c = $c -replace '(?m)^MYSQL_ROOT_PASSWORD=.*$', "MYSQL_ROOT_PASSWORD=$rootPass"
$c = $c -replace '(?m)^POSTGRES_PASSWORD=.*$', "POSTGRES_PASSWORD=$pgPass"
[System.IO.File]::WriteAllText($envPath, $c, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "已生成 docker\.env(随机密钥已填入)。"
Write-Host "请按需补充 AGENT_MODEL / AGENT_MODEL_API_KEY,然后: docker compose up --build"
