Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Install.psm1') -Force

Describe 'native Windows service installer' {
    It 'reuses an existing WinSW binary instead of replacing a running service executable' {
        $serviceRoot = Join-Path $TestDrive 'service'
        New-Item -ItemType Directory -Path $serviceRoot | Out-Null
        'existing-winsw' | Set-Content (Join-Path $serviceRoot 'ChristopherBellDev.exe')
        Mock Invoke-WebRequest { throw 'WinSW should not be downloaded again.' }

        Install-WinSwBinary -ServiceRoot $serviceRoot

        Get-Content (Join-Path $serviceRoot 'ChristopherBellDev.exe') -Raw | Should -Match 'existing-winsw'
        Should -Invoke Invoke-WebRequest -Times 0
    }

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

    It 'adds new native defaults without replacing existing deploy values' {
        $root = Join-Path $TestDrive 'existing-data'
        New-ProductionDirectories $root
        $deploy = Join-Path $root 'config\deploy.json'
        @{ repositoryPath='A:\custom-repository'; smokeAccountEmail='admin@christopherbell.dev'; wslDistro='Debian'; wslWebsiteStartCommand='start-site' } |
            ConvertTo-Json | Set-Content $deploy
        Install-ConfigurationExamples $root
        $updated = Get-Content $deploy -Raw | ConvertFrom-Json
        $updated.repositoryPath | Should -Be 'A:\custom-repository'
        $updated.smokeAccountEmail | Should -Be 'admin@christopherbell.dev'
        $updated.cloudflaredExe | Should -Match 'cloudflared\.exe$'
        $updated.publicUrl | Should -Be 'https://www.christopherbell.dev/'
        $updated.PSObject.Properties.Name | Should -Not -Contain 'wslDistro'
        $updated.PSObject.Properties.Name | Should -Not -Contain 'wslWebsiteStartCommand'
    }
}

Describe 'native cloudflared service installer' {
    InModuleScope Production.Install {
        It 'requires a token path only when cloudflared is not installed' {
            Mock Get-Service { $null } -ParameterFilter { $Name -eq 'cloudflared' }
            Mock Test-Path { $true } -ParameterFilter { $LiteralPath -eq 'C:\cloudflared.exe' }
            { Install-CloudflaredService -Executable 'C:\cloudflared.exe' -TokenPath $null } |
                Should -Throw '*CloudflareTokenPath*'
        }

        It 'installs cloudflared without writing the token to output' {
            $tokenPath = Join-Path $TestDrive 'tunnel-token.txt'
            ('a' * 240) | Set-Content $tokenPath -NoNewline
            Mock Get-Service { $null } -ParameterFilter { $Name -eq 'cloudflared' }
            Mock Test-Path { $true }
            Mock Invoke-CheckedProcess {}
            Mock Set-Service {}
            Mock Start-Service {}
            Mock Start-Process {}
            $output = Install-CloudflaredService -Executable 'C:\cloudflared.exe' -TokenPath $tokenPath
            Should -Invoke Invoke-CheckedProcess -ParameterFilter {
                $FilePath -eq 'C:\cloudflared.exe' -and
                $ArgumentList[0] -eq 'service' -and
                $ArgumentList[1] -eq 'install' -and
                $ArgumentList[2].Length -eq 240
            }
            ($output -join '') | Should -Not -Match ('a' * 20)
        }

        It 'replaces an existing cloudflared credential only when a token path is supplied' {
            $tokenPath = Join-Path $TestDrive 'rotated-token.txt'
            ('b' * 240) | Set-Content $tokenPath -NoNewline
            Mock Get-Service { [pscustomobject]@{ Status='Running' } } -ParameterFilter { $Name -eq 'cloudflared' }
            Mock Test-Path { $true }
            Mock Invoke-CheckedProcess {}
            Mock Set-Service {}
            Mock Start-Service {}
            Install-CloudflaredService -Executable 'C:\cloudflared.exe' -TokenPath $tokenPath
            Should -Invoke Invoke-CheckedProcess -ParameterFilter {
                $FilePath -eq 'C:\cloudflared.exe' -and ($ArgumentList -join ' ') -eq 'service uninstall'
            }
            Should -Invoke Invoke-CheckedProcess -ParameterFilter {
                $FilePath -eq 'C:\cloudflared.exe' -and
                $ArgumentList[0] -eq 'service' -and
                $ArgumentList[1] -eq 'install' -and
                $ArgumentList[2].Length -eq 240
            }
        }
    }
}
