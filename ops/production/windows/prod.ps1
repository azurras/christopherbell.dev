[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('help','install','deploy','status','logs','restart','releases','rollback','backup',
        'postgres-prepare','postgres-install','postgres-bootstrap','postgres-status','postgres-backup',
        'postgres-restore-check','postgres-pgadmin',
        'postgres-shadow','postgres-reconcile','postgres-cutover',
        'mongo-inventory','mongo-consolidation-preview','mongo-consolidate',
        'mongo-consolidation-rollback','verify-startup','uninstall','auto-install',
        'auto-deploy','auto-status','auto-remove','sensor-install','sensor-status',
        'sensor-enable','sensor-disable')]
    [string]$Command = 'help',
    [switch]$WhatIf,
    [switch]$ConfirmDomainCollectionCutover,
    [switch]$ConfirmDomainCollectionRollback,
    [switch]$ConfirmPostgreSqlPreparation,
    [switch]$ConfirmPostgreSqlBootstrap,
    [switch]$ConfirmPostgreSqlCutover,
    [string]$CloudflareTokenPath
)

$ErrorActionPreference = 'Stop'
$moduleRoot = Join-Path $PSScriptRoot 'modules'
Import-Module (Join-Path $moduleRoot 'Production.Common.psm1') -Global -Force
Import-Module (Join-Path $moduleRoot 'Production.WriterStart.psm1') -Global -Force
foreach ($module in 'Production.MusicRuntime','Production.Deploy','Production.SharedFolder',
    'Production.Install','Production.Sensors','Production.Operations','Production.AutoDeploy',
    'Production.DomainCollections','Production.PostgreSql','Production.PostgreSqlMigration') {
    Import-Module (Join-Path $moduleRoot "$module.psm1") -Force
}

function Invoke-ProductionCommand {
    param(
        [Parameter(Mandatory)][string]$Command,
        [switch]$WhatIf,
        [switch]$ConfirmDomainCollectionCutover,
        [switch]$ConfirmDomainCollectionRollback,
        [switch]$ConfirmPostgreSqlPreparation,
        [switch]$ConfirmPostgreSqlBootstrap,
        [switch]$ConfirmPostgreSqlCutover,
        [string]$CloudflareTokenPath
    )

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
        'postgres-prepare' = {
            if (-not $WhatIf -and -not $ConfirmPostgreSqlPreparation) {
                throw 'PostgreSQL preparation requires explicit confirmation.'
            }
            Initialize-ProductionPostgreSqlPreparation -WhatIf:$WhatIf
        }
        'postgres-install' = {
            Install-ProductionPostgreSql -Config (Read-ProductionConfig) -WhatIf:$WhatIf
        }
        'postgres-bootstrap' = {
            if (-not $WhatIf -and -not $ConfirmPostgreSqlBootstrap) {
                throw 'PostgreSQL bootstrap requires explicit confirmation.'
            }
            Initialize-ProductionPostgreSql -WhatIf:$WhatIf
        }
        'postgres-status' = { Get-ProductionPostgreSqlStatus }
        'postgres-backup' = { New-ProductionPostgreSqlBackup -WhatIf:$WhatIf }
        'postgres-restore-check' = { Test-ProductionPostgreSqlRestore -WhatIf:$WhatIf }
        'postgres-pgadmin' = { Install-ProductionPgAdmin -WhatIf:$WhatIf }
        'postgres-shadow' = { Invoke-ProductionPostgreSqlShadow -WhatIf:$WhatIf }
        'postgres-reconcile' = { Invoke-ProductionPostgreSqlReconcile -WhatIf:$WhatIf }
        'postgres-cutover' = {
            if (-not $WhatIf -and -not $ConfirmPostgreSqlCutover) {
                throw 'PostgreSQL authority cutover requires explicit confirmation.'
            }
            Invoke-ProductionPostgreSqlCutover `
                -ConfirmPostgreSqlCutover:$ConfirmPostgreSqlCutover -WhatIf:$WhatIf
        }
        'mongo-inventory' = {
            Get-ProductionMongoCollectionInventory | ConvertTo-Json -Depth 100
        }
        'mongo-consolidation-preview' = {
            Get-ProductionDomainCollectionPreview
        }
        'mongo-consolidate' = {
            if (-not $WhatIf -and -not $ConfirmDomainCollectionCutover) {
                throw 'Domain collection consolidation requires explicit confirmation.'
            }
            Invoke-ProductionDomainCollectionCutover `
                -Confirm:$ConfirmDomainCollectionCutover `
                -WhatIf:$WhatIf
        }
        'mongo-consolidation-rollback' = {
            if (-not $WhatIf -and -not $ConfirmDomainCollectionRollback) {
                throw 'Domain collection rollback requires explicit confirmation.'
            }
            Invoke-ProductionDomainCollectionRollback `
                -Confirm:$ConfirmDomainCollectionRollback `
                -WhatIf:$WhatIf
        }
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
}

Invoke-ProductionCommand -Command $Command -WhatIf:$WhatIf `
    -ConfirmDomainCollectionCutover:$ConfirmDomainCollectionCutover `
    -ConfirmDomainCollectionRollback:$ConfirmDomainCollectionRollback `
    -ConfirmPostgreSqlPreparation:$ConfirmPostgreSqlPreparation `
    -ConfirmPostgreSqlBootstrap:$ConfirmPostgreSqlBootstrap `
    -ConfirmPostgreSqlCutover:$ConfirmPostgreSqlCutover `
    -CloudflareTokenPath $CloudflareTokenPath
