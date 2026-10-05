Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.WriterStart.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.MusicRuntime.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Deploy.psm1') -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.AutoDeploy.psm1') -Force

# Validates the operations requests committed to this repository, so a malformed request fails
# CI before it can reach main. Freshness is checked by the poller, not here.
Describe 'committed operations requests' {
    BeforeDiscovery {
        $requestDirectory = Join-Path $PSScriptRoot '..\..\..\..\ops\requests'
        $script:requestFiles = @(Get-ChildItem -LiteralPath $requestDirectory -File -ErrorAction Stop |
            ForEach-Object { @{ Name = $_.Name; Path = $_.FullName } })
    }

    It 'contains only JSON requests and the README' {
        $requestDirectory = Join-Path $PSScriptRoot '..\..\..\..\ops\requests'
        $unexpectedFiles = @(Get-ChildItem -LiteralPath $requestDirectory -Force |
            Where-Object { $_.PSIsContainer -or ($_.Name -ne 'README.md' -and $_.Extension -ne '.json') })

        $unexpectedFiles.Name | Should -BeNullOrEmpty
    }

    It 'accepts <Name>' -ForEach @($script:requestFiles | Where-Object Name -like '*.json') {
        $verdict = Test-AutoDeployOpsRequestDocument `
            -FileId ([IO.Path]::GetFileNameWithoutExtension($Name)) `
            -Content (Get-Content -LiteralPath $Path -Raw)

        $verdict.Detail | Should -Be 'Valid request.'
        $verdict.Outcome | Should -Be 'READY'
    }
}
