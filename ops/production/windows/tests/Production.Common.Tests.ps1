BeforeAll {
    Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Force
}

Describe 'production common operations' {
    BeforeEach {
        $script:repo = Join-Path $TestDrive 'repo'
        $script:tools = Join-Path $TestDrive 'mongo-tools'
        $script:backup = Join-Path $TestDrive 'backups'
        New-Item -ItemType Directory -Force $repo,$tools,$backup | Out-Null
        $script:java = New-Item -ItemType File -Force (Join-Path $TestDrive 'java.exe')
        $script:node = New-Item -ItemType File -Force (Join-Path $TestDrive 'node.exe')
        $script:mongosh = New-Item -ItemType File -Force (Join-Path $TestDrive 'mongosh.exe')
        $script:configPath = Join-Path $TestDrive 'deploy.json'
        $script:validConfig = @{
            repositoryPath=$repo; remote='origin'; branch='main'; programDataRoot=(Join-Path $TestDrive 'data')
            javaExe=$java.FullName; nodeExe=$node.FullName; mongoToolsPath=$tools; mongoShellExe=$mongosh.FullName
            backupRoot=$backup
            cloudflaredExe=(New-Item -ItemType File -Force (Join-Path $TestDrive 'cloudflared.exe')).FullName
            publicUrl='https://www.christopherbell.dev/'
            smokeAccountEmail='admin@christopherbell.dev'; candidatePort=8081; productionPort=8080
            sensorLibrariesEnabled=$false
            releaseRetention=5; autoDeployPollSeconds=60; autoDeployFailureBackoffSeconds=900
        }
    }

    It 'loads a complete valid configuration' {
        $validConfig | ConvertTo-Json | Set-Content $configPath
        (Read-ProductionConfig -Path $configPath).branch | Should -Be 'main'
    }

    It 'loads a Windows-only configuration without WSL fields' {
        $validConfig | ConvertTo-Json | Set-Content $configPath
        $config = Read-ProductionConfig -Path $configPath
        $config.publicUrl | Should -Be 'https://www.christopherbell.dev/'
        $config.PSObject.Properties.Name | Should -Not -Contain 'wslDistro'
    }

    It 'rejects a missing or string sensor provider switch' {
        $validConfig.Remove('sensorLibrariesEnabled')
        $validConfig | ConvertTo-Json | Set-Content $configPath
        { Read-ProductionConfig -Path $configPath } | Should -Throw '*Boolean*'
        $validConfig.sensorLibrariesEnabled = 'false'
        $validConfig | ConvertTo-Json | Set-Content $configPath
        { Read-ProductionConfig -Path $configPath } | Should -Throw '*Boolean*'
    }

    It 'rejects concurrent deployment locks' {
        $path = Join-Path $TestDrive 'locks\deploy.lock'
        $first = Enter-DeploymentLock -LockPath $path
        try { { Enter-DeploymentLock -LockPath $path } | Should -Throw '*already running*' }
        finally { $first.Dispose() }
    }

    It 'rejects candidate and production port collisions' {
        $validConfig.candidatePort = 8080
        $validConfig | ConvertTo-Json | Set-Content $configPath
        { Read-ProductionConfig -Path $configPath } | Should -Throw '*must differ*'
    }

    It 'rejects the example smoke account' {
        $validConfig.smokeAccountEmail = 'operator@example.com'
        $validConfig | ConvertTo-Json | Set-Content $configPath
        { Read-ProductionConfig -Path $configPath } | Should -Throw '*real production account*'
    }

    It 'rejects configured paths that do not exist' {
        $validConfig.javaExe = Join-Path $TestDrive 'missing-java.exe'
        $validConfig | ConvertTo-Json | Set-Content $configPath
        { Read-ProductionConfig -Path $configPath } | Should -Throw '*javaExe*'
    }

    It 'keeps junction targets below the release root' {
        $config = [pscustomobject]@{ programDataRoot = (Join-Path $TestDrive 'data') }
        { Assert-ReleasePath $config (Join-Path $TestDrive 'elsewhere') } | Should -Throw '*releases directory*'
    }

    It 'parses only allowlisted environment keys' {
        $path = Join-Path $TestDrive 'app.env'
        @('APP_JWT_SECRET=abcdefghijklmnopqrstuvwxyz123456','RESEND_API_KEY=re_test',
          'APP_MAIL_FROM=noreply@example.com','SPRING_MONGODB_URI=mongodb://127.0.0.1:27017',
          'APP_SHARED_FOLDER_ENABLED=true') | Set-Content $path
        $environment = Read-ProductionEnvironment $path
        $environment.APP_MAIL_FROM | Should -Be 'noreply@example.com'
        $environment.APP_SHARED_FOLDER_ENABLED | Should -Be 'true'
    }

    It 'rejects a non-Boolean shared-folder switch' {
        $path = Join-Path $TestDrive 'app.env'
        @('APP_JWT_SECRET=abcdefghijklmnopqrstuvwxyz123456','RESEND_API_KEY=re_test',
          'APP_MAIL_FROM=noreply@example.com','SPRING_MONGODB_URI=mongodb://127.0.0.1:27017',
          'APP_SHARED_FOLDER_ENABLED=yes') | Set-Content $path

        { Read-ProductionEnvironment $path } | Should -Throw '*APP_SHARED_FOLDER_ENABLED*Boolean*'
    }

    It 'rejects unsupported environment keys' {
        $path = Join-Path $TestDrive 'app.env'
        'UNSAFE_KEY=value' | Set-Content $path
        { Read-ProductionEnvironment $path } | Should -Throw '*Unsupported*'
    }

    It 'rejects placeholder secrets' {
        $path = Join-Path $TestDrive 'app.env'
        @('APP_JWT_SECRET=replace-with-at-least-32-random-characters','RESEND_API_KEY=re_your_resend_api_key',
          'APP_MAIL_FROM=noreply@your-verified-domain.com','SPRING_MONGODB_URI=mongodb://127.0.0.1:27017') | Set-Content $path
        { Read-ProductionEnvironment $path } | Should -Throw '*non-placeholder*'
    }

    It 'rejects a non-zero child exit without echoing child output' {
        { Invoke-CheckedProcess -FilePath 'cmd.exe' -ArgumentList @('/d','/c','echo sensitive-child-output 1>&2 & exit /b 7') -WorkingDirectory $TestDrive } |
            Should -Throw '*cmd.exe exited with code 7*'
        try { Invoke-CheckedProcess -FilePath 'cmd.exe' -ArgumentList @('/d','/c','echo sensitive-child-output 1>&2 & exit /b 7') -WorkingDirectory $TestDrive }
        catch { $_.Exception.Message | Should -Not -Match 'sensitive-child-output' }
    }

    It 'runs checked processes from Windows PowerShell 5.1 with arguments and environment' {
        $target = Join-Path $TestDrive 'legacy-process-target.ps1'
        @'
param([Parameter(Mandatory)][string]$Value)
[Console]::Write("$Value|$env:PROBE_VALUE")
'@ | Set-Content -LiteralPath $target
        $probe = Join-Path $TestDrive 'legacy-process-probe.ps1'
        @'
param(
    [Parameter(Mandatory)][string]$ModulePath,
    [Parameter(Mandatory)][string]$TargetPath
)
$ErrorActionPreference = 'Stop'
Import-Module $ModulePath -Force
$output = Invoke-CheckedProcess `
    -FilePath 'powershell.exe' `
    -ArgumentList @('-NoProfile','-File',$TargetPath,'-Value','argument with spaces "and quotes"\tail') `
    -Environment @{ PROBE_VALUE = 'legacy value' }
if ($output -ne 'argument with spaces "and quotes"\tail|legacy value') {
    throw "Unexpected child output: $output"
}
'@ | Set-Content -LiteralPath $probe

        $modulePath = (Resolve-Path (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1')).Path
        & powershell.exe -NoProfile -File $probe -ModulePath $modulePath -TargetPath $target

        $LASTEXITCODE | Should -Be 0
    }

    It 'normalizes an HTTP error response without PowerShell 7-only switches' {
        Mock Invoke-WebRequest -ModuleName Production.Common {
            $exception = [Exception]::new('simulated HTTP 401')
            $exception | Add-Member -MemberType NoteProperty -Name Response -Value (
                [pscustomobject]@{ StatusCode = 401; Content = '{"code":"UNAUTHORIZED"}' })
            throw $exception
        }

        $response = Invoke-ProductionWebRequest `
            -Uri 'http://127.0.0.1/login' `
            -Method Post `
            -ContentType 'application/json' `
            -Body '{}'

        $response.StatusCode | Should -Be 401
        $response.Content | Should -Be '{"code":"UNAUTHORIZED"}'
        Should -Invoke Invoke-WebRequest -ModuleName Production.Common -Times 1 -Exactly `
            -ParameterFilter { $UseBasicParsing -and -not $PSBoundParameters.ContainsKey('SkipHttpErrorCheck') }
    }

    It 'does not attach an empty request body to a health-check GET' {
        Mock Invoke-WebRequest -ModuleName Production.Common {
            [pscustomobject]@{ StatusCode = 200; Content = 'ok' }
        }

        $response = Invoke-ProductionWebRequest -Uri 'http://127.0.0.1/'

        $response.StatusCode | Should -Be 200
        Should -Invoke Invoke-WebRequest -ModuleName Production.Common -Times 1 -Exactly `
            -ParameterFilter {
                $Method -eq 'Get' -and
                -not $PSBoundParameters.ContainsKey('Body')
            }
    }

    It 'scopes Git repository trust to each production command' {
        $arguments = Get-TrustedGitArguments -RepositoryPath 'A:\Projects\christopherbell.dev' -ArgumentList @('status','--short')
        $arguments | Should -Be @('-c','safe.directory=A:/Projects/christopherbell.dev','-C','A:\Projects\christopherbell.dev','status','--short')
    }

    It 'builds a protected directory ACL owned by Administrators with only privileged writers' {
        $acl = New-ProtectedProductionAcl -Directory
        $acl.AreAccessRulesProtected | Should -BeTrue
        $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value | Should -Be 'S-1-5-32-544'
        $rules = @($acl.GetAccessRules($true, $false, [Security.Principal.SecurityIdentifier]))
        @($rules.IdentityReference.Value | Sort-Object) | Should -Be @('S-1-5-18','S-1-5-32-544')
        @($rules | Where-Object {
            $_.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            -not ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl)
        }) | Should -BeNullOrEmpty
    }

    It 'rejects a reparse point before applying a privileged ACL' {
        InModuleScope Production.Common {
            Mock Get-Item {
                [pscustomobject]@{
                    Attributes = [IO.FileAttributes]::ReparsePoint
                    PSIsContainer = $true
                }
            }
            Mock Set-Acl {}

            { Protect-ProductionPath -Path 'C:\audit\linked-tools' } | Should -Throw '*reparse*'

            Should -Invoke Set-Acl -Times 0
        }
    }
}
