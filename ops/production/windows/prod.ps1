[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('help','install','migrate','deploy','status','logs','restart','releases','rollback','backup','uninstall','auto-install','auto-deploy','auto-status','auto-remove')]
    [string]$Command = 'help',
    [switch]$WhatIf,
    [switch]$ConfirmCutover
)

$ErrorActionPreference = 'Stop'
$moduleRoot = Join-Path $PSScriptRoot 'modules'
foreach ($module in 'Production.Deploy','Production.Install','Production.Migrate','Production.Operations','Production.AutoDeploy','Production.Common') {
    Import-Module (Join-Path $moduleRoot "$module.psm1") -Force
}

$handlers = @{
    help = { Show-ProductionHelp }
    install = { Install-ProductionRuntime -WhatIf:$WhatIf }
    migrate = { Invoke-ProductionMigration -WhatIf:$WhatIf -ConfirmCutover:$ConfirmCutover }
    deploy = { Invoke-ProductionDeploy -WhatIf:$WhatIf }
    status = { Get-ProductionStatus }
    logs = { Watch-ProductionLogs }
    restart = { Restart-ProductionService -Verify }
    releases = { Get-ProductionReleases }
    rollback = { Invoke-ProductionRollback -WhatIf:$WhatIf }
    backup = { New-ProductionBackup }
    uninstall = { Uninstall-ProductionRuntime -WhatIf:$WhatIf }
    'auto-install' = { Install-AutoDeployTask -WhatIf:$WhatIf }
    'auto-deploy' = { Start-AutoDeployLoop }
    'auto-status' = { Get-AutoDeployStatus }
    'auto-remove' = { Remove-AutoDeployTask -WhatIf:$WhatIf }
}

& $handlers[$Command]
