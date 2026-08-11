Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.WriterStart.psm1') -Force

function ConvertTo-TestWriterStartDirectoryAcl {
    param(
        [Parameter(Mandatory)]
        [Security.AccessControl.RawSecurityDescriptor]$SecurityDescriptor
    )

    $bytes = [byte[]]::new($SecurityDescriptor.BinaryLength)
    $SecurityDescriptor.GetBinaryForm($bytes, 0)
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetSecurityDescriptorBinaryForm($bytes)
    return $acl
}

function New-TestWriterStartCallbackDirectoryDescriptor {
    $aceFlags = [Security.AccessControl.AceFlags](
        [int][Security.AccessControl.AceFlags]::ObjectInherit -bor
        [int][Security.AccessControl.AceFlags]::ContainerInherit)
    $allow = [Security.AccessControl.AceQualifier]::AccessAllowed
    $system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    $administrators =
        [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    $localService = [Security.Principal.SecurityIdentifier]::new('S-1-5-19')
    $dacl = [Security.AccessControl.RawAcl]::new(2, 3)
    $dacl.InsertAce(0, [Security.AccessControl.CommonAce]::new(
            $aceFlags,
            $allow,
            [int][Security.AccessControl.FileSystemRights]::FullControl,
            $system,
            $true,
            [byte[]](1,2,3,4)))
    $dacl.InsertAce(1, [Security.AccessControl.CommonAce]::new(
            $aceFlags,
            $allow,
            [int][Security.AccessControl.FileSystemRights]::FullControl,
            $administrators,
            $false,
            $null))
    $dacl.InsertAce(2, [Security.AccessControl.CommonAce]::new(
            $aceFlags,
            $allow,
            0x1200a9,
            $localService,
            $false,
            $null))
    $controlFlags = [Security.AccessControl.ControlFlags](
        [int][Security.AccessControl.ControlFlags]::DiscretionaryAclPresent -bor
        [int][Security.AccessControl.ControlFlags]::DiscretionaryAclProtected)
    return [Security.AccessControl.RawSecurityDescriptor]::new(
        $controlFlags,
        $administrators,
        $null,
        $null,
        $dacl)
}

$serviceDirectoryAclCases = @(
    @{ Case='the exact descriptor'; Accepted=$true;
        Sddl='O:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;LS)' },
    @{ Case='SYSTEM as owner'; Accepted=$false;
        Sddl='O:SYD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;LS)' },
    @{ Case='unprotected inheritance'; Accepted=$false;
        Sddl='O:BAD:(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;LS)' },
    @{ Case='a null DACL'; Accepted=$false; Sddl='O:BAD:NO_ACCESS_CONTROL' },
    @{ Case='an empty DACL'; Accepted=$false; Sddl='O:BAD:P' },
    @{ Case='duplicate SYSTEM ACEs'; Accepted=$false;
        Sddl='O:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;LS)' },
    @{ Case='duplicate Administrators ACEs'; Accepted=$false;
        Sddl='O:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;LS)' },
    @{ Case='duplicate LocalService ACEs'; Accepted=$false;
        Sddl='O:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;LS)(A;OICI;0x1200a9;;;LS)' },
    @{ Case='a missing SYSTEM ACE'; Accepted=$false;
        Sddl='O:BAD:P(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;LS)' },
    @{ Case='a missing Administrators ACE'; Accepted=$false;
        Sddl='O:BAD:P(A;OICI;FA;;;SY)(A;OICI;0x1200a9;;;LS)' },
    @{ Case='a missing LocalService ACE'; Accepted=$false;
        Sddl='O:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)' },
    @{ Case='an extra Users ACE'; Accepted=$false;
        Sddl='O:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;LS)(A;OICI;FR;;;BU)' },
    @{ Case='an inherited-flag Users ACE on a protected DACL'; Accepted=$false;
        Sddl='O:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;LS)(A;OICIID;FR;;;BU)' },
    @{ Case='an inherited-flag Everyone ACE on a protected DACL'; Accepted=$false;
        Sddl='O:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;LS)(A;OICIID;FR;;;WD)' },
    @{ Case='a LocalService deny ACE'; Accepted=$false;
        Sddl='O:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(D;OICI;0x1200a9;;;LS)' },
    @{ Case='an object-specific SYSTEM allow ACE'; Accepted=$false;
        Sddl='O:BAD:P(OA;OICI;FA;11111111-1111-1111-1111-111111111111;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;LS)' },
    @{ Case='a callback SYSTEM allow ACE'; Accepted=$false; Callback=$true },
    @{ Case='partial SYSTEM rights'; Accepted=$false;
        Sddl='O:BAD:P(A;OICI;FR;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;LS)' },
    @{ Case='partial Administrators rights'; Accepted=$false;
        Sddl='O:BAD:P(A;OICI;FA;;;SY)(A;OICI;FR;;;BA)(A;OICI;0x1200a9;;;LS)' },
    @{ Case='LocalService write rights'; Accepted=$false;
        Sddl='O:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1201bf;;;LS)' },
    @{ Case='LocalService missing Synchronize'; Accepted=$false;
        Sddl='O:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x200a9;;;LS)' },
    @{ Case='ObjectInherit-only flags'; Accepted=$false;
        Sddl='O:BAD:P(A;OI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;LS)' },
    @{ Case='ContainerInherit-only flags'; Accepted=$false;
        Sddl='O:BAD:P(A;CI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;LS)' },
    @{ Case='Inherited flags'; Accepted=$false;
        Sddl='O:BAD:P(A;OICIID;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;LS)' },
    @{ Case='InheritOnly flags'; Accepted=$false;
        Sddl='O:BAD:P(A;OICIIO;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;LS)' },
    @{ Case='NoPropagate flags'; Accepted=$false;
        Sddl='O:BAD:P(A;OICINP;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;LS)' }
)
foreach ($case in $serviceDirectoryAclCases) {
    $descriptor = if ($case.Callback) {
        New-TestWriterStartCallbackDirectoryDescriptor
    } else {
        [Security.AccessControl.RawSecurityDescriptor]::new($case.Sddl)
    }
    $case.Descriptor = $descriptor
    $case.AclObject = ConvertTo-TestWriterStartDirectoryAcl `
        -SecurityDescriptor $descriptor
}

Describe 'production fixed root boundary' {
    InModuleScope Production.WriterStart {
        It 'captures stable native identity and final path for an ordinary directory' {
            $root = Join-Path $TestDrive 'native-fixed-root'
            New-Item -ItemType Directory -Path $root | Out-Null

            $first = Get-ProductionFixedRootDirectoryIdentity -Path $root
            $second = Get-ProductionFixedRootDirectoryIdentity -Path $root

            $first.ReparsePoint | Should -BeFalse
            $first.NativeFinalPath | Should -BeExactly ([IO.Path]::GetFullPath($root))
            $first.VolumeSerialNumber | Should -Be $second.VolumeSerialNumber
            $first.FileIndex | Should -Be $second.FileIndex
        }

        It 'returns only the fixed canonical root and lock boundary after stable readback' {
            $root = Join-Path $TestDrive 'guarded-fixed-root'
            $locks = Join-Path $root 'locks'
            New-Item -ItemType Directory -Path $locks -Force | Out-Null
            $config = [pscustomobject]@{ programDataRoot=$root.ToUpperInvariant() }
            Mock Assert-ProductionFixedRootDirectoryAcl { }
            Mock Get-ProductionFixedRootDirectoryIdentity {
                $canonical = [IO.Path]::GetFullPath($Path)
                [pscustomobject]@{
                    NativeFinalPath=$canonical
                    VolumeSerialNumber=7
                    FileIndex=if ($canonical.EndsWith('\locks')) { 22 } else { 11 }
                    ReparsePoint=$false
                }
            }

            $boundary = Assert-ProductionFixedRootBoundary `
                -Config $config -FixedRoot $root

            $boundary.Root | Should -BeExactly ([IO.Path]::GetFullPath($root))
            $boundary.Locks | Should -BeExactly ([IO.Path]::GetFullPath($locks))
            $boundary.LockPath | Should -BeExactly (
                [IO.Path]::GetFullPath((Join-Path $locks 'deploy.lock')))
            $boundary.RootIdentity.FileIndex | Should -Be 11
            $boundary.LocksIdentity.FileIndex | Should -Be 22
            $config.programDataRoot | Should -BeExactly $boundary.Root
        }

        It 'rejects <Failure> with guarded-install guidance' -ForEach @(
            @{ Failure='root reparse traversal'; Mode='root-reparse' },
            @{ Failure='locks reparse traversal'; Mode='locks-reparse' },
            @{ Failure='root ACL mismatch'; Mode='root-acl' },
            @{ Failure='locks ACL mismatch'; Mode='locks-acl' },
            @{ Failure='root final path mismatch'; Mode='root-final' },
            @{ Failure='locks final path mismatch'; Mode='locks-final' },
            @{ Failure='root reparse identity'; Mode='root-identity-reparse' },
            @{ Failure='root identity replacement'; Mode='root-identity-change' },
            @{ Failure='locks identity replacement'; Mode='locks-identity-change' }
        ) {
            $root = Join-Path $TestDrive "fixed-root-$Mode"
            $locks = Join-Path $root 'locks'
            New-Item -ItemType Directory -Path $locks -Force | Out-Null
            $config = [pscustomobject]@{ programDataRoot=$root }
            $script:identityReads = @{}
            Mock Assert-ProductionWriterStartPathNotReparseTraversal {
                $canonical = [IO.Path]::GetFullPath($Path)
                if (($Mode -eq 'root-reparse' -and $canonical -eq [IO.Path]::GetFullPath($root)) -or
                    ($Mode -eq 'locks-reparse' -and $canonical -eq [IO.Path]::GetFullPath($locks))) {
                    throw 'simulated reparse traversal'
                }
                $canonical
            }
            Mock Assert-ProductionFixedRootDirectoryAcl {
                $canonical = [IO.Path]::GetFullPath($Path)
                if (($Mode -eq 'root-acl' -and $canonical -eq [IO.Path]::GetFullPath($root)) -or
                    ($Mode -eq 'locks-acl' -and $canonical -eq [IO.Path]::GetFullPath($locks))) {
                    throw 'simulated exact ACL mismatch'
                }
            }
            Mock Get-ProductionFixedRootDirectoryIdentity {
                $canonical = [IO.Path]::GetFullPath($Path)
                $key = $canonical.ToLowerInvariant()
                $read = 1 + [int]$script:identityReads[$key]
                $script:identityReads[$key] = $read
                $isLocks = $canonical -eq [IO.Path]::GetFullPath($locks)
                $fileIndex = if ($isLocks) { 22 } else { 11 }
                if (($Mode -eq 'root-identity-change' -and -not $isLocks -and $read -gt 1) -or
                    ($Mode -eq 'locks-identity-change' -and $isLocks -and $read -gt 1)) {
                    $fileIndex++
                }
                $final = if (($Mode -eq 'root-final' -and -not $isLocks) -or
                    ($Mode -eq 'locks-final' -and $isLocks)) {
                    Join-Path $TestDrive 'redirect-target'
                } else { $canonical }
                [pscustomobject]@{
                    NativeFinalPath=[IO.Path]::GetFullPath($final)
                    VolumeSerialNumber=7
                    FileIndex=$fileIndex
                    ReparsePoint=($Mode -eq 'root-identity-reparse' -and -not $isLocks)
                }
            }

            { Assert-ProductionFixedRootBoundary `
                    -Config $config -FixedRoot $root } |
                Should -Throw '*Run guarded prod install before retrying*'
        }

        It 'acquires only the asserted fixed lock and rechecks the same identities under lock' {
            $root = Join-Path $TestDrive 'locked-fixed-root'
            $lockPath = Join-Path $root 'locks\deploy.lock'
            $boundary = [pscustomobject]@{
                Root=[IO.Path]::GetFullPath($root)
                Locks=[IO.Path]::GetFullPath((Join-Path $root 'locks'))
                LockPath=[IO.Path]::GetFullPath($lockPath)
                RootIdentity=[pscustomobject]@{ VolumeSerialNumber=7; FileIndex=11 }
                LocksIdentity=[pscustomobject]@{ VolumeSerialNumber=7; FileIndex=22 }
            }
            $config = [pscustomobject]@{ programDataRoot=$root }
            $lock = [IO.MemoryStream]::new()
            $script:boundaryAssertions = 0
            Mock Assert-ProductionFixedRootBoundary {
                $script:boundaryAssertions++
                if ($script:boundaryAssertions -eq 2 -and $ExpectedBoundary -ne $boundary) {
                    throw 'expected identity boundary was not rechecked'
                }
                $boundary
            }
            Mock Enter-DeploymentLock {
                if ($LockPath -cne $boundary.LockPath) {
                    throw 'config-derived lock path reached'
                }
                $lock
            }

            $guard = Enter-ProductionFixedRootDeploymentLock `
                -Config $config -FixedRoot $root

            [object]::ReferenceEquals($guard.Lock, $lock) | Should -BeTrue
            [object]::ReferenceEquals($guard.Boundary, $boundary) | Should -BeTrue
            $script:boundaryAssertions | Should -Be 2
            $guard.Lock.Dispose()
        }

        It 'rejects a configured alternate root before any boundary read' {
            $fixedRoot = Join-Path $TestDrive 'fixed-root'
            $alternateRoot = Join-Path $TestDrive 'alternate-root'
            $config = [pscustomobject]@{ programDataRoot=$alternateRoot }
            Mock Get-Acl { throw 'boundary ACL must not be read' }

            { Assert-ProductionFixedRootBoundary `
                    -Config $config -FixedRoot $fixedRoot } |
                Should -Throw '*Run guarded prod install before retrying*'

            Should -Invoke Get-Acl -Times 0
        }

        It 'rejects non-fully-qualified configured root syntax before any boundary read' `
                -ForEach @(
            @{ ConfiguredRoot='C:ProgramData\christopherbell.dev' }
            @{ ConfiguredRoot='relative\production' }
            @{ ConfiguredRoot='\\server\share\christopherbell.dev' }
            @{ ConfiguredRoot='\\?\C:\ProgramData\christopherbell.dev' }
        ) {
            $config = [pscustomobject]@{ programDataRoot=$ConfiguredRoot }
            Mock Get-Acl { throw 'boundary ACL must not be read' }
            $failure = $null

            try {
                Assert-ProductionFixedRootBoundary `
                    -Config $config `
                    -FixedRoot 'C:\ProgramData\christopherbell.dev' | Out-Null
            } catch {
                $failure = $_.Exception
            }

            $failure | Should -Not -BeNullOrEmpty
            $failure.InnerException.Message |
                Should -Be 'Configured production root must use a fully qualified local drive path.'
            Should -Invoke Get-Acl -Times 0 -Exactly
        }
    }
}

Describe 'production writer-start exact service-directory ACL' {
    InModuleScope Production.WriterStart -Parameters @{
        Cases=$serviceDirectoryAclCases
    } {
        It 'publisher verifier handles <Case>' -ForEach $Cases {
            $serviceDirectory = Join-Path $TestDrive 'exact-service-directory'
            New-Item -ItemType Directory -Path $serviceDirectory -Force | Out-Null

            $verification = {
                Assert-ProductionWriterStartServiceDirectory `
                    -Path $serviceDirectory `
                    -SecurityDescriptor $Descriptor
            }
            if ($Accepted) {
                $verification | Should -Not -Throw
            } else {
                $verification | Should -Throw
            }
        }

        It 'publisher reads inherited-flag ACEs from the filesystem binary form' `
                -ForEach @($Cases | Where-Object {
                    $_.Case -ceq
                        'an inherited-flag Users ACE on a protected DACL'
                }) {
            $serviceDirectory = Join-Path $TestDrive 'binary-service-directory'
            New-Item -ItemType Directory -Path $serviceDirectory -Force | Out-Null
            $script:serviceDirectoryAclFixture = $AclObject
            Mock Get-Acl { $script:serviceDirectoryAclFixture }

            { Assert-ProductionWriterStartServiceDirectory -Path $serviceDirectory } |
                Should -Throw
        }
    }

    Describe 'installed launcher verifier' {
        BeforeAll {
            $launcherPath =
                Join-Path $PSScriptRoot '..\service\Start-ChristopherBellDev.ps1'
            $tokens = $null
            $errors = $null
            $ast = [Management.Automation.Language.Parser]::ParseFile(
                $launcherPath,
                [ref]$tokens,
                [ref]$errors)
            $definition = $ast.FindAll({
                    param($node)
                    $node -is
                        [Management.Automation.Language.FunctionDefinitionAst] -and
                    $node.Name -ceq
                        'Assert-InstalledWriterStartServiceDirectoryAcl'
                }, $true)
            @($definition).Count | Should -Be 1
            $script:launcherServiceDirectoryAssertion =
                [scriptblock]::Create($definition[0].Extent.Text)
        }

        It 'pre-import verifier handles <Case>' -ForEach $serviceDirectoryAclCases {
            . $script:launcherServiceDirectoryAssertion

            $verification = {
                Assert-InstalledWriterStartServiceDirectoryAcl `
                    -Path 'C:\guard\service' `
                    -SecurityDescriptor $Descriptor
            }
            if ($Accepted) {
                $verification | Should -Not -Throw
            } else {
                $verification | Should -Throw
            }
        }

        It 'pre-import verifier reads inherited-flag ACEs from the filesystem binary form' `
                -ForEach @($serviceDirectoryAclCases | Where-Object {
                    $_.Case -ceq
                        'an inherited-flag Users ACE on a protected DACL'
                }) {
            . $script:launcherServiceDirectoryAssertion
            $script:launcherServiceDirectoryAclFixture = $AclObject
            Mock Get-Acl { $script:launcherServiceDirectoryAclFixture }

            { Assert-InstalledWriterStartServiceDirectoryAcl `
                    -Path 'C:\guard\service' } | Should -Throw
        }
    }
}

Describe 'production writer-start exact service-file ACL' {
    InModuleScope Production.WriterStart {
        BeforeEach {
            $script:serviceFileAclFixture = Join-Path $TestDrive 'service-file-acl-fixture.exe'
            'fixture' | Set-Content -LiteralPath $script:serviceFileAclFixture
        }

        It 'emits and accepts the exact three-principal service-file ACL' {
            $acl = New-ProductionWriterStartServiceFileAcl
            $descriptor = [Security.AccessControl.RawSecurityDescriptor]::new(
                $acl.GetSecurityDescriptorBinaryForm(), 0)

            { Assert-ProductionWriterStartServiceFile `
                    -Path $script:serviceFileAclFixture `
                    -SecurityDescriptor $descriptor } | Should -Not -Throw
        }

        It 'rejects the generic protected production file ACL that omits LocalService' {
            $acl = New-ProtectedProductionAcl
            $descriptor = [Security.AccessControl.RawSecurityDescriptor]::new(
                $acl.GetSecurityDescriptorBinaryForm(), 0)

            { Assert-ProductionWriterStartServiceFile `
                    -Path $script:serviceFileAclFixture `
                    -SecurityDescriptor $descriptor } |
                Should -Throw '*exactly three explicit ACEs*'
        }

        It 'rejects <Case>' -ForEach @(
            @{
                Case='duplicate SYSTEM ACEs'
                Sddl='O:BAD:P(A;;FA;;;SY)(A;;FA;;;SY)(A;;FA;;;BA)(A;;0x1200a9;;;LS)'
                Error='*exactly three explicit ACEs*'
            },
            @{
                Case='duplicate Administrators ACEs'
                Sddl='O:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;FA;;;BA)(A;;0x1200a9;;;LS)'
                Error='*exactly three explicit ACEs*'
            },
            @{
                Case='duplicate LocalService ACEs'
                Sddl='O:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;0x1200a9;;;LS)(A;;0x1200a9;;;LS)'
                Error='*exactly three explicit ACEs*'
            },
            @{
                Case='a missing SYSTEM ACE'
                Sddl='O:BAD:P(A;;FA;;;BA)(A;;0x1200a9;;;LS)'
                Error='*exactly three explicit ACEs*'
            },
            @{
                Case='a missing Administrators ACE'
                Sddl='O:BAD:P(A;;FA;;;SY)(A;;0x1200a9;;;LS)'
                Error='*exactly three explicit ACEs*'
            },
            @{
                Case='a missing LocalService ACE'
                Sddl='O:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)'
                Error='*exactly three explicit ACEs*'
            },
            @{
                Case='an empty DACL'
                Sddl='O:BAD:P'
                Error='*exactly three explicit ACEs*'
            },
            @{
                Case='an extra Users ACE'
                Sddl='O:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;0x1200a9;;;LS)(A;;FR;;;BU)'
                Error='*exactly three explicit ACEs*'
            },
            @{
                Case='a LocalService deny ACE'
                Sddl='O:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)(D;;0x1200a9;;;LS)'
                Error='*one SYSTEM, one Administrators, and one LocalService allow ACE*'
            },
            @{
                Case='the wrong owner'
                Sddl='O:SYD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;0x1200a9;;;LS)'
                Error='*owner must be Builtin Administrators*'
            },
            @{
                Case='unprotected inheritance'
                Sddl='O:BAD:(A;;FA;;;SY)(A;;FA;;;BA)(A;;0x1200a9;;;LS)'
                Error='*inheritance must be protected*'
            },
            @{
                Case='ObjectInherit on a file ACE'
                Sddl='O:BAD:P(A;OI;FA;;;SY)(A;;FA;;;BA)(A;;0x1200a9;;;LS)'
                Error='*must not inherit or propagate*'
            },
            @{
                Case='ContainerInherit on a file ACE'
                Sddl='O:BAD:P(A;CI;FA;;;SY)(A;;FA;;;BA)(A;;0x1200a9;;;LS)'
                Error='*must not inherit or propagate*'
            },
            @{
                Case='NoPropagate on a file ACE'
                Sddl='O:BAD:P(A;OINP;FA;;;SY)(A;;FA;;;BA)(A;;0x1200a9;;;LS)'
                Error='*must not inherit or propagate*'
            },
            @{
                Case='InheritOnly on a file ACE'
                Sddl='O:BAD:P(A;OIIO;FA;;;SY)(A;;FA;;;BA)(A;;0x1200a9;;;LS)'
                Error='*must not inherit or propagate*'
            },
            @{
                Case='an inherited file ACE'
                Sddl='O:BAD:P(A;ID;FA;;;SY)(A;;FA;;;BA)(A;;0x1200a9;;;LS)'
                Error='*must not inherit or propagate*'
            },
            @{
                Case='partial SYSTEM rights'
                Sddl='O:BAD:P(A;;FR;;;SY)(A;;FA;;;BA)(A;;0x1200a9;;;LS)'
                Error='*SYSTEM and Administrators must have exact FullControl*'
            },
            @{
                Case='LocalService write rights'
                Sddl='O:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;0x1201bf;;;LS)'
                Error='*LocalService must have exact ReadAndExecute and Synchronize*'
            },
            @{
                Case='LocalService missing Synchronize'
                Sddl='O:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;0x200a9;;;LS)'
                Error='*LocalService must have exact ReadAndExecute and Synchronize*'
            }
        ) {
            $descriptor = [Security.AccessControl.RawSecurityDescriptor]::new($Sddl)

            { Assert-ProductionWriterStartServiceFile `
                    -Path $script:serviceFileAclFixture `
                    -SecurityDescriptor $descriptor } | Should -Throw $Error
        }

        It 'rejects reparse traversal before trusting an exact in-memory file ACL' {
            $target = Join-Path $TestDrive 'service-file-acl-target'
            $alias = Join-Path $TestDrive 'service-file-acl-alias'
            New-Item -ItemType Directory -Path $target | Out-Null
            'fixture' | Set-Content -LiteralPath (Join-Path $target 'guard.exe')
            New-Item -ItemType Junction -Path $alias -Target $target | Out-Null
            $descriptor = [Security.AccessControl.RawSecurityDescriptor]::new(
                'O:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;0x1200a9;;;LS)')
            try {
                { Assert-ProductionWriterStartServiceFile `
                        -Path (Join-Path $alias 'guard.exe') `
                        -SecurityDescriptor $descriptor } | Should -Throw '*reparse*'
            } finally {
                if (Test-Path -LiteralPath $alias) {
                    [IO.Directory]::Delete($alias, $false)
                }
            }
        }
    }
}

Describe 'production writer-start schema boundary' {
    InModuleScope Production.WriterStart {
        BeforeEach {
            $script:config = [pscustomobject]@{ programDataRoot=$TestDrive; mongoShellExe='mongosh.exe' }
            Mock Assert-ProductionFixedRootBoundary {
                [pscustomobject]@{ Root=$TestDrive }
            }
            Mock Protect-ProductionPath {}
            Mock Assert-ProtectedProductionPath { $true }
            Mock Protect-ProductionWriterStartServiceDirectory { }
            Mock Assert-ProductionWriterStartServiceDirectory { }
            Mock Protect-ProductionWriterStartServiceFile { }
            Mock Assert-ProductionWriterStartServiceFile { }
            $markerPath = Get-ProductionMusicSchemaDirectionPath -Config $script:config
            New-Item -ItemType Directory -Path (Split-Path -Parent $markerPath) -Force |
                Out-Null
            [ordered]@{
                version=1
                state='TARGET_ACTIVE'
                updatedAtEpochMillis=1
                targetRelease='1111111111111111111111111111111111111111'
                legacyRelease='2222222222222222222222222222222222222222'
            } | ConvertTo-Json | Set-Content -LiteralPath $markerPath
        }

        It 'rejects an unsafe fixed root before release, marker, or authorization reads' {
            $fixedRoot = Join-Path $TestDrive 'trusted-fixed-root'
            Mock Assert-ProductionFixedRootBoundary { throw 'guarded fixed root required' }
            Mock Read-ProductionReleaseIdentity { throw 'release read must not run' }
            Mock Read-ProductionMusicSchemaDirection { throw 'marker read must not run' }
            Mock Use-ProductionWriterStartAuthorization { throw 'authorization read must not run' }

            { Assert-ProductionWriterStartAllowed `
                    -Config $script:config -FixedRoot $fixedRoot } |
                Should -Throw '*guarded fixed root required*'

            Should -Invoke Read-ProductionReleaseIdentity -Times 0
            Should -Invoke Read-ProductionMusicSchemaDirection -Times 0
            Should -Invoke Use-ProductionWriterStartAuthorization -Times 0
        }

        It 'allows only the exact release bound by a stable target marker' {
            Mock Read-ProductionReleaseIdentity {
                [pscustomobject]@{ sha='1111111111111111111111111111111111111111'; musicSchema='TARGET' }
            }
            Mock Read-ProductionMusicSchemaDirection {
                [pscustomobject]@{
                    state='TARGET_ACTIVE'
                    targetRelease='1111111111111111111111111111111111111111'
                    legacyRelease='2222222222222222222222222222222222222222'
                }
            }

            { Assert-ProductionWriterStartAllowed `
                    -Config $script:config -FixedRoot $TestDrive } |
                Should -Not -Throw
        }

        It 'allows the current v2 release without changing its immutable cutover release' {
            Mock Read-ProductionReleaseIdentity {
                [pscustomobject]@{
                    sha='3333333333333333333333333333333333333333'
                    musicSchema='TARGET'
                    domainSchema='TARGET'
                }
            }
            $v2Marker = [pscustomobject]@{
                version=2
                state='TARGET_ACTIVE'
                targetRelease='1111111111111111111111111111111111111111'
                currentRelease='3333333333333333333333333333333333333333'
                legacyRelease='2222222222222222222222222222222222222222'
            }
            Mock Read-ProductionDomainSchemaDirection { $v2Marker }
            Mock Read-ProductionMusicSchemaDirection {
                $v2Marker
            }

            { Assert-ProductionWriterStartAllowed `
                    -Config $script:config -FixedRoot $TestDrive } |
                Should -Not -Throw
        }

        It 'categorically blocks WinSW and recovery start during domain rollback' -ForEach @(
            @{ Sha=('1' * 40); Schema='TARGET' }
            @{ Sha=('2' * 40); Schema='LEGACY' }
        ) {
            Mock Read-ProductionReleaseIdentity {
                [pscustomobject]@{
                    sha=$Sha
                    musicSchema='TARGET'
                    domainSchema=$Schema
                }
            }
            $rollbackMarker = [pscustomobject]@{
                version=2
                state='ROLLBACK_IN_PROGRESS'
                targetRelease='1' * 40
                currentRelease='1' * 40
                legacyRelease='2' * 40
            }
            Mock Read-ProductionDomainSchemaDirection { $rollbackMarker }
            Mock Read-ProductionMusicSchemaDirection { $rollbackMarker }
            Mock Use-ProductionWriterStartAuthorization {
                throw 'rollback barrier must reject before authorization consumption'
            }

            { Assert-ProductionWriterStartAllowed `
                    -Config $script:config -FixedRoot $TestDrive } |
                Should -Throw '*rollback*blocked*'

            Should -Invoke Use-ProductionWriterStartAuthorization -Times 0
        }

        It 'round-trips the crash-durable v2 rollback barrier without admitting it to v1' {
            $target = '1' * 40
            $legacy = '2' * 40
            $marker = Write-ProductionDomainSchemaDirection `
                -Config $script:config `
                -State ROLLBACK_IN_PROGRESS `
                -TargetRelease $target `
                -CurrentRelease $target `
                -LegacyRelease $legacy `
                -EvidenceDigest ('a' * 64) `
                -BackupIdentity ('b' * 64) `
                -LegacyDropped $true

            $marker.version | Should -Be 2
            $marker.state | Should -BeExactly 'ROLLBACK_IN_PROGRESS'

            $path = Get-ProductionMusicSchemaDirectionPath -Config $script:config
            [ordered]@{
                version=1
                state='ROLLBACK_IN_PROGRESS'
                updatedAtEpochMillis=1
                targetRelease=$target
                legacyRelease=$legacy
            } | ConvertTo-Json | Set-Content -LiteralPath $path
            { Read-ProductionMusicSchemaDirection -Config $script:config } |
                Should -Throw '*marker is invalid*'
        }

        It 'fails closed when a stable marker release differs and no authorization exists' {
            Mock Read-ProductionReleaseIdentity {
                [pscustomobject]@{ sha='3333333333333333333333333333333333333333'; musicSchema='TARGET' }
            }
            Mock Read-ProductionMusicSchemaDirection {
                [pscustomobject]@{
                    state='TARGET_ACTIVE'
                    targetRelease='1111111111111111111111111111111111111111'
                    legacyRelease='2222222222222222222222222222222222222222'
                }
            }

            { Assert-ProductionWriterStartAllowed `
                    -Config $script:config -FixedRoot $TestDrive } |
                Should -Throw '*incompatible*blocked*'
        }

        It 'permits a fresh legacy start only after proving migration inactive' {
            Mock Read-ProductionReleaseIdentity {
                [pscustomobject]@{ sha='1111111111111111111111111111111111111111'; musicSchema='LEGACY' }
            }
            Mock Read-ProductionMusicSchemaDirection { $null }
            Mock Get-ProductionMusicMigrationActivationForWriterStart { $false }

            { Assert-ProductionWriterStartAllowed `
                    -Config $script:config -FixedRoot $TestDrive } |
                Should -Not -Throw
        }

        It 'blocks an absent marker when migration is active or the probe is unknown' -ForEach @(
            @{ Mode='active' }, @{ Mode='unknown' }
        ) {
            Mock Read-ProductionReleaseIdentity {
                [pscustomobject]@{ sha='1111111111111111111111111111111111111111'; musicSchema='LEGACY' }
            }
            Mock Read-ProductionMusicSchemaDirection { $null }
            if ($Mode -eq 'active') {
                Mock Get-ProductionMusicMigrationActivationForWriterStart { $true }
            } else {
                Mock Get-ProductionMusicMigrationActivationForWriterStart { throw 'unknown' }
            }

            { Assert-ProductionWriterStartAllowed `
                    -Config $script:config -FixedRoot $TestDrive } | Should -Throw
        }

        It 'consumes an exact pending start authorization once' {
            $marker = [pscustomobject]@{
                state='TARGET_ACTIVE'
                targetRelease='1111111111111111111111111111111111111111'
                legacyRelease='2222222222222222222222222222222222222222'
            }
            $release = [pscustomobject]@{
                sha='3333333333333333333333333333333333333333'; musicSchema='TARGET'
            }
            Grant-ProductionWriterStartAuthorization -Config $script:config `
                -MarkerState TARGET_ACTIVE -Release $release.sha -Purpose TARGET_DEPLOY

            (Use-ProductionWriterStartAuthorization -Config $script:config `
                -Marker $marker -ReleaseIdentity $release) | Should -BeTrue
            (Use-ProductionWriterStartAuthorization -Config $script:config `
                -Marker $marker -ReleaseIdentity $release) | Should -BeFalse
        }

        It 'binds pending authorization to the live issuer process identity' {
            $authorization = Grant-ProductionWriterStartAuthorization -Config $script:config `
                -MarkerState TARGET_ACTIVE `
                -Release '3333333333333333333333333333333333333333' `
                -Purpose TARGET_DEPLOY
            $path = Get-ProductionWriterStartAuthorizationPath -Config $script:config
            $stored = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json

            $authorization.nonce | Should -Be $stored.nonce
            $authorization.issuerPid | Should -Be $PID
            $authorization.issuerStartTimeUtcTicks | Should -BeGreaterThan 0
            $stored.issuerPid | Should -Be $PID
            $stored.issuerStartTimeUtcTicks | Should -Be $authorization.issuerStartTimeUtcTicks
        }

        It 'rejects pending authorization when the issuer died or its PID was reused' -ForEach @(
            @{ Kind='dead' }, @{ Kind='reused' }
        ) {
            $marker = [pscustomobject]@{
                state='TARGET_ACTIVE'
                targetRelease='1111111111111111111111111111111111111111'
                legacyRelease='2222222222222222222222222222222222222222'
            }
            $release = [pscustomobject]@{
                sha='3333333333333333333333333333333333333333'; musicSchema='TARGET'
            }
            Grant-ProductionWriterStartAuthorization -Config $script:config `
                -MarkerState TARGET_ACTIVE -Release $release.sha -Purpose TARGET_DEPLOY | Out-Null
            if ($Kind -eq 'dead') {
                Mock Get-Process { throw 'missing issuer' }
            } else {
                Mock Get-Process {
                    [pscustomobject]@{ StartTime=[datetime]::UtcNow.AddHours(-12).ToLocalTime() }
                }
            }

            { Use-ProductionWriterStartAuthorization -Config $script:config `
                    -Marker $marker -ReleaseIdentity $release } | Should -Throw '*blocked*'
            Test-Path (Get-ProductionWriterStartAuthorizationPath -Config $script:config) |
                Should -BeFalse
        }

        It 'revokes only the exact returned authorization token idempotently' {
            $authorization = Grant-ProductionWriterStartAuthorization -Config $script:config `
                -MarkerState TARGET_ACTIVE `
                -Release '3333333333333333333333333333333333333333' `
                -Purpose TARGET_DEPLOY
            $path = Get-ProductionWriterStartAuthorizationPath -Config $script:config

            Revoke-ProductionWriterStartAuthorization `
                -Config $script:config -Authorization $authorization
            { Revoke-ProductionWriterStartAuthorization `
                    -Config $script:config -Authorization $authorization } | Should -Not -Throw

            Test-Path -LiteralPath $path | Should -BeFalse
        }

        It 'rejects authorization after either marker release identity changes' {
            $release = [pscustomobject]@{
                sha='3333333333333333333333333333333333333333'; musicSchema='TARGET'
            }
            Grant-ProductionWriterStartAuthorization -Config $script:config `
                -MarkerState TARGET_ACTIVE -Release $release.sha -Purpose TARGET_DEPLOY | Out-Null
            $changedMarker = [pscustomobject]@{
                state='TARGET_ACTIVE'
                targetRelease='4444444444444444444444444444444444444444'
                legacyRelease='2222222222222222222222222222222222222222'
            }

            { Use-ProductionWriterStartAuthorization -Config $script:config `
                    -Marker $changedMarker -ReleaseIdentity $release } |
                Should -Throw '*blocked*'
        }

        It 'binds a v2 authorization to the marker current release identity' {
            $marker = [pscustomobject]@{
                version=2
                state='TARGET_ACTIVE'
                targetRelease='1111111111111111111111111111111111111111'
                currentRelease='3333333333333333333333333333333333333333'
                legacyRelease='2222222222222222222222222222222222222222'
            }
            Mock Read-ProductionMusicSchemaDirection { $marker }
            $release = [pscustomobject]@{
                sha='4444444444444444444444444444444444444444'
                domainSchema='TARGET'
                musicSchema='TARGET'
            }
            Grant-ProductionWriterStartAuthorization -Config $script:config `
                -MarkerState TARGET_ACTIVE -Release $release.sha -Purpose TARGET_DEPLOY |
                Out-Null
            $changed = [pscustomobject]@{
                version=2
                state='TARGET_ACTIVE'
                targetRelease=$marker.targetRelease
                currentRelease='5555555555555555555555555555555555555555'
                legacyRelease=$marker.legacyRelease
            }

            { Use-ProductionWriterStartAuthorization -Config $script:config `
                    -Marker $changed -ReleaseIdentity $release } |
                Should -Throw '*blocked*'
        }

        It 'publishes and verifies the installed launcher guard as one crash-safe bundle' {
            $source = Join-Path $TestDrive 'source'
            $service = Join-Path $TestDrive 'service'
            New-Item -ItemType Directory -Path $source,$service -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            'guarded launcher' | Set-Content -LiteralPath $launcher
            'guard module' | Set-Content -LiteralPath $module
            'old launcher' | Set-Content -LiteralPath (Join-Path $service 'Start-ChristopherBellDev.ps1')
            $bundleConfig = [pscustomobject]@{ programDataRoot=$TestDrive }

            $result = Publish-ProductionWriterStartGuardBundle `
                -Config $bundleConfig `
                -SourceLauncherPath $launcher `
                -SourceModulePath $module

            Assert-ProductionWriterStartGuardBundle `
                -Config $bundleConfig `
                -ExpectedLauncherSha256 $result.launcherSha256 `
                -ExpectedModuleSha256 $result.moduleSha256
            (Get-FileHash (Join-Path $service 'Start-ChristopherBellDev.ps1') -Algorithm SHA256).Hash.ToLowerInvariant() |
                Should -Be $result.launcherSha256
            (Get-FileHash (Join-Path $service 'Production.WriterStart.psm1') -Algorithm SHA256).Hash.ToLowerInvariant() |
                Should -Be $result.moduleSha256
            Test-Path (Join-Path $service 'Production.WriterStart.bundle.json') | Should -BeTrue
        }

        It 'defines the exact shared-compatible protected service-directory ACL' {
            $acl = New-ProductionWriterStartServiceDirectoryAcl
            $rules = @($acl.GetAccessRules(
                $true,
                $false,
                [Security.Principal.SecurityIdentifier]))

            $acl.AreAccessRulesProtected | Should -BeTrue
            @($rules.IdentityReference.Value | Sort-Object) | Should -Be @(
                'S-1-5-18','S-1-5-19','S-1-5-32-544')
            $directoryInheritance =
                [Security.AccessControl.InheritanceFlags]::ContainerInherit -bor
                [Security.AccessControl.InheritanceFlags]::ObjectInherit
            foreach ($rule in $rules) {
                $rule.InheritanceFlags | Should -Be $directoryInheritance
                $rule.PropagationFlags |
                    Should -Be ([Security.AccessControl.PropagationFlags]::None)
            }
            ($rules | Where-Object IdentityReference -eq 'S-1-5-19').FileSystemRights |
                Should -Be (
                    [Security.AccessControl.FileSystemRights]::ReadAndExecute -bor
                    [Security.AccessControl.FileSystemRights]::Synchronize)
        }

        It 'upgrades a compatible base Automatic host to the complete Manual v2 bundle' {
            $root = Join-Path $TestDrive 'complete-host-boundary'
            $source = Join-Path $root 'source'
            $service = Join-Path $root 'service'
            New-Item -ItemType Directory -Path $source,$service -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            $sourceWinSw = Join-Path $source 'ChristopherBellDev.exe'
            $sourceServiceXml = Join-Path $source 'ChristopherBellDev.xml'
            $installedWinSw = Join-Path $service 'ChristopherBellDev.exe'
            $installedServiceXml = Join-Path $service 'ChristopherBellDev.xml'
            'launcher' | Set-Content $launcher
            'module' | Set-Content $module
            'pinned winsw' | Set-Content $sourceWinSw
            'pinned winsw' | Set-Content $installedWinSw
            '<service><startmode>Manual</startmode></service>' |
                Set-Content $sourceServiceXml
            '<service><startmode>Automatic</startmode></service>' |
                Set-Content $installedServiceXml
            $winSwSha = (Get-FileHash $sourceWinSw -Algorithm SHA256).Hash.ToLowerInvariant()
            $xmlSha = (Get-FileHash $sourceServiceXml -Algorithm SHA256).Hash.ToLowerInvariant()

            $result = Publish-ProductionWriterStartGuardBundle `
                -Config ([pscustomobject]@{ programDataRoot=$root }) `
                -SourceLauncherPath $launcher `
                -SourceModulePath $module `
                -SourceWinSwPath $sourceWinSw `
                -SourceServiceXmlPath $sourceServiceXml `
                -ExpectedWinSwSha256 $winSwSha `
                -ExpectedServiceXmlSha256 $xmlSha

            $manifest = Get-Content (Join-Path $service 'Production.WriterStart.bundle.json') `
                -Raw | ConvertFrom-Json
            $manifest.version | Should -Be 2
            $manifest.winSwSha256 | Should -Be $winSwSha
            $manifest.serviceXmlSha256 | Should -Be $xmlSha
            Assert-ProductionWriterStartGuardBundle `
                -Config ([pscustomobject]@{ programDataRoot=$root }) `
                -ExpectedLauncherSha256 $result.launcherSha256 `
                -ExpectedModuleSha256 $result.moduleSha256 `
                -ExpectedWinSwSha256 $winSwSha `
                -ExpectedServiceXmlSha256 $xmlSha | Out-Null
            Get-Content $installedServiceXml -Raw | Should -Match '<startmode>Manual</startmode>'
            Should -Invoke Protect-ProductionWriterStartServiceFile -ParameterFilter {
                $Path -eq $installedWinSw
            }
            Should -Invoke Protect-ProductionWriterStartServiceFile -ParameterFilter {
                $Path -eq $installedServiceXml
            }
        }

        It 'protects all five staged and installed files exactly before committing the manifest' {
            $root = Join-Path $TestDrive 'exact-file-publication'
            $source = Join-Path $root 'source'
            $service = Join-Path $root 'service'
            New-Item -ItemType Directory -Path $source,$service -Force | Out-Null
            $sourceFiles = @{
                'Start-ChristopherBellDev.ps1'='launcher'
                'Production.WriterStart.psm1'='module'
                'ChristopherBellDev.exe'='winsw'
                'ChristopherBellDev.xml'='<service><startmode>Manual</startmode></service>'
            }
            foreach ($entry in $sourceFiles.GetEnumerator()) {
                $entry.Value | Set-Content -LiteralPath (Join-Path $source $entry.Key)
            }
            $winSwSha = (Get-FileHash (Join-Path $source 'ChristopherBellDev.exe') `
                -Algorithm SHA256).Hash.ToLowerInvariant()
            $xmlSha = (Get-FileHash (Join-Path $source 'ChristopherBellDev.xml') `
                -Algorithm SHA256).Hash.ToLowerInvariant()
            $events = [Collections.Generic.List[string]]::new()
            Mock Protect-ProductionWriterStartServiceFile {
                [void]$events.Add("protect:$([IO.Path]::GetFullPath($Path))")
            }
            Mock Publish-ProductionWriterStartGuardFile {
                [void]$events.Add("publish:$([IO.Path]::GetFullPath($Destination))")
                Copy-Item -LiteralPath $Source -Destination $Destination -Force
            }

            Publish-ProductionWriterStartGuardBundle `
                -Config ([pscustomobject]@{ programDataRoot=$root }) `
                -SourceLauncherPath (Join-Path $source 'Start-ChristopherBellDev.ps1') `
                -SourceModulePath (Join-Path $source 'Production.WriterStart.psm1') `
                -SourceWinSwPath (Join-Path $source 'ChristopherBellDev.exe') `
                -SourceServiceXmlPath (Join-Path $source 'ChristopherBellDev.xml') `
                -ExpectedWinSwSha256 $winSwSha `
                -ExpectedServiceXmlSha256 $xmlSha | Out-Null

            $manifest = [IO.Path]::GetFullPath(
                (Join-Path $service 'Production.WriterStart.bundle.json'))
            $manifestPublish = $events.IndexOf("publish:$manifest")
            $manifestPublish | Should -BeGreaterOrEqual 0
            foreach ($name in @(
                'Start-ChristopherBellDev.ps1',
                'Production.WriterStart.psm1',
                'ChristopherBellDev.exe',
                'ChristopherBellDev.xml')) {
                $installed = [IO.Path]::GetFullPath((Join-Path $service $name))
                $events.IndexOf("protect:$installed") | Should -BeGreaterOrEqual 0
                $events.IndexOf("protect:$installed") | Should -BeLessThan $manifestPublish
            }
            foreach ($name in @(
                'Start-ChristopherBellDev.ps1',
                'Production.WriterStart.psm1',
                'ChristopherBellDev.exe',
                'ChristopherBellDev.xml',
                'Production.WriterStart.bundle.json')) {
                @($events | Where-Object { $_ -like "protect:*\$name" }).Count |
                    Should -Be 2
            }
        }

        It 'publishes no file when exact staged file protection fails' {
            $root = Join-Path $TestDrive 'exact-file-staging-failure'
            $source = Join-Path $root 'source'
            New-Item -ItemType Directory -Path $source -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            'launcher' | Set-Content -LiteralPath $launcher
            'module' | Set-Content -LiteralPath $module
            Mock Protect-ProductionWriterStartServiceFile {
                throw 'exact staged service-file ACL protection failed'
            }
            Mock Publish-ProductionWriterStartGuardFile {
                throw 'publication must not run'
            }

            { Publish-ProductionWriterStartGuardBundle `
                    -Config ([pscustomobject]@{ programDataRoot=$root }) `
                    -SourceLauncherPath $launcher `
                    -SourceModulePath $module } |
                Should -Throw '*exact staged service-file ACL protection failed*'

            Should -Invoke Publish-ProductionWriterStartGuardFile -Times 0 -Exactly
            Test-Path (Join-Path $root 'service\Production.WriterStart.bundle.json') |
                Should -BeFalse
        }

        It 'protects and verifies the canonical service directory before staging' {
            $root = Join-Path $TestDrive 'protected-parent'
            $source = Join-Path $root 'source'
            $service = Join-Path $root 'service'
            New-Item -ItemType Directory -Path $source -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            'launcher' | Set-Content $launcher
            'module' | Set-Content $module
            $events = [Collections.Generic.List[string]]::new()
            Mock Protect-ProductionWriterStartServiceDirectory {
                [void]$events.Add("protect:$([IO.Path]::GetFullPath($Path))")
            }
            Mock Assert-ProductionWriterStartServiceDirectory {
                [void]$events.Add("verify:$([IO.Path]::GetFullPath($Path))")
            }
            Mock Protect-ProductionPath {
                [void]$events.Add("protect:$([IO.Path]::GetFullPath($Path))")
            }
            Mock Assert-ProtectedProductionPath {
                [void]$events.Add("verify:$([IO.Path]::GetFullPath($Path))")
            }

            Publish-ProductionWriterStartGuardBundle `
                -Config ([pscustomobject]@{ programDataRoot=$root }) `
                -SourceLauncherPath $launcher `
                -SourceModulePath $module | Out-Null

            $canonicalService = [IO.Path]::GetFullPath($service)
            $events[0] | Should -Be "protect:$canonicalService"
            $events[1] | Should -Be "verify:$canonicalService"
        }

        It 'rejects an installed bundle when its parent service directory ACL is unprotected' {
            $root = Join-Path $TestDrive 'unprotected-parent'
            $source = Join-Path $root 'source'
            New-Item -ItemType Directory -Path $source -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            'launcher' | Set-Content $launcher
            'module' | Set-Content $module
            $config = [pscustomobject]@{ programDataRoot=$root }
            $result = Publish-ProductionWriterStartGuardBundle `
                -Config $config -SourceLauncherPath $launcher -SourceModulePath $module
            $service = [IO.Path]::GetFullPath((Join-Path $root 'service'))
            Mock Assert-ProductionWriterStartServiceDirectory {
                throw 'service directory ACL is unprotected'
            }

            { Assert-ProductionWriterStartGuardBundle `
                    -Config $config `
                    -ExpectedLauncherSha256 $result.launcherSha256 `
                    -ExpectedModuleSha256 $result.moduleSha256 } |
                Should -Throw '*service directory ACL is unprotected*'
        }

        It 'rejects a production root reached through a reparse point' {
            $target = Join-Path $TestDrive 'reparse-target'
            $alias = Join-Path $TestDrive 'reparse-alias'
            $source = Join-Path $TestDrive 'reparse-source'
            New-Item -ItemType Directory -Path $target,$source -Force | Out-Null
            New-Item -ItemType Junction -Path $alias -Target $target | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            'launcher' | Set-Content $launcher
            'module' | Set-Content $module
            try {
                { Publish-ProductionWriterStartGuardBundle `
                        -Config ([pscustomobject]@{ programDataRoot=$alias }) `
                        -SourceLauncherPath $launcher `
                        -SourceModulePath $module } | Should -Throw '*reparse*'
            } finally {
                if (Test-Path -LiteralPath $alias) {
                    Remove-Item -LiteralPath $alias -Force
                }
            }
        }

        It 'rejects an existing installed reparse destination before publication' {
            $root = Join-Path $TestDrive 'installed-reparse'
            $source = Join-Path $root 'source'
            $service = Join-Path $root 'service'
            New-Item -ItemType Directory -Path $source,$service -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            $installedLauncher = Join-Path $service 'Start-ChristopherBellDev.ps1'
            'new launcher' | Set-Content $launcher
            'new module' | Set-Content $module
            'old launcher' | Set-Content $installedLauncher
            Mock Assert-ProductionWriterStartPathNotReparseTraversal {
                $canonical = [IO.Path]::GetFullPath($Path)
                if ([string]::Equals(
                        $canonical,
                        [IO.Path]::GetFullPath($installedLauncher),
                        [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'installed destination is a reparse point'
                }
                return $canonical
            }

            { Publish-ProductionWriterStartGuardBundle `
                    -Config ([pscustomobject]@{ programDataRoot=$root }) `
                    -SourceLauncherPath $launcher `
                    -SourceModulePath $module } | Should -Throw '*installed destination is a reparse point*'

            Get-Content $installedLauncher -Raw | Should -Match 'old launcher'
            Test-Path (Join-Path $service 'Production.WriterStart.bundle.json') |
                Should -BeFalse
        }

        It 'does not publish any guard file when staged ACL verification fails' {
            $source = Join-Path $TestDrive 'failed-source'
            $failedRoot = Join-Path $TestDrive 'failed-root'
            $service = Join-Path $failedRoot 'service'
            New-Item -ItemType Directory -Path $source,$service -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            'guarded launcher' | Set-Content -LiteralPath $launcher
            'guard module' | Set-Content -LiteralPath $module
            'old launcher' | Set-Content -LiteralPath (Join-Path $service 'Start-ChristopherBellDev.ps1')
            Mock Assert-ProtectedProductionPath { throw 'ACL verification failed' }

            { Publish-ProductionWriterStartGuardBundle `
                    -Config ([pscustomobject]@{ programDataRoot=$failedRoot }) `
                    -SourceLauncherPath $launcher `
                    -SourceModulePath $module } | Should -Throw '*ACL verification failed*'

            Get-Content (Join-Path $service 'Start-ChristopherBellDev.ps1') -Raw |
                Should -Match 'old launcher'
            Test-Path (Join-Path $service 'Production.WriterStart.bundle.json') |
                Should -BeFalse
        }

        It 'does not publish staged files when readback hash verification fails' {
            $root = Join-Path $TestDrive 'hash-failure'
            $source = Join-Path $root 'source'
            $service = Join-Path $root 'service'
            New-Item -ItemType Directory -Path $source,$service -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            'launcher' | Set-Content $launcher
            'module' | Set-Content $module
            $script:hashRead = 0
            Mock Get-FileHash {
                $script:hashRead++
                if ($script:hashRead -le 2) { [pscustomobject]@{ Hash=('a' * 64) } }
                else { [pscustomobject]@{ Hash=('b' * 64) } }
            }

            { Publish-ProductionWriterStartGuardBundle `
                    -Config ([pscustomobject]@{ programDataRoot=$root }) `
                    -SourceLauncherPath $launcher `
                    -SourceModulePath $module } | Should -Throw '*SHA-256*'

            Test-Path (Join-Path $service 'Production.WriterStart.bundle.json') |
                Should -BeFalse
        }

        It 'leaves a fail-closed launcher and no commit manifest after partial publication' {
            $root = Join-Path $TestDrive 'partial-publication'
            $source = Join-Path $root 'source'
            $service = Join-Path $root 'service'
            New-Item -ItemType Directory -Path $source,$service -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            'new guarded launcher' | Set-Content $launcher
            'new module' | Set-Content $module
            'old unguarded launcher' | Set-Content (Join-Path $service 'Start-ChristopherBellDev.ps1')
            $script:publishMove = 0
            Mock Publish-ProductionWriterStartGuardFile {
                param($Source, $Destination)
                $script:publishMove++
                if ($script:publishMove -eq 2) { throw 'partial module publication failed' }
                Copy-Item -LiteralPath $Source -Destination $Destination -Force
            }

            { Publish-ProductionWriterStartGuardBundle `
                    -Config ([pscustomobject]@{ programDataRoot=$root }) `
                    -SourceLauncherPath $launcher `
                    -SourceModulePath $module } | Should -Throw '*partial module publication*'

            Get-Content (Join-Path $service 'Start-ChristopherBellDev.ps1') -Raw |
                Should -Match 'new guarded launcher'
            Test-Path (Join-Path $service 'Production.WriterStart.bundle.json') |
                Should -BeFalse
        }

        It 'keeps the five-file host uncommitted at publication checkpoint <Checkpoint>' `
                -ForEach @(
            @{ Checkpoint=1 },
            @{ Checkpoint=2 },
            @{ Checkpoint=3 },
            @{ Checkpoint=4 },
            @{ Checkpoint=5 }
        ) {
            $root = Join-Path $TestDrive "five-file-checkpoint-$Checkpoint"
            $source = Join-Path $root 'source'
            $service = Join-Path $root 'service'
            New-Item -ItemType Directory -Path $source,$service -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            $winSw = Join-Path $source 'ChristopherBellDev.exe'
            $serviceXml = Join-Path $source 'ChristopherBellDev.xml'
            'new guarded launcher' | Set-Content $launcher
            'new guarded module' | Set-Content $module
            'pinned winsw' | Set-Content $winSw
            '<service><startmode>Manual</startmode></service>' | Set-Content $serviceXml
            foreach ($name in @(
                'Start-ChristopherBellDev.ps1',
                'Production.WriterStart.psm1',
                'ChristopherBellDev.exe',
                'ChristopherBellDev.xml')) {
                "old $name" | Set-Content (Join-Path $service $name)
            }
            [ordered]@{
                version=2
                launcherSha256=('1' * 64)
                moduleSha256=('2' * 64)
                winSwSha256=('3' * 64)
                serviceXmlSha256=('4' * 64)
            } | ConvertTo-Json | Set-Content (
                Join-Path $service 'Production.WriterStart.bundle.json')
            $winSwSha = (Get-FileHash $winSw -Algorithm SHA256).Hash.ToLowerInvariant()
            $xmlSha = (Get-FileHash $serviceXml -Algorithm SHA256).Hash.ToLowerInvariant()
            $script:publishMove = 0
            Mock Publish-ProductionWriterStartGuardFile {
                $script:publishMove++
                if ($script:publishMove -eq $Checkpoint) {
                    throw "simulated publication checkpoint $Checkpoint"
                }
                Copy-Item -LiteralPath $Source -Destination $Destination -Force
            }

            { Publish-ProductionWriterStartGuardBundle `
                    -Config ([pscustomobject]@{ programDataRoot=$root }) `
                    -SourceLauncherPath $launcher `
                    -SourceModulePath $module `
                    -SourceWinSwPath $winSw `
                    -SourceServiceXmlPath $serviceXml `
                    -ExpectedWinSwSha256 $winSwSha `
                    -ExpectedServiceXmlSha256 $xmlSha } |
                Should -Throw "*publication checkpoint $Checkpoint*"

            Test-Path (Join-Path $service 'Production.WriterStart.bundle.json') |
                Should -BeFalse
            { Assert-ProductionWriterStartGuardBundle `
                    -Config ([pscustomobject]@{ programDataRoot=$root }) `
                    -ExpectedLauncherSha256 (
                        (Get-FileHash $launcher -Algorithm SHA256).Hash.ToLowerInvariant()) `
                    -ExpectedModuleSha256 (
                        (Get-FileHash $module -Algorithm SHA256).Hash.ToLowerInvariant()) `
                    -ExpectedWinSwSha256 $winSwSha `
                    -ExpectedServiceXmlSha256 $xmlSha } | Should -Throw
        }

        It 'consumes and rejects expired, wrong-release, and wrong-state authorizations' -ForEach @(
            @{ Kind='expired' }, @{ Kind='release' }, @{ Kind='state' }
        ) {
            $marker = [pscustomobject]@{
                state='TARGET_ACTIVE'
                targetRelease='1111111111111111111111111111111111111111'
                legacyRelease='2222222222222222222222222222222222222222'
            }
            $release = [pscustomobject]@{
                sha='3333333333333333333333333333333333333333'; musicSchema='TARGET'
            }
            Grant-ProductionWriterStartAuthorization -Config $script:config `
                -MarkerState TARGET_ACTIVE -Release $release.sha -Purpose TARGET_DEPLOY
            $path = Get-ProductionWriterStartAuthorizationPath -Config $script:config
            $authorization = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
            if ($Kind -eq 'expired') { $authorization.expiresAtEpochMillis = 1 }
            if ($Kind -eq 'release') { $authorization.release = '4444444444444444444444444444444444444444' }
            if ($Kind -eq 'state') { $authorization.markerState = 'TARGET_CUTOVER_IN_PROGRESS' }
            $authorization | ConvertTo-Json | Set-Content -LiteralPath $path

            { Use-ProductionWriterStartAuthorization -Config $script:config `
                    -Marker $marker -ReleaseIdentity $release } | Should -Throw '*blocked*'
            Test-Path -LiteralPath $path | Should -BeFalse
        }

        It 'rejects marker property and state casing variants' -ForEach @(
            @{ Json='{"Version":1,"state":"TARGET_ACTIVE","updatedAtEpochMillis":1,"targetRelease":"1111111111111111111111111111111111111111","legacyRelease":"2222222222222222222222222222222222222222"}' },
            @{ Json='{"version":1,"state":"target_active","updatedAtEpochMillis":1,"targetRelease":"1111111111111111111111111111111111111111","legacyRelease":"2222222222222222222222222222222222222222"}' }
        ) {
            $path = Get-ProductionMusicSchemaDirectionPath -Config $script:config
            New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
            $Json | Set-Content -LiteralPath $path

            { Read-ProductionMusicSchemaDirection -Config $script:config } |
                Should -Throw '*marker is invalid*'
        }

        It 'rejects uppercase marker SHA casing' {
            $path = Get-ProductionMusicSchemaDirectionPath -Config $script:config
            New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
            [ordered]@{
                version=1
                state='TARGET_ACTIVE'
                updatedAtEpochMillis=1
                targetRelease=('A' * 40)
                legacyRelease=('2' * 40)
            } | ConvertTo-Json | Set-Content -LiteralPath $path

            { Read-ProductionMusicSchemaDirection -Config $script:config } |
                Should -Throw '*marker is invalid*'
        }
    }

    It 'makes the installed launcher reject a two-principal file ACL before import' {
        $launcherPath = Join-Path $PSScriptRoot '..\service\Start-ChristopherBellDev.ps1'
        $tokens = $null
        $errors = $null
        $ast = [Management.Automation.Language.Parser]::ParseFile(
            $launcherPath,
            [ref]$tokens,
            [ref]$errors)
        $definition = $ast.FindAll({
                param($node)
                $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
                $node.Name -ceq 'Assert-InstalledWriterStartGuardAcl'
            }, $true)
        @($definition).Count | Should -Be 1
        . ([scriptblock]::Create($definition[0].Extent.Text))
        $script:launcherFileAcl = New-ProtectedProductionAcl
        Mock Get-Acl { $script:launcherFileAcl }

        { Assert-InstalledWriterStartGuardAcl -Path 'C:\guard\bundle-file' } |
            Should -Throw '*exactly three explicit ACEs*'

        $exactDescriptor = [Security.AccessControl.RawSecurityDescriptor]::new(
            'O:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)(A;;0x1200a9;;;LS)')
        $exactBytes = [byte[]]::new($exactDescriptor.BinaryLength)
        $exactDescriptor.GetBinaryForm($exactBytes, 0)
        $script:launcherFileAcl = [Security.AccessControl.FileSecurity]::new()
        $script:launcherFileAcl.SetSecurityDescriptorBinaryForm($exactBytes)
        { Assert-InstalledWriterStartGuardAcl -Path 'C:\guard\bundle-file' } |
            Should -Not -Throw
    }

    It 'guards the actual WinSW boot and recovery launch script' {
        $serviceRoot = Join-Path $PSScriptRoot '..\service'
        $scriptText = Get-Content (Join-Path $serviceRoot 'Start-ChristopherBellDev.ps1') -Raw
        $winsw = Get-Content (Join-Path $serviceRoot 'ChristopherBellDev.xml') -Raw

        $scriptText | Should -Match 'Assert-ProductionWriterStartAllowed -Config \$config'
        $scriptText | Should -Match 'Production\.WriterStart\.bundle\.json'
        $scriptText | Should -Match 'ChristopherBellDev\.exe'
        $scriptText | Should -Match 'ChristopherBellDev\.xml'
        $scriptText | Should -Match 'winSwSha256'
        $scriptText | Should -Match 'serviceXmlSha256'
        $scriptText | Should -Match 'Get-FileHash'
        $scriptText | Should -Match 'Assert-InstalledWriterStartGuardAcl'
        $scriptText | Should -Match 'Assert-InstalledWriterStartGuardNotReparse'
        $scriptText.IndexOf('Assert-InstalledWriterStartGuardNotReparse -Path $root') |
            Should -BeLessThan $scriptText.IndexOf('config\deploy.json')
        $serviceDirectoryAcl = $scriptText.IndexOf(
            'Assert-InstalledWriterStartServiceDirectoryAcl -Path')
        $serviceDirectoryAcl | Should -BeGreaterOrEqual 0
        $serviceDirectoryAcl | Should -BeLessThan $scriptText.IndexOf('Import-Module')
        $scriptText.IndexOf('Get-FileHash') |
            Should -BeLessThan $scriptText.IndexOf('Import-Module')
        $scriptText.IndexOf('Import-Module') |
            Should -BeLessThan $scriptText.IndexOf('config\deploy.json')
        $fixedBoundary = $scriptText.IndexOf(
            'Assert-ProductionFixedRootBoundary -Config $config -FixedRoot $root')
        $fixedBoundary | Should -BeGreaterThan $scriptText.IndexOf('Import-Module')
        $fixedBoundary | Should -BeLessThan $scriptText.IndexOf(
            'Assert-ProductionWriterStartAllowed')
        $scriptText | Should -Match (
            'Assert-ProductionWriterStartAllowed -Config \$config -FixedRoot \$root')
        $scriptText.IndexOf('Assert-ProductionWriterStartAllowed') |
            Should -BeLessThan $scriptText.IndexOf('& $config.javaExe')
        $winsw | Should -Match 'Start-ChristopherBellDev\.ps1'
        $winsw | Should -Match '<onfailure action="restart"'
    }
}

Describe 'production writer-start real Windows ACL boundary' {
    It 'protects the disposable service directory and complete installed service boundary' `
            -Skip:($env:CBELL_RUN_WRITER_START_ACL_TESTS -ne '1') {
        InModuleScope Production.WriterStart {
            $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
            $principal = [Security.Principal.WindowsPrincipal]$identity
            if (-not $principal.IsInRole(
                    [Security.Principal.WindowsBuiltInRole]::Administrator)) {
                throw 'Real writer-start ACL integration requires elevated PowerShell.'
            }
            $root = Join-Path ([IO.Path]::GetTempPath()) (
                'cbell-writer-start-acl-' + [guid]::NewGuid().ToString('N'))
            $source = Join-Path $root 'source'
            $service = Join-Path $root 'service'
            New-Item -ItemType Directory -Path $source,$service -Force | Out-Null
            $launcher = Join-Path $source 'Start-ChristopherBellDev.ps1'
            $module = Join-Path $source 'Production.WriterStart.psm1'
            $winSw = Join-Path $service 'ChristopherBellDev.exe'
            $serviceXml = Join-Path $service 'ChristopherBellDev.xml'
            'launcher' | Set-Content $launcher
            'module' | Set-Content $module
            'winsw' | Set-Content $winSw
            'service xml' | Set-Content $serviceXml
            try {
                $config = [pscustomobject]@{ programDataRoot=$root }
                $winSwSha = (Get-FileHash $winSw -Algorithm SHA256).Hash.ToLowerInvariant()
                $serviceXmlSha = (Get-FileHash $serviceXml -Algorithm SHA256).Hash.ToLowerInvariant()
                $result = Publish-ProductionWriterStartGuardBundle `
                    -Config $config `
                    -SourceLauncherPath $launcher `
                    -SourceModulePath $module `
                    -ExpectedWinSwSha256 $winSwSha `
                    -ExpectedServiceXmlSha256 $serviceXmlSha
                Assert-ProductionWriterStartGuardBundle `
                    -Config $config `
                    -ExpectedLauncherSha256 $result.launcherSha256 `
                    -ExpectedModuleSha256 $result.moduleSha256 `
                    -ExpectedWinSwSha256 $winSwSha `
                    -ExpectedServiceXmlSha256 $serviceXmlSha | Out-Null
                Assert-ProductionWriterStartServiceDirectory -Path $service
                foreach ($name in @(
                    'ChristopherBellDev.exe',
                    'ChristopherBellDev.xml',
                    'Start-ChristopherBellDev.ps1',
                    'Production.WriterStart.psm1',
                    'Production.WriterStart.bundle.json')) {
                    $path = Join-Path $service $name
                    Assert-ProductionWriterStartServiceFile -Path $path
                    $acl = Get-Acl -LiteralPath $path -ErrorAction Stop
                    $descriptor = [Security.AccessControl.RawSecurityDescriptor]::new(
                        $acl.GetSecurityDescriptorBinaryForm(), 0)
                    $aces = @($descriptor.DiscretionaryAcl)
                    $system = @($aces | Where-Object {
                            $_.SecurityIdentifier.Value -ceq 'S-1-5-18'
                        })[0]
                    $administrators = @($aces | Where-Object {
                            $_.SecurityIdentifier.Value -ceq 'S-1-5-32-544'
                        })[0]
                    $localService = @($aces | Where-Object {
                            $_.SecurityIdentifier.Value -ceq 'S-1-5-19'
                        })[0]
                    $readAndExecute = [int](
                        [Security.AccessControl.FileSystemRights]::ReadAndExecute -bor
                        [Security.AccessControl.FileSystemRights]::Synchronize)
                    $write = [int][Security.AccessControl.FileSystemRights]::Write
                    ([int]$system.AccessMask -band $readAndExecute) |
                        Should -Be $readAndExecute
                    ([int]$administrators.AccessMask -band $readAndExecute) |
                        Should -Be $readAndExecute
                    ([int]$localService.AccessMask -band $readAndExecute) |
                        Should -Be $readAndExecute
                    ([int]$localService.AccessMask -band $write) | Should -Be 0
                }
            } finally {
                if (Test-Path -LiteralPath $root) {
                    $temporaryRoot = [IO.Path]::GetFullPath(
                        [IO.Path]::GetTempPath()).TrimEnd('\')
                    $ownedRoot = [IO.Path]::GetFullPath($root)
                    if (-not [string]::Equals(
                            [IO.Path]::GetFullPath((Split-Path -Parent $ownedRoot)),
                            $temporaryRoot,
                            [StringComparison]::OrdinalIgnoreCase) -or
                        [IO.Path]::GetFileName($ownedRoot) -cnotmatch
                            '^cbell-writer-start-acl-[0-9a-f]{32}$') {
                        throw 'Writer-start ACL cleanup root is not an owned disposable path.'
                    }
                    Remove-Item -LiteralPath $root -Recurse -Force
                    if (Test-Path -LiteralPath $root) {
                        throw 'Writer-start ACL cleanup left disposable residue.'
                    }
                }
            }
        }
    }
}
