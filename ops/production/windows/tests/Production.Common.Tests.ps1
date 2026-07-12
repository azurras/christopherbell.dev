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
          'APP_MAIL_FROM=noreply@example.com','SPRING_MONGODB_URI=mongodb://127.0.0.1:27017') | Set-Content $path
        (Read-ProductionEnvironment $path).APP_MAIL_FROM | Should -Be 'noreply@example.com'
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

    It 'scopes Git repository trust to each production command' {
        $arguments = Get-TrustedGitArguments -RepositoryPath 'A:\Projects\christopherbell.dev' -ArgumentList @('status','--short')
        $arguments | Should -Be @('-c','safe.directory=A:/Projects/christopherbell.dev','-C','A:\Projects\christopherbell.dev','status','--short')
    }
}
