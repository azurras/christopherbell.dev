#Requires -Version 7
# Guards shared by the local candidate scripts. Each function either proves a resource is the
# isolated one this tooling owns or refuses, so a mistake cannot reach production data.

Set-StrictMode -Version Latest

# Production MongoDB and the production and default local web ports.
$script:ReservedPorts = @(27017, 8080, 8081)
$script:SocketDirectoryProperty = '-Djdk.net.unixdomain.tmpdir='

<#
.SYNOPSIS
Finds mongod.exe: the requested path, then PATH, then the newest MongoDB Server install.
#>
function Resolve-MongodExecutable {
    [CmdletBinding()]
    param(
        [string]$RequestedPath,
        [string]$ProgramFilesRoot = $env:ProgramFiles
    )
    if ($RequestedPath) {
        if (-not (Test-Path -LiteralPath $RequestedPath -PathType Leaf)) {
            throw "mongod was not found at $RequestedPath."
        }
        return (Resolve-Path -LiteralPath $RequestedPath).Path
    }
    $onPath = Get-Command mongod.exe -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($onPath) {
        return $onPath.Source
    }
    $serverRoot = Join-Path $ProgramFilesRoot 'MongoDB\Server'
    $installs = @(Get-ChildItem -LiteralPath $serverRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -as [version] } |
        Sort-Object { [version]$_.Name } -Descending |
        ForEach-Object { Join-Path $_.FullName 'bin\mongod.exe' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
    if ($installs.Count -eq 0) {
        throw "mongod.exe is not on PATH or under $serverRoot; pass -MongodPath."
    }
    return $installs[0]
}

<#
.SYNOPSIS
Asks the OS for a free loopback port, refusing the production and default local ports.
#>
function Get-FreeLoopbackPort {
    [CmdletBinding()]
    param([int[]]$Excluding = @())
    $picker = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try {
        $picker.Start()
        $port = ([Net.IPEndPoint]$picker.LocalEndpoint).Port
    } finally {
        $picker.Stop()
    }
    if ($port -in ($script:ReservedPorts + $Excluding)) {
        throw "The OS selected reserved port $port; run the script again."
    }
    return $port
}

<#
.SYNOPSIS
Creates <Parent>\<Prefix><32 hex>, a directory Test-OwnedTemporaryDirectory will later accept.
#>
function New-OwnedTemporaryDirectory {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Parent,
        [Parameter(Mandatory)][string]$Prefix
    )
    $path = Join-Path $Parent ($Prefix + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $path -Force | Out-Null
    return $path
}

<#
.SYNOPSIS
True only for a directory directly under Parent whose name is Prefix followed by 32 hex digits.
#>
function Test-OwnedTemporaryDirectory {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Path,
        [Parameter(Mandatory)][string]$Parent,
        [Parameter(Mandatory)][string]$Prefix
    )
    if (-not $Path) {
        return $false
    }
    $fullPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $fullParent = [IO.Path]::GetFullPath($Parent).TrimEnd('\')
    $name = Split-Path -Leaf $fullPath
    $isDirectChild = (Split-Path -Parent $fullPath) -ieq $fullParent
    return $isDirectChild -and ($name -cmatch ('^' + [regex]::Escape($Prefix) + '[0-9a-f]{32}$'))
}

<#
.SYNOPSIS
JAVA_TOOL_OPTIONS that moves the JDK's Unix-domain socket directory to SocketDirectory.

.DESCRIPTION
Java 25 on Windows creates Unix-domain sockets for NIO pipes under %TEMP%; in some agent shells
that fails, and the Gradle daemon and test JVMs cannot start. An existing setting wins.
#>
function Get-JdkSocketToolOptions {
    [CmdletBinding()]
    param(
        [AllowEmptyString()][string]$ExistingOptions,
        [Parameter(Mandatory)][string]$SocketDirectory
    )
    if ($ExistingOptions -and $ExistingOptions.Contains($script:SocketDirectoryProperty)) {
        return $ExistingOptions
    }
    $socketOption = $script:SocketDirectoryProperty + $SocketDirectory
    if ($ExistingOptions) {
        return "$ExistingOptions $socketOption"
    }
    return $socketOption
}

<#
.SYNOPSIS
Runs Action with the given environment variables set, then restores each previous value.
#>
function Invoke-WithEnvironment {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][hashtable]$Variables,
        [Parameter(Mandatory)][scriptblock]$Action
    )
    $previousValues = @{}
    foreach ($name in $Variables.Keys) {
        $previousValues[$name] = [Environment]::GetEnvironmentVariable($name)
        Set-ProcessEnvironmentVariable -Name $name -Value $Variables[$name]
    }
    try {
        & $Action
    } finally {
        foreach ($name in $previousValues.Keys) {
            Set-ProcessEnvironmentVariable -Name $name -Value $previousValues[$name]
        }
    }
}

# PowerShell turns $null into "" for .NET string parameters, so absence needs Remove-Item.
function Set-ProcessEnvironmentVariable([string]$Name, $Value) {
    if ($null -eq $Value) {
        Remove-Item -LiteralPath "Env:$Name" -ErrorAction SilentlyContinue
    } else {
        Set-Item -LiteralPath "Env:$Name" -Value $Value
    }
}

<#
.SYNOPSIS
A base64 secret of 32 random bytes.
#>
function New-CandidateSecret {
    $secretBytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($secretBytes)
    return [Convert]::ToBase64String($secretBytes)
}

Export-ModuleMember -Function Resolve-MongodExecutable, Get-FreeLoopbackPort, New-OwnedTemporaryDirectory,
    Test-OwnedTemporaryDirectory, Get-JdkSocketToolOptions, Invoke-WithEnvironment, New-CandidateSecret
