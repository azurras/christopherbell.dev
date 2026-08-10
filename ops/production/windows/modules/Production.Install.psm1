Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:WinSwUri = 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe'
$script:WinSwSha256 = '05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA'
$script:ProtectedProductionDirectorySddl =
    'O:BAG:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)'

if (-not ('ChristopherBell.Dev.ProductionInstallNativeDirectory' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Text;

namespace ChristopherBell.Dev
{
    public sealed class ProductionInstallDirectoryIdentity
    {
        public string NativeFinalPath { get; set; }
        public uint VolumeSerialNumber { get; set; }
        public ulong FileIndex { get; set; }
        public bool ReparsePoint { get; set; }
    }

    public static class ProductionInstallNativeDirectory
    {
        private const uint FileReadAttributes = 0x80;
        private const uint FileShareRead = 0x1;
        private const uint FileShareWrite = 0x2;
        private const uint FileShareDelete = 0x4;
        private const uint OpenExisting = 3;
        private const uint FileFlagBackupSemantics = 0x02000000;
        private const uint FileFlagOpenReparsePoint = 0x00200000;
        private const uint FileAttributeReparsePoint = 0x400;
        private const uint MoveFileWriteThrough = 0x8;
        private const uint DirectoryAllAccess = 0x001F01FF;
        private const uint FileCreate = 2;
        private const uint FileDirectoryFile = 0x1;
        private const uint FileSynchronousIoNonAlert = 0x20;
        private const uint ObjectCaseInsensitive = 0x40;

        [StructLayout(LayoutKind.Sequential)]
        private struct SecurityAttributes
        {
            public int Length;
            public IntPtr SecurityDescriptor;
            public bool InheritHandle;
        }

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

        [StructLayout(LayoutKind.Sequential)]
        private struct UnicodeString
        {
            public ushort Length;
            public ushort MaximumLength;
            public IntPtr Buffer;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct ObjectAttributes
        {
            public int Length;
            public IntPtr RootDirectory;
            public IntPtr ObjectName;
            public uint Attributes;
            public IntPtr SecurityDescriptor;
            public IntPtr SecurityQualityOfService;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct IoStatusBlock
        {
            public IntPtr Status;
            public IntPtr Information;
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool CreateDirectoryW(
            string path,
            ref SecurityAttributes securityAttributes);

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

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool MoveFileExW(
            string existingFileName,
            string newFileName,
            uint flags);

        [DllImport("ntdll.dll")]
        private static extern int NtCreateFile(
            out IntPtr fileHandle,
            uint desiredAccess,
            ref ObjectAttributes objectAttributes,
            out IoStatusBlock ioStatusBlock,
            IntPtr allocationSize,
            uint fileAttributes,
            uint shareAccess,
            uint createDisposition,
            uint createOptions,
            IntPtr eaBuffer,
            uint eaLength);

        [DllImport("ntdll.dll")]
        private static extern uint RtlNtStatusToDosError(int status);

        public static ProductionInstallDirectoryIdentity CreateProtectedDirectoryNew(
            string path,
            string sddl)
        {
            RawSecurityDescriptor security = new RawSecurityDescriptor(sddl);
            byte[] descriptor = new byte[security.BinaryLength];
            security.GetBinaryForm(descriptor, 0);
            IntPtr descriptorBuffer = Marshal.AllocHGlobal(descriptor.Length);
            try
            {
                Marshal.Copy(descriptor, 0, descriptorBuffer, descriptor.Length);
                SecurityAttributes attributes = new SecurityAttributes();
                attributes.Length = Marshal.SizeOf(typeof(SecurityAttributes));
                attributes.SecurityDescriptor = descriptorBuffer;
                attributes.InheritHandle = false;
                if (!CreateDirectoryW(path, ref attributes))
                {
                    throw new Win32Exception(Marshal.GetLastWin32Error(),
                        "Protected directory creation failed.");
                }
            }
            finally
            {
                Marshal.FreeHGlobal(descriptorBuffer);
            }
            return GetIdentity(path);
        }

        public static ProductionInstallDirectoryIdentity GetIdentity(string path)
        {
            IntPtr handle = OpenDirectoryIdentityHandle(path);
            try
            {
                return GetIdentityFromHandle(handle);
            }
            finally
            {
                CloseHandle(handle);
            }
        }

        public static ProductionInstallDirectoryIdentity CreateProtectedChildDirectoryNew(
            string parentPath,
            uint expectedVolumeSerialNumber,
            ulong expectedFileIndex,
            string childName,
            string sddl)
        {
            if (String.IsNullOrEmpty(childName) || childName == "." || childName == ".." ||
                childName.IndexOfAny(new char[] { '\\', '/' }) >= 0)
            {
                throw new ArgumentException("Protected child directory name is invalid.",
                    "childName");
            }
            IntPtr parentHandle = OpenDirectoryIdentityHandle(parentPath);
            try
            {
                ProductionInstallDirectoryIdentity parentIdentity =
                    GetIdentityFromHandle(parentHandle);
                if (parentIdentity.ReparsePoint ||
                    parentIdentity.VolumeSerialNumber != expectedVolumeSerialNumber ||
                    parentIdentity.FileIndex != expectedFileIndex)
                {
                    InvalidOperationException identityFailure =
                        new InvalidOperationException(
                        "Protected child parent identity changed before creation.");
                    identityFailure.Data["ProductionInstallErrorCode"] =
                        "PARENT_IDENTITY_CHANGED";
                    throw identityFailure;
                }
                return CreateProtectedChildDirectoryNew(
                    parentHandle, childName, sddl);
            }
            finally
            {
                CloseHandle(parentHandle);
            }
        }

        private static IntPtr OpenDirectoryIdentityHandle(string path)
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
                    "Directory identity could not be opened.");
            }
            return handle;
        }

        private static ProductionInstallDirectoryIdentity GetIdentityFromHandle(
            IntPtr handle)
        {
            ByHandleFileInformation information;
            if (!GetFileInformationByHandle(handle, out information))
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(),
                    "Directory identity could not be read.");
            }
            StringBuilder finalPath = new StringBuilder(1024);
            uint length = GetFinalPathNameByHandleW(
                handle, finalPath, (uint)finalPath.Capacity, 0);
            if (length == 0)
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(),
                    "Directory final path could not be read.");
            }
            if (length >= finalPath.Capacity)
            {
                finalPath = new StringBuilder((int)length + 1);
                length = GetFinalPathNameByHandleW(
                    handle, finalPath, (uint)finalPath.Capacity, 0);
                if (length == 0 || length >= finalPath.Capacity)
                {
                    throw new Win32Exception(Marshal.GetLastWin32Error(),
                        "Directory final path could not be read.");
                }
            }
            return new ProductionInstallDirectoryIdentity
            {
                NativeFinalPath = NormalizeFinalPath(finalPath.ToString()),
                VolumeSerialNumber = information.VolumeSerialNumber,
                FileIndex = ((ulong)information.FileIndexHigh << 32) |
                    information.FileIndexLow,
                ReparsePoint =
                    (information.FileAttributes & FileAttributeReparsePoint) != 0
            };
        }

        private static ProductionInstallDirectoryIdentity CreateProtectedChildDirectoryNew(
            IntPtr parentHandle,
            string childName,
            string sddl)
        {
            RawSecurityDescriptor security = new RawSecurityDescriptor(sddl);
            byte[] descriptor = new byte[security.BinaryLength];
            security.GetBinaryForm(descriptor, 0);
            IntPtr descriptorBuffer = Marshal.AllocHGlobal(descriptor.Length);
            IntPtr nameBuffer = Marshal.StringToHGlobalUni(childName);
            IntPtr unicodeBuffer = IntPtr.Zero;
            IntPtr childHandle = IntPtr.Zero;
            try
            {
                Marshal.Copy(descriptor, 0, descriptorBuffer, descriptor.Length);
                UnicodeString unicodeName = new UnicodeString();
                unicodeName.Length = checked((ushort)(childName.Length * 2));
                unicodeName.MaximumLength = unicodeName.Length;
                unicodeName.Buffer = nameBuffer;
                unicodeBuffer = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(UnicodeString)));
                Marshal.StructureToPtr(unicodeName, unicodeBuffer, false);
                ObjectAttributes attributes = new ObjectAttributes();
                attributes.Length = Marshal.SizeOf(typeof(ObjectAttributes));
                attributes.RootDirectory = parentHandle;
                attributes.ObjectName = unicodeBuffer;
                attributes.Attributes = ObjectCaseInsensitive;
                attributes.SecurityDescriptor = descriptorBuffer;
                IoStatusBlock statusBlock;
                int status = NtCreateFile(
                    out childHandle,
                    DirectoryAllAccess,
                    ref attributes,
                    out statusBlock,
                    IntPtr.Zero,
                    0,
                    FileShareRead | FileShareWrite | FileShareDelete,
                    FileCreate,
                    FileDirectoryFile | FileSynchronousIoNonAlert |
                        FileFlagOpenReparsePoint,
                    IntPtr.Zero,
                    0);
                if (status < 0)
                {
                    throw new Win32Exception((int)RtlNtStatusToDosError(status),
                        "Protected child directory creation failed.");
                }
                return GetIdentityFromHandle(childHandle);
            }
            finally
            {
                if (childHandle != IntPtr.Zero && childHandle != new IntPtr(-1))
                {
                    CloseHandle(childHandle);
                }
                if (unicodeBuffer != IntPtr.Zero)
                {
                    Marshal.FreeHGlobal(unicodeBuffer);
                }
                Marshal.FreeHGlobal(nameBuffer);
                Marshal.FreeHGlobal(descriptorBuffer);
            }
        }

        public static void MoveDirectoryNew(string source, string destination)
        {
            if (!MoveFileExW(source, destination, MoveFileWriteThrough))
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(),
                    "Protected directory publication failed.");
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

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]$identity
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'This operation requires elevated PowerShell.'
    }
}

function New-ProductionDirectories {
    param([string]$Root)
    if (-not (Test-Path -LiteralPath $Root)) {
        New-Item -ItemType Directory -Path $Root -ErrorAction Stop | Out-Null
    }
    Assert-ProductionInstallDirectory -Path $Root | Out-Null
    foreach ($name in 'backups','config','gradle-home','locks','logs','releases','service','state','tools','worktrees') {
        $path = Join-Path $Root $name
        if (-not (Test-Path -LiteralPath $path)) {
            New-Item -ItemType Directory -Path $path -ErrorAction Stop | Out-Null
        }
        Assert-ProductionInstallDirectory -Path $path | Out-Null
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

function Get-ProductionWinSwBundleSource {
    param([Parameter(Mandatory)][string]$ServiceRoot)

    $installed = Join-Path $ServiceRoot 'ChristopherBellDev.exe'
    Assert-ProductionWebsiteServiceDestinationNotReparse -Path $installed
    if (Test-Path -LiteralPath $installed -PathType Leaf) {
        $installedHash = (Get-FileHash `
            -LiteralPath $installed `
            -Algorithm SHA256 `
            -ErrorAction Stop).Hash
        if ($installedHash -ceq $script:WinSwSha256) {
            return [pscustomobject][ordered]@{
                Path = $installed
                Temporary = $false
            }
        }
    }

    $source = Join-Path $ServiceRoot (
        '.winsw-source-' + [guid]::NewGuid().ToString('N') + '.exe')
    Assert-ProductionWebsiteServiceDestinationNotReparse -Path $source
    try {
        Invoke-WebRequest $script:WinSwUri -OutFile $source
        if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -cne
                $script:WinSwSha256) {
            throw 'WinSW SHA-256 verification failed.'
        }
        Protect-ProductionPath -Path $source
        Assert-ProtectedProductionPath -Path $source | Out-Null
        if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -cne
                $script:WinSwSha256) {
            throw 'Protected WinSW source SHA-256 verification failed.'
        }
        return [pscustomobject][ordered]@{
            Path = $source
            Temporary = $true
        }
    } catch {
        if (Test-Path -LiteralPath $source) {
            Remove-Item -LiteralPath $source -Force -ErrorAction SilentlyContinue
        }
        throw
    }
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

function Resolve-CanonicalProductionInstallPath {
    param([Parameter(Mandatory)][string]$Path)

    if ($Path -notmatch '^[A-Za-z]:[\\/]') {
        throw "Production installation path must be fully qualified: $Path"
    }
    $canonical = [IO.Path]::GetFullPath($Path)
    $pathRoot = [IO.Path]::GetPathRoot($canonical)
    if ([string]::Equals(
            $canonical.TrimEnd([IO.Path]::DirectorySeparatorChar),
            $pathRoot.TrimEnd([IO.Path]::DirectorySeparatorChar),
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Production installation path must not be a filesystem root: $Path"
    }
    return $canonical.TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar)
}

function Get-ProductionInstallPathComponents {
    param([Parameter(Mandatory)][string]$Path)

    $canonical = Resolve-CanonicalProductionInstallPath -Path $Path
    $pathRoot = [IO.Path]::GetPathRoot($canonical)
    $components = [Collections.Generic.List[string]]::new()
    [void]$components.Add($pathRoot)
    $current = $pathRoot
    foreach ($segment in $canonical.Substring($pathRoot.Length) -split '[\\/]') {
        if ([string]::IsNullOrEmpty($segment)) { continue }
        $current = Join-Path $current $segment
        [void]$components.Add($current)
    }
    return $components.ToArray()
}

function Assert-ProductionInstallDirectory {
    param([Parameter(Mandatory)][string]$Path)

    $item = Assert-ProductionPathNotReparse -Path $Path
    if (-not $item.PSIsContainer) {
        throw "Production installation directory is not a directory: $Path"
    }
    return $item
}

function Assert-ProductionInstallPathTraversal {
    param(
        [Parameter(Mandatory)][string]$Path,
        [switch]$LeafMayBeMissing
    )

    $components = @(Get-ProductionInstallPathComponents -Path $Path)
    for ($index = 0; $index -lt $components.Count; $index++) {
        $component = $components[$index]
        $isLeaf = $index -eq $components.Count - 1
        if (-not (Test-Path -LiteralPath $component)) {
            if ($isLeaf -and $LeafMayBeMissing) { return }
            throw "Missing production installation path component: $component"
        }
        if ($isLeaf) {
            Assert-ProductionPathNotReparse -Path $component | Out-Null
        } else {
            Assert-ProductionInstallDirectory -Path $component | Out-Null
        }
    }
}

function Get-ProductionInstallDirectoryIdentity {
    param([Parameter(Mandatory)][string]$Path)

    return [ChristopherBell.Dev.ProductionInstallNativeDirectory]::GetIdentity($Path)
}

function Test-ProductionInstallDirectoryIdentityEqual {
    param(
        [Parameter(Mandatory)]$Expected,
        [Parameter(Mandatory)]$Actual
    )

    return [uint32]$Expected.VolumeSerialNumber -eq
        [uint32]$Actual.VolumeSerialNumber -and
        [uint64]$Expected.FileIndex -eq [uint64]$Actual.FileIndex
}

function New-ProductionInstallProtectedDirectoryNative {
    param([Parameter(Mandatory)][string]$Path)

    return [ChristopherBell.Dev.ProductionInstallNativeDirectory]::
        CreateProtectedDirectoryNew(
            $Path,
            $script:ProtectedProductionDirectorySddl)
}

function New-ProductionInstallProtectedChildDirectoryNative {
    param(
        [Parameter(Mandatory)][string]$ParentPath,
        [Parameter(Mandatory)]$ParentIdentity,
        [Parameter(Mandatory)][string]$ChildName
    )

    return [ChristopherBell.Dev.ProductionInstallNativeDirectory]::
        CreateProtectedChildDirectoryNew(
            $ParentPath,
            [uint32]$ParentIdentity.VolumeSerialNumber,
            [uint64]$ParentIdentity.FileIndex,
            $ChildName,
            $script:ProtectedProductionDirectorySddl)
}

function Move-ProductionInstallRootStageNew {
    param(
        [Parameter(Mandatory)][string]$Source,
        [Parameter(Mandatory)][string]$Destination
    )

    [ChristopherBell.Dev.ProductionInstallNativeDirectory]::
        MoveDirectoryNew($Source, $Destination)
}

function Assert-ProductionInstallDirectoryIdentity {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$ExpectedIdentity
    )

    $actual = Get-ProductionInstallDirectoryIdentity -Path $Path
    if ($actual.ReparsePoint -or
        -not (Test-ProductionInstallDirectoryIdentityEqual `
            -Expected $ExpectedIdentity -Actual $actual)) {
        throw "Production installation directory identity changed: $Path"
    }
    $expectedPath = Resolve-CanonicalProductionInstallPath -Path $Path
    $actualPath = [IO.Path]::GetFullPath([string]$actual.NativeFinalPath)
    if (-not [string]::Equals(
            $expectedPath, $actualPath,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Production installation directory final path changed: $Path"
    }
    return $actual
}

function Assert-ProductionInstallRootDirectoryAcl {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [Security.AccessControl.RawSecurityDescriptor]$SecurityDescriptor
    )

    Assert-ProductionInstallPathTraversal -Path $Path
    $arguments = @{ Path=$Path }
    if ($PSBoundParameters.ContainsKey('SecurityDescriptor')) {
        $arguments.SecurityDescriptor = $SecurityDescriptor
    }
    Assert-ProductionFixedRootDirectoryAcl @arguments
}

function Assert-ProductionInstallProtectedDirectoryState {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$ExpectedIdentity
    )

    Assert-ProductionInstallPathTraversal -Path $Path
    $actual = Assert-ProductionInstallDirectoryIdentity `
        -Path $Path -ExpectedIdentity $ExpectedIdentity
    Assert-ProductionInstallRootDirectoryAcl -Path $Path
    return $actual
}

function Assert-ProductionInstallRootParentBoundary {
    param([Parameter(Mandatory)][string]$Path)

    $canonicalParent = Resolve-CanonicalProductionInstallPath -Path $Path
    Assert-ProductionInstallPathTraversal -Path $canonicalParent
    $trusted = @(
        'S-1-5-18',
        'S-1-5-32-544',
        'S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464')
    $dangerous =
        [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
        [Security.AccessControl.FileSystemRights]::Delete -bor
        [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [Security.AccessControl.FileSystemRights]::TakeOwnership
    $genericAll = [long]268435456
    foreach ($component in @(Get-ProductionInstallPathComponents `
                -Path $canonicalParent)) {
        $acl = Get-Acl -LiteralPath $component -ErrorAction Stop
        $owner = $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
        $rawSecurity = [Security.AccessControl.RawSecurityDescriptor]::new(
            $acl.GetSecurityDescriptorSddlForm(
                [Security.AccessControl.AccessControlSections]::All))
        $unsafe = -not $acl.AreAccessRulesProtected -or
            $trusted -notcontains $owner -or
            $null -eq $rawSecurity.DiscretionaryAcl
        foreach ($rule in @($acl.GetAccessRules(
                    $true,
                    $true,
                    [Security.Principal.SecurityIdentifier]))) {
            if ($rule.AccessControlType -ne
                    [Security.AccessControl.AccessControlType]::Allow -or
                $trusted -contains $rule.IdentityReference.Value -or
                ($rule.PropagationFlags -band
                    [Security.AccessControl.PropagationFlags]::InheritOnly)) {
                continue
            }
            $rightsValue = [long]$rule.FileSystemRights
            if ($rightsValue -lt 0) { $rightsValue += [long]4294967296 }
            if (($rule.FileSystemRights -band $dangerous) -ne 0 -or
                ($rightsValue -band $genericAll) -ne 0) {
                $unsafe = $true
                break
            }
        }
        if ($unsafe) {
            throw "Production install root parent grants untrusted replacement control: $component"
        }
    }
    Assert-ProductionInstallPathTraversal -Path $canonicalParent
    $identity = Get-ProductionInstallDirectoryIdentity -Path $canonicalParent
    if ($identity.ReparsePoint) {
        throw "Production install root parent must not be a reparse point: $canonicalParent"
    }
    return $identity
}

function Assert-ProductionInstallRootParentState {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$ExpectedIdentity
    )

    $actual = Assert-ProductionInstallRootParentBoundary -Path $Path
    if (-not (Test-ProductionInstallDirectoryIdentityEqual `
            -Expected $ExpectedIdentity -Actual $actual)) {
        throw "Production install root parent identity changed: $Path"
    }
    return $actual
}

function Remove-ProductionInstallRootStage {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$ExpectedIdentity
    )

    if (-not (Test-Path -LiteralPath $Path)) { return }
    Assert-ProductionInstallDirectoryIdentity `
        -Path $Path -ExpectedIdentity $ExpectedIdentity | Out-Null
    [IO.Directory]::Delete($Path, $false)
    if (Test-Path -LiteralPath $Path) {
        throw "Staged production root cleanup was not verified: $Path"
    }
}

function Initialize-ProductionInstallRootBoundary {
    param([Parameter(Mandatory)][string]$Root)

    $canonicalRoot = Resolve-CanonicalProductionInstallPath -Path $Root
    $parent = Resolve-CanonicalProductionInstallPath -Path (
        Split-Path -Parent $canonicalRoot)
    $parentIdentity = Assert-ProductionInstallRootParentBoundary -Path $parent
    Assert-ProductionInstallPathTraversal -Path $canonicalRoot -LeafMayBeMissing
    if (Test-Path -LiteralPath $canonicalRoot) {
        $rootIdentity = Get-ProductionInstallDirectoryIdentity -Path $canonicalRoot
        Assert-ProductionInstallProtectedDirectoryState `
            -Path $canonicalRoot -ExpectedIdentity $rootIdentity | Out-Null
        Assert-ProductionInstallRootParentState `
            -Path $parent -ExpectedIdentity $parentIdentity | Out-Null
        return [pscustomobject]@{
            Root = $canonicalRoot
            RootIdentity = $rootIdentity
            Parent = $parent
            ParentIdentity = $parentIdentity
        }
    }

    $stage = Join-Path $parent (
        '.production.install-{0}' -f ([guid]::NewGuid().ToString('N')))
    $stageIdentity = $null
    try {
        $stageIdentity = New-ProductionInstallProtectedDirectoryNative -Path $stage
        Assert-ProductionInstallProtectedDirectoryState `
            -Path $stage -ExpectedIdentity $stageIdentity | Out-Null
        Assert-ProductionInstallRootParentState `
            -Path $parent -ExpectedIdentity $parentIdentity | Out-Null
        try {
            Move-ProductionInstallRootStageNew `
                -Source $stage -Destination $canonicalRoot
            $rootIdentity = $stageIdentity
        } catch {
            $publicationFailure = $_.Exception
            if (-not (Test-Path -LiteralPath $canonicalRoot)) {
                throw
            }
            try {
                $rootIdentity = Get-ProductionInstallDirectoryIdentity `
                    -Path $canonicalRoot
                Assert-ProductionInstallProtectedDirectoryState `
                    -Path $canonicalRoot -ExpectedIdentity $rootIdentity | Out-Null
            } catch {
                throw [AggregateException]::new(
                    'Protected production root publication lost an unsafe creation race.',
                    [Exception[]]@($publicationFailure, $_.Exception))
            }
        }
        Assert-ProductionInstallProtectedDirectoryState `
            -Path $canonicalRoot -ExpectedIdentity $rootIdentity | Out-Null
        Assert-ProductionInstallRootParentState `
            -Path $parent -ExpectedIdentity $parentIdentity | Out-Null
        Remove-ProductionInstallRootStage `
            -Path $stage -ExpectedIdentity $stageIdentity
        return [pscustomobject]@{
            Root = $canonicalRoot
            RootIdentity = $rootIdentity
            Parent = $parent
            ParentIdentity = $parentIdentity
        }
    } catch {
        $bootstrapFailure = $_.Exception
        if ($null -ne $stageIdentity -and (Test-Path -LiteralPath $stage)) {
            try {
                Remove-ProductionInstallRootStage `
                    -Path $stage -ExpectedIdentity $stageIdentity
            } catch {
                throw [AggregateException]::new(
                    'Production root bootstrap failed and staged cleanup was not verified.',
                    [Exception[]]@($bootstrapFailure, $_.Exception))
            }
        }
        throw $bootstrapFailure
    }
}

function Initialize-ProductionDeploymentLockDirectory {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Root)

    $rootBoundary = Initialize-ProductionInstallRootBoundary -Root $Root
    $canonicalRoot = $rootBoundary.Root
    $locks = Resolve-CanonicalProductionInstallPath -Path (Join-Path $canonicalRoot 'locks')
    $lockPath = [IO.Path]::GetFullPath((Join-Path $locks 'deploy.lock'))
    Assert-ProductionInstallProtectedDirectoryState `
        -Path $canonicalRoot -ExpectedIdentity $rootBoundary.RootIdentity | Out-Null
    Assert-ProductionInstallPathTraversal -Path $locks -LeafMayBeMissing
    if (Test-Path -LiteralPath $locks) {
        $locksIdentity = Get-ProductionInstallDirectoryIdentity -Path $locks
    } else {
        try {
            $locksIdentity = New-ProductionInstallProtectedChildDirectoryNative `
                -ParentPath $canonicalRoot `
                -ParentIdentity $rootBoundary.RootIdentity `
                -ChildName 'locks'
        } catch {
            if (-not (Test-Path -LiteralPath $locks)) { throw }
            $locksIdentity = Get-ProductionInstallDirectoryIdentity -Path $locks
        }
    }
    Assert-ProductionInstallProtectedDirectoryState `
        -Path $canonicalRoot -ExpectedIdentity $rootBoundary.RootIdentity | Out-Null
    Assert-ProductionInstallProtectedDirectoryState `
        -Path $locks -ExpectedIdentity $locksIdentity | Out-Null
    Assert-ProductionInstallRootParentState `
        -Path $rootBoundary.Parent `
        -ExpectedIdentity $rootBoundary.ParentIdentity | Out-Null
    Assert-ProductionInstallPathTraversal -Path $lockPath -LeafMayBeMissing

    return [pscustomobject]@{
        Root = $canonicalRoot
        RootIdentity = $rootBoundary.RootIdentity
        Parent = $rootBoundary.Parent
        ParentIdentity = $rootBoundary.ParentIdentity
        Locks = $locks
        LocksIdentity = $locksIdentity
        LockPath = $lockPath
    }
}

function Assert-ProductionDeploymentLockBoundary {
    param([Parameter(Mandatory)]$Boundary)

    $expectedLocks = [IO.Path]::GetFullPath((Join-Path $Boundary.Root 'locks'))
    $expectedLockPath = [IO.Path]::GetFullPath((Join-Path $expectedLocks 'deploy.lock'))
    if (-not [string]::Equals(
            [string]$Boundary.Locks, $expectedLocks,
            [StringComparison]::OrdinalIgnoreCase) -or
        -not [string]::Equals(
            [string]$Boundary.LockPath, $expectedLockPath,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Production deployment lock boundary path identity changed.'
    }
    Assert-ProductionInstallRootParentState `
        -Path $Boundary.Parent -ExpectedIdentity $Boundary.ParentIdentity | Out-Null
    Assert-ProductionInstallProtectedDirectoryState `
        -Path $Boundary.Root -ExpectedIdentity $Boundary.RootIdentity | Out-Null
    Assert-ProductionInstallProtectedDirectoryState `
        -Path $Boundary.Locks -ExpectedIdentity $Boundary.LocksIdentity | Out-Null
    Assert-ProductionInstallPathTraversal -Path $Boundary.LockPath
}

function Assert-ProductionConfiguredRootBoundary {
    param(
        [Parameter(Mandatory)]$Configuration,
        [Parameter(Mandatory)]$Boundary
    )

    $property = $Configuration.PSObject.Properties['programDataRoot']
    if (-not $property -or $property.Value -isnot [string] -or
        [string]::IsNullOrWhiteSpace([string]$property.Value)) {
        throw 'Configured production root is missing or malformed.'
    }
    $configuredValue = [string]$property.Value
    $pathRoot = [IO.Path]::GetPathRoot($configuredValue)
    foreach ($segment in $configuredValue.Substring($pathRoot.Length) -split '[\\/]') {
        if ($segment -in @('.','..')) {
            throw 'Configured production root must not contain relative path traversal.'
        }
    }
    $configuredRoot = Resolve-CanonicalProductionInstallPath -Path $configuredValue
    if (-not [string]::Equals(
            $configuredRoot,
            [string]$Boundary.Root,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Configured production root does not match the locked production boundary.'
    }
    Assert-ProductionInstallProtectedDirectoryState `
        -Path $configuredRoot -ExpectedIdentity $Boundary.RootIdentity | Out-Null
    $Configuration.programDataRoot = [string]$Boundary.Root
}

function Read-ProductionWebsiteStopPort {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Root)

    $configPath = [IO.Path]::GetFullPath((Join-Path $Root 'config\deploy.json'))
    Assert-ProductionInstallPathTraversal -Path $configPath
    try {
        $legacyConfig = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    } catch {
        throw [System.InvalidOperationException]::new(
            'Legacy deploy config could not be parsed for a safe website stop.')
    }
    $property = $legacyConfig.PSObject.Properties['productionPort']
    if (-not $property -or (
            $property.Value -isnot [int] -and
            $property.Value -isnot [long])) {
        throw 'Legacy deploy config productionPort is malformed.'
    }
    $port = [long]$property.Value
    if ($port -lt 1 -or $port -gt 65535) {
        throw 'Legacy deploy config productionPort must be between 1 and 65535.'
    }
    return [int]$port
}

function Stop-ProductionWebsiteServiceWithoutPort {
    [CmdletBinding()]
    param([ValidateRange(1,300)][int]$ServiceTimeoutSeconds = 30)

    Stop-Service -Name 'ChristopherBellDev' -ErrorAction Stop
    $service = Get-Service -Name 'ChristopherBellDev' -ErrorAction Stop
    $service.WaitForStatus(
        [System.ServiceProcess.ServiceControllerStatus]::Stopped,
        [timespan]::FromSeconds($ServiceTimeoutSeconds))
    $service.Refresh()
    if ([string]$service.Status -cne 'Stopped') {
        throw "ChristopherBellDev did not reach Stopped within $ServiceTimeoutSeconds seconds."
    }
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

function Assert-ProductionWebsiteServiceXmlSafeRegistration {
    param([Parameter(Mandatory)][string]$Path)

    [xml]$serviceDefinition = Get-Content -LiteralPath $Path -Raw
    if ([string]$serviceDefinition.service.startmode -cne 'Manual') {
        throw 'Website WinSW service definition must register in Manual startup mode.'
    }
}

function Invoke-ProductionWinSwServiceInstall {
    param([Parameter(Mandatory)][string]$Binary)

    & $Binary install | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'WinSW service installation failed.' }
}

function Publish-ProductionWebsiteWriterStartBundle {
    param(
        [Parameter(Mandatory)]$Configuration,
        [Parameter(Mandatory)][string]$SourceWinSwPath,
        [Parameter(Mandatory)][string]$SourceServiceXmlPath,
        [Parameter(Mandatory)][string]$WinSwSha,
        [Parameter(Mandatory)][string]$ServiceXmlSha
    )

    $writerStartModule = Get-Module Production.WriterStart -ErrorAction Stop
    & $writerStartModule {
        param(
            $Value,
            $Launcher,
            $ModulePath,
            $SourceWinSw,
            $SourceServiceXml,
            $ExpectedWinSw,
            $ExpectedServiceXml
        )
        Publish-ProductionWriterStartGuardBundle `
            -Config $Value `
            -SourceLauncherPath $Launcher `
            -SourceModulePath $ModulePath `
            -SourceWinSwPath $SourceWinSw `
            -SourceServiceXmlPath $SourceServiceXml `
            -ExpectedWinSwSha256 $ExpectedWinSw `
            -ExpectedServiceXmlSha256 $ExpectedServiceXml | Out-Null
    } $Configuration `
        (Join-Path $PSScriptRoot '..\service\Start-ChristopherBellDev.ps1') `
        (Join-Path $PSScriptRoot 'Production.WriterStart.psm1') `
        $SourceWinSwPath `
        $SourceServiceXmlPath `
        $WinSwSha `
        $ServiceXmlSha
}

function Set-ProductionMongoServiceInstallPolicy {
    Set-Service MongoDB -StartupType Automatic
    & sc.exe failure MongoDB reset= 3600 actions= restart/10000/restart/30000 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to configure MongoDB service recovery.' }
}

function Set-ProductionWebsiteServiceInstallPolicy {
    & sc.exe config ChristopherBellDev depend= MongoDB | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to configure the website service dependency.'
    }
    & sc.exe failure ChristopherBellDev reset= 3600 actions= restart/10000/restart/30000 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to configure website service recovery.' }
}

function Install-WebsiteService {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)]$Configuration,
        [psobject]$RegistrationState = ([pscustomobject]@{ ServiceRegistered=$false })
    )

    $registrationProperty = $RegistrationState.PSObject.Properties['ServiceRegistered']
    if (-not $registrationProperty -or
        $registrationProperty.Value -isnot [bool]) {
        throw 'Website service registration state must contain a Boolean ServiceRegistered value.'
    }
    $service = Protect-ProductionWebsiteServiceDirectory -Configuration $Configuration
    Set-ProductionMongoServiceInstallPolicy
    $sourceXml = Join-Path $PSScriptRoot '..\service\ChristopherBellDev.xml'
    Assert-ProductionWebsiteServiceXmlSafeRegistration -Path $sourceXml
    $winSwSha = Get-ProductionWinSwSha256
    $serviceXmlSha = (Get-FileHash -LiteralPath $sourceXml -Algorithm SHA256).Hash.ToLowerInvariant()
    $winSwSource = Get-ProductionWinSwBundleSource -ServiceRoot $service
    try {
        Publish-ProductionWebsiteWriterStartBundle `
            -Configuration $Configuration `
            -SourceWinSwPath $winSwSource.Path `
            -SourceServiceXmlPath $sourceXml `
            -WinSwSha $winSwSha `
            -ServiceXmlSha $serviceXmlSha
    } finally {
        if ($winSwSource.Temporary -and
            (Test-Path -LiteralPath $winSwSource.Path -PathType Leaf)) {
            Assert-ProductionWebsiteServiceDestinationNotReparse `
                -Path $winSwSource.Path
            Remove-Item -LiteralPath $winSwSource.Path -Force -ErrorAction SilentlyContinue
        }
    }
    $binary = Join-Path $service 'ChristopherBellDev.exe'
    if (-not (Get-ProductionWebsiteServiceOrNull)) {
        Invoke-ProductionWinSwServiceInstall -Binary $binary
        if (-not (Get-ProductionWebsiteServiceOrNull)) {
            throw 'WinSW service registration was not observed after installation.'
        }
        $RegistrationState.ServiceRegistered = $true
    }
    Set-ProductionWebsiteStartupType -StartupType Disabled
    Set-ProductionWebsiteServiceInstallPolicy
    Assert-ProductionWebsiteServiceBoundary -Root $Root -Configuration $Configuration
}

function Invoke-ProductionRuntimeInstallAtRoot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Root,
        [string]$CloudflareTokenPath
    )

    $boundary = Initialize-ProductionDeploymentLockDirectory -Root $Root
    $root = $boundary.Root
    $lock = Enter-DeploymentLock -LockPath $boundary.LockPath
    try {
        Assert-ProductionDeploymentLockBoundary -Boundary $boundary
        $priorService = $null
        $wasRunning = $false
        $config = $null
        $productionPort = $null
        $registrationState = [pscustomobject]@{ ServiceRegistered=$false }
        try {
            $priorService = Get-ProductionWebsiteServiceOrNull
            if ($priorService) {
                Set-ProductionWebsiteStartupType -StartupType Disabled
                $priorStatus = [string]$priorService.Status
                if ($priorStatus -notin @('Running','Stopped')) {
                    throw 'ChristopherBellDev must be Running or Stopped before installation.'
                }
                $wasRunning = $priorStatus -eq 'Running'
                Protect-ProductionSecrets $root
                $productionPort = Read-ProductionWebsiteStopPort -Root $root
                Stop-ProductionWebsiteService -ProductionPort $productionPort `
                    -KeepRecoverySuspended
            }
            New-ProductionDirectories $root
            Protect-ProductionSecrets $root
            Install-ConfigurationExamples $root
            $config = Read-ProductionConfig (Join-Path $root 'config\deploy.json')
            Assert-ProductionConfiguredRootBoundary `
                -Configuration $config -Boundary $boundary
            Assert-ProductionFixedRootBoundary `
                -Config $config `
                -FixedRoot $root `
                -ExpectedBoundary $boundary | Out-Null
            $productionPort = [int]$config.productionPort
            Read-ProductionEnvironment (Join-Path $root 'config\app.env') | Out-Null
            Protect-ProductionSecrets $root
            Protect-ProductionWebsiteServiceDirectory -Configuration $config | Out-Null
            Install-CloudflaredService `
                -Executable $config.cloudflaredExe -TokenPath $CloudflareTokenPath
            Install-WebsiteService `
                -Root $root `
                -Configuration $config `
                -RegistrationState $registrationState
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
                    if ($null -eq $productionPort) {
                        Stop-ProductionWebsiteServiceWithoutPort
                    } else {
                        Stop-ProductionWebsiteService -ProductionPort $productionPort `
                            -KeepRecoverySuspended
                    }
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
            } elseif ($priorService -or $registrationState.ServiceRegistered) {
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

function Install-ProductionRuntime {
    [CmdletBinding()]
    param([switch]$WhatIf, [string]$CloudflareTokenPath)

    Assert-Administrator
    $root = 'C:\ProgramData\christopherbell.dev'
    if ($WhatIf) {
        Write-Output "Would install the website runtime and restricted shared media worker under $root."
        return
    }
    Invoke-ProductionRuntimeInstallAtRoot `
        -Root $root `
        -CloudflareTokenPath $CloudflareTokenPath
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
