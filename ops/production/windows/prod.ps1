[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('help','install','deploy','status','logs','restart','releases','rollback','backup','verify-startup','uninstall','auto-install','auto-deploy','auto-status','auto-remove','sensor-install','sensor-status','sensor-enable','sensor-disable')]
    [string]$Command = 'help',
    [switch]$WhatIf,
    [string]$CloudflareTokenPath
)

$ErrorActionPreference = 'Stop'
$moduleRoot = Join-Path $PSScriptRoot 'modules'
Import-Module (Join-Path $moduleRoot 'Production.Common.psm1') -Global -Force
foreach ($module in 'Production.Deploy','Production.Install','Production.Sensors','Production.Operations','Production.AutoDeploy') {
    Import-Module (Join-Path $moduleRoot "$module.psm1") -Force
}

$handlers = @{
    help = { Show-ProductionHelp }
    install = { Install-ProductionRuntime -WhatIf:$WhatIf -CloudflareTokenPath $CloudflareTokenPath }
    deploy = { Invoke-ProductionDeploy -WhatIf:$WhatIf }
    status = { Get-ProductionStatus }
    logs = { Watch-ProductionLogs }
    restart = { Restart-ProductionService -Verify }
    releases = { Get-ProductionReleases }
    rollback = { Invoke-ProductionRollback -WhatIf:$WhatIf }
    backup = { New-ProductionBackup }
    'verify-startup' = { Test-ProductionStartup }
    uninstall = { Uninstall-ProductionRuntime -WhatIf:$WhatIf }
    'auto-install' = { Install-AutoDeployTask -WhatIf:$WhatIf }
    'auto-deploy' = { Start-AutoDeployLoop }
    'auto-status' = { Get-AutoDeployStatus }
    'auto-remove' = { Remove-AutoDeployTask -WhatIf:$WhatIf }
    'sensor-install' = { Install-PawnIoProvider -WhatIf:$WhatIf }
    'sensor-status' = { Get-ProductionSensorStatus }
    'sensor-enable' = { Set-ProductionSensorState -Enabled $true -WhatIf:$WhatIf }
    'sensor-disable' = { Set-ProductionSensorState -Enabled $false -WhatIf:$WhatIf }
}

& $handlers[$Command]
