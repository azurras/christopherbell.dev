Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Operations.psm1') -Force

Describe 'native Windows production operations' {
    InModuleScope Production.Operations {
        It 'refuses rollback unless both release junctions exist' {
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot='C:\data' } }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            Mock Get-JunctionTarget { $null }
            { Invoke-ProductionRollback -WhatIf } | Should -Throw '*Both current and previous*'
        }

        It 'reports cloudflared with native website and MongoDB services' {
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot='C:\data'; productionPort=8080 } }
            Mock Get-Service { [pscustomobject]@{ Status='Running'; StartType='Automatic' } }
            Mock Get-JunctionTarget { $null }
            Mock Get-NetTCPConnection { [pscustomobject]@{ OwningProcess=42 } }
            (Get-ProductionStatus).CloudflaredService | Should -Be 'Running'
        }

        It 'rejects startup when a required service is not automatic' {
            Mock Read-ProductionConfig { [pscustomobject]@{ publicUrl='https://www.christopherbell.dev/'; productionPort=8080 } }
            Mock Get-Service { [pscustomobject]@{ Status='Running'; StartType='Manual' } }
            { Test-ProductionStartup } | Should -Throw '*Automatic*'
        }
    }
}
