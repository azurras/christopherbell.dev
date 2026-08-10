Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:MarkerStates = @(
    'TARGET_ACTIVE',
    'TARGET_CUTOVER_IN_PROGRESS',
    'LEGACY_ACTIVE_RECONCILIATION_REQUIRED'
)
$script:AuthorizationPurposes = @(
    'TARGET_CUTOVER',
    'TARGET_DEPLOY',
    'TARGET_RECONCILIATION',
    'LEGACY_ROLLBACK',
    'LEGACY_RESTORE'
)

if (-not ('ChristopherBell.Dev.ProductionFixedRootNativeDirectory' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

namespace ChristopherBell.Dev
{
    public sealed class ProductionFixedRootDirectoryIdentity
    {
        public string NativeFinalPath { get; set; }
        public uint VolumeSerialNumber { get; set; }
        public ulong FileIndex { get; set; }
        public bool ReparsePoint { get; set; }
    }

    public static class ProductionFixedRootNativeDirectory
    {
        private const uint FileReadAttributes = 0x80;
        private const uint FileShareRead = 0x1;
        private const uint FileShareWrite = 0x2;
        private const uint FileShareDelete = 0x4;
        private const uint OpenExisting = 3;
        private const uint FileFlagBackupSemantics = 0x02000000;
        private const uint FileFlagOpenReparsePoint = 0x00200000;
        private const uint FileAttributeReparsePoint = 0x400;

        [StructLayout(LayoutKind.Sequential)]
        private struct FileTime
        {
            public uint LowDateTime;
            public uint HighDateTime;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct ByHandleFileInformation
        {
            public uint FileAttributes;
            public FileTime CreationTime;
            public FileTime LastAccessTime;
            public FileTime LastWriteTime;
            public uint VolumeSerialNumber;
            public uint FileSizeHigh;
            public uint FileSizeLow;
            public uint NumberOfLinks;
            public uint FileIndexHigh;
            public uint FileIndexLow;
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateFileW(
            string fileName,
            uint desiredAccess,
            uint shareMode,
            IntPtr securityAttributes,
            uint creationDisposition,
            uint flagsAndAttributes,
            IntPtr templateFile);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern uint GetFinalPathNameByHandleW(
            IntPtr file,
            StringBuilder filePath,
            uint filePathLength,
            uint flags);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetFileInformationByHandle(
            IntPtr file,
            out ByHandleFileInformation fileInformation);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool CloseHandle(IntPtr handle);

        public static ProductionFixedRootDirectoryIdentity GetIdentity(string path)
        {
            IntPtr handle = CreateFileW(
                path,
                FileReadAttributes,
                FileShareRead | FileShareWrite | FileShareDelete,
                IntPtr.Zero,
                OpenExisting,
                FileFlagBackupSemantics | FileFlagOpenReparsePoint,
                IntPtr.Zero);
            if (handle == new IntPtr(-1))
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(),
                    "Fixed production directory identity could not be opened.");
            }
            try
            {
                ByHandleFileInformation information;
                if (!GetFileInformationByHandle(handle, out information))
                {
                    throw new Win32Exception(Marshal.GetLastWin32Error(),
                        "Fixed production directory identity could not be read.");
                }
                StringBuilder finalPath = new StringBuilder(1024);
                uint length = GetFinalPathNameByHandleW(
                    handle, finalPath, (uint)finalPath.Capacity, 0);
                if (length == 0)
                {
                    throw new Win32Exception(Marshal.GetLastWin32Error(),
                        "Fixed production directory final path could not be read.");
                }
                if (length >= finalPath.Capacity)
                {
                    finalPath = new StringBuilder((int)length + 1);
                    length = GetFinalPathNameByHandleW(
                        handle, finalPath, (uint)finalPath.Capacity, 0);
                    if (length == 0 || length >= finalPath.Capacity)
                    {
                        throw new Win32Exception(Marshal.GetLastWin32Error(),
                            "Fixed production directory final path could not be read.");
                    }
                }
                return new ProductionFixedRootDirectoryIdentity
                {
                    NativeFinalPath = NormalizeFinalPath(finalPath.ToString()),
                    VolumeSerialNumber = information.VolumeSerialNumber,
                    FileIndex = ((ulong)information.FileIndexHigh << 32) |
                        information.FileIndexLow,
                    ReparsePoint =
                        (information.FileAttributes & FileAttributeReparsePoint) != 0
                };
            }
            finally
            {
                CloseHandle(handle);
            }
        }

        private static string NormalizeFinalPath(string path)
        {
            const string uncPrefix = @"\\?\UNC\";
            const string localPrefix = @"\\?\";
            if (path.StartsWith(uncPrefix, StringComparison.OrdinalIgnoreCase))
            {
                return @"\\" + path.Substring(uncPrefix.Length);
            }
            if (path.StartsWith(localPrefix, StringComparison.OrdinalIgnoreCase))
            {
                return path.Substring(localPrefix.Length);
            }
            return path;
        }
    }
}
'@
}

function Get-ProductionFixedRootDirectoryIdentity {
    param([Parameter(Mandatory)][string]$Path)

    [ChristopherBell.Dev.ProductionFixedRootNativeDirectory]::GetIdentity($Path)
}

function Get-ProductionMusicSchemaDirectionPath {
    param([Parameter(Mandatory)]$Config)
    Join-Path $Config.programDataRoot 'state\music-runtime-schema-direction.json'
}

function Get-ProductionWriterStartAuthorizationPath {
    param([Parameter(Mandatory)]$Config)
    Join-Path $Config.programDataRoot 'state\music-runtime-pending-start.json'
}

function Get-ProductionWriterStartGuardManifestPath {
    param([Parameter(Mandatory)]$Config)
    Join-Path $Config.programDataRoot 'service\Production.WriterStart.bundle.json'
}

function Assert-ProductionWriterStartPathNotReparseTraversal {
    param([Parameter(Mandatory)][string]$Path)

    $fullPath = [IO.Path]::GetFullPath($Path)
    $pathRoot = [IO.Path]::GetPathRoot($fullPath)
    if ([string]::IsNullOrWhiteSpace($pathRoot)) {
        throw 'Writer-start guard path must be an absolute Windows path.'
    }
    $current = $pathRoot
    $relative = $fullPath.Substring($pathRoot.Length)
    foreach ($segment in @($relative -split '[\\/]' | Where-Object { $_ })) {
        $current = [IO.Path]::Combine($current, $segment)
        $item = Get-Item -LiteralPath $current -Force -ErrorAction Stop
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "Writer-start guard path traversal contains a reparse point: $current"
        }
    }
    return $fullPath
}

function Assert-ProductionFixedRootDirectoryAcl {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [Security.AccessControl.RawSecurityDescriptor]$SecurityDescriptor
    )

    Assert-ProductionWriterStartPathNotReparseTraversal -Path $Path | Out-Null
    if (-not $PSBoundParameters.ContainsKey('SecurityDescriptor')) {
        $acl = Get-Acl -LiteralPath $Path -ErrorAction Stop
        $SecurityDescriptor =
            [Security.AccessControl.RawSecurityDescriptor]::new(
                $acl.GetSecurityDescriptorBinaryForm(), 0)
    }
    $protectedFlag =
        [Security.AccessControl.ControlFlags]::DiscretionaryAclProtected
    if (($SecurityDescriptor.ControlFlags -band $protectedFlag) -eq 0) {
        throw "Production root ACL inheritance must be protected: $Path"
    }
    $administrators = 'S-1-5-32-544'
    if ($null -eq $SecurityDescriptor.Owner -or
        $SecurityDescriptor.Owner.Value -cne $administrators) {
        throw "Production root ACL owner must be Builtin Administrators: $Path"
    }
    $aces = @($SecurityDescriptor.DiscretionaryAcl)
    if ($aces.Count -ne 2) {
        throw "Production root ACL must have exactly two explicit ACEs: $Path"
    }
    $system = 'S-1-5-18'
    $systemAces = @($aces | Where-Object {
            $_ -is [Security.AccessControl.CommonAce] -and
            $_.AceType -eq [Security.AccessControl.AceType]::AccessAllowed -and
            $_.SecurityIdentifier.Value -ceq $system
        })
    $administratorAces = @($aces | Where-Object {
            $_ -is [Security.AccessControl.CommonAce] -and
            $_.AceType -eq [Security.AccessControl.AceType]::AccessAllowed -and
            $_.SecurityIdentifier.Value -ceq $administrators
        })
    if ($systemAces.Count -ne 1 -or $administratorAces.Count -ne 1) {
        throw (
            'Production root ACL must have one SYSTEM and one Administrators allow ACE: ' +
            $Path)
    }
    $fullControl = [int][Security.AccessControl.FileSystemRights]::FullControl
    $inheritance =
        [int][Security.AccessControl.AceFlags]::ContainerInherit -bor
        [int][Security.AccessControl.AceFlags]::ObjectInherit
    foreach ($ace in @($systemAces[0],$administratorAces[0])) {
        if ([int]$ace.AccessMask -ne $fullControl) {
            throw "Production root ACEs must grant exact FullControl rights: $Path"
        }
        if ([int]$ace.AceFlags -ne $inheritance) {
            throw (
                "Production root ACEs must use exact inheritance and propagation: $Path")
        }
    }
}

function Test-ProductionFixedRootDirectoryIdentityEqual {
    param(
        [Parameter(Mandatory)]$Expected,
        [Parameter(Mandatory)]$Actual
    )

    [uint32]$Expected.VolumeSerialNumber -eq
        [uint32]$Actual.VolumeSerialNumber -and
        [uint64]$Expected.FileIndex -eq [uint64]$Actual.FileIndex -and
        [string]::Equals(
            [IO.Path]::GetFullPath([string]$Expected.NativeFinalPath),
            [IO.Path]::GetFullPath([string]$Actual.NativeFinalPath),
            [StringComparison]::OrdinalIgnoreCase)
}

function Assert-ProductionFixedRootDirectoryState {
    param(
        [Parameter(Mandatory)][string]$Path,
        $ExpectedIdentity
    )

    $canonical = Assert-ProductionWriterStartPathNotReparseTraversal -Path $Path
    $first = Get-ProductionFixedRootDirectoryIdentity -Path $canonical
    if ($first.ReparsePoint -or -not [string]::Equals(
            [IO.Path]::GetFullPath([string]$first.NativeFinalPath),
            $canonical,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Production root directory identity is unsafe: $canonical"
    }
    if ($PSBoundParameters.ContainsKey('ExpectedIdentity') -and
        -not (Test-ProductionFixedRootDirectoryIdentityEqual `
            -Expected $ExpectedIdentity -Actual $first)) {
        throw "Production root directory identity changed: $canonical"
    }
    Assert-ProductionFixedRootDirectoryAcl -Path $canonical
    $second = Get-ProductionFixedRootDirectoryIdentity -Path $canonical
    if ($second.ReparsePoint -or
        -not (Test-ProductionFixedRootDirectoryIdentityEqual `
            -Expected $first -Actual $second)) {
        throw "Production root directory identity changed during verification: $canonical"
    }
    return $second
}

function Assert-ProductionFixedRootBoundary {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$FixedRoot,
        $ExpectedBoundary
    )

    try {
        if ($FixedRoot -cnotmatch '^[A-Za-z]:[\\/]') {
            throw 'Fixed production root must use a fully qualified local drive path.'
        }
        $fixed = [IO.Path]::GetFullPath($FixedRoot)
        $property = $Config.PSObject.Properties['programDataRoot']
        if (-not $property -or $property.Value -isnot [string] -or
            [string]::IsNullOrWhiteSpace([string]$property.Value)) {
            throw 'Configured production root is missing or malformed.'
        }
        $configuredValue = [string]$property.Value
        if ($configuredValue -cnotmatch '^[A-Za-z]:[\\/]') {
            throw ('Configured production root must use a fully qualified ' +
                'local drive path.')
        }
        $configuredPathRoot = [IO.Path]::GetPathRoot($configuredValue)
        foreach ($segment in $configuredValue.Substring($configuredPathRoot.Length) -split '[\\/]') {
            if ($segment -in @('.','..')) {
                throw 'Configured production root must not contain relative traversal.'
            }
        }
        $configured = [IO.Path]::GetFullPath($configuredValue)
        if (-not [string]::Equals(
                $configured, $fixed, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Configured production root does not match the fixed production root.'
        }
        $locks = [IO.Path]::GetFullPath((Join-Path $fixed 'locks'))
        $lockPath = [IO.Path]::GetFullPath((Join-Path $locks 'deploy.lock'))
        if (-not [string]::Equals(
                [IO.Path]::GetFullPath((Split-Path -Parent $locks)),
                $fixed,
                [StringComparison]::OrdinalIgnoreCase) -or
            -not [string]::Equals(
                [IO.Path]::GetFullPath((Split-Path -Parent $lockPath)),
                $locks,
                [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Fixed production lock path escaped the fixed root.'
        }
        if ($PSBoundParameters.ContainsKey('ExpectedBoundary')) {
            foreach ($name in 'Root','Locks','LockPath','RootIdentity','LocksIdentity') {
                if (-not $ExpectedBoundary.PSObject.Properties[$name]) {
                    throw 'Expected fixed production boundary is malformed.'
                }
            }
            if (-not [string]::Equals(
                    [string]$ExpectedBoundary.Root, $fixed,
                    [StringComparison]::OrdinalIgnoreCase) -or
                -not [string]::Equals(
                    [string]$ExpectedBoundary.Locks, $locks,
                    [StringComparison]::OrdinalIgnoreCase) -or
                -not [string]::Equals(
                    [string]$ExpectedBoundary.LockPath, $lockPath,
                    [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Expected fixed production boundary paths changed.'
            }
        }
        $rootArguments = @{ Path=$fixed }
        $locksArguments = @{ Path=$locks }
        if ($PSBoundParameters.ContainsKey('ExpectedBoundary')) {
            $rootArguments.ExpectedIdentity = $ExpectedBoundary.RootIdentity
            $locksArguments.ExpectedIdentity = $ExpectedBoundary.LocksIdentity
        }
        $rootIdentity = Assert-ProductionFixedRootDirectoryState @rootArguments
        $locksIdentity = Assert-ProductionFixedRootDirectoryState @locksArguments
        Assert-ProductionFixedRootDirectoryState `
            -Path $fixed -ExpectedIdentity $rootIdentity | Out-Null
        Assert-ProductionFixedRootDirectoryState `
            -Path $locks -ExpectedIdentity $locksIdentity | Out-Null
        $Config.programDataRoot = $fixed
        return [pscustomobject][ordered]@{
            Root = $fixed
            Locks = $locks
            LockPath = $lockPath
            RootIdentity = $rootIdentity
            LocksIdentity = $locksIdentity
        }
    } catch {
        throw [System.InvalidOperationException]::new(
            'Production root boundary is not guarded. Run guarded prod install before retrying.',
            $_.Exception)
    }
}

function Enter-ProductionFixedRootDeploymentLock {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$FixedRoot,
        [scriptblock]$EnterLockAction
    )

    $boundary = Assert-ProductionFixedRootBoundary `
        -Config $Config -FixedRoot $FixedRoot
    $lock = if ($PSBoundParameters.ContainsKey('EnterLockAction')) {
        & $EnterLockAction $boundary.LockPath
    } else {
        Enter-DeploymentLock -LockPath $boundary.LockPath
    }
    try {
        $lockedBoundary = Assert-ProductionFixedRootBoundary `
            -Config $Config `
            -FixedRoot $FixedRoot `
            -ExpectedBoundary $boundary
        return [pscustomobject][ordered]@{
            Lock = $lock
            Boundary = $lockedBoundary
        }
    } catch {
        $lock.Dispose()
        throw
    }
}

function Get-CanonicalProductionWriterStartServiceRoot {
    param([Parameter(Mandatory)]$Config)

    $configuredRoot = [string]$Config.programDataRoot
    if ([string]::IsNullOrWhiteSpace($configuredRoot)) {
        throw 'Writer-start guard production root is missing.'
    }
    $productionRoot = Assert-ProductionWriterStartPathNotReparseTraversal `
        -Path ([IO.Path]::GetFullPath($configuredRoot))
    $serviceRoot = [IO.Path]::GetFullPath((Join-Path $productionRoot 'service'))
    if (-not [string]::Equals(
            [IO.Path]::GetFullPath((Split-Path -Parent $serviceRoot)),
            $productionRoot,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Writer-start guard service directory escaped the production root.'
    }
    return $serviceRoot
}

function New-ProductionWriterStartServiceDirectoryAcl {
    [CmdletBinding()]
    param()

    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $administrators = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    $localService = [Security.Principal.SecurityIdentifier]::new('S-1-5-19')
    $acl.SetOwner($administrators)
    $allow = [Security.AccessControl.AccessControlType]::Allow
    $inheritance = [Security.AccessControl.InheritanceFlags](
        [int][Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
        [int][Security.AccessControl.InheritanceFlags]::ObjectInherit)
    foreach ($identity in @($system,$administrators)) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            $identity,
            [Security.AccessControl.FileSystemRights]::FullControl,
            $inheritance,
            [Security.AccessControl.PropagationFlags]::None,
            $allow)
        [void]$acl.AddAccessRule($rule)
    }
    $localServiceRule = [Security.AccessControl.FileSystemAccessRule]::new(
        $localService,
        [Security.AccessControl.FileSystemRights]::ReadAndExecute,
        $inheritance,
        [Security.AccessControl.PropagationFlags]::None,
        $allow)
    [void]$acl.AddAccessRule($localServiceRule)
    return $acl
}

function Assert-ProductionWriterStartServiceDirectory {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [Security.AccessControl.RawSecurityDescriptor]$SecurityDescriptor
    )

    Assert-ProductionWriterStartPathNotReparseTraversal -Path $Path | Out-Null
    if (-not $PSBoundParameters.ContainsKey('SecurityDescriptor')) {
        $acl = Get-Acl -LiteralPath $Path -ErrorAction Stop
        $SecurityDescriptor = [Security.AccessControl.RawSecurityDescriptor]::new(
            $acl.GetSecurityDescriptorBinaryForm(), 0)
    }
    $protectedFlag =
        [Security.AccessControl.ControlFlags]::DiscretionaryAclProtected
    if (($SecurityDescriptor.ControlFlags -band $protectedFlag) -eq 0) {
        throw "Writer-start service directory ACL inheritance must be protected: $Path"
    }
    $administrators = 'S-1-5-32-544'
    if ($null -eq $SecurityDescriptor.Owner -or
        $SecurityDescriptor.Owner.Value -cne $administrators) {
        throw (
            "Writer-start service directory ACL owner must be Builtin Administrators: $Path")
    }
    $aces = @($SecurityDescriptor.DiscretionaryAcl)
    if ($aces.Count -ne 3) {
        throw (
            "Writer-start service directory ACL must have exactly three ACEs: $Path")
    }
    $expectedIdentities = @('S-1-5-18',$administrators,'S-1-5-19')
    $exactAces = @{}
    foreach ($identity in $expectedIdentities) {
        $matching = @($aces | Where-Object {
                $_ -is [Security.AccessControl.CommonAce] -and
                $_.AceType -eq [Security.AccessControl.AceType]::AccessAllowed -and
                $_.SecurityIdentifier.Value -ceq $identity
            })
        if ($matching.Count -ne 1) {
            throw ('Writer-start service directory ACL must have one SYSTEM, one ' +
                "Administrators, and one LocalService allow ACE: $Path")
        }
        $exactAces[$identity] = $matching[0]
    }
    $inheritance =
        [int][Security.AccessControl.AceFlags]::ObjectInherit -bor
        [int][Security.AccessControl.AceFlags]::ContainerInherit
    foreach ($ace in $exactAces.Values) {
        if ([int]$ace.AceFlags -ne $inheritance) {
            throw ('Writer-start service directory ACEs must use exact ObjectInherit ' +
                "and ContainerInherit flags: $Path")
        }
    }
    $fullControl = [int][Security.AccessControl.FileSystemRights]::FullControl
    foreach ($identity in @('S-1-5-18',$administrators)) {
        if ([int]$exactAces[$identity].AccessMask -ne $fullControl) {
            throw ('Writer-start service directory SYSTEM and Administrators must have ' +
                "exact FullControl rights: $Path")
        }
    }
    $localServiceRights = [int](
        [Security.AccessControl.FileSystemRights]::ReadAndExecute -bor
        [Security.AccessControl.FileSystemRights]::Synchronize)
    if ([int]$exactAces['S-1-5-19'].AccessMask -ne $localServiceRights) {
        throw ('Writer-start service directory LocalService must have exact ' +
            "ReadAndExecute and Synchronize rights: $Path")
    }
}

function Protect-ProductionWriterStartServiceDirectory {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)

    Assert-ProductionWriterStartPathNotReparseTraversal -Path $Path | Out-Null
    Set-Acl -LiteralPath $Path `
        -AclObject (New-ProductionWriterStartServiceDirectoryAcl) -ErrorAction Stop
    Assert-ProductionWriterStartServiceDirectory -Path $Path
}

function New-ProductionWriterStartServiceFileAcl {
    [CmdletBinding()]
    param()

    $acl = [Security.AccessControl.FileSecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $administrators = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    $localService = [Security.Principal.SecurityIdentifier]::new('S-1-5-19')
    $acl.SetOwner($administrators)
    $allow = [Security.AccessControl.AccessControlType]::Allow
    foreach ($identity in @($system,$administrators)) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            $identity,
            [Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.InheritanceFlags]::None,
            [Security.AccessControl.PropagationFlags]::None,
            $allow)
        [void]$acl.AddAccessRule($rule)
    }
    $localServiceRights =
        [Security.AccessControl.FileSystemRights]::ReadAndExecute -bor
        [Security.AccessControl.FileSystemRights]::Synchronize
    $localServiceRule = [Security.AccessControl.FileSystemAccessRule]::new(
        $localService,
        $localServiceRights,
        [Security.AccessControl.InheritanceFlags]::None,
        [Security.AccessControl.PropagationFlags]::None,
        $allow)
    [void]$acl.AddAccessRule($localServiceRule)
    return $acl
}

function Assert-ProductionWriterStartServiceFile {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [Security.AccessControl.RawSecurityDescriptor]$SecurityDescriptor
    )

    Assert-ProductionWriterStartPathNotReparseTraversal -Path $Path | Out-Null
    if (-not $PSBoundParameters.ContainsKey('SecurityDescriptor')) {
        $acl = Get-Acl -LiteralPath $Path -ErrorAction Stop
        $SecurityDescriptor = [Security.AccessControl.RawSecurityDescriptor]::new(
            $acl.GetSecurityDescriptorBinaryForm(), 0)
    }
    $protectedFlag =
        [Security.AccessControl.ControlFlags]::DiscretionaryAclProtected
    if (($SecurityDescriptor.ControlFlags -band $protectedFlag) -eq 0) {
        throw "Writer-start service file ACL inheritance must be protected: $Path"
    }
    $administrators = 'S-1-5-32-544'
    if ($null -eq $SecurityDescriptor.Owner -or
        $SecurityDescriptor.Owner.Value -cne $administrators) {
        throw "Writer-start service file ACL owner must be Builtin Administrators: $Path"
    }
    $aces = @($SecurityDescriptor.DiscretionaryAcl)
    if ($aces.Count -ne 3) {
        throw "Writer-start service file ACL must have exactly three explicit ACEs: $Path"
    }
    $expectedIdentities = @('S-1-5-18',$administrators,'S-1-5-19')
    $exactAces = @{}
    foreach ($identity in $expectedIdentities) {
        $matching = @($aces | Where-Object {
                $_ -is [Security.AccessControl.CommonAce] -and
                $_.AceType -eq [Security.AccessControl.AceType]::AccessAllowed -and
                $_.SecurityIdentifier.Value -ceq $identity
            })
        if ($matching.Count -ne 1) {
            throw ('Writer-start service file ACL must have one SYSTEM, one ' +
                "Administrators, and one LocalService allow ACE: $Path")
        }
        $exactAces[$identity] = $matching[0]
    }
    foreach ($ace in $exactAces.Values) {
        if ([int]$ace.AceFlags -ne [int][Security.AccessControl.AceFlags]::None) {
            throw "Writer-start service file ACEs must not inherit or propagate: $Path"
        }
    }
    $fullControl = [int][Security.AccessControl.FileSystemRights]::FullControl
    foreach ($identity in @('S-1-5-18',$administrators)) {
        if ([int]$exactAces[$identity].AccessMask -ne $fullControl) {
            throw ('Writer-start service file SYSTEM and Administrators must have ' +
                "exact FullControl rights: $Path")
        }
    }
    $localServiceRights = [int](
        [Security.AccessControl.FileSystemRights]::ReadAndExecute -bor
        [Security.AccessControl.FileSystemRights]::Synchronize)
    if ([int]$exactAces['S-1-5-19'].AccessMask -ne $localServiceRights) {
        throw ('Writer-start service file LocalService must have exact ' +
            "ReadAndExecute and Synchronize rights: $Path")
    }
}

function Protect-ProductionWriterStartServiceFile {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)

    Assert-ProductionWriterStartPathNotReparseTraversal -Path $Path | Out-Null
    Set-Acl -LiteralPath $Path `
        -AclObject (New-ProductionWriterStartServiceFileAcl) -ErrorAction Stop
    Assert-ProductionWriterStartServiceFile -Path $Path
}

function Assert-ExactJsonProperties {
    param($Value, [string[]]$Expected, [string]$Label)
    $actual = @($Value.PSObject.Properties.Name)
    if ($actual.Count -ne $Expected.Count) { throw "$Label has invalid properties." }
    foreach ($name in $Expected) {
        if (-not ($actual -ccontains $name)) { throw "$Label has invalid properties." }
    }
}

function Get-ProductionWriterStartIssuerIdentity {
    $process = [Diagnostics.Process]::GetCurrentProcess()
    try {
        [pscustomobject][ordered]@{
            issuerPid = [int]$process.Id
            issuerStartTimeUtcTicks = [long]$process.StartTime.ToUniversalTime().Ticks
        }
    } finally {
        $process.Dispose()
    }
}

function Assert-ProductionWriterStartIssuerIdentity {
    param([Parameter(Mandatory)]$Authorization)
    try {
        $issuer = Get-Process -Id ([int]$Authorization.issuerPid) -ErrorAction Stop
        $actualTicks = [long]$issuer.StartTime.ToUniversalTime().Ticks
    } catch {
        throw [System.InvalidOperationException]::new(
            'Writer-start authorization issuer is not alive.', $_.Exception)
    }
    if ($actualTicks -ne [long]$Authorization.issuerStartTimeUtcTicks) {
        throw 'Writer-start authorization issuer process identity changed.'
    }
}

function Read-ProductionWriterStartGuardManifest {
    param([Parameter(Mandatory)]$Config)
    $path = Get-ProductionWriterStartGuardManifestPath -Config $Config
    try {
        $value = Get-Content -LiteralPath $path -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        $version = if ($value.version -is [int] -or $value.version -is [long]) {
            [int]$value.version
        } else {
            0
        }
        $expectedProperties = if ($version -eq 2) {
            @('version','launcherSha256','moduleSha256','winSwSha256','serviceXmlSha256')
        } else {
            @('version','launcherSha256','moduleSha256')
        }
        Assert-ExactJsonProperties $value $expectedProperties 'Writer-start guard manifest'
        if (($value.version -isnot [int] -and $value.version -isnot [long]) -or
            $version -notin @(1,2) -or
            $value.launcherSha256 -isnot [string] -or
            [string]$value.launcherSha256 -cnotmatch '^[0-9a-f]{64}$' -or
            $value.moduleSha256 -isnot [string] -or
            [string]$value.moduleSha256 -cnotmatch '^[0-9a-f]{64}$' -or
            ($version -eq 2 -and (
                $value.winSwSha256 -isnot [string] -or
                [string]$value.winSwSha256 -cnotmatch '^[0-9a-f]{64}$' -or
                $value.serviceXmlSha256 -isnot [string] -or
                [string]$value.serviceXmlSha256 -cnotmatch '^[0-9a-f]{64}$'))) {
            throw 'Invalid writer-start guard manifest.'
        }
        $manifest = [ordered]@{
            version = $version
            launcherSha256 = [string]$value.launcherSha256
            moduleSha256 = [string]$value.moduleSha256
        }
        if ($version -eq 2) {
            $manifest.winSwSha256 = [string]$value.winSwSha256
            $manifest.serviceXmlSha256 = [string]$value.serviceXmlSha256
        }
        [pscustomobject]$manifest
    } catch {
        throw [System.IO.InvalidDataException]::new(
            'Installed writer-start guard manifest is invalid.', $_.Exception)
    }
}

function Assert-ProductionWriterStartGuardBundle {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')]
        [string]$ExpectedLauncherSha256,
        [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')]
        [string]$ExpectedModuleSha256,
        [ValidatePattern('^[0-9a-f]{64}$')]
        [string]$ExpectedWinSwSha256,
        [ValidatePattern('^[0-9a-f]{64}$')]
        [string]$ExpectedServiceXmlSha256
    )
    $hasWinSw = -not [string]::IsNullOrWhiteSpace($ExpectedWinSwSha256)
    $hasServiceXml = -not [string]::IsNullOrWhiteSpace($ExpectedServiceXmlSha256)
    if ($hasWinSw -ne $hasServiceXml) {
        throw 'Writer-start host boundary hashes must be supplied together.'
    }
    $serviceRoot = Get-CanonicalProductionWriterStartServiceRoot -Config $Config
    Assert-ProductionWriterStartPathNotReparseTraversal -Path $serviceRoot | Out-Null
    $launcher = Join-Path $serviceRoot 'Start-ChristopherBellDev.ps1'
    $module = Join-Path $serviceRoot 'Production.WriterStart.psm1'
    $winSw = Join-Path $serviceRoot 'ChristopherBellDev.exe'
    $serviceXml = Join-Path $serviceRoot 'ChristopherBellDev.xml'
    $manifestPath = Get-ProductionWriterStartGuardManifestPath -Config $Config
    $manifest = Read-ProductionWriterStartGuardManifest -Config $Config
    if (($hasWinSw -and [int]$manifest.version -ne 2) -or
        (-not $hasWinSw -and [int]$manifest.version -ne 1) -or
        $manifest.launcherSha256 -cne $ExpectedLauncherSha256 -or
        $manifest.moduleSha256 -cne $ExpectedModuleSha256 -or
        (Get-FileHash -LiteralPath $launcher -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant() -cne
            $ExpectedLauncherSha256 -or
        (Get-FileHash -LiteralPath $module -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant() -cne
            $ExpectedModuleSha256) {
        throw 'Installed writer-start guard SHA-256 verification failed.'
    }
    if ($hasWinSw -and (
            $manifest.winSwSha256 -cne $ExpectedWinSwSha256 -or
            $manifest.serviceXmlSha256 -cne $ExpectedServiceXmlSha256 -or
            (Get-FileHash -LiteralPath $winSw -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant() -cne
                $ExpectedWinSwSha256 -or
            (Get-FileHash -LiteralPath $serviceXml -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant() -cne
                $ExpectedServiceXmlSha256)) {
        throw 'Installed writer-start service host SHA-256 verification failed.'
    }
    Assert-ProductionWriterStartServiceDirectory -Path $serviceRoot
    $protectedFiles = @($launcher,$module,$manifestPath)
    if ($hasWinSw) { $protectedFiles += @($winSw,$serviceXml) }
    foreach ($path in $protectedFiles) {
        Assert-ProductionWriterStartServiceFile -Path $path
    }
    return $manifest
}

function Publish-ProductionWriterStartGuardFile {
    param(
        [Parameter(Mandatory)][string]$Source,
        [Parameter(Mandatory)][string]$Destination
    )
    if (-not (Test-Path -LiteralPath $Destination -PathType Leaf)) {
        [IO.File]::Move($Source, $Destination)
        return
    }
    $backup = "$Destination.$PID.$([guid]::NewGuid().ToString('N')).backup"
    try {
        [IO.File]::Replace($Source, $Destination, $backup, $true)
    } finally {
        if (Test-Path -LiteralPath $backup) {
            Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue
        }
    }
}

function Publish-ProductionWriterStartGuardBundle {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$SourceLauncherPath,
        [Parameter(Mandatory)][string]$SourceModulePath,
        [string]$SourceWinSwPath,
        [string]$SourceServiceXmlPath,
        [ValidatePattern('^[0-9a-f]{64}$')]
        [string]$ExpectedWinSwSha256,
        [ValidatePattern('^[0-9a-f]{64}$')]
        [string]$ExpectedServiceXmlSha256
    )
    $hasWinSw = -not [string]::IsNullOrWhiteSpace($ExpectedWinSwSha256)
    $hasServiceXml = -not [string]::IsNullOrWhiteSpace($ExpectedServiceXmlSha256)
    if ($hasWinSw -ne $hasServiceXml) {
        throw 'Writer-start host boundary hashes must be supplied together.'
    }
    $hasSourceWinSw = -not [string]::IsNullOrWhiteSpace($SourceWinSwPath)
    $hasSourceServiceXml = -not [string]::IsNullOrWhiteSpace($SourceServiceXmlPath)
    if ($hasSourceWinSw -ne $hasSourceServiceXml -or
        $hasSourceWinSw -and -not $hasWinSw) {
        throw 'Writer-start host boundary sources and hashes must be supplied together.'
    }
    foreach ($source in @($SourceLauncherPath,$SourceModulePath)) {
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw 'Writer-start guard source file is missing.'
        }
        Assert-ProductionPathNotReparse -Path $source | Out-Null
    }
    $serviceRoot = Get-CanonicalProductionWriterStartServiceRoot -Config $Config
    New-Item -ItemType Directory -Path $serviceRoot -Force | Out-Null
    Assert-ProductionWriterStartPathNotReparseTraversal -Path $serviceRoot | Out-Null
    Protect-ProductionWriterStartServiceDirectory -Path $serviceRoot
    Assert-ProductionWriterStartServiceDirectory -Path $serviceRoot
    $installedLauncher = Join-Path $serviceRoot 'Start-ChristopherBellDev.ps1'
    $installedModule = Join-Path $serviceRoot 'Production.WriterStart.psm1'
    $installedManifest = Join-Path $serviceRoot 'Production.WriterStart.bundle.json'
    $installedWinSw = Join-Path $serviceRoot 'ChristopherBellDev.exe'
    $installedServiceXml = Join-Path $serviceRoot 'ChristopherBellDev.xml'
    if ($hasWinSw -and -not $hasSourceWinSw) {
        $SourceWinSwPath = $installedWinSw
        $SourceServiceXmlPath = $installedServiceXml
    }
    $destinations = @($installedLauncher,$installedModule,$installedManifest)
    if ($hasWinSw) { $destinations += @($installedWinSw,$installedServiceXml) }
    foreach ($destination in $destinations) {
        if (Test-Path -LiteralPath $destination) {
            Assert-ProductionWriterStartPathNotReparseTraversal -Path $destination |
                Out-Null
        }
    }
    if ($hasWinSw) {
        foreach ($source in @($SourceWinSwPath,$SourceServiceXmlPath)) {
            if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
                throw 'Writer-start host boundary source file is missing.'
            }
            Assert-ProductionWriterStartPathNotReparseTraversal -Path $source |
                Out-Null
        }
        if ((Get-FileHash -LiteralPath $SourceWinSwPath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant() -cne
                $ExpectedWinSwSha256 -or
            (Get-FileHash -LiteralPath $SourceServiceXmlPath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant() -cne
                $ExpectedServiceXmlSha256) {
            throw 'Writer-start service host source SHA-256 verification failed.'
        }
    }
    $launcherSha = (Get-FileHash -LiteralPath $SourceLauncherPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $moduleSha = (Get-FileHash -LiteralPath $SourceModulePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $staging = Join-Path $serviceRoot ('.writer-start-guard-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $staging | Out-Null
    try {
        $stagedLauncher = Join-Path $staging 'Start-ChristopherBellDev.ps1'
        $stagedModule = Join-Path $staging 'Production.WriterStart.psm1'
        $stagedManifest = Join-Path $staging 'Production.WriterStart.bundle.json'
        $stagedWinSw = Join-Path $staging 'ChristopherBellDev.exe'
        $stagedServiceXml = Join-Path $staging 'ChristopherBellDev.xml'
        Copy-Item -LiteralPath $SourceLauncherPath -Destination $stagedLauncher
        Copy-Item -LiteralPath $SourceModulePath -Destination $stagedModule
        if ($hasWinSw) {
            Copy-Item -LiteralPath $SourceWinSwPath -Destination $stagedWinSw
            Copy-Item -LiteralPath $SourceServiceXmlPath -Destination $stagedServiceXml
        }
        $manifest = [ordered]@{
            version = if ($hasWinSw) { 2 } else { 1 }
            launcherSha256 = $launcherSha
            moduleSha256 = $moduleSha
        }
        if ($hasWinSw) {
            $manifest.winSwSha256 = $ExpectedWinSwSha256
            $manifest.serviceXmlSha256 = $ExpectedServiceXmlSha256
        }
        $manifest | ConvertTo-Json | Set-Content -LiteralPath $stagedManifest -Encoding utf8
        Protect-ProductionPath -Path $staging
        Assert-ProtectedProductionPath -Path $staging | Out-Null
        $stagedFiles = @($stagedLauncher,$stagedModule,$stagedManifest)
        if ($hasWinSw) { $stagedFiles += @($stagedWinSw,$stagedServiceXml) }
        foreach ($path in $stagedFiles) {
            Protect-ProductionWriterStartServiceFile -Path $path
        }
        if ((Get-FileHash $stagedLauncher -Algorithm SHA256).Hash.ToLowerInvariant() -cne $launcherSha -or
            (Get-FileHash $stagedModule -Algorithm SHA256).Hash.ToLowerInvariant() -cne $moduleSha -or
            ($hasWinSw -and (
                (Get-FileHash $stagedWinSw -Algorithm SHA256).Hash.ToLowerInvariant() -cne
                    $ExpectedWinSwSha256 -or
                (Get-FileHash $stagedServiceXml -Algorithm SHA256).Hash.ToLowerInvariant() -cne
                    $ExpectedServiceXmlSha256))) {
            throw 'Staged writer-start guard SHA-256 verification failed.'
        }

        if (Test-Path -LiteralPath $installedManifest) {
            Remove-Item -LiteralPath $installedManifest -Force -ErrorAction Stop
            if (Test-Path -LiteralPath $installedManifest) {
                throw 'Installed writer-start guard manifest invalidation was not verified.'
            }
        }
        # Publishing executable inputs first makes every partial upgrade fail closed against the
        # absent or old manifest. The manifest is the atomic commit point for all five files.
        Publish-ProductionWriterStartGuardFile `
            -Source $stagedLauncher `
            -Destination $installedLauncher
        Protect-ProductionWriterStartServiceFile -Path $installedLauncher
        Publish-ProductionWriterStartGuardFile `
            -Source $stagedModule `
            -Destination $installedModule
        Protect-ProductionWriterStartServiceFile -Path $installedModule
        if ($hasWinSw) {
            Publish-ProductionWriterStartGuardFile `
                -Source $stagedWinSw `
                -Destination $installedWinSw
            Protect-ProductionWriterStartServiceFile -Path $installedWinSw
            Publish-ProductionWriterStartGuardFile `
                -Source $stagedServiceXml `
                -Destination $installedServiceXml
            Protect-ProductionWriterStartServiceFile -Path $installedServiceXml
        }
        try {
            Publish-ProductionWriterStartGuardFile `
                -Source $stagedManifest `
                -Destination $installedManifest
            Protect-ProductionWriterStartServiceFile -Path $installedManifest
        } catch {
            if (Test-Path -LiteralPath $installedManifest -PathType Leaf) {
                Remove-Item -LiteralPath $installedManifest -Force `
                    -ErrorAction SilentlyContinue
            }
            throw
        }
        $assertArguments = @{
            Config = $Config
            ExpectedLauncherSha256 = $launcherSha
            ExpectedModuleSha256 = $moduleSha
        }
        if ($hasWinSw) {
            $assertArguments.ExpectedWinSwSha256 = $ExpectedWinSwSha256
            $assertArguments.ExpectedServiceXmlSha256 = $ExpectedServiceXmlSha256
        }
        Assert-ProductionWriterStartGuardBundle @assertArguments | Out-Null
        [pscustomobject][ordered]@{
            launcherSha256 = $launcherSha
            moduleSha256 = $moduleSha
        }
    } finally {
        if (Test-Path -LiteralPath $staging) {
            Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

function Read-ProductionMusicSchemaDirection {
    param([Parameter(Mandatory)]$Config)
    $path = Get-ProductionMusicSchemaDirectionPath -Config $Config
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    try {
        $value = Get-Content -LiteralPath $path -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        Assert-ExactJsonProperties $value @(
            'version','state','updatedAtEpochMillis','targetRelease','legacyRelease') 'Marker'
        if (($value.version -isnot [int] -and $value.version -isnot [long]) -or
            [int]$value.version -ne 1 -or
            $value.state -isnot [string] -or
            -not ($script:MarkerStates -ccontains [string]$value.state) -or
            ($value.updatedAtEpochMillis -isnot [int] -and
                $value.updatedAtEpochMillis -isnot [long]) -or
            [long]$value.updatedAtEpochMillis -lt 1 -or
            $value.targetRelease -isnot [string] -or
            [string]$value.targetRelease -cnotmatch '^[0-9a-f]{40}$' -or
            $value.legacyRelease -isnot [string] -or
            [string]$value.legacyRelease -cnotmatch '^[0-9a-f]{40}$') {
            throw 'Invalid marker.'
        }
        [pscustomobject][ordered]@{
            version = 1
            state = [string]$value.state
            updatedAtEpochMillis = [long]$value.updatedAtEpochMillis
            targetRelease = [string]$value.targetRelease
            legacyRelease = [string]$value.legacyRelease
        }
    } catch {
        throw [System.IO.InvalidDataException]::new(
            'Music runtime schema-direction marker is invalid.', $_.Exception)
    }
}

function Write-ProductionMusicSchemaDirection {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)]
        [ValidateSet('TARGET_ACTIVE','TARGET_CUTOVER_IN_PROGRESS','LEGACY_ACTIVE_RECONCILIATION_REQUIRED')]
        [string]$State,
        [Parameter(Mandatory)][ValidateScript({ $_ -cmatch '^[0-9a-f]{40}$' })][string]$TargetRelease,
        [Parameter(Mandatory)][ValidateScript({ $_ -cmatch '^[0-9a-f]{40}$' })][string]$LegacyRelease
    )
    if (-not ($script:MarkerStates -ccontains $State)) {
        throw 'Music runtime schema-direction marker state is invalid.'
    }
    $path = Get-ProductionMusicSchemaDirectionPath -Config $Config
    $parent = Split-Path -Parent $path
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    if (Get-Command Protect-ProductionPath -ErrorAction SilentlyContinue) {
        Protect-ProductionPath -Path $parent
        Assert-ProtectedProductionPath -Path $parent | Out-Null
    }
    $temporary = "$path.$PID.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        [ordered]@{
            version = 1
            state = $State
            updatedAtEpochMillis = [DateTimeOffset]::new(
                (Get-Date).ToUniversalTime()).ToUnixTimeMilliseconds()
            targetRelease = $TargetRelease
            legacyRelease = $LegacyRelease
        } | ConvertTo-Json | Set-Content -LiteralPath $temporary -Encoding utf8
        if (Get-Command Protect-ProductionPath -ErrorAction SilentlyContinue) {
            Protect-ProductionPath -Path $temporary
            Assert-ProtectedProductionPath -Path $temporary | Out-Null
        }
        Move-Item -LiteralPath $temporary -Destination $path -Force
        if (Get-Command Protect-ProductionPath -ErrorAction SilentlyContinue) {
            Protect-ProductionPath -Path $path
            Assert-ProtectedProductionPath -Path $path | Out-Null
        }
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
}

function Read-ProductionReleaseIdentity {
    param([Parameter(Mandatory)]$Config)
    $current = Join-Path $Config.programDataRoot 'current'
    $release = Get-Item -LiteralPath $current -Force -ErrorAction Stop
    $targets = @($release.Target)
    if ($targets.Count -ne 1 -or [string]::IsNullOrWhiteSpace([string]$targets[0])) {
        throw 'Active release junction is invalid.'
    }
    $sha = Split-Path -Leaf ([string]$targets[0])
    if ($sha -cnotmatch '^[0-9a-f]{40}$') {
        throw 'Active release identity is invalid.'
    }
    $metadataPath = Join-Path $current 'release.json'
    try {
        $metadata = Get-Content -LiteralPath $metadataPath -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        $names = @($metadata.PSObject.Properties.Name)
        if (-not ($names -ccontains 'sha') -or [string]$metadata.sha -cne $sha) {
            throw 'Release SHA mismatch.'
        }
        $schema = $null
        if ($names -ccontains 'musicSchema') {
            if ($metadata.musicSchema -isnot [string] -or
                [string]$metadata.musicSchema -cnotin @('LEGACY','TARGET')) {
                throw 'Invalid release schema.'
            }
            $schema = [string]$metadata.musicSchema
        }
        [pscustomobject][ordered]@{ sha=$sha; musicSchema=$schema }
    } catch {
        throw [System.IO.InvalidDataException]::new(
            'Active release metadata is invalid.', $_.Exception)
    }
}

function Get-ProductionMusicMigrationActivationForWriterStart {
    param([Parameter(Mandatory)]$Config)
    $query = @'
const target = db.getSiblingDB('christopherbell');
const migrations = target.getCollection('application_migrations')
  .countDocuments({_id:'014-consolidate-music-runtime-state'});
const destination = target.getCollection('music_runtime_state').countDocuments({});
print(JSON.stringify({active:migrations !== 0 || destination !== 0}));
'@
    $errorPath = Join-Path $Config.programDataRoot `
        "state\writer-start-probe.$PID.$([guid]::NewGuid().ToString('N')).err"
    try {
        $output = & $Config.mongoShellExe '--quiet' '--norc' `
            'mongodb://127.0.0.1:27017/admin' '--eval' $query 2>$errorPath
        if ($LASTEXITCODE -ne 0) { throw 'Activation probe failed.' }
        $value = ($output -join "`n") | ConvertFrom-Json -ErrorAction Stop
        Assert-ExactJsonProperties $value @('active') 'Activation result'
        if ($value.active -isnot [bool]) { throw 'Activation result is invalid.' }
        [bool]$value.active
    } catch {
        throw [System.InvalidOperationException]::new(
            'Music runtime migration activation could not be proven; writer start is blocked.',
            $_.Exception)
    } finally {
        if (Test-Path -LiteralPath $errorPath) {
            Remove-Item -LiteralPath $errorPath -Force -ErrorAction SilentlyContinue
        }
    }
}

function Grant-ProductionWriterStartAuthorization {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)]
        [ValidateSet('TARGET_ACTIVE','TARGET_CUTOVER_IN_PROGRESS','LEGACY_ACTIVE_RECONCILIATION_REQUIRED')]
        [string]$MarkerState,
        [Parameter(Mandatory)][ValidateScript({ $_ -cmatch '^[0-9a-f]{40}$' })][string]$Release,
        [Parameter(Mandatory)]
        [ValidateSet('TARGET_CUTOVER','TARGET_DEPLOY','TARGET_RECONCILIATION','LEGACY_ROLLBACK','LEGACY_RESTORE')]
        [string]$Purpose,
        [ValidateRange(1,120)][int]$LifetimeSeconds = 30
    )
    if (-not ($script:MarkerStates -ccontains $MarkerState) -or
        -not ($script:AuthorizationPurposes -ccontains $Purpose)) {
        throw 'Writer-start authorization state or purpose is invalid.'
    }
    $marker = Read-ProductionMusicSchemaDirection -Config $Config
    if (-not $marker -or [string]$marker.state -cne $MarkerState) {
        throw 'Writer-start authorization does not match the exact schema-direction marker.'
    }
    $issuer = Get-ProductionWriterStartIssuerIdentity
    $nonce = [guid]::NewGuid().ToString('N')
    $expiresAt = [DateTimeOffset]::UtcNow.AddSeconds($LifetimeSeconds).ToUnixTimeMilliseconds()
    $path = Get-ProductionWriterStartAuthorizationPath -Config $Config
    $published = $false
    $parent = Split-Path -Parent $path
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    if (Get-Command Protect-ProductionPath -ErrorAction SilentlyContinue) {
        Protect-ProductionPath -Path $parent
        Assert-ProtectedProductionPath -Path $parent | Out-Null
    }
    $temporary = "$path.$PID.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        [ordered]@{
            version = 1
            markerState = $MarkerState
            markerTargetRelease = [string]$marker.targetRelease
            markerLegacyRelease = [string]$marker.legacyRelease
            release = $Release
            purpose = $Purpose
            expiresAtEpochMillis = $expiresAt
            nonce = $nonce
            issuerPid = [int]$issuer.issuerPid
            issuerStartTimeUtcTicks = [long]$issuer.issuerStartTimeUtcTicks
        } | ConvertTo-Json | Set-Content -LiteralPath $temporary -Encoding utf8
        if (Get-Command Protect-ProductionPath -ErrorAction SilentlyContinue) {
            Protect-ProductionPath -Path $temporary
            Assert-ProtectedProductionPath -Path $temporary | Out-Null
        }
        Move-Item -LiteralPath $temporary -Destination $path -Force
        $published = $true
        if (Get-Command Protect-ProductionPath -ErrorAction SilentlyContinue) {
            Protect-ProductionPath -Path $path
            Assert-ProtectedProductionPath -Path $path | Out-Null
        }
    } catch {
        if ($published -and (Test-Path -LiteralPath $path -PathType Leaf)) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
        throw [System.InvalidOperationException]::new(
            'Pending writer-start authorization creation failed.', $_.Exception)
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
    [pscustomobject][ordered]@{
        nonce = $nonce
        markerState = $MarkerState
        markerTargetRelease = [string]$marker.targetRelease
        markerLegacyRelease = [string]$marker.legacyRelease
        release = $Release
        purpose = $Purpose
        issuerPid = [int]$issuer.issuerPid
        issuerStartTimeUtcTicks = [long]$issuer.issuerStartTimeUtcTicks
    }
}

function Revoke-ProductionWriterStartAuthorization {
    param([Parameter(Mandatory)]$Config, [Parameter(Mandatory)]$Authorization)
    $path = Get-ProductionWriterStartAuthorizationPath -Config $Config
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return }
    try {
        $value = Get-Content -LiteralPath $path -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        if ($value.nonce -isnot [string] -or
            [string]$value.nonce -cne [string]$Authorization.nonce -or
            [string]$value.markerState -cne [string]$Authorization.markerState -or
            [string]$value.markerTargetRelease -cne
                [string]$Authorization.markerTargetRelease -or
            [string]$value.markerLegacyRelease -cne
                [string]$Authorization.markerLegacyRelease -or
            [string]$value.release -cne [string]$Authorization.release -or
            [string]$value.purpose -cne [string]$Authorization.purpose -or
            [int]$value.issuerPid -ne [int]$Authorization.issuerPid -or
            [long]$value.issuerStartTimeUtcTicks -ne
                [long]$Authorization.issuerStartTimeUtcTicks) {
            throw 'Pending writer-start authorization does not match the revocation token.'
        }
        Remove-Item -LiteralPath $path -Force -ErrorAction Stop
    } catch {
        throw [System.InvalidOperationException]::new(
            'Pending writer-start authorization revocation failed.', $_.Exception)
    }
}

function Use-ProductionWriterStartAuthorization {
    param([Parameter(Mandatory)]$Config, [Parameter(Mandatory)]$Marker,
        [Parameter(Mandatory)]$ReleaseIdentity)
    $path = Get-ProductionWriterStartAuthorizationPath -Config $Config
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $false }
    $claimed = "$path.claimed.$PID.$([guid]::NewGuid().ToString('N'))"
    try {
        Move-Item -LiteralPath $path -Destination $claimed -ErrorAction Stop
        $value = Get-Content -LiteralPath $claimed -Raw -ErrorAction Stop |
            ConvertFrom-Json -ErrorAction Stop
        Assert-ExactJsonProperties $value @(
            'version','markerState','markerTargetRelease','markerLegacyRelease',
            'release','purpose','expiresAtEpochMillis','nonce',
            'issuerPid','issuerStartTimeUtcTicks') 'Authorization'
        $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        if (($value.version -isnot [int] -and $value.version -isnot [long]) -or
            [int]$value.version -ne 1 -or
            $value.markerState -isnot [string] -or
            [string]$value.markerState -cne [string]$Marker.state -or
            $value.markerTargetRelease -isnot [string] -or
            [string]$value.markerTargetRelease -cne [string]$Marker.targetRelease -or
            $value.markerLegacyRelease -isnot [string] -or
            [string]$value.markerLegacyRelease -cne [string]$Marker.legacyRelease -or
            $value.release -isnot [string] -or
            [string]$value.release -cne [string]$ReleaseIdentity.sha -or
            $value.purpose -isnot [string] -or
            -not ($script:AuthorizationPurposes -ccontains [string]$value.purpose) -or
            ($value.expiresAtEpochMillis -isnot [int] -and
                $value.expiresAtEpochMillis -isnot [long]) -or
            [long]$value.expiresAtEpochMillis -lt $now -or
            [long]$value.expiresAtEpochMillis -gt ($now + 120000) -or
            $value.nonce -isnot [string] -or
            [string]$value.nonce -cnotmatch '^[0-9a-f]{32}$' -or
            ($value.issuerPid -isnot [int] -and $value.issuerPid -isnot [long]) -or
            [int]$value.issuerPid -lt 1 -or
            ($value.issuerStartTimeUtcTicks -isnot [int] -and
                $value.issuerStartTimeUtcTicks -isnot [long]) -or
            [long]$value.issuerStartTimeUtcTicks -lt 1) {
            throw 'Authorization is invalid.'
        }
        Assert-ProductionWriterStartIssuerIdentity -Authorization $value
        $schema = [string]$ReleaseIdentity.musicSchema
        $purpose = [string]$value.purpose
        $validPurpose =
            ($purpose -eq 'TARGET_CUTOVER' -and $Marker.state -eq 'TARGET_CUTOVER_IN_PROGRESS' -and $schema -eq 'TARGET') -or
            ($purpose -eq 'TARGET_DEPLOY' -and $Marker.state -eq 'TARGET_ACTIVE' -and $schema -eq 'TARGET') -or
            ($purpose -eq 'TARGET_RECONCILIATION' -and $Marker.state -eq 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED' -and $schema -eq 'TARGET') -or
            ($purpose -in @('LEGACY_ROLLBACK','LEGACY_RESTORE') -and $Marker.state -eq 'TARGET_ACTIVE' -and $schema -eq 'LEGACY')
        if (-not $validPurpose) { throw 'Authorization purpose is invalid.' }
        return $true
    } catch {
        throw [System.InvalidOperationException]::new(
            'Pending writer-start authorization is invalid or unavailable; writer start is blocked.',
            $_.Exception)
    } finally {
        if (Test-Path -LiteralPath $claimed) {
            Remove-Item -LiteralPath $claimed -Force -ErrorAction SilentlyContinue
        }
    }
}

function Assert-ProductionWriterStartAllowed {
    param(
        [Parameter(Mandatory)]$Config,
        [Parameter(Mandatory)][string]$FixedRoot
    )

    Assert-ProductionFixedRootBoundary `
        -Config $Config -FixedRoot $FixedRoot | Out-Null
    $release = Read-ProductionReleaseIdentity -Config $Config
    $marker = Read-ProductionMusicSchemaDirection -Config $Config
    if (-not $marker) {
        if (Get-ProductionMusicMigrationActivationForWriterStart -Config $Config) {
            throw 'Music schema-direction marker is absent after migration activation; writer start is blocked.'
        }
        if ($release.musicSchema -eq 'TARGET') {
            throw 'A target-schema release cannot start before the protected first cutover.'
        }
        return
    }
    $expected = if ($marker.state -eq 'TARGET_ACTIVE') {
        [string]$marker.targetRelease
    } elseif ($marker.state -eq 'LEGACY_ACTIVE_RECONCILIATION_REQUIRED') {
        [string]$marker.legacyRelease
    } else { '' }
    $expectedSchema = if ($marker.state -eq 'TARGET_ACTIVE') { 'TARGET' } else { 'LEGACY' }
    if ($release.sha -eq $expected -and
        ($null -eq $release.musicSchema -or $release.musicSchema -eq $expectedSchema)) {
        return
    }
    if (Use-ProductionWriterStartAuthorization -Config $Config -Marker $marker -ReleaseIdentity $release) {
        return
    }
    throw 'The active release is incompatible with the Music schema-direction marker; writer start is blocked.'
}

Export-ModuleMember -Function `
    Assert-ProductionFixedRootDirectoryAcl,Assert-ProductionFixedRootBoundary,`
    Enter-ProductionFixedRootDeploymentLock,Get-ProductionMusicSchemaDirectionPath,`
    Read-ProductionMusicSchemaDirection,Write-ProductionMusicSchemaDirection,`
    Assert-ProductionWriterStartAllowed,Get-ProductionMusicMigrationActivationForWriterStart,`
    Read-ProductionReleaseIdentity
