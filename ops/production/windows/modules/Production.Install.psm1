Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:WinSwUri = 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe'
$script:WinSwSha256 = '05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA'

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]$identity
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'This operation requires elevated PowerShell.'
    }
}

function New-ProductionDirectories {
    param([string]$Root)
    foreach ($name in 'backups','config','gradle-home','locks','logs','releases','service','state','tools','worktrees') {
        New-Item -ItemType Directory -Force (Join-Path $Root $name) | Out-Null
    }
}

function Install-ConfigurationExamples {
    param([string]$Root)
    $configSource = Join-Path $PSScriptRoot '..\config'
    $deployTarget = Join-Path $Root 'config\deploy.json'
    $environmentTarget = Join-Path $Root 'config\app.env'
    Copy-Item (Join-Path $configSource 'deploy.example.json') (Join-Path $Root 'config\deploy.example.json') -Force
    Copy-Item (Join-Path $configSource 'app.env.example') (Join-Path $Root 'config\app.env.example') -Force
    if (-not (Test-Path -LiteralPath $deployTarget)) {
        Copy-Item (Join-Path $configSource 'deploy.example.json') $deployTarget
    } else {
        $defaults = Get-Content (Join-Path $configSource 'deploy.example.json') -Raw | ConvertFrom-Json
        $existing = Get-Content $deployTarget -Raw | ConvertFrom-Json
        foreach ($property in $defaults.PSObject.Properties) {
            if ($existing.PSObject.Properties.Name -notcontains $property.Name) {
                $existing | Add-Member -NotePropertyName $property.Name -NotePropertyValue $property.Value
            }
        }
        foreach ($retired in 'wslDistro','wslWebsiteStopCommand','wslWebsiteStartCommand','wslMongoStopCommand','wslMongoStartCommand') {
            $existing.PSObject.Properties.Remove($retired)
        }
        $existing | ConvertTo-Json -Depth 10 | Set-Content $deployTarget -Encoding utf8
    }
    if (-not (Test-Path -LiteralPath $environmentTarget)) { Copy-Item (Join-Path $configSource 'app.env.example') $environmentTarget }
}

function Protect-ProductionSecrets {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Root,
        [scriptblock]$ProtectTreeAction = { param($Path) Protect-ProductionTree -Path $Path },
        [scriptblock]$AssertTreeAction = { param($Path) Assert-ProtectedProductionTree -Path $Path }
    )

    $config = Join-Path $Root 'config'
    & $ProtectTreeAction $config
    & $AssertTreeAction $config
}

function Assert-CloudflaredExecutable {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Executable)
    if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) {
        throw "Missing cloudflared executable: $Executable"
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $Executable
    $signer = $signature.SignerCertificate
    $subject = if ($signer) { [string]$signer.Subject } else { '' }
    if ([string]$signature.Status -ne 'Valid' -or
        $subject -notmatch '(?i)(?:^|,\s*)O="?Cloudflare, Inc\."?(?:,|$)') {
        throw 'The configured cloudflared executable must have a valid Authenticode signature signed by Cloudflare, Inc.'
    }
}

function Get-ServiceExecutablePath {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$PathName)
    $match = [regex]::Match($PathName, '^\s*(?:"([^"]+)"|(\S+))')
    if (-not $match.Success) { throw 'The cloudflared service executable path is invalid.' }
    if ($match.Groups[1].Success) { return $match.Groups[1].Value }
    return $match.Groups[2].Value
}

function Assert-CloudflaredServiceBinding {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Executable)
    $service = Get-CimInstance -ClassName Win32_Service -Filter "Name='cloudflared'" -ErrorAction Stop
    if (-not $service) { throw 'The cloudflared service registration is missing.' }
    $serviceExecutable = Get-ServiceExecutablePath -PathName ([string]$service.PathName)
    if (-not [string]::Equals($serviceExecutable, $Executable, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The cloudflared service is not bound to the configured signed executable; provide CloudflareTokenPath to reinstall it.'
    }
}

function Install-CloudflaredService {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Executable,
        [string]$TokenPath,
        [switch]$WhatIf
    )
    Assert-CloudflaredExecutable -Executable $Executable
    $existing = Get-Service cloudflared -ErrorAction SilentlyContinue
    $tokenProvided = -not [string]::IsNullOrWhiteSpace($TokenPath)
    if (-not $existing -and -not $tokenProvided) {
        throw 'CloudflareTokenPath must reference a protected file when installing cloudflared.'
    }
    if ($existing -and -not $tokenProvided) {
        Assert-CloudflaredServiceBinding -Executable $Executable
    }
    if ($tokenProvided) {
        if (-not (Test-Path -LiteralPath $TokenPath -PathType Leaf)) {
            throw 'CloudflareTokenPath must reference a protected file when installing cloudflared.'
        }
        $token = (Get-Content -LiteralPath $TokenPath -Raw).Trim()
        try {
            if ($token.Length -lt 100 -or $token -notmatch '^[A-Za-z0-9_.=-]+$') {
                throw 'Cloudflare tunnel token is invalid.'
            }
            if (-not $WhatIf) {
                if ($existing) {
                    Invoke-CheckedProcess $Executable @('service','uninstall') (Split-Path -Parent $Executable) | Out-Null
                }
                Invoke-CheckedProcess $Executable @('service','install',$token) (Split-Path -Parent $Executable) | Out-Null
                Assert-CloudflaredServiceBinding -Executable $Executable
            }
        } finally { $token = $null }
    }
    if (-not $WhatIf) {
        Set-Service cloudflared -StartupType Automatic
        Invoke-CheckedProcess 'sc.exe' @('failure','cloudflared','reset=','3600','actions=','restart/10000/restart/30000') | Out-Null
        Start-Service cloudflared
    }
}

function Install-WinSwBinary {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$ServiceRoot)
    $binary = Join-Path $ServiceRoot 'ChristopherBellDev.exe'
    Assert-ProductionWebsiteServiceDestinationNotReparse -Path $binary
    if (Test-Path -LiteralPath $binary -PathType Leaf) {
        if ((Get-FileHash -LiteralPath $binary -Algorithm SHA256 -ErrorAction Stop).Hash -cne
            $script:WinSwSha256) {
            throw 'Existing installed WinSW SHA-256 verification failed.'
        }
        return $binary
    }
    $download = "$binary.download"
    Assert-ProductionWebsiteServiceDestinationNotReparse -Path $download
    Invoke-WebRequest $script:WinSwUri -OutFile $download
    if ((Get-FileHash $download -Algorithm SHA256).Hash -ne $script:WinSwSha256) {
        Remove-Item -LiteralPath $download -Force
        throw 'WinSW SHA-256 verification failed.'
    }
    Move-Item -LiteralPath $download -Destination $binary
    return $binary
}

function Assert-ProductionWebsiteServiceDestinationNotReparse {
    param([Parameter(Mandatory)][string]$Path)

    try {
        $item = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    } catch [System.Management.Automation.ItemNotFoundException] {
        return
    }
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "Production service-host destination must not be a reparse point: $Path"
    }
}

function Get-ProductionWinSwSha256 {
    $script:WinSwSha256.ToLowerInvariant()
}

function Get-ProductionWebsiteServiceOrNull {
    try {
        return Get-Service -Name 'ChristopherBellDev' -ErrorAction Stop
    } catch {
        $missingServiceErrorId =
            'NoServiceFoundForGivenName,Microsoft.PowerShell.Commands.GetServiceCommand'
        if ($_.FullyQualifiedErrorId -eq $missingServiceErrorId) { return $null }
        throw
    }
}

function Initialize-ProductionDeploymentLockDirectory {
    param([Parameter(Mandatory)][string]$Root)

    New-Item -ItemType Directory -Path $Root -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $Root 'locks') -Force | Out-Null
}

function Assert-ProductionWebsiteServiceBinding {
    param([Parameter(Mandatory)][string]$Root)

    $serviceRoot = [IO.Path]::GetFullPath((Join-Path $Root 'service'))
    $expectedBinary = [IO.Path]::GetFullPath(
        (Join-Path $serviceRoot 'ChristopherBellDev.exe'))
    $services = @(
        Get-CimInstance -ClassName Win32_Service `
            -Filter "Name='ChristopherBellDev'" -ErrorAction Stop
    )
    if ($services.Count -ne 1) {
        throw 'ChristopherBellDev service registration was not verified.'
    }
    $actualBinary = [IO.Path]::GetFullPath(
        (Get-ServiceExecutablePath -PathName ([string]$services[0].PathName)))
    if (-not [string]::Equals(
            $actualBinary, $expectedBinary, [StringComparison]::OrdinalIgnoreCase) -or
        [string]$services[0].StartName -cne 'LocalSystem') {
        throw 'ChristopherBellDev service binding was not verified.'
    }
}

function Assert-ProductionWebsiteServiceBoundary {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)]$Configuration
    )

    $sourceLauncher = Join-Path $PSScriptRoot '..\service\Start-ChristopherBellDev.ps1'
    $sourceModule = Join-Path $PSScriptRoot 'Production.WriterStart.psm1'
    $sourceXml = Join-Path $PSScriptRoot '..\service\ChristopherBellDev.xml'
    $writerStartModule = Get-Module Production.WriterStart -ErrorAction Stop
    & $writerStartModule {
        param($Value, $LauncherSha, $ModuleSha, $WinSwSha, $ServiceXmlSha)
        Assert-ProductionWriterStartGuardBundle `
            -Config $Value `
            -ExpectedLauncherSha256 $LauncherSha `
            -ExpectedModuleSha256 $ModuleSha `
            -ExpectedWinSwSha256 $WinSwSha `
            -ExpectedServiceXmlSha256 $ServiceXmlSha | Out-Null
    } $Configuration `
        (Get-FileHash -LiteralPath $sourceLauncher -Algorithm SHA256).Hash.ToLowerInvariant() `
        (Get-FileHash -LiteralPath $sourceModule -Algorithm SHA256).Hash.ToLowerInvariant() `
        (Get-ProductionWinSwSha256) `
        (Get-FileHash -LiteralPath $sourceXml -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-ProductionWebsiteServiceBinding -Root $Root
}

function Protect-ProductionWebsiteServiceDirectory {
    param([Parameter(Mandatory)]$Configuration)

    $writerStartModule = Get-Module Production.WriterStart -ErrorAction Stop
    & $writerStartModule {
        param($Value)
        $serviceRoot = Get-CanonicalProductionWriterStartServiceRoot -Config $Value
        Protect-ProductionWriterStartServiceDirectory -Path $serviceRoot
        Assert-ProductionWriterStartServiceDirectory -Path $serviceRoot
        return $serviceRoot
    } $Configuration
}

function Install-WebsiteService {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)]$Configuration
    )

    $null = $Configuration
    $service = Protect-ProductionWebsiteServiceDirectory -Configuration $Configuration
    Set-Service MongoDB -StartupType Automatic
    & sc.exe failure MongoDB reset= 3600 actions= restart/10000/restart/30000 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to configure MongoDB service recovery.' }
    $binary = Install-WinSwBinary -ServiceRoot $service
    $sourceXml = Join-Path $PSScriptRoot '..\service\ChristopherBellDev.xml'
    $installedXml = Join-Path $service 'ChristopherBellDev.xml'
    Assert-ProductionWebsiteServiceDestinationNotReparse -Path $installedXml
    Copy-Item $sourceXml $installedXml -Force
    $winSwSha = Get-ProductionWinSwSha256
    $serviceXmlSha = (Get-FileHash -LiteralPath $sourceXml -Algorithm SHA256).Hash.ToLowerInvariant()
    $writerStartModule = Get-Module Production.WriterStart -ErrorAction Stop
    & $writerStartModule {
        param($Value, $Launcher, $ModulePath, $ExpectedWinSw, $ExpectedServiceXml)
        Publish-ProductionWriterStartGuardBundle `
            -Config $Value `
            -SourceLauncherPath $Launcher `
            -SourceModulePath $ModulePath `
            -ExpectedWinSwSha256 $ExpectedWinSw `
            -ExpectedServiceXmlSha256 $ExpectedServiceXml | Out-Null
    } $Configuration `
        (Join-Path $PSScriptRoot '..\service\Start-ChristopherBellDev.ps1') `
        (Join-Path $PSScriptRoot 'Production.WriterStart.psm1') `
        $winSwSha `
        $serviceXmlSha
    if (-not (Get-ProductionWebsiteServiceOrNull)) {
        & $binary install | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'WinSW service installation failed.' }
    }
    & sc.exe config ChristopherBellDev start= disabled depend= MongoDB | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to keep the website service Disabled during installation.'
    }
    & sc.exe failure ChristopherBellDev reset= 3600 actions= restart/10000/restart/30000 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to configure website service recovery.' }
    Assert-ProductionWebsiteServiceBoundary -Root $Root -Configuration $Configuration
}

function Install-ProductionRuntime {
    [CmdletBinding()]
    param([switch]$WhatIf, [string]$CloudflareTokenPath)
    Assert-Administrator
    $root = 'C:\ProgramData\christopherbell.dev'
    if ($WhatIf) {
        Write-Output "Would install the website runtime and restricted shared media worker under $root."
        return
    }
    Initialize-ProductionDeploymentLockDirectory -Root $root
    $lock = Enter-DeploymentLock (Join-Path $root 'locks\deploy.lock')
    try {
        $priorService = $null
        $wasRunning = $false
        $config = $null
        $productionPort = 8080
        $websiteInstallStarted = $false
        try {
            $priorService = Get-ProductionWebsiteServiceOrNull
            if ($priorService) {
                Set-ProductionWebsiteStartupType -StartupType Disabled
                $priorStatus = [string]$priorService.Status
                if ($priorStatus -notin @('Running','Stopped')) {
                    throw 'ChristopherBellDev must be Running or Stopped before installation.'
                }
                $wasRunning = $priorStatus -eq 'Running'
                $config = Read-ProductionConfig (Join-Path $root 'config\deploy.json')
                $productionPort = [int]$config.productionPort
                Stop-ProductionWebsiteService -ProductionPort $config.productionPort `
                    -KeepRecoverySuspended
            }
            New-ProductionDirectories $root
            Install-ConfigurationExamples $root
            $config = Read-ProductionConfig (Join-Path $root 'config\deploy.json')
            $productionPort = [int]$config.productionPort
            Read-ProductionEnvironment (Join-Path $root 'config\app.env') | Out-Null
            Protect-ProductionSecrets $root
            Install-CloudflaredService `
                -Executable $config.cloudflaredExe -TokenPath $CloudflareTokenPath
            $websiteInstallStarted = $true
            Install-WebsiteService -Root $root -Configuration $config
            Install-SharedFolderRuntime -ProductionRoot $root -Configuration $config
            Assert-ProductionWebsiteServiceBoundary -Root $root -Configuration $config
            Set-ProductionWebsiteStartupType -StartupType Automatic
            if ($wasRunning) {
                Start-Service -Name 'ChristopherBellDev' -ErrorAction Stop
                Test-ProductionEndpoints -Config $config -Port $config.productionPort
                Test-ProductionPublicEndpoints -Config $config | Out-Null
            }
        } catch {
            $installFailure = $_.Exception
            $containmentFailures = [Collections.Generic.List[System.Exception]]::new()
            $installedService = $null
            try {
                $installedService = Get-ProductionWebsiteServiceOrNull
            } catch {
                [void]$containmentFailures.Add($_.Exception)
            }
            if ($installedService) {
                try {
                    Set-ProductionWebsiteStartupType -StartupType Disabled
                } catch {
                    [void]$containmentFailures.Add($_.Exception)
                }
                try {
                    Stop-ProductionWebsiteService -ProductionPort $productionPort `
                        -KeepRecoverySuspended
                } catch {
                    [void]$containmentFailures.Add($_.Exception)
                }
                try {
                    $containedService = Get-ProductionWebsiteServiceOrNull
                    if (-not $containedService -or
                        [string]$containedService.Status -cne 'Stopped') {
                        throw 'ChristopherBellDev stopped containment was not verified.'
                    }
                } catch {
                    [void]$containmentFailures.Add($_.Exception)
                }
            } elseif ($priorService -or $websiteInstallStarted) {
                [void]$containmentFailures.Add(
                    [System.InvalidOperationException]::new(
                        'ChristopherBellDev disappeared before containment could be verified.'))
            }
            if ($containmentFailures.Count -gt 0) {
                $causes = [Collections.Generic.List[System.Exception]]::new()
                [void]$causes.Add($installFailure)
                foreach ($failure in $containmentFailures) { [void]$causes.Add($failure) }
                throw [System.AggregateException]::new(
                    'Production runtime installation failed and website containment could not be verified.',
                    [System.Exception[]]$causes.ToArray())
            }
            $containment = if ($installedService) {
                'the website remains stopped and Disabled'
            } else {
                'no website service is registered'
            }
            throw [System.InvalidOperationException]::new(
                "Production runtime installation failed; $containment`: $($installFailure.Message)",
                $installFailure)
        }
    } finally {
        $lock.Dispose()
    }
}

function Uninstall-ProductionRuntime {
    [CmdletBinding()]
    param([switch]$WhatIf)
    Assert-Administrator
    $binary = 'C:\ProgramData\christopherbell.dev\service\ChristopherBellDev.exe'
    if ($WhatIf) { Write-Output 'Would remove only the ChristopherBellDev service; data and MongoDB remain.'; return }
    if (Get-Service ChristopherBellDev -ErrorAction SilentlyContinue) {
        Stop-Service ChristopherBellDev -ErrorAction SilentlyContinue
        & $binary uninstall | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'WinSW service removal failed.' }
    }
}

Export-ModuleMember -Function Assert-Administrator,New-ProductionDirectories,Install-ConfigurationExamples,Protect-ProductionSecrets,Assert-CloudflaredExecutable,Get-ServiceExecutablePath,Assert-CloudflaredServiceBinding,Install-CloudflaredService,Install-WinSwBinary,Get-ProductionWinSwSha256,Assert-ProductionWebsiteServiceBoundary,Install-WebsiteService,Install-ProductionRuntime,Uninstall-ProductionRuntime
