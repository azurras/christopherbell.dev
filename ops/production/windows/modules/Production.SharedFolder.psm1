Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:LocalServiceSid = 'S-1-5-19'
$script:SystemSid = 'S-1-5-18'
$script:AdministratorsSid = 'S-1-5-32-544'

function Get-SharedFolderRuntimePaths {
    [CmdletBinding()]
    param(
        [string]$SharedRoot = 'A:\Shared',
        [string]$SystemRoot = 'A:\Shared-System'
    )

    $canonicalSharedRoot = [IO.Path]::GetFullPath($SharedRoot).TrimEnd('\')
    $canonicalSystemRoot = [IO.Path]::GetFullPath($SystemRoot).TrimEnd('\')
    return [pscustomobject]@{
        SharedRoot = $canonicalSharedRoot
        SystemRoot = $canonicalSystemRoot
        JobRoot = Join-Path $canonicalSystemRoot 'shared-folder-media-jobs'
        StagingRoot = Join-Path $canonicalSystemRoot 'shared-folder-media-staging'
        PartialRoot = Join-Path $canonicalSystemRoot 'shared-folder-media-partial'
        CacheRoot = Join-Path $canonicalSystemRoot 'shared-folder-media-cache'
        StatusRoot = Join-Path $canonicalSystemRoot 'shared-folder-media-status'
        CancellationRoot = Join-Path $canonicalSystemRoot 'shared-folder-media-cancel'
        LogRoot = Join-Path $canonicalSystemRoot 'shared-folder-media-logs'
        LockRoot = Join-Path $canonicalSystemRoot 'shared-folder-media-locks'
        ToolRoot = Join-Path $canonicalSystemRoot 'shared-folder-media-tools'
    }
}

function New-SharedFolderRuntimeDirectories {
    [CmdletBinding()]
    param(
        [string]$SharedRoot = 'A:\Shared',
        [string]$SystemRoot = 'A:\Shared-System'
    )

    $paths = Get-SharedFolderRuntimePaths -SharedRoot $SharedRoot -SystemRoot $SystemRoot
    foreach ($path in $paths.PSObject.Properties.Value) {
        if (Test-Path -LiteralPath $path) {
            $item = Get-Item -LiteralPath $path -Force
            if (-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                throw "Shared-folder runtime path must be a non-reparse directory: $path"
            }
        } else {
            New-Item -ItemType Directory -Path $path -Force | Out-Null
        }
    }
    return $paths
}

function New-SharedFolderAcl {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][ValidateSet('ReadAndExecute', 'Modify')][string]$WorkerAccess
    )

    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $administrators = [Security.Principal.SecurityIdentifier]::new($script:AdministratorsSid)
    $system = [Security.Principal.SecurityIdentifier]::new($script:SystemSid)
    $worker = [Security.Principal.SecurityIdentifier]::new($script:LocalServiceSid)
    $acl.SetOwner($administrators)
    $inheritance = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    $propagation = [Security.AccessControl.PropagationFlags]::None
    $allow = [Security.AccessControl.AccessControlType]::Allow
    foreach ($identity in @($system, $administrators)) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            $identity,
            [Security.AccessControl.FileSystemRights]::FullControl,
            $inheritance,
            $propagation,
            $allow)
        [void]$acl.AddAccessRule($rule)
    }
    $workerRights = if ($WorkerAccess -eq 'Modify') {
        [Security.AccessControl.FileSystemRights]::Modify
    } else {
        [Security.AccessControl.FileSystemRights]::ReadAndExecute
    }
    $workerRule = [Security.AccessControl.FileSystemAccessRule]::new(
        $worker,
        $workerRights,
        $inheritance,
        $propagation,
        $allow)
    [void]$acl.AddAccessRule($workerRule)
    return $acl
}

function New-SharedFolderWorkerFileAcl {
    [CmdletBinding()]
    param()

    $acl = [Security.AccessControl.FileSecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $administrators = [Security.Principal.SecurityIdentifier]::new($script:AdministratorsSid)
    $system = [Security.Principal.SecurityIdentifier]::new($script:SystemSid)
    $worker = [Security.Principal.SecurityIdentifier]::new($script:LocalServiceSid)
    $acl.SetOwner($administrators)
    $allow = [Security.AccessControl.AccessControlType]::Allow
    foreach ($identity in @($system, $administrators)) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            $identity,
            [Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.AccessControlType]::Allow)
        [void]$acl.AddAccessRule($rule)
    }
    $workerRule = [Security.AccessControl.FileSystemAccessRule]::new(
        $worker,
        [Security.AccessControl.FileSystemRights]::ReadAndExecute,
        $allow)
    [void]$acl.AddAccessRule($workerRule)
    return $acl
}

function Set-SharedFolderRuntimeAcls {
    [CmdletBinding()]
    param(
        [string]$SharedRoot = 'A:\Shared',
        [string]$SystemRoot = 'A:\Shared-System',
        [scriptblock]$SetAclAction = { param($Path, $Acl) Set-Acl -LiteralPath $Path -AclObject $Acl }
    )

    $paths = Get-SharedFolderRuntimePaths -SharedRoot $SharedRoot -SystemRoot $SystemRoot
    & $SetAclAction $paths.SharedRoot (New-SharedFolderAcl -WorkerAccess ReadAndExecute)
    & $SetAclAction $paths.SystemRoot (New-SharedFolderAcl -WorkerAccess ReadAndExecute)
    foreach ($path in @(
        $paths.JobRoot,
        $paths.StagingRoot,
        $paths.PartialRoot,
        $paths.CacheRoot,
        $paths.StatusRoot,
        $paths.CancellationRoot,
        $paths.LogRoot,
        $paths.LockRoot
    )) {
        & $SetAclAction $path (New-SharedFolderAcl -WorkerAccess Modify)
    }
    & $SetAclAction $paths.ToolRoot (New-SharedFolderAcl -WorkerAccess ReadAndExecute)
}

function Read-PinnedMediaToolManifest {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Pinned media tool manifest is missing: $Path"
    }
    $file = Get-Item -LiteralPath $Path -Force
    if ($file.Length -gt 16384) { throw 'Pinned media tool manifest is too large.' }
    try { $manifest = Get-Content -LiteralPath $Path -Raw -Encoding utf8 | ConvertFrom-Json -ErrorAction Stop }
    catch { throw 'Pinned media tool manifest is not valid JSON.' }
    $actual = @($manifest.PSObject.Properties.Name | Sort-Object)
    $expected = @(@('packageVersion', 'schemaVersion', 'sha256', 'uri') | Sort-Object)
    if (($actual -join "`n") -cne ($expected -join "`n")) {
        throw 'Pinned media tool manifest fields are invalid.'
    }
    if ([int]$manifest.schemaVersion -ne 1 -or
        $manifest.packageVersion -isnot [string] -or
        $manifest.packageVersion -notmatch '^[A-Za-z0-9._-]{1,40}$' -or
        $manifest.sha256 -isnot [string] -or
        $manifest.sha256 -notmatch '^[A-Fa-f0-9]{64}$') {
        throw 'Pinned media tool manifest values are invalid.'
    }
    $uri = [uri]$manifest.uri
    if ($uri.Scheme -ne 'https' -or [string]::IsNullOrWhiteSpace($uri.Host)) {
        throw 'Pinned media tool URI must use HTTPS.'
    }
    return [pscustomobject]@{
        schemaVersion = 1
        packageVersion = [string]$manifest.packageVersion
        uri = $uri.AbsoluteUri
        sha256 = ([string]$manifest.sha256).ToUpperInvariant()
    }
}

function Expand-ValidatedMediaArchive {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$ArchivePath,
        [Parameter(Mandatory)][string]$Destination
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    $destinationRoot = [IO.Path]::GetFullPath($Destination).TrimEnd('\') + '\'
    $archive = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
    try {
        foreach ($entry in $archive.Entries) {
            $target = [IO.Path]::GetFullPath((Join-Path $Destination $entry.FullName))
            if (-not $target.StartsWith($destinationRoot, [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Pinned media tool archive contains an unsafe path.'
            }
        }
    } finally {
        $archive.Dispose()
    }
    [IO.Compression.ZipFile]::ExtractToDirectory($ArchivePath, $Destination, $true)
}

function Install-PinnedMediaTools {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$ManifestPath,
        [Parameter(Mandatory)][string]$ToolRoot,
        [scriptblock]$DownloadAction = {
            param($Uri, $Destination)
            Invoke-WebRequest -Uri $Uri -OutFile $Destination
        }
    )

    $manifest = Read-PinnedMediaToolManifest -Path $ManifestPath
    New-Item -ItemType Directory -Path $ToolRoot -Force | Out-Null
    $activeMarker = Join-Path $ToolRoot 'active-media-tools.json'
    if (Test-Path -LiteralPath $activeMarker -PathType Leaf) {
        try {
            $active = Get-Content -LiteralPath $activeMarker -Raw -Encoding utf8 |
                ConvertFrom-Json -DateKind String -ErrorAction Stop
            $activeFields = @($active.PSObject.Properties.Name | Sort-Object)
            $expectedActiveFields = @(@(
                'ffmpegSha256',
                'ffprobeSha256',
                'packageSha256',
                'packageVersion',
                'schemaVersion',
                'versionDirectory'
            ) | Sort-Object)
            if (($activeFields -join "`n") -ceq ($expectedActiveFields -join "`n") -and
                [string]$active.packageSha256 -ceq $manifest.sha256 -and
                [int]$active.schemaVersion -eq 1 -and
                [string]$active.versionDirectory -match '^[A-Za-z0-9._-]{1,120}$' -and
                [string]$active.ffmpegSha256 -match '^[A-Fa-f0-9]{64}$' -and
                [string]$active.ffprobeSha256 -match '^[A-Fa-f0-9]{64}$') {
                $activeVersion = Join-Path (Join-Path $ToolRoot 'versions') $active.versionDirectory
                $activeFfmpeg = @(Get-ChildItem -LiteralPath $activeVersion -Filter ffmpeg.exe -File -Recurse)
                $activeFfprobe = @(Get-ChildItem -LiteralPath $activeVersion -Filter ffprobe.exe -File -Recurse)
                if ($activeFfmpeg.Count -eq 1 -and $activeFfprobe.Count -eq 1 -and
                    (Get-FileHash -LiteralPath $activeFfmpeg[0].FullName -Algorithm SHA256).Hash -ceq
                        ([string]$active.ffmpegSha256).ToUpperInvariant() -and
                    (Get-FileHash -LiteralPath $activeFfprobe[0].FullName -Algorithm SHA256).Hash -ceq
                        ([string]$active.ffprobeSha256).ToUpperInvariant()) {
                    return [pscustomobject]@{
                        Ffmpeg = $activeFfmpeg[0].FullName
                        Ffprobe = $activeFfprobe[0].FullName
                        FfmpegSha256 = ([string]$active.ffmpegSha256).ToUpperInvariant()
                        FfprobeSha256 = ([string]$active.ffprobeSha256).ToUpperInvariant()
                    }
                }
            }
        } catch { }
    }

    $versionsRoot = Join-Path $ToolRoot 'versions'
    New-Item -ItemType Directory -Path $versionsRoot -Force | Out-Null
    $temporaryRoot = Join-Path $ToolRoot ('.install-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
    try {
        $archivePath = Join-Path $temporaryRoot 'media-tools.zip'
        & $DownloadAction $manifest.uri $archivePath
        if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $manifest.sha256) {
            throw 'Pinned media tool package SHA-256 verification failed.'
        }
        $expanded = Join-Path $temporaryRoot 'expanded'
        Expand-ValidatedMediaArchive -ArchivePath $archivePath -Destination $expanded
        $ffmpeg = @(Get-ChildItem -LiteralPath $expanded -Filter ffmpeg.exe -File -Recurse)
        $ffprobe = @(Get-ChildItem -LiteralPath $expanded -Filter ffprobe.exe -File -Recurse)
        if ($ffmpeg.Count -ne 1 -or $ffprobe.Count -ne 1) {
            throw 'Pinned media tool package has an unexpected layout.'
        }

        $versionName = '{0}-{1}-{2}' -f $manifest.packageVersion,
            $manifest.sha256.Substring(0, 12), [Guid]::NewGuid().ToString('N')
        $publishedVersion = Join-Path $versionsRoot $versionName
        [IO.Directory]::Move($expanded, $publishedVersion)
        $ffmpegTarget = Join-Path $publishedVersion ([IO.Path]::GetRelativePath($expanded, $ffmpeg[0].FullName))
        $ffprobeTarget = Join-Path $publishedVersion ([IO.Path]::GetRelativePath($expanded, $ffprobe[0].FullName))
        $active = [ordered]@{
            schemaVersion = 1
            packageVersion = $manifest.packageVersion
            packageSha256 = $manifest.sha256
            versionDirectory = $versionName
            ffmpegSha256 = (Get-FileHash -LiteralPath $ffmpegTarget -Algorithm SHA256).Hash
            ffprobeSha256 = (Get-FileHash -LiteralPath $ffprobeTarget -Algorithm SHA256).Hash
        }
        $temporaryMarker = "$activeMarker.$([Guid]::NewGuid().ToString('N')).tmp"
        try {
            [IO.File]::WriteAllText(
                $temporaryMarker,
                ($active | ConvertTo-Json -Compress),
                [Text.UTF8Encoding]::new($false))
            [IO.File]::Move($temporaryMarker, $activeMarker, $true)
        } finally {
            Remove-Item -LiteralPath $temporaryMarker -Force -ErrorAction SilentlyContinue
        }
    } finally {
        if (Test-Path -LiteralPath $temporaryRoot) {
            Remove-Item -LiteralPath $temporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    return [pscustomobject]@{
        Ffmpeg = $ffmpegTarget
        Ffprobe = $ffprobeTarget
        FfmpegSha256 = $active.ffmpegSha256
        FfprobeSha256 = $active.ffprobeSha256
    }
}

function Install-SharedFolderWorkerService {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$ProductionRoot,
        [scriptblock]$GetServiceAction = {
            param($Name)
            try {
                Get-Service -Name $Name -ErrorAction Stop
            } catch {
                $missingServiceErrorId =
                    'NoServiceFoundForGivenName,Microsoft.PowerShell.Commands.GetServiceCommand'
                if ($_.FullyQualifiedErrorId -eq $missingServiceErrorId) { return $null }
                throw
            }
        },
        [scriptblock]$InvokeWinSwAction = {
            param($Binary, $Command)
            & $Binary $Command | Out-Null
            $LASTEXITCODE
        },
        [ValidateRange(1, 300)][int]$ServiceWaitTimeoutSeconds = 30,
        [scriptblock]$WaitForServicePresenceAction = {
            param($Name, $ShouldExist, $TimeoutSeconds, $QueryServiceAction)
            $timer = [Diagnostics.Stopwatch]::StartNew()
            do {
                $service = & $QueryServiceAction $Name
                if ([bool]$service -eq [bool]$ShouldExist) { return $service }
                Start-Sleep -Milliseconds 250
            } while ($timer.Elapsed -lt [TimeSpan]::FromSeconds($TimeoutSeconds))

            $expected = if ($ShouldExist) { 'appear' } else { 'disappear' }
            throw "Service did not $expected within $TimeoutSeconds seconds: $Name"
        },
        [scriptblock]$StopServiceAction = {
            param($Name, $QueryServiceAction)
            $service = & $QueryServiceAction $Name
            if (-not $service) { return }
            if ($service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
                Stop-Service $Name -Force -ErrorAction Stop
                $service.WaitForStatus(
                    [ServiceProcess.ServiceControllerStatus]::Stopped,
                    [TimeSpan]::FromSeconds(30))
                $service.Refresh()
            }
            if ($service.Status -ne [ServiceProcess.ServiceControllerStatus]::Stopped) {
                throw "Service did not stop: $Name"
            }
        },
        [scriptblock]$SetServiceIdentityAction = {
            param($Name, $Identity)
            Invoke-CheckedProcess -FilePath 'sc.exe' `
                -ArgumentList @('config', $Name, 'obj=', $Identity) | Out-Null
        },
        [scriptblock]$GetServiceIdentityAction = {
            param($Name)
            $service = Get-CimInstance Win32_Service -Filter "Name='$Name'" -ErrorAction Stop
            if (-not $service) { throw "Missing installed service: $Name" }
            return [string]$service.StartName
        },
        [scriptblock]$ProtectPathAction = { param($Path) Protect-ProductionPath -Path $Path },
        [scriptblock]$SetAclAction = { param($Path, $Acl) Set-Acl -LiteralPath $Path -AclObject $Acl }
    )

    $serviceRoot = Join-Path $ProductionRoot 'service'
    $websiteBinary = Join-Path $serviceRoot 'ChristopherBellDev.exe'
    $workerBinary = Join-Path $serviceRoot 'ChristopherBellMediaWorker.exe'
    $serviceName = 'ChristopherBellMediaWorker'
    $serviceExists = [bool](& $GetServiceAction $serviceName)
    try {
        if ($serviceExists) { & $StopServiceAction $serviceName $GetServiceAction }

        if (-not (Test-Path -LiteralPath $websiteBinary -PathType Leaf)) {
            throw 'The verified website WinSW binary must be installed before the media worker.'
        }
        if (-not (Test-Path -LiteralPath $workerBinary -PathType Leaf)) {
            Copy-Item -LiteralPath $websiteBinary -Destination $workerBinary
        } elseif ((Get-FileHash -LiteralPath $workerBinary -Algorithm SHA256).Hash -ne
            (Get-FileHash -LiteralPath $websiteBinary -Algorithm SHA256).Hash) {
            throw 'The media worker WinSW binary does not match the verified website WinSW binary.'
        }
        Copy-Item (Join-Path $PSScriptRoot '..\service\ChristopherBellMediaWorker.xml') $serviceRoot -Force
        Copy-Item (Join-Path $PSScriptRoot '..\service\Start-SharedFolderMediaWorker.ps1') $serviceRoot -Force
        Copy-Item (Join-Path $PSScriptRoot 'Production.SharedFolderWorker.psm1') $serviceRoot -Force

        foreach ($websiteControlFile in @(
            'ChristopherBellDev.exe',
            'ChristopherBellDev.xml',
            'Start-ChristopherBellDev.ps1'
        )) {
            $path = Join-Path $serviceRoot $websiteControlFile
            if (Test-Path -LiteralPath $path -PathType Leaf) { & $ProtectPathAction $path }
        }
        foreach ($workerFile in @(
            'ChristopherBellMediaWorker.exe',
            'ChristopherBellMediaWorker.xml',
            'Start-SharedFolderMediaWorker.ps1',
            'Production.SharedFolderWorker.psm1'
        )) {
            & $SetAclAction (Join-Path $serviceRoot $workerFile) (New-SharedFolderWorkerFileAcl)
        }

        if ($serviceExists) {
            $exitCode = & $InvokeWinSwAction $workerBinary 'uninstall'
            if ($exitCode -ne 0) {
                throw 'Media worker WinSW service uninstallation failed.'
            }
            & $WaitForServicePresenceAction `
                $serviceName $false $ServiceWaitTimeoutSeconds $GetServiceAction | Out-Null
        }

        $exitCode = & $InvokeWinSwAction $workerBinary 'install'
        if ($exitCode -ne 0) {
            throw 'Media worker WinSW service installation failed.'
        }
        & $WaitForServicePresenceAction `
            $serviceName $true $ServiceWaitTimeoutSeconds $GetServiceAction | Out-Null
        & $StopServiceAction $serviceName $GetServiceAction
        $expectedIdentity = 'NT AUTHORITY\LocalService'
        & $SetServiceIdentityAction $serviceName $expectedIdentity
        $actualIdentity = [string](& $GetServiceIdentityAction $serviceName)
        if (-not [string]::Equals(
            $actualIdentity, $expectedIdentity, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'The media worker service must run as LocalService.'
        }
    } catch {
        $setupFailure = $_
        try {
            & $StopServiceAction $serviceName $GetServiceAction
        } catch {
            $failures = [System.Exception[]]@($setupFailure.Exception, $_.Exception)
            throw [System.AggregateException]::new(
                'Media worker setup and stopped-state cleanup both failed.', $failures)
        }
        throw $setupFailure
    }
}

function Install-SharedFolderRuntime {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$ProductionRoot,
        [Parameter(Mandatory)]$Configuration
    )

    $null = $Configuration
    $paths = New-SharedFolderRuntimeDirectories
    Set-SharedFolderRuntimeAcls
    $manifest = Join-Path $PSScriptRoot '..\config\media-tools-manifest.json'
    Install-PinnedMediaTools -ManifestPath $manifest -ToolRoot $paths.ToolRoot | Out-Null
    Set-Acl -LiteralPath $paths.ToolRoot -AclObject (New-SharedFolderAcl -WorkerAccess ReadAndExecute)
    Install-SharedFolderWorkerService -ProductionRoot $ProductionRoot
}

Export-ModuleMember -Function Get-SharedFolderRuntimePaths,New-SharedFolderRuntimeDirectories,New-SharedFolderAcl,New-SharedFolderWorkerFileAcl,Set-SharedFolderRuntimeAcls,Read-PinnedMediaToolManifest,Expand-ValidatedMediaArchive,Install-PinnedMediaTools,Install-SharedFolderWorkerService,Install-SharedFolderRuntime
