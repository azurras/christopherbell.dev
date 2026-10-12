Import-Module (Join-Path $PSScriptRoot '..\CandidateRuntime.psm1') -Force

Describe 'local candidate guards' {
    It 'accepts only a generated directory directly under its parent' {
        $parent = Join-Path $TestDrive 'temp'
        $owned = New-OwnedTemporaryDirectory -Parent $parent -Prefix 'cbell-jdk-'

        Test-OwnedTemporaryDirectory -Path $owned -Parent $parent -Prefix 'cbell-jdk-' | Should -BeTrue
        Test-Path -LiteralPath $owned -PathType Container | Should -BeTrue
        Test-OwnedTemporaryDirectory -Path $parent -Parent $parent -Prefix 'cbell-jdk-' | Should -BeFalse
        Test-OwnedTemporaryDirectory -Path (Join-Path $owned 'db') -Parent $parent -Prefix 'cbell-jdk-' |
            Should -BeFalse
        Test-OwnedTemporaryDirectory -Path (Join-Path $parent 'cbell-jdk-production') -Parent $parent `
            -Prefix 'cbell-jdk-' | Should -BeFalse
        Test-OwnedTemporaryDirectory -Path (Join-Path $parent ('other-' + ('a' * 32))) -Parent $parent `
            -Prefix 'cbell-jdk-' | Should -BeFalse
        Test-OwnedTemporaryDirectory -Path '' -Parent $parent -Prefix 'cbell-jdk-' | Should -BeFalse
    }

    It 'adds the socket directory to JAVA_TOOL_OPTIONS unless one is already set' {
        Get-JdkSocketToolOptions -ExistingOptions '' -SocketDirectory 'C:\Temp\s' |
            Should -Be '-Djdk.net.unixdomain.tmpdir=C:\Temp\s'
        Get-JdkSocketToolOptions -ExistingOptions '-Xss2m' -SocketDirectory 'C:\Temp\s' |
            Should -Be '-Xss2m -Djdk.net.unixdomain.tmpdir=C:\Temp\s'
        Get-JdkSocketToolOptions -ExistingOptions '-Djdk.net.unixdomain.tmpdir=D:\x' -SocketDirectory 'C:\Temp\s' |
            Should -Be '-Djdk.net.unixdomain.tmpdir=D:\x'
    }

    It 'restores the caller environment after running the action, even when it fails' {
        $env:CANDIDATE_RUNTIME_TEST = 'caller'
        Remove-Item Env:CANDIDATE_RUNTIME_ABSENT -ErrorAction SilentlyContinue

        $seen = Invoke-WithEnvironment -Variables @{ CANDIDATE_RUNTIME_TEST = 'child'; CANDIDATE_RUNTIME_ABSENT = 'set' } `
            -Action { "$env:CANDIDATE_RUNTIME_TEST/$env:CANDIDATE_RUNTIME_ABSENT" }
        { Invoke-WithEnvironment -Variables @{ CANDIDATE_RUNTIME_TEST = 'child' } -Action { throw 'boom' } } |
            Should -Throw 'boom'

        $seen | Should -Be 'child/set'
        $env:CANDIDATE_RUNTIME_TEST | Should -Be 'caller'
        Test-Path Env:CANDIDATE_RUNTIME_ABSENT | Should -BeFalse
        Remove-Item Env:CANDIDATE_RUNTIME_TEST
    }

    It 'resolves the newest MongoDB Server install when mongod is not on PATH' {
        Mock Get-Command { $null } -ModuleName CandidateRuntime -ParameterFilter { $Name -eq 'mongod.exe' }
        foreach ($version in '8.0', '8.3', '10.1', 'notes') {
            $bin = Join-Path $TestDrive "MongoDB\Server\$version\bin"
            New-Item -ItemType Directory -Path $bin -Force | Out-Null
            if ($version -ne 'notes') {
                Set-Content -LiteralPath (Join-Path $bin 'mongod.exe') -Value ''
            }
        }

        Resolve-MongodExecutable -ProgramFilesRoot $TestDrive |
            Should -Be (Join-Path $TestDrive 'MongoDB\Server\10.1\bin\mongod.exe')
        { Resolve-MongodExecutable -RequestedPath (Join-Path $TestDrive 'missing.exe') } |
            Should -Throw '*not found*'
        { Resolve-MongodExecutable -ProgramFilesRoot (Join-Path $TestDrive 'empty') } |
            Should -Throw '*pass -MongodPath*'
    }

    It 'never returns a reserved port' {
        1..20 | ForEach-Object { Get-FreeLoopbackPort } | Should -Not -BeIn @(27017, 8080, 8081)
    }
}
