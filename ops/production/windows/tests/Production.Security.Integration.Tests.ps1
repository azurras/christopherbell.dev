Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.SharedFolder.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.SharedFolderWorker.psm1') -Force

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]$identity
$isElevatedWindows = $IsWindows -and $principal.IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)

BeforeAll {
function Test-InstalledWorkerAcceptanceRequested {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][bool]$Windows,
        [Parameter(Mandatory)][bool]$Elevated,
        [AllowEmptyString()][string]$OptInValue
    )

    return $Windows -and $Elevated -and [string]::Equals(
        $OptInValue,
        'I_ACCEPT_LOCAL_SERVICE_PROBE',
        [StringComparison]::Ordinal)
}

function Get-InstalledWorkerAcceptanceEnvironment {
    [CmdletBinding()]
    param()

    return @{
        CBDEV_INSTALLED_WORKER_ACCEPTANCE = $env:CBDEV_INSTALLED_WORKER_ACCEPTANCE
        CBDEV_WORKER_ACCEPTANCE_VISIBLE_ROOT = $env:CBDEV_WORKER_ACCEPTANCE_VISIBLE_ROOT
        CBDEV_WORKER_ACCEPTANCE_MEDIA_FIXTURE = $env:CBDEV_WORKER_ACCEPTANCE_MEDIA_FIXTURE
        CBDEV_WORKER_ACCEPTANCE_PRIVATE_ROOT = $env:CBDEV_WORKER_ACCEPTANCE_PRIVATE_ROOT
        CBDEV_WORKER_ACCEPTANCE_PRIVATE_PROBE_DIRECTORY =
            $env:CBDEV_WORKER_ACCEPTANCE_PRIVATE_PROBE_DIRECTORY
        CBDEV_WORKER_ACCEPTANCE_PROTECTED_CONFIG =
            $env:CBDEV_WORKER_ACCEPTANCE_PROTECTED_CONFIG
    }
}

function Resolve-InstalledWorkerAcceptancePath {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][hashtable]$Environment,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][bool]$Directory,
        [Parameter(Mandatory)][scriptblock]$GetItemAction
    )

    [string]$value = $Environment[$Name]
    if ([string]::IsNullOrWhiteSpace($value) -or -not [IO.Path]::IsPathFullyQualified($value)) {
        throw "$Name must be an explicit absolute path."
    }
    $canonical = [IO.Path]::GetFullPath($value).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar)
    if ([string]::IsNullOrWhiteSpace($canonical) -or
        [string]::Equals($canonical, [IO.Path]::GetPathRoot($canonical).TrimEnd('\'),
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Name must not be a filesystem root."
    }

    try {
        $item = & $GetItemAction $canonical
    } catch {
        throw "$Name must identify an existing path."
    }
    if ($null -eq $item -or [bool]$item.PSIsContainer -ne $Directory) {
        throw "$Name has the wrong path type."
    }

    $current = $canonical
    while (-not [string]::IsNullOrWhiteSpace($current)) {
        try {
            $component = & $GetItemAction $current
        } catch {
            throw "$Name must identify a complete existing path."
        }
        if ($component.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "$Name contains a reparse point."
        }
        $parent = [IO.Directory]::GetParent($current)
        if ($null -eq $parent) { break }
        $current = $parent.FullName
    }
    return [IO.Path]::GetFullPath([string]$item.FullName).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar)
}

function Test-StrictlyBelowPath {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Root
    )

    return $Path.StartsWith(
        $Root.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)
}

function Get-InstalledWorkerAcceptanceInputs {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][hashtable]$Environment,
        [scriptblock]$GetItemAction = {
            param($Path)
            Get-Item -LiteralPath $Path -Force -ErrorAction Stop
        }
    )

    $visibleRoot = Resolve-InstalledWorkerAcceptancePath -Environment $Environment `
        -Name 'CBDEV_WORKER_ACCEPTANCE_VISIBLE_ROOT' -Directory $true `
        -GetItemAction $GetItemAction
    $mediaFixture = Resolve-InstalledWorkerAcceptancePath -Environment $Environment `
        -Name 'CBDEV_WORKER_ACCEPTANCE_MEDIA_FIXTURE' -Directory $false `
        -GetItemAction $GetItemAction
    $privateRoot = Resolve-InstalledWorkerAcceptancePath -Environment $Environment `
        -Name 'CBDEV_WORKER_ACCEPTANCE_PRIVATE_ROOT' -Directory $true `
        -GetItemAction $GetItemAction
    $privateProbeDirectory = Resolve-InstalledWorkerAcceptancePath -Environment $Environment `
        -Name 'CBDEV_WORKER_ACCEPTANCE_PRIVATE_PROBE_DIRECTORY' -Directory $true `
        -GetItemAction $GetItemAction
    $protectedConfig = Resolve-InstalledWorkerAcceptancePath -Environment $Environment `
        -Name 'CBDEV_WORKER_ACCEPTANCE_PROTECTED_CONFIG' -Directory $false `
        -GetItemAction $GetItemAction

    if (-not (Test-StrictlyBelowPath -Path $mediaFixture -Root $visibleRoot)) {
        throw 'The media fixture must be strictly below the visible root.'
    }
    if (-not (Test-StrictlyBelowPath -Path $privateProbeDirectory -Root $privateRoot)) {
        throw 'The private probe directory must be strictly below the private root.'
    }
    if ((Test-StrictlyBelowPath -Path $visibleRoot -Root $privateRoot) -or
        (Test-StrictlyBelowPath -Path $privateRoot -Root $visibleRoot) -or
        [string]::Equals($visibleRoot, $privateRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The visible and private roots must be disjoint.'
    }
    foreach ($root in @($visibleRoot, $privateRoot)) {
        if ((Test-StrictlyBelowPath -Path $protectedConfig -Root $root) -or
            [string]::Equals($protectedConfig, $root, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'The protected config must be outside both shared roots.'
        }
    }
    if ((& $GetItemAction $mediaFixture).Length -lt 1) {
        throw 'The media fixture must not be empty.'
    }

    return [pscustomobject]@{
        VisibleRoot = $visibleRoot
        MediaFixture = $mediaFixture
        PrivateRoot = $privateRoot
        PrivateProbeDirectory = $privateProbeDirectory
        ProtectedConfig = $protectedConfig
    }
}

function Assert-InstalledWorkerServiceReady {
    [CmdletBinding()]
    param(
        [scriptblock]$GetServiceAction = {
            Get-CimInstance Win32_Service -Filter "Name='ChristopherBellMediaWorker'" `
                -ErrorAction SilentlyContinue
        }
    )

    $service = & $GetServiceAction
    if ($null -eq $service) { throw 'The installed worker service is unavailable.' }
    if ($service.State -ne 'Running') { throw 'The installed worker service is not running.' }
    return $service
}

function ConvertTo-SingleQuotedPowerShellLiteral {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Value)

    return "'$($Value.Replace("'", "''"))'"
}

function New-InstalledWorkerProbeScript {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Inputs,
        [Parameter(Mandatory)][string]$ResultRoot,
        [Parameter(Mandatory)][string]$ProbeFile
    )

    $template = @'
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class AcceptanceNative {
    [StructLayout(LayoutKind.Sequential)] public struct LUID { public uint LowPart; public int HighPart; }
    [StructLayout(LayoutKind.Sequential)] public struct LUID_AND_ATTRIBUTES { public LUID Luid; public uint Attributes; }
    [StructLayout(LayoutKind.Sequential)] public struct PRIVILEGE_SET { public uint PrivilegeCount; public uint Control; public LUID_AND_ATTRIBUTES Privilege; }
    [DllImport("advapi32.dll", SetLastError=true)] public static extern IntPtr OpenSCManager(string machine, string database, uint access);
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)] public static extern IntPtr OpenServiceW(IntPtr manager, string name, uint access);
    [DllImport("advapi32.dll")] public static extern bool CloseServiceHandle(IntPtr handle);
    [DllImport("advapi32.dll", SetLastError=true)] public static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)] public static extern bool LookupPrivilegeValue(string system, string name, out LUID luid);
    [DllImport("advapi32.dll", SetLastError=true)] public static extern bool PrivilegeCheck(IntPtr token, ref PRIVILEGE_SET required, out bool result);
    [DllImport("kernel32.dll")] public static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr handle);
}
"@
$fixture = __FIXTURE__
$config = __CONFIG__
$probe = __PROBE__
$resultRoot = __RESULT_ROOT__
$resultPath = [IO.Path]::Combine($resultRoot, 'result.json')
$temporaryResult = [IO.Path]::Combine($resultRoot, 'result.tmp')
$result = [ordered]@{
    schemaVersion = 1
    identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    fixtureReadable = $false
    privateCreate = $false
    privateRead = $false
    privateDelete = $false
    configReadDenied = $false
    websiteServiceControlDenied = $false
    shutdownPrivilegeEnabled = $false
    errorCode = 'NONE'
}
try {
    [IO.Directory]::CreateDirectory($resultRoot) | Out-Null
    $stream = [IO.File]::Open($fixture, 'Open', 'Read', 'Read')
    try { $result.fixtureReadable = $stream.ReadByte() -ge 0 } finally { $stream.Dispose() }
    [IO.File]::WriteAllBytes($probe, [byte[]](37))
    $result.privateCreate = [IO.File]::Exists($probe)
    $result.privateRead = ([IO.File]::ReadAllBytes($probe).Length -eq 1)
    [IO.File]::Delete($probe)
    $result.privateDelete = -not [IO.File]::Exists($probe)
    try {
        $protected = [IO.File]::Open($config, 'Open', 'Read', 'ReadWrite')
        $protected.Dispose()
    } catch [UnauthorizedAccessException] {
        $result.configReadDenied = $true
    }
    $manager = [AcceptanceNative]::OpenSCManager($null, $null, 1)
    if ($manager -eq [IntPtr]::Zero) { $result.errorCode = 'SERVICE_MANAGER_QUERY_FAILED' }
    else {
        try {
            $website = [AcceptanceNative]::OpenServiceW($manager, 'ChristopherBellDev', 32)
            if ($website -eq [IntPtr]::Zero) {
                $result.websiteServiceControlDenied = [Runtime.InteropServices.Marshal]::GetLastWin32Error() -eq 5
                if (-not $result.websiteServiceControlDenied) { $result.errorCode = 'SERVICE_HANDLE_CHECK_FAILED' }
            } else { [AcceptanceNative]::CloseServiceHandle($website) | Out-Null }
        } finally { [AcceptanceNative]::CloseServiceHandle($manager) | Out-Null }
    }
    $token = [IntPtr]::Zero
    if (-not [AcceptanceNative]::OpenProcessToken([AcceptanceNative]::GetCurrentProcess(), 8, [ref]$token)) {
        $result.errorCode = 'TOKEN_QUERY_FAILED'
    } else {
        try {
            $luid = [AcceptanceNative+LUID]::new()
            if (-not [AcceptanceNative]::LookupPrivilegeValue($null, 'SeShutdownPrivilege', [ref]$luid)) {
                $result.errorCode = 'TOKEN_QUERY_FAILED'
            } else {
                $required = [AcceptanceNative+PRIVILEGE_SET]::new()
                $required.PrivilegeCount = 1
                $required.Control = 1
                $required.Privilege.Luid = $luid
                $required.Privilege.Attributes = 2
                $enabled = $false
                if (-not [AcceptanceNative]::PrivilegeCheck($token, [ref]$required, [ref]$enabled)) {
                    $result.errorCode = 'TOKEN_QUERY_FAILED'
                } else { $result.shutdownPrivilegeEnabled = $enabled }
            }
        } finally { [AcceptanceNative]::CloseHandle($token) | Out-Null }
    }
} catch {
    $result.errorCode = 'PROBE_FAILURE'
} finally {
    if ([IO.File]::Exists($probe)) { [IO.File]::Delete($probe) }
    if ([IO.Directory]::Exists($resultRoot)) {
        $json = $result | ConvertTo-Json -Compress
        if ([Text.Encoding]::UTF8.GetByteCount($json) -le 2048) {
            [IO.File]::WriteAllText($temporaryResult, $json, [Text.UTF8Encoding]::new($false))
            [IO.File]::Move($temporaryResult, $resultPath, $true)
        }
    }
}
'@
    return $template.Replace('__FIXTURE__',
        (ConvertTo-SingleQuotedPowerShellLiteral $Inputs.MediaFixture)).Replace('__CONFIG__',
        (ConvertTo-SingleQuotedPowerShellLiteral $Inputs.ProtectedConfig)).Replace('__PROBE__',
        (ConvertTo-SingleQuotedPowerShellLiteral $ProbeFile)).Replace('__RESULT_ROOT__',
        (ConvertTo-SingleQuotedPowerShellLiteral $ResultRoot))
}

function Read-InstalledWorkerProbeResult {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw 'The probe result is unavailable.'
    }
    $file = Get-Item -LiteralPath $Path -Force
    if ($file.Length -gt 4096) { throw 'The probe result is too large.' }
    try { $result = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -ErrorAction Stop }
    catch { throw 'The probe result is invalid JSON.' }
    $expected = @(
        'configReadDenied','errorCode','fixtureReadable','identity','privateCreate',
        'privateDelete','privateRead','schemaVersion','shutdownPrivilegeEnabled',
        'websiteServiceControlDenied')
    $actual = @($result.PSObject.Properties.Name | Sort-Object)
    if ([string]::Join('|', $actual) -ne [string]::Join('|', $expected)) {
        throw 'The probe result schema is invalid.'
    }
    if ($result.schemaVersion -isnot [long] -or $result.schemaVersion -ne 1 -or
        $result.identity -isnot [string] -or $result.identity.Length -gt 128 -or
        $result.errorCode -isnot [string] -or
        $result.errorCode -notin @('NONE','PROBE_FAILURE','SERVICE_MANAGER_QUERY_FAILED',
            'SERVICE_HANDLE_CHECK_FAILED','TOKEN_QUERY_FAILED')) {
        throw 'The probe result schema is invalid.'
    }
    foreach ($field in @('fixtureReadable','privateCreate','privateRead','privateDelete',
        'configReadDenied','websiteServiceControlDenied','shutdownPrivilegeEnabled')) {
        if ($result.$field -isnot [bool]) { throw 'The probe result schema is invalid.' }
    }
    return $result
}

function Assert-InstalledWorkerInstallation {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Service,
        [Parameter(Mandatory)]$Inputs
    )

    if ($Service.StartName -ne 'NT AUTHORITY\LocalService' -or
        $Service.StartMode -ne 'Auto' -or $Service.ProcessId -lt 1) {
        throw 'The installed worker service identity or startup state is invalid.'
    }
    $serviceRoot = 'C:\ProgramData\christopherbell.dev\service'
    $installed = @{
        WorkerExe = Join-Path $serviceRoot 'ChristopherBellMediaWorker.exe'
        WebsiteExe = Join-Path $serviceRoot 'ChristopherBellDev.exe'
        Xml = Join-Path $serviceRoot 'ChristopherBellMediaWorker.xml'
        Script = Join-Path $serviceRoot 'Start-SharedFolderMediaWorker.ps1'
        Module = Join-Path $serviceRoot 'Production.SharedFolderWorker.psm1'
    }
    foreach ($key in @($installed.Keys)) {
        $pathEnvironment = @{ InstalledPath = $installed[$key] }
        $installed[$key] = Resolve-InstalledWorkerAcceptancePath -Environment $pathEnvironment `
            -Name InstalledPath -Directory $false -GetItemAction {
                param($Path)
                Get-Item -LiteralPath $Path -Force -ErrorAction Stop
            }
    }
    $servicePath = ([string]$Service.PathName).Trim().Trim('"')
    if (-not [string]::Equals($servicePath, $installed.WorkerExe,
        [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The installed worker service executable is unexpected.'
    }
    if ((Get-FileHash $installed.WorkerExe -Algorithm SHA256).Hash -ne
        (Get-FileHash $installed.WebsiteExe -Algorithm SHA256).Hash) {
        throw 'The installed worker executable hash is unverified.'
    }
    foreach ($copy in @(
        @($installed.Xml, (Join-Path $PSScriptRoot '..\service\ChristopherBellMediaWorker.xml')),
        @($installed.Script, (Join-Path $PSScriptRoot '..\service\Start-SharedFolderMediaWorker.ps1')),
        @($installed.Module, (Join-Path $PSScriptRoot '..\modules\Production.SharedFolderWorker.psm1'))
    )) {
        if ((Get-FileHash $copy[0] -Algorithm SHA256).Hash -ne
            (Get-FileHash $copy[1] -Algorithm SHA256).Hash) {
            throw 'An installed worker configuration hash is unverified.'
        }
    }
    [xml]$configuration = Get-Content -LiteralPath $installed.Xml -Raw
    $expectedPwsh = Join-Path $env:ProgramFiles 'PowerShell\7\pwsh.exe'
    $expandedExecutable = [Environment]::ExpandEnvironmentVariables(
        [string]$configuration.service.executable)
    if ([string]$configuration.service.serviceaccount.username -ne
            'NT AUTHORITY\LocalService' -or
        [string]$configuration.service.startmode -ne 'Automatic' -or
        [string]$configuration.service.delayedAutoStart -ne 'true' -or
        [string]$configuration.service.priority -ne 'belownormal' -or
        -not [string]::Equals($expandedExecutable, $expectedPwsh,
            [StringComparison]::OrdinalIgnoreCase) -or
        [string]$configuration.service.arguments -notmatch
            '^-NoLogo -NoProfile -NonInteractive -ExecutionPolicy RemoteSigned -File "%BASE%\\Start-SharedFolderMediaWorker\.ps1"$') {
        throw 'The installed worker service configuration is unexpected.'
    }
    $delayedStart = Get-ItemPropertyValue -LiteralPath `
        'HKLM:\SYSTEM\CurrentControlSet\Services\ChristopherBellMediaWorker' `
        -Name DelayedAutoStart -ErrorAction Stop
    $serviceController = Get-Service -Name ChristopherBellMediaWorker -ErrorAction Stop
    if ($delayedStart -ne 1 -or $serviceController.StartType -ne 'Automatic') {
        throw 'The installed worker startup state is invalid.'
    }

    $processes = @(Get-CimInstance Win32_Process -ErrorAction Stop)
    $descendants = [Collections.Generic.List[object]]::new()
    $parents = [Collections.Generic.Queue[uint32]]::new()
    $parents.Enqueue([uint32]$Service.ProcessId)
    while ($parents.Count -gt 0) {
        $parentId = $parents.Dequeue()
        foreach ($child in @($processes | Where-Object ParentProcessId -eq $parentId)) {
            if ($descendants.Count -ge 32) { throw 'The worker process tree is unexpectedly large.' }
            $descendants.Add($child)
            $parents.Enqueue([uint32]$child.ProcessId)
        }
    }
    $workerProcess = @($descendants | Where-Object {
        [string]::Equals($_.ExecutablePath, $expectedPwsh,
            [StringComparison]::OrdinalIgnoreCase)
    })
    if ($workerProcess.Count -ne 1) { throw 'The worker process tree is unexpected.' }
    $priority = (Get-Process -Id $workerProcess[0].ProcessId -ErrorAction Stop).PriorityClass
    if ($priority -ne [Diagnostics.ProcessPriorityClass]::BelowNormal) {
        throw 'The worker process priority is unexpected.'
    }
    $toolRoot = Join-Path $Inputs.PrivateRoot 'shared-folder-media-tools'
    Assert-PathHasNoReparseComponent -Path $toolRoot -Root $Inputs.PrivateRoot
    $tools = Assert-PinnedMediaToolSet -ToolRoot $toolRoot

    return [pscustomobject]@{
        Service = $Service
        Installed = $installed
        WorkerProcessId = $workerProcess[0].ProcessId
        Tools = $tools
    }
}

function Invoke-InstalledWorkerLocalServiceProbe {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Inputs)

    $nonce = [guid]::NewGuid().ToString('N')
    $taskName = "ChristopherBellMediaWorkerAcceptance-$nonce"
    $programData = [IO.Path]::GetFullPath(
        [Environment]::GetFolderPath([Environment+SpecialFolder]::CommonApplicationData))
    $programDataEnvironment = @{ ProgramData = $programData }
    $programData = Resolve-InstalledWorkerAcceptancePath `
        -Environment $programDataEnvironment -Name ProgramData -Directory $true `
        -GetItemAction {
            param($Path)
            Get-Item -LiteralPath $Path -Force -ErrorAction Stop
        }
    $resultRoot = Join-Path $programData "ChristopherBellWorkerAcceptance-$nonce"
    if (-not (Test-StrictlyBelowPath -Path $resultRoot -Root $programData)) {
        throw 'The unique acceptance result path is invalid.'
    }
    $resultPath = Join-Path $resultRoot 'result.json'
    $probeFile = Join-Path $Inputs.PrivateProbeDirectory "acceptance-$nonce.bin"
    $registered = $false
    try {
        if ((Test-Path -LiteralPath $resultRoot) -or (Test-Path -LiteralPath $probeFile)) {
            throw 'A unique acceptance resource already exists.'
        }
        $script = New-InstalledWorkerProbeScript -Inputs $Inputs `
            -ResultRoot $resultRoot -ProbeFile $probeFile
        $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($script))
        $pwsh = (Get-Command pwsh.exe -ErrorAction Stop).Source
        $arguments = "-NoLogo -NoProfile -NonInteractive -EncodedCommand $encoded"
        $action = New-ScheduledTaskAction -Execute $pwsh -Argument $arguments
        $principal = New-ScheduledTaskPrincipal -UserId 'NT AUTHORITY\LOCAL SERVICE' `
            -LogonType ServiceAccount -RunLevel Limited
        $settings = New-ScheduledTaskSettingsSet -Hidden `
            -ExecutionTimeLimit ([TimeSpan]::FromMinutes(1)) `
            -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
        $task = New-ScheduledTask -Action $action -Principal $principal -Settings $settings
        Register-ScheduledTask -TaskName $taskName -InputObject $task -ErrorAction Stop |
            Out-Null
        $registered = $true
        $registeredTask = Get-ScheduledTask -TaskName $taskName -ErrorAction Stop
        if ($registeredTask.Actions.Count -ne 1 -or
            -not [string]::Equals($registeredTask.Actions[0].Execute, $pwsh,
                [StringComparison]::OrdinalIgnoreCase) -or
            $registeredTask.Actions[0].Arguments -ne $arguments -or
            $registeredTask.Principal.UserId -ne 'NT AUTHORITY\LOCAL SERVICE' -or
            -not $registeredTask.Settings.Hidden -or
            $registeredTask.Settings.ExecutionTimeLimit -ne 'PT1M') {
            throw 'The registered acceptance task action is unexpected.'
        }
        Start-ScheduledTask -TaskName $taskName -ErrorAction Stop
        $deadline = [DateTime]::UtcNow.AddSeconds(45)
        while (-not (Test-Path -LiteralPath $resultPath -PathType Leaf) -and
            [DateTime]::UtcNow -lt $deadline) {
            Start-Sleep -Milliseconds 250
        }
        if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
            throw 'The bounded LocalService probe did not produce a result.'
        }
        return Read-InstalledWorkerProbeResult -Path $resultPath
    } finally {
        if ($registered) {
            $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
            if ($task -and $task.State -eq 'Running') {
                Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
            }
            Unregister-ScheduledTask -TaskName $taskName -Confirm:$false `
                -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $probeFile -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $resultRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
}

Describe 'installed-worker acceptance guard and probe safety' {
    BeforeEach {
        $visibleRoot = Join-Path $TestDrive 'visible'
        $privateRoot = Join-Path $TestDrive 'private'
        $probeDirectory = Join-Path $privateRoot 'acceptance-probe'
        $outsideRoot = Join-Path $TestDrive 'outside'
        New-Item -ItemType Directory -Path $visibleRoot,$privateRoot,$probeDirectory,$outsideRoot -Force |
            Out-Null
        $mediaFixture = Join-Path $visibleRoot 'fixture.bin'
        $protectedConfig = Join-Path $outsideRoot 'app.env'
        [IO.File]::WriteAllBytes($mediaFixture, [byte[]](1..8))
        [IO.File]::WriteAllText($protectedConfig, 'protected')
        $acceptanceEnvironment = @{
            CBDEV_INSTALLED_WORKER_ACCEPTANCE = 'I_ACCEPT_LOCAL_SERVICE_PROBE'
            CBDEV_WORKER_ACCEPTANCE_VISIBLE_ROOT = $visibleRoot
            CBDEV_WORKER_ACCEPTANCE_MEDIA_FIXTURE = $mediaFixture
            CBDEV_WORKER_ACCEPTANCE_PRIVATE_ROOT = $privateRoot
            CBDEV_WORKER_ACCEPTANCE_PRIVATE_PROBE_DIRECTORY = $probeDirectory
            CBDEV_WORKER_ACCEPTANCE_PROTECTED_CONFIG = $protectedConfig
        }
    }

    It 'requires Windows elevation and the exact opt-in value' {
        Test-InstalledWorkerAcceptanceRequested -Windows $true -Elevated $true `
            -OptInValue 'I_ACCEPT_LOCAL_SERVICE_PROBE' | Should -BeTrue
        Test-InstalledWorkerAcceptanceRequested -Windows $false -Elevated $true `
            -OptInValue 'I_ACCEPT_LOCAL_SERVICE_PROBE' | Should -BeFalse
        Test-InstalledWorkerAcceptanceRequested -Windows $true -Elevated $false `
            -OptInValue 'I_ACCEPT_LOCAL_SERVICE_PROBE' | Should -BeFalse
        Test-InstalledWorkerAcceptanceRequested -Windows $true -Elevated $true `
            -OptInValue 'true' | Should -BeFalse
    }

    It 'canonicalizes only complete existing path inputs with the required root relationships' {
        $inputs = Get-InstalledWorkerAcceptanceInputs -Environment $acceptanceEnvironment

        $inputs.VisibleRoot | Should -Be ([IO.Path]::GetFullPath($visibleRoot))
        $inputs.MediaFixture | Should -Be ([IO.Path]::GetFullPath($mediaFixture))
        $inputs.PrivateRoot | Should -Be ([IO.Path]::GetFullPath($privateRoot))
        $inputs.PrivateProbeDirectory | Should -Be ([IO.Path]::GetFullPath($probeDirectory))
        $inputs.ProtectedConfig | Should -Be ([IO.Path]::GetFullPath($protectedConfig))
    }

    It 'rejects missing relative out-of-root and reparse-point path inputs before effects' {
        foreach ($key in @(
            'CBDEV_WORKER_ACCEPTANCE_VISIBLE_ROOT',
            'CBDEV_WORKER_ACCEPTANCE_MEDIA_FIXTURE',
            'CBDEV_WORKER_ACCEPTANCE_PRIVATE_ROOT',
            'CBDEV_WORKER_ACCEPTANCE_PRIVATE_PROBE_DIRECTORY',
            'CBDEV_WORKER_ACCEPTANCE_PROTECTED_CONFIG')) {
            $missing = $acceptanceEnvironment.Clone()
            $missing[$key] = ''
            { Get-InstalledWorkerAcceptanceInputs -Environment $missing } |
                Should -Throw "*$key*"
        }

        $relative = $acceptanceEnvironment.Clone()
        $relative.CBDEV_WORKER_ACCEPTANCE_MEDIA_FIXTURE = 'fixture.bin'
        { Get-InstalledWorkerAcceptanceInputs -Environment $relative } |
            Should -Throw '*CBDEV_WORKER_ACCEPTANCE_MEDIA_FIXTURE*'

        $outside = $acceptanceEnvironment.Clone()
        $outside.CBDEV_WORKER_ACCEPTANCE_MEDIA_FIXTURE = $protectedConfig
        { Get-InstalledWorkerAcceptanceInputs -Environment $outside } |
            Should -Throw '*media fixture*'

        $reparseAttributes = {
            param($path)
            $item = Get-Item -LiteralPath $path -Force -ErrorAction Stop
            if ([string]::Equals($item.FullName, $mediaFixture, [StringComparison]::OrdinalIgnoreCase)) {
                return [pscustomobject]@{
                    FullName = $item.FullName
                    PSIsContainer = $item.PSIsContainer
                    Attributes = $item.Attributes -bor [IO.FileAttributes]::ReparsePoint
                    Length = $item.Length
                }
            }
            return $item
        }.GetNewClosure()
        { Get-InstalledWorkerAcceptanceInputs -Environment $acceptanceEnvironment `
            -GetItemAction $reparseAttributes } | Should -Throw '*reparse*'
    }

    It 'rejects absent or stopped installed services without invoking a probe effect' {
        $effects = [Collections.Generic.List[string]]::new()
        { Assert-InstalledWorkerServiceReady -GetServiceAction { $effects.Add('service'); $null } } |
            Should -Throw '*unavailable*'
        $effects | Should -Be @('service')

        { Assert-InstalledWorkerServiceReady -GetServiceAction {
            [pscustomobject]@{ Name = 'ChristopherBellMediaWorker'; State = 'Stopped' }
        } } | Should -Throw '*not running*'
    }

    It 'builds a parseable probe that inspects handles and token state without prohibited actions' {
        $inputs = Get-InstalledWorkerAcceptanceInputs -Environment $acceptanceEnvironment
        $resultRoot = Join-Path $TestDrive 'result'
        $probeFile = Join-Path $probeDirectory 'probe.bin'
        $probeScript = New-InstalledWorkerProbeScript -Inputs $inputs `
            -ResultRoot $resultRoot -ProbeFile $probeFile
        $tokens = $null
        $parseErrors = $null
        $ast = [Management.Automation.Language.Parser]::ParseInput(
            $probeScript, [ref]$tokens, [ref]$parseErrors)

        $parseErrors.Count | Should -Be 0
        $ast | Should -Not -BeNullOrEmpty
        $probeScript | Should -Match 'OpenServiceW'
        $probeScript | Should -Match 'PrivilegeCheck'
        foreach ($forbidden in @(
            'ControlService', 'Stop-Service', 'Restart-Service', 'shutdown.exe',
            'Stop-Computer', 'Restart-Computer', 'AdjustTokenPrivileges', 'Set-Acl',
            'icacls.exe', 'sc.exe')) {
            $probeScript | Should -Not -Match ([regex]::Escape($forbidden))
        }
    }

    It 'accepts only the fixed bounded probe JSON schema' {
        $resultPath = Join-Path $TestDrive 'result.json'
        $valid = [ordered]@{
            schemaVersion = 1
            identity = 'NT AUTHORITY\LOCAL SERVICE'
            fixtureReadable = $true
            privateCreate = $true
            privateRead = $true
            privateDelete = $true
            configReadDenied = $true
            websiteServiceControlDenied = $true
            shutdownPrivilegeEnabled = $false
            errorCode = 'NONE'
        }
        $valid | ConvertTo-Json -Compress | Set-Content -LiteralPath $resultPath -Encoding utf8
        (Read-InstalledWorkerProbeResult -Path $resultPath).schemaVersion | Should -Be 1

        $invalid = [ordered]@{} + $valid
        $invalid.extra = 'not allowed'
        $invalid | ConvertTo-Json -Compress | Set-Content -LiteralPath $resultPath -Encoding utf8
        { Read-InstalledWorkerProbeResult -Path $resultPath } | Should -Throw '*schema*'

        [IO.File]::WriteAllText($resultPath, 'x' * 4097)
        { Read-InstalledWorkerProbeResult -Path $resultPath } | Should -Throw '*large*'
    }
}

$installedWorkerAcceptanceRequested = $IsWindows -and $isElevatedWindows -and
    ($env:CBDEV_INSTALLED_WORKER_ACCEPTANCE -ceq 'I_ACCEPT_LOCAL_SERVICE_PROBE')

Describe 'installed worker LocalService security acceptance' `
    -Tag 'InstalledWorkerSecurityAcceptance' `
    -Skip:(-not $installedWorkerAcceptanceRequested) {
    It 'verifies the installed worker and its bounded negative capabilities' {
        $inputs = Get-InstalledWorkerAcceptanceInputs `
            -Environment (Get-InstalledWorkerAcceptanceEnvironment)
        $service = Assert-InstalledWorkerServiceReady
        $installation = Assert-InstalledWorkerInstallation -Service $service -Inputs $inputs
        $installation | Should -Not -BeNullOrEmpty

        $result = Invoke-InstalledWorkerLocalServiceProbe -Inputs $inputs
        $result.identity | Should -Be 'NT AUTHORITY\LOCAL SERVICE'
        $result.fixtureReadable | Should -BeTrue
        $result.privateCreate | Should -BeTrue
        $result.privateRead | Should -BeTrue
        $result.privateDelete | Should -BeTrue
        $result.configReadDenied | Should -BeTrue
        $result.websiteServiceControlDenied | Should -BeTrue
        $result.shutdownPrivilegeEnabled | Should -BeFalse
        $result.errorCode | Should -Be 'NONE'
    }
}

Describe 'protected production NTFS integration' -Tag 'WindowsAclIntegration' -Skip:(-not $isElevatedWindows) {
    It 'applies effective LocalService read-only and private-write ACL boundaries to temporary roots' {
        $sharedRoot = Join-Path $TestDrive 'effective-shared'
        $systemRoot = Join-Path $TestDrive 'effective-system'
        $paths = New-SharedFolderRuntimeDirectories -SharedRoot $sharedRoot -SystemRoot $systemRoot

        Set-SharedFolderRuntimeAcls -SharedRoot $sharedRoot -SystemRoot $systemRoot

        $sharedRules = @(Get-Acl $paths.SharedRoot |
            ForEach-Object { $_.GetAccessRules($true,$false,[Security.Principal.SecurityIdentifier]) })
        $privateRules = @(Get-Acl $paths.StagingRoot |
            ForEach-Object { $_.GetAccessRules($true,$false,[Security.Principal.SecurityIdentifier]) })
        $sharedWorker = $sharedRules | Where-Object IdentityReference -eq 'S-1-5-19'
        $privateWorker = $privateRules | Where-Object IdentityReference -eq 'S-1-5-19'
        ($sharedWorker.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Modify) |
            Should -Not -Be ([Security.AccessControl.FileSystemRights]::Modify)
        ($privateWorker.FileSystemRights -band [Security.AccessControl.FileSystemRights]::Modify) |
            Should -Be ([Security.AccessControl.FileSystemRights]::Modify)
        [xml]$service = Get-Content (Join-Path $PSScriptRoot '..\service\ChristopherBellMediaWorker.xml') -Raw
        [string]$service.service.serviceaccount.username | Should -Be 'NT AUTHORITY\LocalService'
    }

    It 'removes low-privilege writes throughout a real tree and rejects nested junctions' {
        $root = Join-Path $TestDrive 'protected-root'
        $nested = Join-Path $root 'tools\modules'
        New-Item -ItemType Directory -Path $nested -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $nested 'Production.Sensors.psm1') -Value 'harmless'

        $acl = Get-Acl -LiteralPath $root
        $users = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-545')
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            $users,
            [Security.AccessControl.FileSystemRights]::Modify,
            [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit',
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow)
        [void]$acl.AddAccessRule($rule)
        Set-Acl -LiteralPath $root -AclObject $acl

        Protect-ProductionTree -Path $root
        Assert-ProtectedProductionTree -Path $root

        foreach ($item in @((Get-Item -LiteralPath $root)) + @(Get-ChildItem -LiteralPath $root -Recurse -Force)) {
            $itemAcl = Get-Acl -LiteralPath $item.FullName
            $itemAcl.AreAccessRulesProtected | Should -BeTrue
            @($itemAcl.GetAccessRules($true, $false, [Security.Principal.SecurityIdentifier]) |
                Where-Object IdentityReference -eq $users).Count | Should -Be 0
        }

        $junctionRoot = Join-Path $TestDrive 'junction-root'
        $target = Join-Path $TestDrive 'junction-target'
        New-Item -ItemType Directory -Path $junctionRoot,$target | Out-Null
        New-Item -ItemType Junction -Path (Join-Path $junctionRoot 'nested-link') -Target $target | Out-Null
        { Protect-ProductionTree -Path $junctionRoot } | Should -Throw '*reparse*'
    }

    It 'denies a standard local account from writing the protected tree' {
        $nonce = [guid]::NewGuid().ToString('N')
        $userName = "cbacl$($nonce.Substring(0, 10))"
        $password = "A1!$([guid]::NewGuid().ToString('N'))"
        $securePassword = ConvertTo-SecureString $password -AsPlainText -Force
        $credential = [pscredential]::new("$env:COMPUTERNAME\$userName", $securePassword)
        $testRoot = Join-Path $env:ProgramData "ChristopherBellDevAclTests\$nonce"
        $control = Join-Path $testRoot 'control'
        $root = Join-Path $testRoot 'protected'
        $attempt = Join-Path $control 'attempt.ps1'
        $result = Join-Path $control 'result.json'
        $blocked = Join-Path $root 'blocked.txt'
        $process = $null
        try {
            $user = New-LocalUser -Name $userName -Password $securePassword -AccountNeverExpires -PasswordNeverExpires
            New-Item -ItemType Directory -Path $control,$root -Force | Out-Null
            $controlAcl = Get-Acl -LiteralPath $control
            $controlRule = [Security.AccessControl.FileSystemAccessRule]::new(
                $user.Sid,
                [Security.AccessControl.FileSystemRights]::Modify,
                [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit',
                [Security.AccessControl.PropagationFlags]::None,
                [Security.AccessControl.AccessControlType]::Allow)
            [void]$controlAcl.AddAccessRule($controlRule)
            Set-Acl -LiteralPath $control -AclObject $controlAcl
            Protect-ProductionTree -Path $root
            Assert-ProtectedProductionTree -Path $root

            @"
`$tokenIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
`$tokenPrincipal = [Security.Principal.WindowsPrincipal]`$tokenIdentity
`$tokenIsAdministrator = `$tokenPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
`$writeResult = 'unknown'
try {
    Set-Content -LiteralPath '$($blocked.Replace("'", "''"))' -Value 'unexpected' -ErrorAction Stop
    `$writeResult = 'allowed'
} catch {
    `$writeResult = 'denied'
}
[pscustomobject]@{
    Identity = `$tokenIdentity.Name
    IsAdministrator = `$tokenIsAdministrator
    WriteResult = `$writeResult
} | ConvertTo-Json | Set-Content -LiteralPath '$($result.Replace("'", "''"))'
"@ | Set-Content -LiteralPath $attempt -Encoding utf8
            $process = Start-Process -FilePath (Get-Command pwsh.exe).Source -Credential $credential -LoadUserProfile:$false -WindowStyle Hidden -WorkingDirectory $control -ArgumentList @('-NoLogo','-NoProfile','-File',"`"$attempt`"") -Wait -PassThru
            $process.ExitCode | Should -Be 0
            Test-Path -LiteralPath $result | Should -BeTrue
            $attemptResult = Get-Content -LiteralPath $result -Raw | ConvertFrom-Json
            $attemptResult.Identity | Should -Be "$env:COMPUTERNAME\$userName"
            $attemptResult.IsAdministrator | Should -BeFalse
            $attemptResult.WriteResult | Should -Be 'denied'
            Test-Path -LiteralPath $blocked | Should -BeFalse
        } finally {
            if ($process -and -not $process.HasExited) {
                Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            }
            Remove-LocalUser -Name $userName -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
