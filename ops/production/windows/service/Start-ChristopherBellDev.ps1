$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath('C:\ProgramData\christopherbell.dev')
$serviceRoot = [IO.Path]::GetFullPath((Join-Path $root 'service'))
$modulePath = Join-Path $serviceRoot 'Production.WriterStart.psm1'
$manifestPath = Join-Path $serviceRoot 'Production.WriterStart.bundle.json'
$winSwPath = Join-Path $serviceRoot 'ChristopherBellDev.exe'
$serviceXmlPath = Join-Path $serviceRoot 'ChristopherBellDev.xml'
function Assert-InstalledWriterStartGuardNotReparse {
    param([Parameter(Mandatory)][string]$Path)
    $fullPath = [IO.Path]::GetFullPath($Path)
    $pathRoot = [IO.Path]::GetPathRoot($fullPath)
    if ([string]::IsNullOrWhiteSpace($pathRoot)) {
        throw 'Installed writer-start guard path must be absolute.'
    }
    $current = $pathRoot
    $relative = $fullPath.Substring($pathRoot.Length)
    foreach ($segment in @($relative -split '[\\/]' | Where-Object { $_ })) {
        $current = [IO.Path]::Combine($current, $segment)
        $item = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'Installed writer-start guard path traversal contains a reparse point.'
        }
    }
}
function Assert-InstalledWriterStartGuardAcl {
    param([Parameter(Mandatory)][string]$Path)
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
function Assert-InstalledWriterStartServiceDirectoryAcl {
    param([Parameter(Mandatory)][string]$Path)
    $acl = Get-Acl -LiteralPath $Path -ErrorAction Stop
    $owner = $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
    $rules = @($acl.GetAccessRules(
        $true, $false, [Security.Principal.SecurityIdentifier]))
    if (-not $acl.AreAccessRulesProtected -or
        @('S-1-5-18','S-1-5-32-544') -notcontains $owner -or
        $rules.Count -ne 3) {
        throw 'Installed writer-start service directory ACL is not protected.'
    }
    $expected = @{
        'S-1-5-18' = [Security.AccessControl.FileSystemRights]::FullControl
        'S-1-5-32-544' = [Security.AccessControl.FileSystemRights]::FullControl
        'S-1-5-19' = (
            [Security.AccessControl.FileSystemRights]::ReadAndExecute -bor
            [Security.AccessControl.FileSystemRights]::Synchronize)
    }
    $seen = @{}
    foreach ($rule in $rules) {
        $identity = $rule.IdentityReference.Value
        if (-not $expected.ContainsKey($identity) -or $seen.ContainsKey($identity) -or
            $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            $rule.InheritanceFlags -ne [Security.AccessControl.InheritanceFlags]::None -or
            $rule.PropagationFlags -ne [Security.AccessControl.PropagationFlags]::None -or
            $rule.FileSystemRights -ne $expected[$identity]) {
            throw 'Installed writer-start service directory ACL grants untrusted access.'
        }
        $seen[$identity] = $true
    }
}
try {
    Assert-InstalledWriterStartGuardNotReparse -Path $root
    Assert-InstalledWriterStartGuardNotReparse -Path $serviceRoot
    Assert-InstalledWriterStartServiceDirectoryAcl -Path $serviceRoot
    foreach ($path in @($PSCommandPath,$modulePath,$manifestPath,$winSwPath,$serviceXmlPath)) {
        Assert-InstalledWriterStartGuardNotReparse -Path $path
        Assert-InstalledWriterStartGuardAcl -Path $path
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -ErrorAction Stop |
        ConvertFrom-Json -ErrorAction Stop
    $properties = @($manifest.PSObject.Properties.Name)
    foreach ($name in @(
        'version','launcherSha256','moduleSha256','winSwSha256','serviceXmlSha256')) {
        if (-not ($properties -ccontains $name)) { throw 'Invalid guard manifest properties.' }
    }
    if ($properties.Count -ne 5 -or
        ($manifest.version -isnot [int] -and $manifest.version -isnot [long]) -or
        [int]$manifest.version -ne 2 -or
        $manifest.launcherSha256 -isnot [string] -or
        [string]$manifest.launcherSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        $manifest.moduleSha256 -isnot [string] -or
        [string]$manifest.moduleSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        $manifest.winSwSha256 -isnot [string] -or
        [string]$manifest.winSwSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        $manifest.serviceXmlSha256 -isnot [string] -or
        [string]$manifest.serviceXmlSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne
            [string]$manifest.launcherSha256 -or
        (Get-FileHash -LiteralPath $modulePath -Algorithm SHA256).Hash.ToLowerInvariant() -cne
            [string]$manifest.moduleSha256 -or
        (Get-FileHash -LiteralPath $winSwPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne
            [string]$manifest.winSwSha256 -or
        (Get-FileHash -LiteralPath $serviceXmlPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne
            [string]$manifest.serviceXmlSha256) {
        throw 'Installed writer-start guard bundle does not match its protected manifest.'
    }
} catch {
    throw [System.InvalidOperationException]::new(
        'Installed writer-start guard bundle verification failed; writer start is blocked.',
        $_.Exception)
}
Import-Module $modulePath -Force
$config = Get-Content -LiteralPath (Join-Path $root 'config\deploy.json') -Raw |
    ConvertFrom-Json
Assert-ProductionFixedRootBoundary -Config $config -FixedRoot $root | Out-Null
Assert-ProductionWriterStartAllowed -Config $config -FixedRoot $root
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
