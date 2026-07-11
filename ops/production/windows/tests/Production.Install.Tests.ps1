BeforeAll {
    Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Install.psm1') -Force
}

Describe 'native Windows service installer' {
    It 'preserves an existing secret environment file' {
        $root = Join-Path $TestDrive 'data'
        New-ProductionDirectories $root
        $environment = Join-Path $root 'config\app.env'
        'APP_JWT_SECRET=keep-this-value' | Set-Content $environment
        Install-ConfigurationExamples $root
        Get-Content $environment -Raw | Should -Match 'keep-this-value'
    }

    It 'creates configuration examples without real credentials' {
        $root = Join-Path $TestDrive 'new-data'
        New-ProductionDirectories $root
        Install-ConfigurationExamples $root
        Get-Content (Join-Path $root 'config\app.env') -Raw | Should -Match 'replace-with'
    }
}
