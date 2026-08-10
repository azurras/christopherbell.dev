$ErrorActionPreference = 'Stop'
$root = 'C:\ProgramData\christopherbell.dev'
$config = Get-Content -LiteralPath (Join-Path $root 'config\deploy.json') -Raw | ConvertFrom-Json
$serviceRoot = Join-Path $root 'service'
$modulePath = Join-Path $serviceRoot 'Production.WriterStart.psm1'
$manifestPath = Join-Path $serviceRoot 'Production.WriterStart.bundle.json'
function Assert-InstalledWriterStartGuardAcl {
    param([Parameter(Mandatory)][string]$Path)
    $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'Installed writer-start guard path is a reparse point.'
    }
    $acl = Get-Acl -LiteralPath $Path -ErrorAction Stop
    $allowed = @('S-1-5-18','S-1-5-32-544')
    $owner = $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
    $rules = @($acl.GetAccessRules(
        $true, $false, [Security.Principal.SecurityIdentifier]))
    if (-not $acl.AreAccessRulesProtected -or
        $allowed -notcontains $owner -or
        $rules.Count -ne 2) {
        throw 'Installed writer-start guard ACL is not protected.'
    }
    foreach ($rule in $rules) {
        $fullControl = [Security.AccessControl.FileSystemRights]::FullControl
        if ($allowed -notcontains $rule.IdentityReference.Value -or
            $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            ($rule.FileSystemRights -band $fullControl) -ne $fullControl) {
            throw 'Installed writer-start guard ACL grants untrusted access.'
        }
    }
}
try {
    foreach ($path in @($PSCommandPath,$modulePath,$manifestPath)) {
        Assert-InstalledWriterStartGuardAcl -Path $path
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -ErrorAction Stop |
        ConvertFrom-Json -ErrorAction Stop
    $properties = @($manifest.PSObject.Properties.Name)
    foreach ($name in @('version','launcherSha256','moduleSha256')) {
        if (-not ($properties -ccontains $name)) { throw 'Invalid guard manifest properties.' }
    }
    if ($properties.Count -ne 3 -or
        ($manifest.version -isnot [int] -and $manifest.version -isnot [long]) -or
        [int]$manifest.version -ne 1 -or
        $manifest.launcherSha256 -isnot [string] -or
        [string]$manifest.launcherSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        $manifest.moduleSha256 -isnot [string] -or
        [string]$manifest.moduleSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne
            [string]$manifest.launcherSha256 -or
        (Get-FileHash -LiteralPath $modulePath -Algorithm SHA256).Hash.ToLowerInvariant() -cne
            [string]$manifest.moduleSha256) {
        throw 'Installed writer-start guard bundle does not match its protected manifest.'
    }
} catch {
    throw [System.InvalidOperationException]::new(
        'Installed writer-start guard bundle verification failed; writer start is blocked.',
        $_.Exception)
}
Import-Module $modulePath -Force
Assert-ProductionWriterStartAllowed -Config $config
$sensorProperty = $config.PSObject.Properties['sensorLibrariesEnabled']
if (-not $sensorProperty -or $sensorProperty.Value -isnot [bool]) {
    throw 'deploy.json sensorLibrariesEnabled must be a Boolean.'
}
$sensorLibrariesEnabled = if ($sensorProperty.Value) { 'true' } else { 'false' }
[Environment]::SetEnvironmentVariable(
    'COMMAND_CENTER_SENSOR_LIBRARIES_ENABLED', $sensorLibrariesEnabled, 'Process')
[Environment]::SetEnvironmentVariable(
    'APP_SHARED_FOLDER_ENABLED', 'false', 'Process')
$allowed = @(
    'APP_JWT_SECRET',
    'APP_MAIL_ENABLED',
    'RESEND_API_KEY',
    'APP_MAIL_FROM',
    'SPRING_MONGODB_URI',
    'APP_SHARED_FOLDER_ENABLED'
)
foreach ($line in Get-Content -LiteralPath (Join-Path $root 'config\app.env')) {
    if ($line -match '^([A-Z0-9_]+)=(.*)$' -and $allowed -contains $Matches[1]) {
        if ($Matches[1] -in @('APP_MAIL_ENABLED','APP_SHARED_FOLDER_ENABLED') -and
            $Matches[2] -notin @('true','false')) {
            throw "$($Matches[1]) must be a Boolean."
        }
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
& $config.javaExe `
    '-Xrs' `
    '--enable-native-access=ALL-UNNAMED' `
    '-jar' `
    (Join-Path $root 'current\app.jar') `
    '--spring.profiles.active=prod' `
    "--server.port=$($config.productionPort)"
exit $LASTEXITCODE
