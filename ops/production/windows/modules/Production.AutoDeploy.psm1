Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function New-AutoDeployState {
    [pscustomobject][ordered]@{ lastCheckedAt=$null; remoteSha=$null; attemptedSha=$null; successfulSha=$null; failedSha=$null; failedAt=$null; error=$null }
}

function Read-AutoDeployState {
    param($Config)
    $path = Join-Path $Config.programDataRoot 'state\auto-deploy.json'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return New-AutoDeployState }
    try { return Get-Content -LiteralPath $path -Raw | ConvertFrom-Json }
    catch { throw 'Automatic deployment state is invalid JSON.' }
}

function Write-AutoDeployState {
    param($Config, $State)
    $path = Join-Path $Config.programDataRoot 'state\auto-deploy.json'
    New-Item -ItemType Directory -Force (Split-Path -Parent $path) | Out-Null
    $temporary = "$path.$PID.tmp"
    try {
        $State | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $temporary -Encoding utf8
        Move-Item -LiteralPath $temporary -Destination $path -Force
    } finally { if (Test-Path $temporary) { Remove-Item $temporary -Force -ErrorAction SilentlyContinue } }
}

function Get-RemoteMainSha {
    param($Config)
    $output = Invoke-CheckedProcess 'git.exe' @('-C',$Config.repositoryPath,'ls-remote',$Config.remote,"refs/heads/$($Config.branch)") $Config.repositoryPath
    $sha = (($output.Trim() -split '\s+')[0]).ToLowerInvariant()
    if ($sha -notmatch '^[0-9a-f]{40}$') { throw 'Remote main returned an invalid SHA.' }
    return $sha
}

function Get-ActiveReleaseSha {
    param($Config)
    $metadata = Join-Path $Config.programDataRoot 'current\release.json'
    if (-not (Test-Path -LiteralPath $metadata -PathType Leaf)) { return $null }
    $sha = (Get-Content -LiteralPath $metadata -Raw | ConvertFrom-Json).sha
    if ($sha -notmatch '^[0-9a-f]{40}$') { throw 'Active release metadata contains an invalid SHA.' }
    return $sha
}

function Invoke-AutoDeployOnce {
    param($Config = (Read-ProductionConfig))
    $state = Read-AutoDeployState $Config
    $remote = Get-RemoteMainSha $Config
    $now = (Get-Date).ToUniversalTime()
    $state.lastCheckedAt = $now.ToString('o')
    $state.remoteSha = $remote
    if ($remote -eq (Get-ActiveReleaseSha $Config)) {
        $state.successfulSha = $remote
        $state.error = $null
        Write-AutoDeployState $Config $state
        return
    }
    if ($state.failedSha -eq $remote -and $state.failedAt) {
        $retryAt = ([datetime]$state.failedAt).AddSeconds([int]$Config.autoDeployFailureBackoffSeconds)
        if ($now -lt $retryAt) { Write-AutoDeployState $Config $state; return }
    }
    $state.attemptedSha = $remote
    Write-AutoDeployState $Config $state
    try {
        Invoke-ProductionDeploy
        $active = Get-ActiveReleaseSha $Config
        if (-not $active) { throw 'Deployment completed without valid active release metadata.' }
        $state.successfulSha = $active
        $state.failedSha = $null
        $state.failedAt = $null
        $state.error = $null
    } catch {
        $state.failedSha = $remote
        $state.failedAt = (Get-Date).ToUniversalTime().ToString('o')
        $state.error = $_.Exception.Message
    } finally { Write-AutoDeployState $Config $state }
}

function Start-AutoDeployLoop {
    $config = Read-ProductionConfig
    while ($true) {
        try { Invoke-AutoDeployOnce $config }
        catch {
            $log = Join-Path $config.programDataRoot 'logs\auto-deploy-errors.log'
            "$(Get-Date -Format o) $($_.Exception.Message)" | Add-Content -LiteralPath $log
        }
        Start-Sleep -Seconds ([int]$config.autoDeployPollSeconds)
    }
}

function Install-AutoDeployTask {
    [CmdletBinding()]
    param([switch]$WhatIf)
    Assert-Administrator
    $config = Read-ProductionConfig
    if ($WhatIf) { Write-Output 'Would register and start the ChristopherBellAutoDeploy startup task.'; return }
    $tools = Join-Path $config.programDataRoot 'tools'
    New-Item -ItemType Directory -Force $tools | Out-Null
    Copy-Item (Join-Path $PSScriptRoot '..\*') $tools -Recurse -Force
    $action = New-ScheduledTaskAction -Execute 'pwsh.exe' -Argument "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$tools\prod.ps1`" auto-deploy"
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([timespan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    Register-ScheduledTask -TaskName 'ChristopherBellAutoDeploy' -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
    Start-ScheduledTask -TaskName 'ChristopherBellAutoDeploy'
}

function Remove-AutoDeployTask {
    [CmdletBinding()]
    param([switch]$WhatIf)
    Assert-Administrator
    if ($WhatIf) { Write-Output 'Would remove the ChristopherBellAutoDeploy task.'; return }
    Stop-ScheduledTask -TaskName 'ChristopherBellAutoDeploy' -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName 'ChristopherBellAutoDeploy' -Confirm:$false -ErrorAction SilentlyContinue
}

function Get-AutoDeployStatus {
    $config = Read-ProductionConfig
    [pscustomobject]@{
        Task = Get-ScheduledTask -TaskName 'ChristopherBellAutoDeploy' -ErrorAction SilentlyContinue
        State = Read-AutoDeployState $config
    }
}

Export-ModuleMember -Function New-AutoDeployState,Read-AutoDeployState,Write-AutoDeployState,Get-RemoteMainSha,Get-ActiveReleaseSha,Invoke-AutoDeployOnce,Start-AutoDeployLoop,Install-AutoDeployTask,Remove-AutoDeployTask,Get-AutoDeployStatus
