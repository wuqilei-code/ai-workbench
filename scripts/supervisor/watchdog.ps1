param(
    [int]$IntervalSeconds = 300,
    [int]$MaxRunsPerHour = 6
)

$root       = Split-Path -Parent $PSScriptRoot
$stateFile  = Join-Path $PSScriptRoot "state.json"
$logFile    = Join-Path $PSScriptRoot "watchdog.log"
$stopFile   = Join-Path $PSScriptRoot "STOP"
$promptFile = Join-Path $PSScriptRoot "prompt.txt"

function Write-Log([string]$msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $msg
    Add-Content -LiteralPath $logFile -Value $line -Encoding utf8
}

function Read-State {
    if (Test-Path -LiteralPath $stateFile) {
        try { return Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json } catch {}
    }
    return [pscustomobject]@{
        lastCommit = ""
        runTimes   = @()
    }
}

function Save-State($state) {
    $state | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding utf8
}

$prompt = Get-Content -LiteralPath $promptFile -Raw -Encoding utf8

Write-Log "watchdog started (interval=${IntervalSeconds}s, maxRuns/hour=$MaxRunsPerHour)"

while ($true) {
    if (Test-Path -LiteralPath $stopFile) {
        Write-Log "STOP file detected, exiting"
        Remove-Item -LiteralPath $stopFile -Force
        break
    }

    try {
        $head = (& git -C $root rev-parse HEAD 2>$null)
        if ($LASTEXITCODE -eq 0 -and $head) {
            $state = Read-State
            $cutoff = (Get-Date).AddHours(-1)
            $state.runTimes = @($state.runTimes | ForEach-Object { [datetime]$_ } | Where-Object { $_ -gt $cutoff })

            if ($head -ne $state.lastCommit) {
                if ($state.runTimes.Count -ge $MaxRunsPerHour) {
                    Write-Log "rate limit reached ($($state.runTimes.Count)/hour), skip trigger for $head"
                    $state.lastCommit = $head
                    Save-State $state
                } else {
                    Write-Log "new commit detected: $head, invoking supervisor session"
                    & opencode run $prompt 2>&1 | ForEach-Object { "$_" } | Add-Content -LiteralPath $logFile -Encoding utf8
                    Write-Log "supervisor run finished"
                    $state.lastCommit = $head
                    $state.runTimes = @($state.runTimes + (Get-Date))
                    Save-State $state
                }
            }
        }
    } catch {
        Write-Log "error: $($_.Exception.Message)"
    }

    Start-Sleep -Seconds $IntervalSeconds
}
