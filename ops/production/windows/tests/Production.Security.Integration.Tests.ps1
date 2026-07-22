Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.SharedFolder.psm1') -Force

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]$identity
$isElevatedWindows = $IsWindows -and $principal.IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)

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
