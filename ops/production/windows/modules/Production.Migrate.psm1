Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Production.Common.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'Production.Install.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'Production.Deploy.psm1') -Force

function Get-MongoInventory {
    param([string]$MongoShell, [string]$Uri, [string]$Database)
    $javascript = @'
const d = db.getSiblingDB(process.env.INVENTORY_DATABASE);
const result = {};
d.getCollectionNames().sort().forEach(name => {
  result[name] = {
    count: d.getCollection(name).countDocuments({}),
    indexes: d.getCollection(name).getIndexes()
      .map(i => ({name:i.name,key:i.key,unique:!!i.unique}))
      .sort((a,b) => a.name.localeCompare(b.name))
  };
});
print(EJSON.stringify(result));
'@
    $previous = $env:INVENTORY_DATABASE
    try {
        $env:INVENTORY_DATABASE = $Database
        $output = & $MongoShell $Uri '--quiet' '--eval' $javascript
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($output -join ''))) {
            throw "MongoDB inventory failed for $Database."
        }
        return (($output -join "`n").Trim())
    } finally { $env:INVENTORY_DATABASE = $previous }
}

function Assert-MongoInventoryEqual {
    param([string]$Source, [string]$Target)
    if ($Source -cne $Target) { throw 'MongoDB collection/count/index inventory mismatch.' }
}

function New-MigrationBackup {
    param($Config)
    New-Item -ItemType Directory -Force $Config.backupRoot | Out-Null
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
    $archive = Join-Path $Config.backupRoot "christopherbell-pre-native-$stamp.archive.gz"
    $wslArchive = (Invoke-CheckedProcess 'wsl.exe' @('-d',$Config.wslDistro,'--exec','wslpath','-u',$archive) $Config.repositoryPath).Trim()
    if ([string]::IsNullOrWhiteSpace($wslArchive)) { throw 'Could not map the migration backup path into WSL.' }
    Invoke-CheckedProcess 'wsl.exe' @('-d',$Config.wslDistro,'--exec','mongodump','--uri','mongodb://127.0.0.1:27017','--db','christopherbell','--archive',$wslArchive,'--gzip') $Config.repositoryPath | Out-Null
    if (-not (Test-Path $archive) -or (Get-Item $archive).Length -eq 0) { throw 'Migration backup is empty.' }
    $hash = (Get-FileHash $archive -Algorithm SHA256).Hash
    [ordered]@{ archive=$archive; sha256=$hash; createdAt=(Get-Date).ToUniversalTime().ToString('o') } | ConvertTo-Json | Set-Content "$archive.sha256.json"
    return $archive
}

function Get-WslMongoInventory {
    param($Config, [string]$Database = 'christopherbell')
    $javascript = @'
const d = db.getSiblingDB(process.env.INVENTORY_DATABASE);
const result = {};
d.getCollectionNames().sort().forEach(name => {
  result[name] = {
    count: d.getCollection(name).countDocuments({}),
    indexes: d.getCollection(name).getIndexes()
      .map(i => ({name:i.name,key:i.key,unique:!!i.unique}))
      .sort((a,b) => a.name.localeCompare(b.name))
  };
});
print(EJSON.stringify(result));
'@
    $output = Invoke-CheckedProcess 'wsl.exe' @('-d',$Config.wslDistro,'--exec','env',"INVENTORY_DATABASE=$Database",'mongosh','mongodb://127.0.0.1:27017','--quiet','--eval',$javascript) $Config.repositoryPath
    if ([string]::IsNullOrWhiteSpace($output)) { throw "WSL MongoDB inventory failed for $Database." }
    return $output.Trim()
}

function Test-MongoArchive {
    param($Config, [string]$Archive)
    Invoke-CheckedProcess (Join-Path $Config.mongoToolsPath 'mongorestore.exe') @('--archive',$Archive,'--gzip','--dryRun') $Config.repositoryPath | Out-Null
}

function Invoke-WslCommand {
    param($Config, [string]$Command)
    Invoke-CheckedProcess 'wsl.exe' @('-d',$Config.wslDistro,'--','bash','-lc',$Command) $Config.repositoryPath | Out-Null
}

function Invoke-WslRootCommand {
    param($Config, [string]$Command)
    Invoke-CheckedProcess 'wsl.exe' @('-d',$Config.wslDistro,'-u','root','--','bash','-lc',$Command) $Config.repositoryPath | Out-Null
}

function Restore-MongoDatabase {
    param($Config, [string]$Archive, [string]$Database, [switch]$Drop)
    $arguments = @('--uri','mongodb://127.0.0.1:27017','--archive',$Archive,'--gzip',"--nsFrom=christopherbell.*","--nsTo=$Database.*")
    if ($Drop) { $arguments += '--drop' }
    Invoke-CheckedProcess (Join-Path $Config.mongoToolsPath 'mongorestore.exe') $arguments $Config.repositoryPath | Out-Null
}

function Invoke-ProductionMigration {
    [CmdletBinding()]
    param([switch]$WhatIf, [switch]$ConfirmCutover)
    Assert-Administrator
    $config = Read-ProductionConfig
    $lock = Enter-DeploymentLock (Join-Path $config.programDataRoot 'locks\migration.lock')
    $wslStopped = $false
    $wslMongoStopped = $false
    try {
        $archive = New-MigrationBackup $config
        Test-MongoArchive $config $archive
        $source = Get-WslMongoInventory $config 'christopherbell'
        $source | Set-Content "$archive.inventory.json" -Encoding utf8
        if ($WhatIf) { Write-Output "Verified backup and source inventory at $archive; no cutover performed."; return }
        if (-not $ConfirmCutover) { throw 'Migration cutover requires -ConfirmCutover after the verified dry run.' }
        $sha = Resolve-OriginMainRelease $config
        $release = New-ReleaseFromOriginMain $config $sha
        Invoke-WslCommand $config $config.wslWebsiteStopCommand
        $wslStopped = $true
        Invoke-WslRootCommand $config $config.wslMongoStopCommand
        $wslMongoStopped = $true
        Start-Service MongoDB
        Restore-MongoDatabase $config $archive 'christopherbell_restore_check' -Drop
        $validation = Get-MongoInventory $config.mongoShellExe 'mongodb://127.0.0.1:27017' 'christopherbell_restore_check'
        Assert-MongoInventoryEqual $source $validation
        Test-CandidateRelease $config $release 'christopherbell_restore_check'
        Restore-MongoDatabase $config $archive 'christopherbell' -Drop
        $final = Get-MongoInventory $config.mongoShellExe 'mongodb://127.0.0.1:27017' 'christopherbell'
        Assert-MongoInventoryEqual $source $final
        Test-CandidateRelease $config $release 'christopherbell'
    } catch {
        if ($wslMongoStopped) {
            Stop-Service MongoDB -ErrorAction SilentlyContinue
            try { Invoke-WslRootCommand $config $config.wslMongoStartCommand } catch { }
        }
        if ($wslStopped) { try { Invoke-WslCommand $config $config.wslWebsiteStartCommand } catch { } }
        throw
    } finally { $lock.Dispose() }
}

Export-ModuleMember -Function Get-MongoInventory,Get-WslMongoInventory,Assert-MongoInventoryEqual,New-MigrationBackup,Test-MongoArchive,Restore-MongoDatabase,Invoke-ProductionMigration
