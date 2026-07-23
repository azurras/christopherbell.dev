Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.SharedFolder.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.SharedFolderWorker.psm1') -Force

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]$identity
$isElevatedWindows = $IsWindows -and $principal.IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)

BeforeAll {
if (-not ('ChristopherBell.Dev.Acceptance.NativePath' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace ChristopherBell.Dev.Acceptance
{
    public sealed class NativePathIdentity
    {
        public string NativeFinalPath { get; set; }
        public uint VolumeSerialNumber { get; set; }
        public ulong FileIndex { get; set; }
    }

    public sealed class NativeAccessRule
    {
        public string Sid { get; set; }
        public int AccessMask { get; set; }
        public int AceFlags { get; set; }
        public string AceType { get; set; }
    }

    public sealed class NativePathSecurity
    {
        public string OwnerSid { get; set; }
        public bool DaclProtected { get; set; }
        public NativeAccessRule[] AccessRules { get; set; }
    }

    public static class NativePath
    {
        private const uint FileReadAttributes = 0x80;
        private const uint FileShareAll = 0x7;
        private const uint OpenExisting = 3;
        private const uint BackupSemantics = 0x02000000;
        private const string ResultDirectorySddl =
            "O:BAG:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1301BF;;;LS)";

        [StructLayout(LayoutKind.Sequential)]
        private struct SecurityAttributes
        {
            public int Length;
            public IntPtr SecurityDescriptor;
            public int InheritHandle;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct ByHandleFileInformation
        {
            public uint FileAttributes;
            public System.Runtime.InteropServices.ComTypes.FILETIME CreationTime;
            public System.Runtime.InteropServices.ComTypes.FILETIME LastAccessTime;
            public System.Runtime.InteropServices.ComTypes.FILETIME LastWriteTime;
            public uint VolumeSerialNumber;
            public uint FileSizeHigh;
            public uint FileSizeLow;
            public uint NumberOfLinks;
            public uint FileIndexHigh;
            public uint FileIndexLow;
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern SafeFileHandle CreateFileW(
            string path,
            uint access,
            uint share,
            IntPtr securityAttributes,
            uint creationDisposition,
            uint flags,
            IntPtr template);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern uint GetFinalPathNameByHandleW(
            SafeFileHandle handle,
            StringBuilder path,
            uint pathLength,
            uint flags);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetFileInformationByHandle(
            SafeFileHandle handle,
            out ByHandleFileInformation information);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool CreateDirectoryW(string path, IntPtr securityAttributes);

        public static NativePathIdentity GetIdentity(string path, bool directory)
        {
            uint flags = directory ? BackupSemantics : 0;
            using (SafeFileHandle handle = CreateFileW(
                path, FileReadAttributes, FileShareAll, IntPtr.Zero, OpenExisting, flags, IntPtr.Zero))
            {
                if (handle.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
                var finalPath = new StringBuilder(32768);
                uint length = GetFinalPathNameByHandleW(
                    handle, finalPath, (uint)finalPath.Capacity, 0);
                if (length == 0 || length >= finalPath.Capacity)
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                ByHandleFileInformation information;
                if (!GetFileInformationByHandle(handle, out information))
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                return new NativePathIdentity
                {
                    NativeFinalPath = NormalizeFinalPath(finalPath.ToString()),
                    VolumeSerialNumber = information.VolumeSerialNumber,
                    FileIndex = ((ulong)information.FileIndexHigh << 32) | information.FileIndexLow
                };
            }
        }

        public static NativePathSecurity GetSecurity(string path, bool directory)
        {
            FileSystemSecurity security = directory
                ? (FileSystemSecurity)FileSystemAclExtensions.GetAccessControl(
                    new DirectoryInfo(path), AccessControlSections.Owner | AccessControlSections.Access)
                : (FileSystemSecurity)FileSystemAclExtensions.GetAccessControl(
                    new FileInfo(path), AccessControlSections.Owner | AccessControlSections.Access);
            var rules = security.GetAccessRules(
                true, true, typeof(SecurityIdentifier));
            var nativeRules = new NativeAccessRule[rules.Count];
            for (int index = 0; index < rules.Count; index++)
            {
                var rule = (FileSystemAccessRule)rules[index];
                int flags = 0;
                if ((rule.InheritanceFlags & InheritanceFlags.ObjectInherit) != 0) flags |= 1;
                if ((rule.InheritanceFlags & InheritanceFlags.ContainerInherit) != 0) flags |= 2;
                if ((rule.PropagationFlags & PropagationFlags.NoPropagateInherit) != 0) flags |= 4;
                if ((rule.PropagationFlags & PropagationFlags.InheritOnly) != 0) flags |= 8;
                if (rule.IsInherited) flags |= 16;
                nativeRules[index] = new NativeAccessRule
                {
                    Sid = rule.IdentityReference.Value,
                    AccessMask = (int)rule.FileSystemRights,
                    AceFlags = flags,
                    AceType = rule.AccessControlType == AccessControlType.Allow
                        ? "AccessAllowed" : "AccessDenied"
                };
            }
            return new NativePathSecurity
            {
                OwnerSid = security.GetOwner(typeof(SecurityIdentifier)).Value,
                DaclProtected = security.AreAccessRulesProtected,
                AccessRules = nativeRules
            };
        }

        public static void CreateResultDirectoryNew(string path)
        {
            CreateResultDirectoryNew(path, null);
        }

        public static void CreateResultDirectoryNew(
            string path,
            Func<string, IntPtr, byte[], bool> createDirectory)
        {
            var descriptor = new RawSecurityDescriptor(ResultDirectorySddl);
            var descriptorBytes = new byte[descriptor.BinaryLength];
            descriptor.GetBinaryForm(descriptorBytes, 0);
            IntPtr descriptorPointer = IntPtr.Zero;
            IntPtr attributesPointer = IntPtr.Zero;
            try
            {
                descriptorPointer = Marshal.AllocHGlobal(descriptorBytes.Length);
                Marshal.Copy(descriptorBytes, 0, descriptorPointer, descriptorBytes.Length);
                var attributes = new SecurityAttributes
                {
                    Length = Marshal.SizeOf<SecurityAttributes>(),
                    SecurityDescriptor = descriptorPointer,
                    InheritHandle = 0
                };
                attributesPointer = Marshal.AllocHGlobal(attributes.Length);
                Marshal.StructureToPtr(attributes, attributesPointer, false);
                bool created = createDirectory == null
                    ? CreateDirectoryW(path, attributesPointer)
                    : createDirectory(path, attributesPointer, (byte[])descriptorBytes.Clone());
                if (!created) throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            finally
            {
                if (attributesPointer != IntPtr.Zero)
                    Marshal.FreeHGlobal(attributesPointer);
                if (descriptorPointer != IntPtr.Zero)
                    Marshal.FreeHGlobal(descriptorPointer);
            }
        }

        private static string NormalizeFinalPath(string path)
        {
            string normalized = path;
            if (normalized.StartsWith(@"\\?\UNC\", StringComparison.OrdinalIgnoreCase))
                normalized = @"\\" + normalized.Substring(8);
            else if (normalized.StartsWith(@"\\?\", StringComparison.OrdinalIgnoreCase))
                normalized = normalized.Substring(4);
            return Path.GetFullPath(normalized).TrimEnd(
                Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        }
    }
}
'@
}

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

function Get-NativePathIdentity {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [switch]$Directory
    )

    return [ChristopherBell.Dev.Acceptance.NativePath]::GetIdentity($Path, $Directory.IsPresent)
}

function Get-NativePathSecurity {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [switch]$Directory
    )

    return [ChristopherBell.Dev.Acceptance.NativePath]::GetSecurity(
        $Path, $Directory.IsPresent)
}

function Assert-InstalledWorkerResultSecurity {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Security,
        [Parameter(Mandatory)][ValidateSet('Directory','File')][string]$Kind
    )

    $isDirectory = $Kind -eq 'Directory'
    $expectedOwner = if ($isDirectory) { 'S-1-5-32-544' } else { 'S-1-5-19' }
    $expectedProtected = $isDirectory
    $expectedFlags = if ($isDirectory) { 3 } else { 16 }
    $expectedMasks = @{
        'S-1-5-18' = 0x001F01FF
        'S-1-5-32-544' = 0x001F01FF
        'S-1-5-19' = 0x001301BF
    }
    $rules = @($Security.AccessRules)
    $valid = $null -ne $Security -and
        [string]$Security.OwnerSid -ceq $expectedOwner -and
        [bool]$Security.DaclProtected -eq $expectedProtected -and
        $rules.Count -eq $expectedMasks.Count
    if ($valid) {
        foreach ($sid in $expectedMasks.Keys) {
            $matches = @($rules | Where-Object { [string]$_.Sid -ceq $sid })
            if ($matches.Count -ne 1 -or
                [string]$matches[0].AceType -cne 'AccessAllowed' -or
                [int]$matches[0].AceFlags -ne $expectedFlags -or
                [int]$matches[0].AccessMask -ne [int]$expectedMasks[$sid]) {
                $valid = $false
                break
            }
        }
    }
    if (-not $valid) {
        $label = if ($isDirectory) { 'result directory' } else { 'result file' }
        throw "The installed-worker $label security is invalid."
    }
}

function Assert-InstalledWorkerResultDirectorySecurity {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Security)

    Assert-InstalledWorkerResultSecurity -Security $Security -Kind Directory
}

function Assert-InstalledWorkerResultFileSecurity {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Security)

    Assert-InstalledWorkerResultSecurity -Security $Security -Kind File
}

function Assert-InstalledWorkerResultDirectoryState {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$ExpectedIdentity,
        [scriptblock]$GetPathIdentityAction = {
            param($Candidate,$Directory)
            Get-NativePathIdentity -Path $Candidate -Directory:$Directory
        },
        [scriptblock]$GetPathSecurityAction = {
            param($Candidate,$Directory)
            Get-NativePathSecurity -Path $Candidate -Directory:$Directory
        }
    )

    try {
        $actualIdentity = & $GetPathIdentityAction $Path $true
        $security = & $GetPathSecurityAction $Path $true
    } catch {
        throw 'The installed-worker result directory state is invalid.'
    }
    if (-not (Test-NativePathIdentityEqual -Expected $ExpectedIdentity `
        -Actual $actualIdentity)) {
        throw 'The installed-worker result directory state is invalid.'
    }
    Assert-InstalledWorkerResultDirectorySecurity -Security $security
}

function Assert-InstalledWorkerResultFileState {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$ResultRootIdentity,
        [scriptblock]$GetItemAction = {
            param($Candidate)
            Get-Item -LiteralPath $Candidate -Force -ErrorAction Stop
        },
        [scriptblock]$GetPathIdentityAction = {
            param($Candidate,$Directory)
            Get-NativePathIdentity -Path $Candidate -Directory:$Directory
        },
        [scriptblock]$GetPathSecurityAction = {
            param($Candidate,$Directory)
            Get-NativePathSecurity -Path $Candidate -Directory:$Directory
        }
    )

    try {
        $item = & $GetItemAction $Path
        $identity = & $GetPathIdentityAction $Path $false
        $security = & $GetPathSecurityAction $Path $false
    } catch {
        throw 'The installed-worker result file state is invalid.'
    }
    if ($null -eq $item -or [bool]$item.PSIsContainer -or
        ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        -not (Test-StrictlyBelowPath -Path $identity.NativeFinalPath `
            -Root $ResultRootIdentity.NativeFinalPath) -or
        [string][IO.Path]::GetFileName($identity.NativeFinalPath) -cne 'result.json') {
        throw 'The installed-worker result file state is invalid.'
    }
    Assert-InstalledWorkerResultFileSecurity -Security $security
}

function Test-NativePathIdentityEqual {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Expected,
        [Parameter(Mandatory)]$Actual
    )

    return [string]::Equals(
        [string]$Expected.NativeFinalPath,
        [string]$Actual.NativeFinalPath,
        [StringComparison]::OrdinalIgnoreCase) -and
        [string]$Expected.VolumeSerialNumber -ceq [string]$Actual.VolumeSerialNumber -and
        [string]$Expected.FileIndex -ceq [string]$Actual.FileIndex
}

function Assert-NativePathIdentity {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Expected,
        [Parameter(Mandatory)][scriptblock]$GetPathIdentityAction,
        [Parameter(Mandatory)][bool]$Directory
    )

    $actual = & $GetPathIdentityAction $Expected.NativeFinalPath $Directory
    if (-not (Test-NativePathIdentityEqual -Expected $Expected -Actual $actual)) {
        throw 'A native path identity changed.'
    }
    return $actual
}

function Assert-FixedProbeExecutable {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [hashtable]$Actions = @{}
    )

    $fixedPath = 'C:\Program Files\PowerShell\7\pwsh.exe'
    if (-not [string]::Equals($Path, $fixedPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The probe executable is not the fixed PowerShell installation.'
    }
    $getItem = if ($Actions.ContainsKey('GetItem') -and $null -ne $Actions['GetItem']) {
        $Actions['GetItem']
    } else {
        { param($Candidate) Get-Item -LiteralPath $Candidate -Force -ErrorAction Stop }
    }
    $getIdentity = if ($Actions.ContainsKey('GetPathIdentity') -and
        $null -ne $Actions['GetPathIdentity']) {
        $Actions['GetPathIdentity']
    } else {
        { param($Candidate,$Directory) Get-NativePathIdentity -Path $Candidate }
    }
    $getSignature = if ($Actions.ContainsKey('GetSignature') -and
        $null -ne $Actions['GetSignature']) {
        $Actions['GetSignature']
    } else {
        { param($Candidate) Get-AuthenticodeSignature -LiteralPath $Candidate -ErrorAction Stop }
    }
    $getVersion = if ($Actions.ContainsKey('GetVersion') -and $null -ne $Actions['GetVersion']) {
        $Actions['GetVersion']
    } else {
        { param($Candidate) (Get-Item -LiteralPath $Candidate -Force -ErrorAction Stop).VersionInfo }
    }

    $environment = @{ FixedProbeExecutable = $Path }
    $canonical = Resolve-InstalledWorkerAcceptancePath -Environment $environment `
        -Name FixedProbeExecutable -Directory $false -GetItemAction $getItem
    $identity = & $getIdentity $canonical $false
    if (-not [string]::Equals($identity.NativeFinalPath, $fixedPath,
        [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The probe executable native path does not match the fixed installation.'
    }
    $signature = & $getSignature $canonical
    $version = & $getVersion $canonical
    if ([string]$signature.Status -ne 'Valid' -or
        $null -eq $signature.SignerCertificate -or
        [string]$signature.SignerCertificate.Subject -notmatch
            '(^|, )O=Microsoft Corporation(,|$)' -or
        [string]$version.CompanyName -ne 'Microsoft Corporation' -or
        [string]$version.ProductName -ne 'PowerShell') {
        throw 'The fixed probe executable provenance is invalid.'
    }
    return [pscustomobject]@{
        Path = $canonical
        Identity = $identity
    }
}

function Read-PinnedWinSwSha256 {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [scriptblock]$ReadTextAction = {
            param($Candidate)
            Get-Content -LiteralPath $Candidate -Raw -ErrorAction Stop
        }
    )

    [string]$text = & $ReadTextAction $Path
    $matches = [regex]::Matches(
        $text,
        "(?m)^\`$script:WinSwSha256 = '([0-9A-F]{64})'\r?$")
    if ($matches.Count -ne 1) {
        throw 'Production.Install.psm1 must contain one authoritative WinSW digest.'
    }
    return $matches[0].Groups[1].Value
}

function Assert-InstalledWinSwDigest {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$WorkerPath,
        [Parameter(Mandatory)][string]$InstallModulePath,
        [scriptblock]$GetHashAction = {
            param($Path)
            Get-FileHash -LiteralPath $Path -Algorithm SHA256 -ErrorAction Stop
        },
        [scriptblock]$ReadTextAction = {
            param($Path)
            Get-Content -LiteralPath $Path -Raw -ErrorAction Stop
        }
    )

    $pinnedDigest = Read-PinnedWinSwSha256 -Path $InstallModulePath `
        -ReadTextAction $ReadTextAction
    $installedDigest = (& $GetHashAction $WorkerPath).Hash
    if ([string]$installedDigest -cne $pinnedDigest) {
        throw 'The installed worker executable does not match the authoritative WinSW digest.'
    }
    return $pinnedDigest
}

function Get-InstalledWorkerAcceptanceInputs {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][hashtable]$Environment,
        [scriptblock]$GetItemAction = {
            param($Path)
            Get-Item -LiteralPath $Path -Force -ErrorAction Stop
        },
        [scriptblock]$GetPathIdentityAction = {
            param($Path,$Directory)
            Get-NativePathIdentity -Path $Path -Directory:$Directory
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

    $visibleRootIdentity = & $GetPathIdentityAction $visibleRoot $true
    $mediaFixtureIdentity = & $GetPathIdentityAction $mediaFixture $false
    $privateRootIdentity = & $GetPathIdentityAction $privateRoot $true
    $privateProbeDirectoryIdentity = & $GetPathIdentityAction $privateProbeDirectory $true
    $protectedConfigIdentity = & $GetPathIdentityAction $protectedConfig $false
    if (-not (Test-StrictlyBelowPath -Path $mediaFixtureIdentity.NativeFinalPath `
        -Root $visibleRootIdentity.NativeFinalPath) -or
        -not (Test-StrictlyBelowPath -Path $privateProbeDirectoryIdentity.NativeFinalPath `
            -Root $privateRootIdentity.NativeFinalPath)) {
        throw 'Native final-path containment is invalid.'
    }
    if ((Test-StrictlyBelowPath -Path $visibleRootIdentity.NativeFinalPath `
            -Root $privateRootIdentity.NativeFinalPath) -or
        (Test-StrictlyBelowPath -Path $privateRootIdentity.NativeFinalPath `
            -Root $visibleRootIdentity.NativeFinalPath) -or
        [string]::Equals($visibleRootIdentity.NativeFinalPath,
            $privateRootIdentity.NativeFinalPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Native shared-root containment is invalid.'
    }
    foreach ($rootIdentity in @($visibleRootIdentity, $privateRootIdentity)) {
        if ((Test-StrictlyBelowPath -Path $protectedConfigIdentity.NativeFinalPath `
                -Root $rootIdentity.NativeFinalPath) -or
            [string]::Equals($protectedConfigIdentity.NativeFinalPath,
                $rootIdentity.NativeFinalPath, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Native protected-config containment is invalid.'
        }
    }

    return [pscustomobject]@{
        VisibleRoot = $visibleRoot
        MediaFixture = $mediaFixture
        PrivateRoot = $privateRoot
        PrivateProbeDirectory = $privateProbeDirectory
        ProtectedConfig = $protectedConfig
        VisibleRootIdentity = $visibleRootIdentity
        MediaFixtureIdentity = $mediaFixtureIdentity
        PrivateRootIdentity = $privateRootIdentity
        PrivateProbeDirectoryIdentity = $privateProbeDirectoryIdentity
        ProtectedConfigIdentity = $protectedConfigIdentity
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
        [Parameter(Mandatory)]$ResultRootIdentity,
        [Parameter(Mandatory)][string]$ProbeFile,
        [Parameter(Mandatory)]$ProbeExecutableIdentity
    )

    function ConvertTo-ProbeIdentityRecord {
        param([Parameter(Mandatory)]$Identity)
        return [ordered]@{
            NativeFinalPath = [string]$Identity.NativeFinalPath
            VolumeSerialNumber = [string]$Identity.VolumeSerialNumber
            FileIndex = [string]$Identity.FileIndex
        }
    }

    $probeInput = [ordered]@{
        schemaVersion = 1
        visibleRoot = ConvertTo-ProbeIdentityRecord $Inputs.VisibleRootIdentity
        mediaFixture = ConvertTo-ProbeIdentityRecord $Inputs.MediaFixtureIdentity
        privateRoot = ConvertTo-ProbeIdentityRecord $Inputs.PrivateRootIdentity
        privateProbeDirectory = ConvertTo-ProbeIdentityRecord `
            $Inputs.PrivateProbeDirectoryIdentity
        protectedConfig = ConvertTo-ProbeIdentityRecord $Inputs.ProtectedConfigIdentity
        resultRoot = ConvertTo-ProbeIdentityRecord $ResultRootIdentity
        probeExecutable = ConvertTo-ProbeIdentityRecord $ProbeExecutableIdentity
        probePath = $ProbeFile
    }
    $probeInputJson = $probeInput | ConvertTo-Json -Compress -Depth 4
    if ([Text.Encoding]::UTF8.GetByteCount($probeInputJson) -gt 4096) {
        throw 'The encoded probe input is too large.'
    }
    $encodedProbeInput = [Convert]::ToBase64String(
        [Text.Encoding]::UTF8.GetBytes($probeInputJson))
    $template = @'
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @"
using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;
public static class AcceptanceNative {
    [StructLayout(LayoutKind.Sequential)] public struct LUID { public uint LowPart; public int HighPart; }
    [StructLayout(LayoutKind.Sequential)] public struct LUID_AND_ATTRIBUTES { public LUID Luid; public uint Attributes; }
    [StructLayout(LayoutKind.Sequential)] public struct PRIVILEGE_SET { public uint PrivilegeCount; public uint Control; public LUID_AND_ATTRIBUTES Privilege; }
    [StructLayout(LayoutKind.Sequential)] private struct FILE_INFO {
        public uint Attributes;
        public System.Runtime.InteropServices.ComTypes.FILETIME CreationTime;
        public System.Runtime.InteropServices.ComTypes.FILETIME AccessTime;
        public System.Runtime.InteropServices.ComTypes.FILETIME WriteTime;
        public uint VolumeSerialNumber;
        public uint SizeHigh;
        public uint SizeLow;
        public uint LinkCount;
        public uint FileIndexHigh;
        public uint FileIndexLow;
    }
    public sealed class PATH_IDENTITY {
        public string NativeFinalPath { get; set; }
        public uint VolumeSerialNumber { get; set; }
        public ulong FileIndex { get; set; }
    }
    [DllImport("advapi32.dll", SetLastError=true)] public static extern IntPtr OpenSCManager(string machine, string database, uint access);
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)] public static extern IntPtr OpenServiceW(IntPtr manager, string name, uint access);
    [DllImport("advapi32.dll")] public static extern bool CloseServiceHandle(IntPtr handle);
    [DllImport("advapi32.dll", SetLastError=true)] public static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)] public static extern bool LookupPrivilegeValue(string system, string name, out LUID luid);
    [DllImport("advapi32.dll", SetLastError=true)] public static extern bool PrivilegeCheck(IntPtr token, ref PRIVILEGE_SET required, out bool result);
    [DllImport("kernel32.dll")] public static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] private static extern SafeFileHandle CreateFileW(string path, uint access, uint share, IntPtr security, uint disposition, uint flags, IntPtr template);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] private static extern uint GetFinalPathNameByHandleW(SafeFileHandle handle, StringBuilder path, uint length, uint flags);
    [DllImport("kernel32.dll", SetLastError=true)] private static extern bool GetFileInformationByHandle(SafeFileHandle handle, out FILE_INFO information);
    public static PATH_IDENTITY GetPathIdentity(string path, bool directory) {
        using (SafeFileHandle handle = CreateFileW(path, 0x80, 7, IntPtr.Zero, 3, directory ? 0x02000000u : 0u, IntPtr.Zero)) {
            if (handle.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
            var finalPath = new StringBuilder(32768);
            uint length = GetFinalPathNameByHandleW(handle, finalPath, (uint)finalPath.Capacity, 0);
            if (length == 0 || length >= finalPath.Capacity) throw new Win32Exception(Marshal.GetLastWin32Error());
            FILE_INFO information;
            if (!GetFileInformationByHandle(handle, out information)) throw new Win32Exception(Marshal.GetLastWin32Error());
            string normalized = finalPath.ToString();
            if (normalized.StartsWith(@"\\?\UNC\", StringComparison.OrdinalIgnoreCase)) normalized = @"\\" + normalized.Substring(8);
            else if (normalized.StartsWith(@"\\?\", StringComparison.OrdinalIgnoreCase)) normalized = normalized.Substring(4);
            return new PATH_IDENTITY {
                NativeFinalPath = Path.GetFullPath(normalized).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar),
                VolumeSerialNumber = information.VolumeSerialNumber,
                FileIndex = ((ulong)information.FileIndexHigh << 32) | information.FileIndexLow
            };
        }
    }
}
"@
$inputJson = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('__PROBE_INPUT__'))
$input = $inputJson | ConvertFrom-Json -ErrorAction Stop
$expectedInputFields = @('mediaFixture','privateProbeDirectory','privateRoot','probeExecutable',
    'probePath','protectedConfig','resultRoot','schemaVersion','visibleRoot')
if ([string]::Join('|', @($input.PSObject.Properties.Name | Sort-Object)) -ne
    [string]::Join('|', $expectedInputFields) -or $input.schemaVersion -ne 1) {
    throw 'Invalid probe input schema.'
}
$expectedIdentityFields = @('FileIndex','NativeFinalPath','VolumeSerialNumber')
foreach ($identityField in @(
    'visibleRoot','mediaFixture','privateRoot','privateProbeDirectory',
    'protectedConfig','resultRoot','probeExecutable')) {
    $identity = $input.$identityField
    if ([string]::Join('|', @($identity.PSObject.Properties.Name | Sort-Object)) -ne
            [string]::Join('|', $expectedIdentityFields) -or
        $identity.NativeFinalPath -isnot [string] -or
        [string]::IsNullOrWhiteSpace($identity.NativeFinalPath) -or
        [string]$identity.VolumeSerialNumber -notmatch '^\d+$' -or
        [string]$identity.FileIndex -notmatch '^\d+$') {
        throw 'Invalid probe identity schema.'
    }
}
if ($input.probePath -isnot [string] -or
    [string]::IsNullOrWhiteSpace($input.probePath) -or $input.probePath.Length -gt 32767) {
    throw 'Invalid probe path schema.'
}
function Assert-ExpectedIdentity {
    param($Expected,[bool]$Directory)
    $actual = [AcceptanceNative]::GetPathIdentity($Expected.NativeFinalPath, $Directory)
    if (-not [string]::Equals($actual.NativeFinalPath, $Expected.NativeFinalPath,
            [StringComparison]::OrdinalIgnoreCase) -or
        [string]$actual.VolumeSerialNumber -cne [string]$Expected.VolumeSerialNumber -or
        [string]$actual.FileIndex -cne [string]$Expected.FileIndex) {
        throw 'Native identity changed.'
    }
}
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
    Assert-ExpectedIdentity $input.probeExecutable $false
    Assert-ExpectedIdentity $input.visibleRoot $true
    Assert-ExpectedIdentity $input.mediaFixture $false
    $stream = [IO.File]::Open($input.mediaFixture.NativeFinalPath, 'Open', 'Read', 'Read')
    try { $result.fixtureReadable = $stream.ReadByte() -ge 0 } finally { $stream.Dispose() }
    Assert-ExpectedIdentity $input.privateRoot $true
    Assert-ExpectedIdentity $input.privateProbeDirectory $true
    if (-not [string]::Equals([IO.Path]::GetDirectoryName($input.probePath),
        $input.privateProbeDirectory.NativeFinalPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Invalid private probe path.'
    }
    $probeStream = [IO.FileStream]::new(
        $input.probePath,
        [IO.FileMode]::CreateNew,
        [IO.FileAccess]::ReadWrite,
        [IO.FileShare]::None,
        4096,
        [IO.FileOptions]::DeleteOnClose)
    try {
        $result.privateCreate = [IO.File]::Exists($input.probePath)
        $probeStream.WriteByte(37)
        $probeStream.Position = 0
        $result.privateRead = $probeStream.ReadByte() -eq 37
    } finally { $probeStream.Dispose() }
    $result.privateDelete = -not [IO.File]::Exists($input.probePath)
    try {
        Assert-ExpectedIdentity $input.protectedConfig $false
        $protected = [IO.File]::Open($input.protectedConfig.NativeFinalPath, 'Open', 'Read', 'ReadWrite')
        $protected.Dispose()
    } catch [UnauthorizedAccessException] {
        $result.configReadDenied = $true
    } catch [System.ComponentModel.Win32Exception] {
        if ($_.Exception.NativeErrorCode -ne 5) { throw }
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
    Assert-ExpectedIdentity $input.resultRoot $true
    $json = $result | ConvertTo-Json -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    if ($bytes.Length -le 2048) {
        $resultPath = [IO.Path]::Combine($input.resultRoot.NativeFinalPath, 'result.json')
        $resultStream = [IO.FileStream]::new(
            $resultPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $resultStream.Write($bytes, 0, $bytes.Length) } finally { $resultStream.Dispose() }
    }
}
'@
    return $template.Replace('__PROBE_INPUT__', $encodedProbeInput)
}

function New-InstalledWorkerProbeArguments {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$ProbeScript,
        [ValidateRange(1, 32767)][int]$MaximumLength = 30000
    )

    $probeBytes = [Text.UTF8Encoding]::new($false, $true).GetBytes($ProbeScript)
    $compressedStream = [IO.MemoryStream]::new()
    try {
        $compressor = [IO.Compression.GZipStream]::new(
            $compressedStream,
            [IO.Compression.CompressionMode]::Compress,
            $true)
        try {
            $compressor.Write($probeBytes, 0, $probeBytes.Length)
        } finally {
            $compressor.Dispose()
        }
        $compressedBytes = $compressedStream.ToArray()
    } finally {
        $compressedStream.Dispose()
    }

    $payload = [Convert]::ToBase64String($compressedBytes)
    $bootstrapTemplate = @'
$ErrorActionPreference = 'Stop'
$compressedBytes = [Convert]::FromBase64String('__COMPRESSED_PROBE__')
$compressedStream = [IO.MemoryStream]::new($compressedBytes)
$decompressor = [IO.Compression.GZipStream]::new($compressedStream, [IO.Compression.CompressionMode]::Decompress)
$reader = [IO.StreamReader]::new($decompressor, [Text.UTF8Encoding]::new($false, $true))
try {
    & ([ScriptBlock]::Create($reader.ReadToEnd()))
} finally {
    $reader.Dispose()
    $decompressor.Dispose()
    $compressedStream.Dispose()
}
'@
    $bootstrap = $bootstrapTemplate.Replace('__COMPRESSED_PROBE__', $payload)
    $encodedBootstrap = [Convert]::ToBase64String(
        [Text.Encoding]::Unicode.GetBytes($bootstrap))
    $arguments = "-NoLogo -NoProfile -NonInteractive -EncodedCommand $encodedBootstrap"
    if ($arguments.Length -gt $MaximumLength) {
        throw "The compressed installed-worker probe command is too large: $($arguments.Length) characters."
    }
    return $arguments
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
    Assert-InstalledWinSwDigest -WorkerPath $installed.WorkerExe `
        -InstallModulePath (Join-Path $PSScriptRoot '..\modules\Production.Install.psm1') |
        Out-Null
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
    $probeExecutable = Assert-FixedProbeExecutable `
        -Path 'C:\Program Files\PowerShell\7\pwsh.exe'
    $expectedPwsh = $probeExecutable.Path
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
    $workerProcessIdentity = Get-NativePathIdentity `
        -Path $workerProcess[0].ExecutablePath
    if (-not (Test-NativePathIdentityEqual -Expected $probeExecutable.Identity `
        -Actual $workerProcessIdentity)) {
        throw 'The running worker child executable identity is unexpected.'
    }
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
        ProbeExecutable = $probeExecutable
        Tools = $tools
    }
}

function Assert-RegisteredProbeTaskContract {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Task,
        [Parameter(Mandatory)]$Specification
    )

    $principalSid = $null
    try {
        $principalAccount = [Security.Principal.NTAccount]::new(
            [string]$Task.Principal.UserId)
        $principalSid = [string]$principalAccount.Translate(
            [Security.Principal.SecurityIdentifier]).Value
    } catch {
        $principalSid = $null
    }

    $actions = @($Task.Actions)
    if ($actions.Count -ne 1 -or
        -not [string]::Equals($actions[0].Execute, $Specification.Execute,
            [StringComparison]::OrdinalIgnoreCase) -or
        $actions[0].Arguments -cne $Specification.Arguments -or
        $principalSid -cne 'S-1-5-19' -or
        [string]$Task.Principal.LogonType -cne 'ServiceAccount' -or
        [string]$Task.Principal.RunLevel -cne 'Limited' -or
        -not [bool]$Task.Settings.Hidden -or
        [string]$Task.Settings.ExecutionTimeLimit -cne 'PT1M') {
        throw 'The registered acceptance task contract is invalid.'
    }
}

function Invoke-BoundedScheduledTaskCleanup {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][ValidateSet('Stop','Unregister')][string]$Operation,
        [Parameter(Mandatory)][string]$Name,
        [hashtable]$Actions = @{}
    )

    $startJob = if ($Actions.ContainsKey('StartJob') -and $null -ne $Actions['StartJob']) {
        $Actions['StartJob']
    } else {
        {
            param($RequestedOperation,$TaskName)
            if ($RequestedOperation -eq 'Stop') {
                Stop-ScheduledTask -TaskName $TaskName -AsJob -ErrorAction Stop
            } else {
                Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false `
                    -AsJob -ErrorAction Stop
            }
        }
    }
    $waitJob = if ($Actions.ContainsKey('WaitJob') -and $null -ne $Actions['WaitJob']) {
        $Actions['WaitJob']
    } else {
        { param($Job,$TimeoutSeconds) Wait-Job -Job $Job -Timeout $TimeoutSeconds }
    }
    $receiveJob = if ($Actions.ContainsKey('ReceiveJob') -and
        $null -ne $Actions['ReceiveJob']) {
        $Actions['ReceiveJob']
    } else {
        { param($Job) Receive-Job -Job $Job -ErrorAction Stop | Out-Null }
    }
    $stopJob = if ($Actions.ContainsKey('StopJob') -and $null -ne $Actions['StopJob']) {
        $Actions['StopJob']
    } else {
        { param($Job) Stop-Job -Job $Job -ErrorAction Stop }
    }
    $removeJob = if ($Actions.ContainsKey('RemoveJob') -and
        $null -ne $Actions['RemoveJob']) {
        $Actions['RemoveJob']
    } else {
        { param($Job) Remove-Job -Job $Job -Force -ErrorAction Stop }
    }

    $job = $null
    $failed = $false
    try {
        $job = & $startJob $Operation $Name
        if ($null -eq $job) { throw 'Missing cleanup job.' }
        $completed = & $waitJob $job 10
        if ($null -eq $completed) {
            & $stopJob $job
            throw 'Cleanup job timed out.'
        }
        & $receiveJob $job
    } catch {
        $failed = $true
    } finally {
        if ($job) {
            try { & $removeJob $job } catch { $failed = $true }
        }
    }
    if ($failed) { throw 'The bounded cleanup action failed.' }
}

function New-InstalledWorkerProbeDependencies {
    [CmdletBinding()]
    param()

    return @{
        NewNonce = { [guid]::NewGuid().ToString('N') }
        ResultRootParent = [Environment]::GetFolderPath(
            [Environment+SpecialFolder]::CommonApplicationData)
        ValidateResultRootParent = {
            param($Path)
            $environment = @{ ProgramData = $Path }
            $canonical = Resolve-InstalledWorkerAcceptancePath -Environment $environment `
                -Name ProgramData -Directory $true -GetItemAction {
                    param($Candidate)
                    Get-Item -LiteralPath $Candidate -Force -ErrorAction Stop
                }
            Get-NativePathIdentity -Path $canonical -Directory
        }
        ValidateProbeExecutable = {
            Assert-FixedProbeExecutable -Path 'C:\Program Files\PowerShell\7\pwsh.exe'
        }
        CreateResultDirectory = {
            param($Path)
            [ChristopherBell.Dev.Acceptance.NativePath]::CreateResultDirectoryNew($Path)
            try {
                Get-NativePathIdentity -Path $Path -Directory
            } catch {
                try { [IO.Directory]::Delete($Path, $false) } catch {
                    throw 'Installed-worker acceptance cleanup failed.'
                }
                throw
            }
        }
        ValidateResultDirectory = {
            param($Path,$ExpectedIdentity)
            Assert-InstalledWorkerResultDirectoryState -Path $Path `
                -ExpectedIdentity $ExpectedIdentity
        }
        ValidateResultFile = {
            param($Path,$ResultRootIdentity)
            Assert-InstalledWorkerResultFileState -Path $Path `
                -ResultRootIdentity $ResultRootIdentity
        }
        RegisterTask = {
            param($Specification)
            $action = New-ScheduledTaskAction -Execute $Specification.Execute `
                -Argument $Specification.Arguments
            $principal = New-ScheduledTaskPrincipal -UserId 'NT AUTHORITY\LOCAL SERVICE' `
                -LogonType ServiceAccount -RunLevel Limited
            $settings = New-ScheduledTaskSettingsSet -Hidden `
                -ExecutionTimeLimit ([TimeSpan]::FromMinutes(1)) `
                -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
            $task = New-ScheduledTask -Action $action -Principal $principal -Settings $settings
            Register-ScheduledTask -TaskName $Specification.Name -InputObject $task `
                -ErrorAction Stop | Out-Null
        }
        GetTask = {
            param($Name)
            $matches = @(Get-ScheduledTask -ErrorAction Stop | Where-Object TaskName -CEQ $Name)
            if ($matches.Count -gt 1) { throw 'The acceptance task lookup is ambiguous.' }
            if ($matches.Count -eq 1) { return $matches[0] }
            return $null
        }
        GetTaskInfo = {
            param($Name)
            $information = Get-ScheduledTaskInfo -TaskName $Name -ErrorAction Stop
            [pscustomobject]@{ LastTaskResult = [int64]$information.LastTaskResult }
        }
        StartTask = { param($Name) Start-ScheduledTask -TaskName $Name -ErrorAction Stop }
        StopTask = {
            param($Name)
            Invoke-BoundedScheduledTaskCleanup -Operation Stop -Name $Name
        }
        UnregisterTask = {
            param($Name)
            Invoke-BoundedScheduledTaskCleanup -Operation Unregister -Name $Name
        }
        GetPathIdentity = {
            param($Path,$Directory)
            Get-NativePathIdentity -Path $Path -Directory:$Directory
        }
        ResultExists = {
            param($Path)
            Test-Path -LiteralPath $Path -PathType Leaf -ErrorAction Stop
        }
        ResultDirectoryExists = {
            param($Path)
            Test-Path -LiteralPath $Path -PathType Container -ErrorAction Stop
        }
        RemoveResultDirectory = {
            param($Path)
            $children = @(Get-ChildItem -LiteralPath $Path -Force -ErrorAction Stop)
            if (@($children | Where-Object Name -CNE 'result.json').Count -gt 0 -or
                @($children | Where-Object Name -CEQ 'result.json').Count -gt 1) {
                throw 'The owned acceptance result directory contains unexpected entries.'
            }
            $resultPath = Join-Path $Path 'result.json'
            if ([IO.File]::Exists($resultPath)) { [IO.File]::Delete($resultPath) }
            [IO.Directory]::Delete($Path, $false)
        }
        ProbeExists = {
            param($Path)
            Test-Path -LiteralPath $Path -PathType Leaf -ErrorAction Stop
        }
        UtcNow = { [DateTime]::UtcNow }
        Wait = { param($Duration) Start-Sleep -Milliseconds $Duration.TotalMilliseconds }
        ReadResult = { param($Path) Read-InstalledWorkerProbeResult -Path $Path }
    }
}

function Invoke-InstalledWorkerLocalServiceProbe {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Inputs,
        [hashtable]$Dependencies
    )

    if ($null -eq $Dependencies) { $Dependencies = New-InstalledWorkerProbeDependencies }
    $requiredDependencies = @(
        'NewNonce','ResultRootParent','ValidateResultRootParent','ValidateProbeExecutable',
        'CreateResultDirectory','ValidateResultDirectory','ValidateResultFile',
        'RegisterTask','GetTask','GetTaskInfo','StartTask','StopTask','UnregisterTask','GetPathIdentity',
        'ResultExists','ResultDirectoryExists','RemoveResultDirectory','ProbeExists','UtcNow',
        'Wait','ReadResult')
    $missingDependencies = @($requiredDependencies | Where-Object {
        -not $Dependencies.ContainsKey($_) -or $null -eq $Dependencies[$_]
    })
    $extraDependencies = @($Dependencies.Keys | Where-Object { $_ -notin $requiredDependencies })
    if ($missingDependencies.Count -gt 0 -or $extraDependencies.Count -gt 0) {
        throw 'The installed-worker probe dependency contract is invalid.'
    }

    $nonce = [string](& $Dependencies.NewNonce)
    if ($nonce -notmatch '^[0-9a-f]{32}$') {
        throw 'The installed-worker probe nonce is invalid.'
    }
    $taskName = "ChristopherBellMediaWorkerAcceptance-$nonce"
    $resultRootParentIdentity = & $Dependencies.ValidateResultRootParent `
        ([string]$Dependencies.ResultRootParent)
    $resultRootParent = [string]$resultRootParentIdentity.NativeFinalPath
    $resultRoot = Join-Path $resultRootParent "ChristopherBellWorkerAcceptance-$nonce"
    $resultPath = Join-Path $resultRoot 'result.json'
    $probeFile = Join-Path $Inputs.PrivateProbeDirectoryIdentity.NativeFinalPath `
        "acceptance-$nonce.bin"
    $resultRootIdentity = $null
    $taskNameReserved = $false
    $primaryFailure = $null
    $probeResult = $null
    $cleanupFailed = $false

    try {
        $probeExecutable = & $Dependencies.ValidateProbeExecutable
        if (& $Dependencies.GetTask $taskName) {
            throw 'A unique acceptance task already exists.'
        }
        $taskNameReserved = $true
        if ((& $Dependencies.ResultDirectoryExists $resultRoot) -or
            (& $Dependencies.ProbeExists $probeFile)) {
            throw 'A unique acceptance resource already exists.'
        }
        $resultRootIdentity = & $Dependencies.CreateResultDirectory $resultRoot
        if (-not [string]::Equals($resultRootIdentity.NativeFinalPath, $resultRoot,
            [StringComparison]::OrdinalIgnoreCase) -or
            -not (Test-StrictlyBelowPath -Path $resultRootIdentity.NativeFinalPath `
                -Root $resultRootParentIdentity.NativeFinalPath)) {
            throw 'The created acceptance result identity is invalid.'
        }
        & $Dependencies.ValidateResultDirectory $resultRoot $resultRootIdentity
        $probeScript = New-InstalledWorkerProbeScript -Inputs $Inputs `
            -ResultRoot $resultRoot -ResultRootIdentity $resultRootIdentity `
            -ProbeFile $probeFile -ProbeExecutableIdentity $probeExecutable.Identity
        $arguments = New-InstalledWorkerProbeArguments -ProbeScript $probeScript
        $specification = [pscustomobject]@{
            Name = $taskName
            Execute = $probeExecutable.Path
            Arguments = $arguments
        }
        & $Dependencies.RegisterTask $specification
        $registeredTask = & $Dependencies.GetTask $taskName
        if ($null -eq $registeredTask) {
            throw 'The registered acceptance task is unavailable.'
        }
        Assert-RegisteredProbeTaskContract -Task $registeredTask `
            -Specification $specification
        & $Dependencies.StartTask $taskName

        $deadline = (& $Dependencies.UtcNow).AddSeconds(45)
        while (-not (& $Dependencies.ResultExists $resultPath)) {
            if ((& $Dependencies.UtcNow) -ge $deadline) {
                $taskResult = 'unavailable'
                try {
                    $taskInformation = & $Dependencies.GetTaskInfo $taskName
                    if ($taskInformation -and
                        $taskInformation.PSObject.Properties['LastTaskResult']) {
                        $unsignedResult = [int64]$taskInformation.LastTaskResult -band 0xFFFFFFFFL
                        $taskResult = '{0} (0x{1})' -f $unsignedResult,
                            $unsignedResult.ToString('X8')
                    }
                } catch { }
                throw "The bounded LocalService probe did not produce a result. LastTaskResult=$taskResult."
            }
            & $Dependencies.Wait ([TimeSpan]::FromMilliseconds(250))
        }
        & $Dependencies.ValidateResultDirectory $resultRoot $resultRootIdentity
        & $Dependencies.ValidateResultFile $resultPath $resultRootIdentity
        $probeResult = & $Dependencies.ReadResult $resultPath
    } catch {
        $primaryFailure = $_
    } finally {
        if ($taskNameReserved) {
            $cleanupTask = $null
            try { $cleanupTask = & $Dependencies.GetTask $taskName }
            catch { $cleanupFailed = $true }
            if ($cleanupTask) {
                if ([string]$cleanupTask.State -ceq 'Running') {
                    try { & $Dependencies.StopTask $taskName }
                    catch { $cleanupFailed = $true }
                }
                try { & $Dependencies.UnregisterTask $taskName }
                catch { $cleanupFailed = $true }
            }
            try {
                if (& $Dependencies.GetTask $taskName) { $cleanupFailed = $true }
            } catch { $cleanupFailed = $true }
        }
        if ($null -ne $resultRootIdentity) {
            $resultDirectoryPresent = $false
            try {
                $resultDirectoryPresent = & $Dependencies.ResultDirectoryExists $resultRoot
            } catch { $cleanupFailed = $true }
            if ($resultDirectoryPresent) {
                $identityMatches = $false
                try {
                    $cleanupIdentity = & $Dependencies.GetPathIdentity $resultRoot $true
                    $identityMatches = Test-NativePathIdentityEqual `
                        -Expected $resultRootIdentity -Actual $cleanupIdentity
                    if (-not $identityMatches) { $cleanupFailed = $true }
                } catch { $cleanupFailed = $true }
                if ($identityMatches) {
                    try { & $Dependencies.RemoveResultDirectory $resultRoot }
                    catch { $cleanupFailed = $true }
                }
            }
            try {
                if (& $Dependencies.ResultDirectoryExists $resultRoot) {
                    $cleanupFailed = $true
                }
            } catch { $cleanupFailed = $true }
        }
        try {
            if (& $Dependencies.ProbeExists $probeFile) { $cleanupFailed = $true }
        } catch { $cleanupFailed = $true }

        if ($cleanupFailed) {
            $inner = if ($primaryFailure) { $primaryFailure.Exception } else { $null }
            throw [InvalidOperationException]::new(
                'Installed-worker acceptance cleanup failed.', $inner)
        }
    }
    if ($primaryFailure) { throw $primaryFailure }
    return $probeResult
}

function New-TestInstalledWorkerProbeScenario {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$ResultRootParent,
        [ValidateSet('Success','Executable','Collision','Registration','Start','Timeout','Result')]
        [string]$FailurePhase = 'Success',
        [ValidateSet('None','Unregister','RemoveResult','ProbeLeak')]
        [string]$CleanupFailure = 'None',
        [switch]$IdentityMismatch,
        [switch]$TaskContractMismatch,
        [ValidateSet('None','Execute','Arguments','UserId','LogonType','RunLevel','Hidden','Limit')]
        [string]$TaskContractField = 'None',
        [switch]$PreexistingResult
    )

    $events = [Collections.Generic.List[string]]::new()
    $state = @{
        TaskPresent = $false
        Task = $null
        Specification = $null
        ResultDirectoryPresent = $PreexistingResult.IsPresent
        ResultReady = $false
        Now = [DateTime]::new(2026, 7, 22, 0, 0, 0, [DateTimeKind]::Utc)
    }
    $resultIdentity = [pscustomobject]@{
        NativeFinalPath = Join-Path $ResultRootParent `
            'ChristopherBellWorkerAcceptance-11111111111111111111111111111111'
        VolumeSerialNumber = 7
        FileIndex = 8
    }
    $probeExecutable = [pscustomobject]@{
        Path = 'C:\Program Files\PowerShell\7\pwsh.exe'
        Identity = [pscustomobject]@{
            NativeFinalPath = 'C:\Program Files\PowerShell\7\pwsh.exe'
            VolumeSerialNumber = 7
            FileIndex = 9
        }
    }
    $validResult = [pscustomobject]@{
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
    $dependencies = @{
        NewNonce = { '11111111111111111111111111111111' }
        ResultRootParent = $ResultRootParent
        ValidateResultRootParent = {
            param($Path)
            $events.Add('validate-result-parent')
            [pscustomobject]@{
                NativeFinalPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
                VolumeSerialNumber = 7
                FileIndex = 6
            }
        }.GetNewClosure()
        ValidateProbeExecutable = {
            $events.Add('validate-executable')
            if ($FailurePhase -eq 'Executable') { throw 'executable failed' }
            return $probeExecutable
        }.GetNewClosure()
        CreateResultDirectory = {
            param($Path)
            $events.Add('create-result')
            if ($FailurePhase -eq 'Collision') { throw 'atomic collision' }
            $state.ResultDirectoryPresent = $true
            return $resultIdentity
        }.GetNewClosure()
        ValidateResultDirectory = {
            param($Path,$ExpectedIdentity)
            $events.Add('validate-result-directory')
        }.GetNewClosure()
        ValidateResultFile = {
            param($Path,$ExpectedRootIdentity)
            $events.Add('validate-result-file')
        }.GetNewClosure()
        RegisterTask = {
            param($Specification)
            $events.Add('register')
            $state.Specification = $Specification
            $execute = if ($TaskContractMismatch -or $TaskContractField -eq 'Execute') {
                'C:\wrong.exe'
            } else { $Specification.Execute }
            $state.Task = [pscustomobject]@{
                Actions = @([pscustomobject]@{
                    Execute = $execute
                    Arguments = if ($TaskContractField -eq 'Arguments') {
                        'wrong'
                    } else { $Specification.Arguments }
                })
                Principal = [pscustomobject]@{
                    UserId = if ($TaskContractField -eq 'UserId') { 'SYSTEM' } else {
                        'NT AUTHORITY\LOCAL SERVICE'
                    }
                    LogonType = if ($TaskContractField -eq 'LogonType') {
                        'Password'
                    } else { 'ServiceAccount' }
                    RunLevel = if ($TaskContractField -eq 'RunLevel') {
                        'Highest'
                    } else { 'Limited' }
                }
                Settings = [pscustomobject]@{
                    Hidden = $TaskContractField -ne 'Hidden'
                    ExecutionTimeLimit = if ($TaskContractField -eq 'Limit') {
                        'PT5M'
                    } else { 'PT1M' }
                }
                State = 'Ready'
            }
            $state.TaskPresent = $true
            if ($FailurePhase -eq 'Registration') { throw 'registration failed' }
        }.GetNewClosure()
        GetTask = {
            param($Name)
            $events.Add('get-task')
            if ($state.TaskPresent) { return $state.Task }
            return $null
        }.GetNewClosure()
        GetTaskInfo = {
            param($Name)
            $events.Add('get-task-info')
            [pscustomobject]@{ LastTaskResult = 2147942402 }
        }.GetNewClosure()
        StartTask = {
            param($Name)
            $events.Add('start')
            if ($FailurePhase -eq 'Start') { throw 'start failed' }
            $state.Task.State = 'Running'
            if ($FailurePhase -in @('Success','Result')) {
                $state.ResultReady = $true
                $state.Task.State = 'Ready'
            }
        }.GetNewClosure()
        StopTask = {
            param($Name)
            $events.Add('stop')
            $state.Task.State = 'Ready'
        }.GetNewClosure()
        UnregisterTask = {
            param($Name)
            $events.Add('unregister')
            if ($CleanupFailure -eq 'Unregister') { throw 'unregister failed' }
            $state.TaskPresent = $false
        }.GetNewClosure()
        GetPathIdentity = {
            param($Path,$Directory)
            $events.Add('get-result-identity')
            if ($IdentityMismatch) {
                return [pscustomobject]@{
                    NativeFinalPath = $Path
                    VolumeSerialNumber = 7
                    FileIndex = 999
                }
            }
            return $resultIdentity
        }.GetNewClosure()
        ResultExists = {
            param($Path)
            $events.Add('result-exists')
            return $state.ResultReady
        }.GetNewClosure()
        ResultDirectoryExists = {
            param($Path)
            $events.Add('result-directory-exists')
            return $state.ResultDirectoryPresent
        }.GetNewClosure()
        RemoveResultDirectory = {
            param($Path)
            $events.Add('remove-result')
            if ($CleanupFailure -eq 'RemoveResult') { throw 'remove failed' }
            $state.ResultDirectoryPresent = $false
        }.GetNewClosure()
        ProbeExists = {
            param($Path)
            $events.Add('probe-exists')
            return $CleanupFailure -eq 'ProbeLeak'
        }.GetNewClosure()
        UtcNow = { return $state.Now }.GetNewClosure()
        Wait = {
            param($Duration)
            $events.Add('wait')
            $state.Now = $state.Now.AddSeconds(46)
        }.GetNewClosure()
        ReadResult = {
            param($Path)
            $events.Add('read-result')
            if ($FailurePhase -eq 'Result') { throw 'result failed' }
            return $validResult
        }.GetNewClosure()
    }
    return [pscustomobject]@{
        Dependencies = $dependencies
        Events = $events
        State = $state
    }
}

function New-TestResultSecuritySnapshot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][ValidateSet('Directory','File')][string]$Kind
    )

    $inherited = $Kind -eq 'File'
    $flags = if ($inherited) { 16 } else { 3 }
    return [pscustomobject]@{
        OwnerSid = if ($Kind -eq 'File') { 'S-1-5-19' } else { 'S-1-5-32-544' }
        DaclProtected = -not $inherited
        AccessRules = @(
            [pscustomobject]@{ Sid='S-1-5-18';AccessMask=0x001F01FF;AceFlags=$flags;AceType='AccessAllowed' }
            [pscustomobject]@{ Sid='S-1-5-32-544';AccessMask=0x001F01FF;AceFlags=$flags;AceType='AccessAllowed' }
            [pscustomobject]@{ Sid='S-1-5-19';AccessMask=0x001301BF;AceFlags=$flags;AceType='AccessAllowed' }
        )
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
        New-Item -ItemType Directory -Path $resultRoot | Out-Null
        $resultRootIdentity = Get-NativePathIdentity -Path $resultRoot -Directory
        $probeFile = Join-Path $probeDirectory 'probe.bin'
        $probeScript = New-InstalledWorkerProbeScript -Inputs $inputs `
            -ResultRoot $resultRoot -ResultRootIdentity $resultRootIdentity `
            -ProbeFile $probeFile -ProbeExecutableIdentity $resultRootIdentity
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

    It 'uses production defaults when fixed-probe action overrides are omitted' -Skip:(-not $IsWindows) {
        Set-StrictMode -Version Latest
        {
            Assert-FixedProbeExecutable -Path 'C:\Program Files\PowerShell\7\pwsh.exe'
        } | Should -Not -Throw
    }

    It 'accepts the Windows-normalized LocalService task principal by SID' -Skip:(-not $IsWindows) {
        $specification = [pscustomobject]@{
            Execute = 'C:\Program Files\PowerShell\7\pwsh.exe'
            Arguments = '-NoLogo -NoProfile -NonInteractive'
        }
        $task = [pscustomobject]@{
            Actions = @([pscustomobject]@{
                Execute = $specification.Execute
                Arguments = $specification.Arguments
            })
            Principal = [pscustomobject]@{
                UserId = 'LOCAL SERVICE'
                LogonType = 'ServiceAccount'
                RunLevel = 'Limited'
            }
            Settings = [pscustomobject]@{
                Hidden = $true
                ExecutionTimeLimit = 'PT1M'
            }
        }

        { Assert-RegisteredProbeTaskContract -Task $task -Specification $specification } |
            Should -Not -Throw
    }

    It 'accepts only the fixed native Microsoft PowerShell executable provenance' {
        $fixedPath = 'C:\Program Files\PowerShell\7\pwsh.exe'
        $fixedIdentity = [pscustomobject]@{
            NativeFinalPath = $fixedPath
            VolumeSerialNumber = 17
            FileIndex = 41
        }
        $actions = @{
            GetItem = { param($Path) [pscustomobject]@{
                FullName = $Path
                PSIsContainer = $false
                Attributes = [IO.FileAttributes]::Normal
            } }
            GetPathIdentity = { param($Path,$Directory) $fixedIdentity }
            GetSignature = { param($Path) [pscustomobject]@{
                Status = 'Valid'
                SignerCertificate = [pscustomobject]@{
                    Subject = 'CN=Microsoft Corporation, O=Microsoft Corporation, C=US'
                }
            } }
            GetVersion = { param($Path) [pscustomobject]@{
                CompanyName = 'Microsoft Corporation'
                ProductName = 'PowerShell'
            } }
        }

        $validated = Assert-FixedProbeExecutable -Path $fixedPath -Actions $actions
        $validated.Path | Should -Be $fixedPath
        $validated.Identity.FileIndex | Should -Be 41

        { Assert-FixedProbeExecutable -Path 'A:\tools\pwsh.exe' -Actions $actions } |
            Should -Throw '*fixed*'
        $actions.GetSignature = { [pscustomobject]@{ Status = 'HashMismatch' } }
        { Assert-FixedProbeExecutable -Path $fixedPath -Actions $actions } |
            Should -Throw '*provenance*'
        $actions.GetSignature = { [pscustomobject]@{
            Status = 'Valid'
            SignerCertificate = [pscustomobject]@{ Subject = 'CN=Other Publisher' }
        } }
        { Assert-FixedProbeExecutable -Path $fixedPath -Actions $actions } |
            Should -Throw '*provenance*'
        $actions.GetSignature = { [pscustomobject]@{
            Status = 'Valid'
            SignerCertificate = [pscustomobject]@{
                Subject = 'CN=Microsoft Corporation, O=Microsoft Corporation, C=US'
            }
        } }
        $actions.GetPathIdentity = { [pscustomobject]@{
            NativeFinalPath = 'C:\redirected\pwsh.exe'
            VolumeSerialNumber = 17
            FileIndex = 41
        } }
        { Assert-FixedProbeExecutable -Path $fixedPath -Actions $actions } |
            Should -Throw '*native path*'
    }

    It 'rejects native final-path containment aliases and identity drift' {
        $identityAction = {
            param($Path,$Directory)
            $fullPath = [IO.Path]::GetFullPath($Path)
            if ([string]::Equals($fullPath, [IO.Path]::GetFullPath($mediaFixture),
                [StringComparison]::OrdinalIgnoreCase)) {
                return [pscustomobject]@{
                    NativeFinalPath = 'C:\escaped\fixture.bin'
                    VolumeSerialNumber = 1
                    FileIndex = 2
                }
            }
            return [pscustomobject]@{
                NativeFinalPath = $fullPath
                VolumeSerialNumber = 1
                FileIndex = [Math]::Abs($fullPath.GetHashCode())
            }
        }.GetNewClosure()

        { Get-InstalledWorkerAcceptanceInputs -Environment $acceptanceEnvironment `
            -GetPathIdentityAction $identityAction } | Should -Throw '*native*containment*'

        $expected = [pscustomobject]@{
            NativeFinalPath = $mediaFixture
            VolumeSerialNumber = 9
            FileIndex = 10
        }
        $changed = [pscustomobject]@{
            NativeFinalPath = $mediaFixture
            VolumeSerialNumber = 9
            FileIndex = 11
        }
        Test-NativePathIdentityEqual -Expected $expected -Actual $expected | Should -BeTrue
        Test-NativePathIdentityEqual -Expected $expected -Actual $changed | Should -BeFalse
    }

    It 'embeds effect-time native identities and atomic no-overwrite file modes in the probe' {
        $inputs = Get-InstalledWorkerAcceptanceInputs -Environment $acceptanceEnvironment
        $resultRoot = Join-Path $TestDrive 'owned-result'
        New-Item -ItemType Directory -Path $resultRoot | Out-Null
        $resultIdentity = Get-NativePathIdentity -Path $resultRoot -Directory
        $probeFile = Join-Path $probeDirectory 'probe.bin'
        $probeScript = New-InstalledWorkerProbeScript -Inputs $inputs `
            -ResultRoot $resultRoot -ResultRootIdentity $resultIdentity `
            -ProbeFile $probeFile -ProbeExecutableIdentity $resultIdentity

        $probeScript | Should -Match 'NativeFinalPath'
        $encodedInput = [regex]::Match(
            $probeScript,
            "FromBase64String\('(?<value>[A-Za-z0-9+/=]+)'\)").Groups['value'].Value
        $decodedInput = [Text.Encoding]::UTF8.GetString(
            [Convert]::FromBase64String($encodedInput)) | ConvertFrom-Json
        @($decodedInput.PSObject.Properties.Name | Sort-Object) | Should -Be @(
            'mediaFixture','privateProbeDirectory','privateRoot','probeExecutable',
            'probePath','protectedConfig','resultRoot','schemaVersion','visibleRoot')
        foreach ($identityField in @(
            'visibleRoot','mediaFixture','privateRoot','privateProbeDirectory',
            'protectedConfig','resultRoot','probeExecutable')) {
            @($decodedInput.$identityField.PSObject.Properties.Name | Sort-Object) |
                Should -Be @('FileIndex','NativeFinalPath','VolumeSerialNumber')
        }
        [string]$decodedInput.mediaFixture.FileIndex |
            Should -Be ([string]$inputs.MediaFixtureIdentity.FileIndex)
        $probeScript | Should -Match 'expectedIdentityFields'
        $probeScript | Should -Match 'FileMode]::CreateNew'
        $probeScript | Should -Match 'FileOptions]::DeleteOnClose'
        $probeScript | Should -Match 'result\.json'
        $probeScript | Should -Match 'catch \[System\.ComponentModel\.Win32Exception\]'
        $probeScript | Should -Match 'NativeErrorCode -ne 5'
        @([regex]::Matches($probeScript, 'FileMode]::CreateNew')).Count | Should -Be 2

        $arguments = New-InstalledWorkerProbeArguments -ProbeScript $probeScript
        $directArguments = '-NoLogo -NoProfile -NonInteractive -EncodedCommand ' +
            [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($probeScript))
        $arguments.Length | Should -BeLessOrEqual 30000
        $arguments.Length | Should -BeLessThan $directArguments.Length
        $arguments | Should -Match `
            '^-NoLogo -NoProfile -NonInteractive -EncodedCommand [A-Za-z0-9+/=]+$'
    }

    It 'round trips a compressed in-memory probe command' {
        $arguments = New-InstalledWorkerProbeArguments `
            -ProbeScript "'compressed-probe-round-trip'"
        $encodedBootstrap = ($arguments -split ' ')[-1]
        $bootstrap = [Text.Encoding]::Unicode.GetString(
            [Convert]::FromBase64String($encodedBootstrap))

        $output = & ([ScriptBlock]::Create($bootstrap))

        $output | Should -BeExactly 'compressed-probe-round-trip'
    }

    It 'reads one authoritative anchored WinSW digest and rejects ambiguous pins' {
        $expected = '05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA'
        Read-PinnedWinSwSha256 -Path 'ignored' -ReadTextAction {
            param($Path)
            "`$script:WinSwSha256 = '$expected'`r`n"
        } | Should -Be $expected

        { Read-PinnedWinSwSha256 -Path 'ignored' -ReadTextAction {
            "`$script:WinSwSha256 = '$expected'`n`$script:WinSwSha256 = '$expected'`n"
        } } | Should -Throw '*authoritative*'
    }

    It 'compares the installed worker binary directly with the authoritative WinSW digest' {
        $expected = '05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA'
        Assert-InstalledWinSwDigest -WorkerPath 'worker.exe' -InstallModulePath 'install.psm1' `
            -GetHashAction { param($Path) [pscustomobject]@{ Hash = $expected } } `
            -ReadTextAction { "`$script:WinSwSha256 = '$expected'`n" } |
            Should -Be $expected

        { Assert-InstalledWinSwDigest -WorkerPath 'worker.exe' `
            -InstallModulePath 'install.psm1' `
            -GetHashAction { [pscustomobject]@{ Hash = '0' * 64 } } `
            -ReadTextAction { "`$script:WinSwSha256 = '$expected'`n" } } |
            Should -Throw '*authoritative WinSW digest*'
    }

    It 'refuses task contract drift before starting the injected non-live task' {
        $events = [Collections.Generic.List[string]]::new()
        $taskState = @{ Present = $false; ResultPresent = $false }
        $dependencies = @{
            NewNonce = { '11111111111111111111111111111111' }
            ResultRootParent = $TestDrive
            ValidateResultRootParent = { param($Path) [pscustomobject]@{
                NativeFinalPath = [IO.Path]::GetFullPath($Path).TrimEnd('\')
                VolumeSerialNumber = 1
                FileIndex = 4
            } }
            ValidateProbeExecutable = { [pscustomobject]@{
                Path = 'C:\Program Files\PowerShell\7\pwsh.exe'
                Identity = [pscustomobject]@{
                    NativeFinalPath = 'C:\Program Files\PowerShell\7\pwsh.exe'
                    VolumeSerialNumber = 1
                    FileIndex = 2
                }
            } }
            CreateResultDirectory = { param($Path) $events.Add('create-result');
                $taskState.ResultPresent = $true
                [pscustomobject]@{ NativeFinalPath=$Path;VolumeSerialNumber=1;FileIndex=3 } }
            ValidateResultDirectory = { param($Path,$Identity)
                $events.Add('validate-result-directory') }
            ValidateResultFile = { param($Path,$Identity)
                $events.Add('validate-result-file') }
            RegisterTask = { param($Spec) $events.Add('register'); $taskState.Present = $true }
            GetTask = { param($Name) $events.Add('get-task'); if ($taskState.Present) {
                [pscustomobject]@{
                    Actions = @([pscustomobject]@{ Execute='C:\wrong.exe';Arguments='wrong' })
                    Principal = [pscustomobject]@{
                        UserId='NT AUTHORITY\LOCAL SERVICE';LogonType='ServiceAccount';RunLevel='Limited'
                    }
                    Settings = [pscustomobject]@{ Hidden=$true;ExecutionTimeLimit='PT1M' }
                    State = 'Ready'
                }
            } }
            GetTaskInfo = { [pscustomobject]@{ LastTaskResult = 0 } }
            StartTask = { $events.Add('start') }
            StopTask = { $events.Add('stop') }
            UnregisterTask = { $events.Add('unregister'); $taskState.Present = $false }
            GetPathIdentity = { param($Path,$Directory)
                [pscustomobject]@{ NativeFinalPath=$Path;VolumeSerialNumber=1;FileIndex=3 } }
            ResultExists = { $false }
            ResultDirectoryExists = { $taskState.ResultPresent }
            RemoveResultDirectory = { $events.Add('remove-result');$taskState.ResultPresent=$false }
            ProbeExists = { $false }
            UtcNow = { [DateTime]::UtcNow }
            Wait = { }
            ReadResult = { throw 'must not read' }
        }
        $inputs = Get-InstalledWorkerAcceptanceInputs -Environment $acceptanceEnvironment

        { Invoke-InstalledWorkerLocalServiceProbe -Inputs $inputs `
            -Dependencies $dependencies } | Should -Throw '*task contract*'
        $events | Should -Not -Contain 'start'
        $events | Should -Contain 'unregister'
        $events | Should -Contain 'remove-result'
        $taskState.Present | Should -BeFalse
    }

    It 're-reads every task principal setting and action field before start' {
        $inputs = Get-InstalledWorkerAcceptanceInputs -Environment $acceptanceEnvironment
        foreach ($field in @(
            'Execute','Arguments','UserId','LogonType','RunLevel','Hidden','Limit')) {
            $scenario = New-TestInstalledWorkerProbeScenario -ResultRootParent $TestDrive `
                -TaskContractField $field

            { Invoke-InstalledWorkerLocalServiceProbe -Inputs $inputs `
                -Dependencies $scenario.Dependencies } | Should -Throw '*task contract*'
            $scenario.Events | Should -Not -Contain 'start'
            $scenario.State.TaskPresent | Should -BeFalse
            $scenario.State.ResultDirectoryPresent | Should -BeFalse
        }
    }

    It 'executes and cleans the exact injected task contract without live effects or sleeps' {
        $inputs = Get-InstalledWorkerAcceptanceInputs -Environment $acceptanceEnvironment
        $scenario = New-TestInstalledWorkerProbeScenario -ResultRootParent $TestDrive

        $result = Invoke-InstalledWorkerLocalServiceProbe -Inputs $inputs `
            -Dependencies $scenario.Dependencies

        $result.errorCode | Should -Be 'NONE'
        $scenario.State.Specification.Execute |
            Should -Be 'C:\Program Files\PowerShell\7\pwsh.exe'
        $scenario.State.Specification.Arguments |
            Should -Match '^-NoLogo -NoProfile -NonInteractive -EncodedCommand [A-Za-z0-9+/=]+$'
        $scenario.Events | Should -Contain 'start'
        $scenario.Events | Should -Contain 'unregister'
        $scenario.Events | Should -Contain 'remove-result'
        $scenario.Events | Should -Not -Contain 'wait'
        @($scenario.Events | Where-Object { $_ -eq 'get-task' }).Count | Should -BeGreaterOrEqual 4
        @($scenario.Events | Where-Object { $_ -eq 'result-directory-exists' }).Count |
            Should -BeGreaterOrEqual 3
        $scenario.State.TaskPresent | Should -BeFalse
        $scenario.State.ResultDirectoryPresent | Should -BeFalse
    }

    It 'cleans deterministically after registration start result and timeout failures' {
        $inputs = Get-InstalledWorkerAcceptanceInputs -Environment $acceptanceEnvironment
        $expectations = [ordered]@{
            Registration = 'registration failed'
            Start = 'start failed'
            Result = 'result failed'
            Timeout = 'did not produce a result*LastTaskResult=2147942402 (0x80070002)'
        }
        foreach ($phase in $expectations.Keys) {
            $scenario = New-TestInstalledWorkerProbeScenario `
                -ResultRootParent $TestDrive -FailurePhase $phase

            { Invoke-InstalledWorkerLocalServiceProbe -Inputs $inputs `
                -Dependencies $scenario.Dependencies } |
                Should -Throw "*$($expectations[$phase])*"
            $scenario.Events | Should -Contain 'unregister'
            $scenario.Events | Should -Contain 'remove-result'
            $scenario.State.TaskPresent | Should -BeFalse
            $scenario.State.ResultDirectoryPresent | Should -BeFalse
        }
    }

    It 'preserves the primary failure behind one fixed cleanup failure' {
        $inputs = Get-InstalledWorkerAcceptanceInputs -Environment $acceptanceEnvironment
        $scenario = New-TestInstalledWorkerProbeScenario -ResultRootParent $TestDrive `
            -FailurePhase Result -CleanupFailure Unregister
        $caught = $null

        try {
            Invoke-InstalledWorkerLocalServiceProbe -Inputs $inputs `
                -Dependencies $scenario.Dependencies | Out-Null
        } catch { $caught = $_ }

        $caught.Exception.Message | Should -Be 'Installed-worker acceptance cleanup failed.'
        $caught.Exception.InnerException.Message | Should -Be 'result failed'
        $scenario.Events | Should -Contain 'remove-result'
        @($scenario.Events | Where-Object { $_ -eq 'get-task' }).Count |
            Should -BeGreaterOrEqual 4
    }

    It 'surfaces result-removal and leaked-probe cleanup postcondition failures' {
        $inputs = Get-InstalledWorkerAcceptanceInputs -Environment $acceptanceEnvironment
        foreach ($cleanupFailure in @('RemoveResult','ProbeLeak')) {
            $scenario = New-TestInstalledWorkerProbeScenario -ResultRootParent $TestDrive `
                -CleanupFailure $cleanupFailure

            { Invoke-InstalledWorkerLocalServiceProbe -Inputs $inputs `
                -Dependencies $scenario.Dependencies } | Should -Throw '*cleanup failed*'
            $scenario.State.TaskPresent | Should -BeFalse
            if ($cleanupFailure -eq 'RemoveResult') {
                @($scenario.Events | Where-Object { $_ -eq 'result-directory-exists' }).Count |
                    Should -BeGreaterOrEqual 3
            }
        }
    }

    It 'bounds scheduled-task stop and unregister cleanup jobs' {
        $events = [Collections.Generic.List[string]]::new()
        $actions = @{
            StartJob = { param($Operation,$Name) $events.Add("start-$Operation");
                [pscustomobject]@{ Id = 1 } }
            WaitJob = { param($Job,$TimeoutSeconds) $events.Add("wait-$TimeoutSeconds"); $null }
            ReceiveJob = { $events.Add('receive') }
            StopJob = { $events.Add('stop-job') }
            RemoveJob = { $events.Add('remove-job') }
        }

        { Invoke-BoundedScheduledTaskCleanup -Operation Stop -Name 'exact-task' `
            -Actions $actions } | Should -Throw '*bounded cleanup action failed*'
        $events | Should -Contain 'start-Stop'
        $events | Should -Contain 'wait-10'
        $events | Should -Contain 'stop-job'
        $events | Should -Contain 'remove-job'
        $events | Should -Not -Contain 'receive'
    }

    It 'refuses atomic collisions without deleting resources it did not create' {
        $inputs = Get-InstalledWorkerAcceptanceInputs -Environment $acceptanceEnvironment
        $createCollision = New-TestInstalledWorkerProbeScenario `
            -ResultRootParent $TestDrive -FailurePhase Collision
        { Invoke-InstalledWorkerLocalServiceProbe -Inputs $inputs `
            -Dependencies $createCollision.Dependencies } | Should -Throw '*atomic collision*'
        $createCollision.Events | Should -Not -Contain 'register'
        $createCollision.Events | Should -Not -Contain 'remove-result'

        $preexisting = New-TestInstalledWorkerProbeScenario `
            -ResultRootParent $TestDrive -PreexistingResult
        { Invoke-InstalledWorkerLocalServiceProbe -Inputs $inputs `
            -Dependencies $preexisting.Dependencies } | Should -Throw '*already exists*'
        $preexisting.Events | Should -Not -Contain 'create-result'
        $preexisting.Events | Should -Not -Contain 'remove-result'
        $preexisting.State.ResultDirectoryPresent | Should -BeTrue
    }

    It 'fails cleanup closed rather than deleting an identity-mismatched result directory' {
        $inputs = Get-InstalledWorkerAcceptanceInputs -Environment $acceptanceEnvironment
        $scenario = New-TestInstalledWorkerProbeScenario -ResultRootParent $TestDrive `
            -FailurePhase Start -IdentityMismatch

        { Invoke-InstalledWorkerLocalServiceProbe -Inputs $inputs `
            -Dependencies $scenario.Dependencies } | Should -Throw '*cleanup failed*'
        $scenario.Events | Should -Not -Contain 'remove-result'
        $scenario.State.ResultDirectoryPresent | Should -BeTrue
        $scenario.State.TaskPresent | Should -BeFalse
    }

    It 'builds the protected result directory descriptor inside the atomic native create call' {
        $captured = @{}
        $nativeCreate = [Func[string,IntPtr,byte[],bool]]{
            param($Path,$SecurityAttributes,$SecurityDescriptor)
            $captured.Path = $Path
            $captured.AttributesLength = [Runtime.InteropServices.Marshal]::ReadInt32(
                $SecurityAttributes, 0)
            $descriptorOffset = if ([IntPtr]::Size -eq 8) { 8 } else { 4 }
            $inheritOffset = $descriptorOffset + [IntPtr]::Size
            $captured.DescriptorPointer = [Runtime.InteropServices.Marshal]::ReadIntPtr(
                $SecurityAttributes, $descriptorOffset)
            $captured.InheritHandle = [Runtime.InteropServices.Marshal]::ReadInt32(
                $SecurityAttributes, $inheritOffset)
            $captured.Descriptor = [Security.AccessControl.RawSecurityDescriptor]::new(
                $SecurityDescriptor, 0)
            return $true
        }

        [ChristopherBell.Dev.Acceptance.NativePath]::CreateResultDirectoryNew(
            'C:\ProgramData\ChristopherBellWorkerAcceptance-test', $nativeCreate)

        $captured.Path | Should -Be 'C:\ProgramData\ChristopherBellWorkerAcceptance-test'
        $captured.AttributesLength | Should -BeGreaterThan 0
        $captured.DescriptorPointer | Should -Not -Be ([IntPtr]::Zero)
        $captured.InheritHandle | Should -Be 0
        $captured.Descriptor.Owner.Value | Should -Be 'S-1-5-32-544'
        ($captured.Descriptor.ControlFlags -band
            [Security.AccessControl.ControlFlags]::SelfRelative) |
            Should -Not -Be 0
        ($captured.Descriptor.ControlFlags -band
            [Security.AccessControl.ControlFlags]::DiscretionaryAclProtected) |
            Should -Not -Be 0
        @($captured.Descriptor.DiscretionaryAcl).Count | Should -Be 3
        @($captured.Descriptor.DiscretionaryAcl | ForEach-Object {
            $_.SecurityIdentifier.Value
        }) | Should -Be @('S-1-5-18','S-1-5-32-544','S-1-5-19')
        @($captured.Descriptor.DiscretionaryAcl | ForEach-Object AccessMask) |
            Should -Be @(0x001F01FF,0x001F01FF,0x001301BF)
    }

    It 'accepts only the exact protected result directory owner and DACL' {
        Assert-InstalledWorkerResultDirectorySecurity `
            -Security (New-TestResultSecuritySnapshot -Kind Directory)

        foreach ($mutation in @('Owner','Protection','Users','Everyone','Inherited','Rights')) {
            $security = New-TestResultSecuritySnapshot -Kind Directory
            switch ($mutation) {
                Owner { $security.OwnerSid = 'S-1-5-18' }
                Protection { $security.DaclProtected = $false }
                Users { $security.AccessRules += [pscustomobject]@{
                    Sid='S-1-5-32-545';AccessMask=0x001301BF;AceFlags=3;AceType='AccessAllowed' } }
                Everyone { $security.AccessRules += [pscustomobject]@{
                    Sid='S-1-1-0';AccessMask=0x001301BF;AceFlags=3;AceType='AccessAllowed' } }
                Inherited { $security.AccessRules[0].AceFlags = 19 }
                Rights { $security.AccessRules[2].AccessMask = 0x001F01FF }
            }
            { Assert-InstalledWorkerResultDirectorySecurity -Security $security } |
                Should -Throw '*result directory security*'
        }
    }

    It 'accepts only a LocalService-owned result file with the exact inherited DACL' {
        Assert-InstalledWorkerResultFileSecurity `
            -Security (New-TestResultSecuritySnapshot -Kind File)

        foreach ($mutation in @('Owner','Dacl','Users','Everyone','Inheritance','Rights')) {
            $security = New-TestResultSecuritySnapshot -Kind File
            switch ($mutation) {
                Owner { $security.OwnerSid = 'S-1-5-18' }
                Dacl { $security.DaclProtected = $true }
                Users { $security.AccessRules += [pscustomobject]@{
                    Sid='S-1-5-32-545';AccessMask=0x001301BF;AceFlags=16;AceType='AccessAllowed' } }
                Everyone { $security.AccessRules += [pscustomobject]@{
                    Sid='S-1-1-0';AccessMask=0x001301BF;AceFlags=16;AceType='AccessAllowed' } }
                Inheritance { $security.AccessRules[0].AceFlags = 0 }
                Rights { $security.AccessRules[2].AccessMask = 0x001F01FF }
            }
            { Assert-InstalledWorkerResultFileSecurity -Security $security } |
                Should -Throw '*result file security*'
        }
    }

    It 'refuses a Users-writable schema-valid result before reading it as success' {
        $inputs = Get-InstalledWorkerAcceptanceInputs -Environment $acceptanceEnvironment
        $scenario = New-TestInstalledWorkerProbeScenario -ResultRootParent $TestDrive
        $scenario.Dependencies.ValidateResultDirectory = { param($Path,$Identity) }
        $forgedSecurity = New-TestResultSecuritySnapshot -Kind File
        $forgedSecurity.OwnerSid = 'S-1-5-32-545'
        $forgedSecurity.AccessRules += [pscustomobject]@{
            Sid='S-1-5-32-545';AccessMask=0x001301BF;AceFlags=16;AceType='AccessAllowed'
        }
        $validateResultFileState = ${function:Assert-InstalledWorkerResultFileState}
        $scenario.Dependencies.ValidateResultFile = {
            param($Path,$ResultRootIdentity)
            $scenario.Events.Add('validate-result-file')
            & $validateResultFileState -Path $Path `
                -ResultRootIdentity $ResultRootIdentity `
                -GetItemAction { [pscustomobject]@{
                    PSIsContainer = $false
                    Attributes = [IO.FileAttributes]::Normal
                } } `
                -GetPathIdentityAction { param($Candidate,$Directory)
                    [pscustomobject]@{
                        NativeFinalPath = $Candidate
                        VolumeSerialNumber = 7
                        FileIndex = 10
                    } } `
                -GetPathSecurityAction { return $forgedSecurity }
        }.GetNewClosure()

        { Invoke-InstalledWorkerLocalServiceProbe -Inputs $inputs `
            -Dependencies $scenario.Dependencies } |
            Should -Throw '*result file security*'
        $scenario.Events | Should -Contain 'validate-result-file'
        $scenario.Events | Should -Not -Contain 'read-result'
        $scenario.State.TaskPresent | Should -BeFalse
        $scenario.State.ResultDirectoryPresent | Should -BeFalse
    }

    It 'accepts only a non-reparse native-contained LocalService result child' {
        $root = [pscustomobject]@{
            NativeFinalPath = 'C:\ProgramData\ChristopherBellWorkerAcceptance-test'
            VolumeSerialNumber = 1
            FileIndex = 2
        }
        $path = Join-Path $root.NativeFinalPath 'result.json'
        $itemAction = { [pscustomobject]@{
            PSIsContainer = $false
            Attributes = [IO.FileAttributes]::Normal
        } }
        $identityAction = { param($Candidate,$Directory) [pscustomobject]@{
            NativeFinalPath = $Candidate
            VolumeSerialNumber = 1
            FileIndex = 3
        } }
        $securityAction = { New-TestResultSecuritySnapshot -Kind File }

        Assert-InstalledWorkerResultFileState -Path $path -ResultRootIdentity $root `
            -GetItemAction $itemAction -GetPathIdentityAction $identityAction `
            -GetPathSecurityAction $securityAction

        { Assert-InstalledWorkerResultFileState -Path $path -ResultRootIdentity $root `
            -GetItemAction { [pscustomobject]@{
                PSIsContainer = $false
                Attributes = [IO.FileAttributes]::ReparsePoint
            } } -GetPathIdentityAction $identityAction `
            -GetPathSecurityAction $securityAction } | Should -Throw '*result file state*'
        { Assert-InstalledWorkerResultFileState -Path $path -ResultRootIdentity $root `
            -GetItemAction $itemAction -GetPathIdentityAction {
                [pscustomobject]@{
                    NativeFinalPath = 'C:\escaped\result.json'
                    VolumeSerialNumber = 1
                    FileIndex = 3
                }
            } -GetPathSecurityAction $securityAction } | Should -Throw '*result file state*'
    }

    It 'revalidates result-directory identity and DACL before accepting a result' {
        $inputs = Get-InstalledWorkerAcceptanceInputs -Environment $acceptanceEnvironment
        $scenario = New-TestInstalledWorkerProbeScenario -ResultRootParent $TestDrive
        $validationState = @{ Count = 0 }
        $scenario.Dependencies.ValidateResultDirectory = {
            param($Path,$Identity)
            $validationState.Count++
            $scenario.Events.Add("validate-result-directory-$($validationState.Count)")
            if ($validationState.Count -eq 2) {
                throw 'The installed-worker result directory security is invalid.'
            }
        }.GetNewClosure()

        { Invoke-InstalledWorkerLocalServiceProbe -Inputs $inputs `
            -Dependencies $scenario.Dependencies } |
            Should -Throw '*result directory security*'
        $scenario.Events | Should -Contain 'validate-result-directory-1'
        $scenario.Events | Should -Contain 'validate-result-directory-2'
        $scenario.Events | Should -Not -Contain 'validate-result-file'
        $scenario.Events | Should -Not -Contain 'read-result'
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
        $result.websiteServiceControlDenied | Should -BeTrue `
            -Because "the probe reported errorCode '$($result.errorCode)'"
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
