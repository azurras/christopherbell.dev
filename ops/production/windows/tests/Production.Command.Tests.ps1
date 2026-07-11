Describe 'native Windows production command surface' {
    It 'provides the dependency-free command entry points' {
        Test-Path (Join-Path $PSScriptRoot '..\..\..\..\prod.cmd') | Should -BeTrue
        Test-Path (Join-Path $PSScriptRoot '..\..\..\..\Makefile') | Should -BeTrue
        Test-Path (Join-Path $PSScriptRoot '..\prod.ps1') | Should -BeTrue
    }

    It 'prints help successfully' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        $output = & pwsh.exe -NoLogo -NoProfile -File (Join-Path $root 'ops\production\windows\prod.ps1') help
        ($output -join "`n") | Should -Match 'auto-install'
        $LASTEXITCODE | Should -Be 0
    }

    It 'rejects unknown commands' {
        $root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
        & pwsh.exe -NoLogo -NoProfile -File (Join-Path $root 'ops\production\windows\prod.ps1') unknown-command 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }
}
