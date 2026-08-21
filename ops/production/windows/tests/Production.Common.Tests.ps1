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
        New-Item -ItemType Directory -Force (Join-Path $TestDrive 'postgres-bin'),
            (Join-Path $TestDrive 'postgres-backups') | Out-Null
        $script:validConfig = @{
            repositoryPath=$repo; remote='origin'; branch='main'; programDataRoot=(Join-Path $TestDrive 'data')
            javaExe=$java.FullName; nodeExe=$node.FullName; mongoToolsPath=$tools; mongoShellExe=$mongosh.FullName
            backupRoot=$backup
            postgresqlVersion='18.4'; postgresqlBinPath=(Join-Path $TestDrive 'postgres-bin')
            postgresqlServiceName='postgresql-x64-18'
            postgresqlDataPath=(Join-Path $TestDrive 'postgres-data')
            pgAdminExe=(New-Item -ItemType File -Force (Join-Path $TestDrive 'pgAdmin4.exe')).FullName
            postgresqlBackupRoot=(Join-Path $TestDrive 'postgres-backups')
            cloudflaredExe=(New-Item -ItemType File -Force (Join-Path $TestDrive 'cloudflared.exe')).FullName
            publicUrl='https://www.christopherbell.dev/'
            publicUrls=@('https://christopherbell.dev/','https://www.christopherbell.dev/')
            smokeAccountEmail='admin@christopherbell.dev'; candidatePort=8081; productionPort=8080
            sensorLibrariesEnabled=$false
            releaseRetention=5; autoDeployPollSeconds=60; autoDeployFailureBackoffSeconds=900
        }
        New-Item -ItemType Directory -Force $validConfig.postgresqlDataPath | Out-Null
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

    It 'derives the apex and canonical roots for an existing www-only configuration' {
        $validConfig.Remove('publicUrls')
        $validConfig | ConvertTo-Json | Set-Content $configPath

        $config = Read-ProductionConfig -Path $configPath

        @($config.publicUrls) | Should -Be @(
            'https://christopherbell.dev/',
            'https://www.christopherbell.dev/'
        )
    }

    It 'requires explicitly configured roots to contain two unique absolute HTTPS roots' {
        $validConfig.publicUrls = @('https://www.christopherbell.dev/')
        $validConfig | ConvertTo-Json | Set-Content $configPath
        { Read-ProductionConfig -Path $configPath } | Should -Throw '*publicUrls*two*'

        $validConfig.publicUrls = @('https://www.christopherbell.dev/','https://www.christopherbell.dev/')
        $validConfig | ConvertTo-Json | Set-Content $configPath
        { Read-ProductionConfig -Path $configPath } | Should -Throw '*publicUrls*unique*'

        $validConfig.publicUrls = @('http://christopherbell.dev/','https://www.christopherbell.dev/')
        $validConfig | ConvertTo-Json | Set-Content $configPath
        { Read-ProductionConfig -Path $configPath } | Should -Throw '*publicUrls*HTTPS*'
    }

    It 'requires the canonical public URL in the public route roots' {
        $validConfig.publicUrls = @('https://christopherbell.dev/','https://app.christopherbell.dev/')
        $validConfig | ConvertTo-Json | Set-Content $configPath

        { Read-ProductionConfig -Path $configPath } | Should -Throw '*publicUrl*publicUrls*'
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
        New-Item -ItemType Directory -Path (Split-Path -Parent $path) | Out-Null
        $first = Enter-DeploymentLock -LockPath $path
        try { { Enter-DeploymentLock -LockPath $path } | Should -Throw '*already running*' }
        finally { $first.Dispose() }
    }

    It 'does not create an unchecked deployment-lock parent' {
        $path = Join-Path $TestDrive 'missing-locks\deploy.lock'

        { Enter-DeploymentLock -LockPath $path } |
            Should -Throw '*deployment lock directory*'

        Test-Path -LiteralPath (Split-Path -Parent $path) | Should -BeFalse
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

    It 'rejects unsupported PostgreSQL versions and non-absolute PostgreSQL paths' {
        $validConfig.postgresqlVersion = '17.7'
        $validConfig | ConvertTo-Json | Set-Content $configPath
        { Read-ProductionConfig -Path $configPath } | Should -Throw '*PostgreSQL*18.4*'

        $validConfig.postgresqlVersion = '18.4'
        $validConfig.postgresqlBinPath = '.\postgres-bin'
        $validConfig | ConvertTo-Json | Set-Content $configPath
        { Read-ProductionConfig -Path $configPath } | Should -Throw '*postgresqlBinPath*absolute*'
    }

    It 'keeps junction targets below the release root' {
        $config = [pscustomobject]@{ programDataRoot = (Join-Path $TestDrive 'data') }
        { Assert-ReleasePath $config (Join-Path $TestDrive 'elsewhere') } | Should -Throw '*releases directory*'
    }

    It 'parses only allowlisted environment keys' {
        $path = Join-Path $TestDrive 'app.env'
        @('APP_JWT_SECRET=abcdefghijklmnopqrstuvwxyz123456','APP_MAIL_ENABLED=true','RESEND_API_KEY=re_test',
          'APP_MAIL_FROM=noreply@example.com','SPRING_MONGODB_URI=mongodb://127.0.0.1:27017',
          'APP_SHARED_FOLDER_ENABLED=true') | Set-Content $path
        $environment = Read-ProductionEnvironment $path
        $environment.APP_MAIL_ENABLED | Should -Be 'true'
        $environment.APP_MAIL_FROM | Should -Be 'noreply@example.com'
        $environment.APP_SHARED_FOLDER_ENABLED | Should -Be 'true'
    }

    It 'allows mail to be explicitly disabled without mail credentials' {
        $path = Join-Path $TestDrive 'app.env'
        @('APP_JWT_SECRET=abcdefghijklmnopqrstuvwxyz123456','APP_MAIL_ENABLED=false',
          'SPRING_MONGODB_URI=mongodb://127.0.0.1:27017') | Set-Content $path

        $environment = Read-ProductionEnvironment $path

        $environment.APP_MAIL_ENABLED | Should -Be 'false'
        $environment.ContainsKey('APP_MAIL_FROM') | Should -BeFalse
        $environment.ContainsKey('RESEND_API_KEY') | Should -BeFalse
    }

    It 'requires mail credentials when mail is enabled' {
        $path = Join-Path $TestDrive 'app.env'
        @('APP_JWT_SECRET=abcdefghijklmnopqrstuvwxyz123456','APP_MAIL_ENABLED=true',
          'SPRING_MONGODB_URI=mongodb://127.0.0.1:27017') | Set-Content $path

        { Read-ProductionEnvironment $path } | Should -Throw '*APP_MAIL_FROM*RESEND_API_KEY*'
    }

    It 'rejects a non-Boolean mail switch' {
        $path = Join-Path $TestDrive 'app.env'
        @('APP_JWT_SECRET=abcdefghijklmnopqrstuvwxyz123456','APP_MAIL_ENABLED=yes',
          'SPRING_MONGODB_URI=mongodb://127.0.0.1:27017') | Set-Content $path

        { Read-ProductionEnvironment $path } | Should -Throw '*APP_MAIL_ENABLED*Boolean*'
    }

    It 'rejects a non-Boolean shared-folder switch' {
        $path = Join-Path $TestDrive 'app.env'
        @('APP_JWT_SECRET=abcdefghijklmnopqrstuvwxyz123456','APP_MAIL_ENABLED=true','RESEND_API_KEY=re_test',
          'APP_MAIL_FROM=noreply@example.com','SPRING_MONGODB_URI=mongodb://127.0.0.1:27017',
          'APP_SHARED_FOLDER_ENABLED=yes') | Set-Content $path

        { Read-ProductionEnvironment $path } | Should -Throw '*APP_SHARED_FOLDER_ENABLED*Boolean*'
    }

    It 'rejects unsupported environment keys' {
        $path = Join-Path $TestDrive 'app.env'
        'UNSAFE_KEY=value' | Set-Content $path
        { Read-ProductionEnvironment $path } | Should -Throw '*Unsupported*'
    }

    It 'validates the PostgreSQL application environment without requiring MongoDB' {
        $path = Join-Path $TestDrive 'app.env'
        @('APP_JWT_SECRET=abcdefghijklmnopqrstuvwxyz123456','APP_MAIL_ENABLED=false',
          'APP_PERSISTENCE_BACKEND=postgresql',
          'SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/christopherbell',
          'SPRING_DATASOURCE_USERNAME=christopherbell_app',
          'SPRING_DATASOURCE_PASSWORD=database-secret-value') | Set-Content $path

        $environment = Read-ProductionEnvironment $path

        $environment.APP_PERSISTENCE_BACKEND | Should -Be 'postgresql'
        $environment.ContainsKey('SPRING_MONGODB_URI') | Should -BeFalse

        (Get-Content -LiteralPath $path) -replace '127\.0\.0\.1','db.example.com' |
            Set-Content -LiteralPath $path
        { Read-ProductionEnvironment $path } | Should -Throw '*loopback*'
    }

    It 'rejects placeholder secrets' {
        $path = Join-Path $TestDrive 'app.env'
        @('APP_JWT_SECRET=replace-with-at-least-32-random-characters','APP_MAIL_ENABLED=true','RESEND_API_KEY=re_your_resend_api_key',
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
