param(
    [switch]$Web,
    [switch]$Api,
    [switch]$Agent,
    [switch]$All
)

# Fast dev apply: copy changed artifacts into running containers.
# No image rebuild. Use -Web / -Api / -Agent (or -All).
#   .\dev-apply.ps1 -Web     # npm build + copy dist into nginx
#   .\dev-apply.ps1 -Api     # maven build in container + copy app.jar + restart gateway
#   .\dev-apply.ps1 -Agent   # copy agent-service app/skills + restart agent-service
#   .\dev-apply.ps1 -All

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$appContainer = 'ai-workbench-app-1'
$agentContainer = 'ai-workbench-agent-service-1'
$mavenImage = 'maven:3.9-eclipse-temurin-21'

if (-not ($Web -or $Api -or $Agent -or $All)) {
    Write-Host 'Specify -Web, -Api, -Agent or -All'
    exit 1
}

if ($All) { $Web = $true; $Api = $true; $Agent = $true }

if ($Web) {
    Write-Host '[web] npm run build'
    npm --prefix "$root\frontend" run build
    if ($LASTEXITCODE -ne 0) { throw 'frontend build failed' }
    Write-Host '[web] copy dist -> nginx'
    docker cp "$root\frontend\dist\." "${appContainer}:/usr/share/nginx/html/"
}

if ($Api) {
    Write-Host '[api] maven package (container, cached m2)'
    docker run --rm -v "$root\backend:/build" -v "aiwb-m2:/root/.m2" -w /build `
        $mavenImage mvn -B -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'backend build failed' }
    Write-Host '[api] copy app.jar + restart gateway'
    docker cp "$root\backend\target\app.jar" "${appContainer}:/app/app.jar"
    docker exec $appContainer supervisorctl restart gateway
}

if ($Agent) {
    Write-Host '[agent] copy app/skills + restart'
    docker cp "$root\agent-service\app\." "${agentContainer}:/app/app/"
    docker cp "$root\agent-service\skills\." "${agentContainer}:/app/skills/"
    docker restart $agentContainer | Out-Null
}

Write-Host 'done'
