Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Operations.psm1') -Force

Describe 'native Windows production operations' {
    InModuleScope Production.Operations {
        It 'refuses rollback unless both release junctions exist' {
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot='C:\data' } }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            Mock Get-JunctionTarget { $null }
            { Invoke-ProductionRollback -WhatIf } | Should -Throw '*Both current and previous*'
        }
    }
}
