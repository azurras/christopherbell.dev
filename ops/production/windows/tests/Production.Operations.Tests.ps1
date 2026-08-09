Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Common.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Deploy.psm1') -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Sensors.psm1') -Global -Force
Import-Module (Join-Path $PSScriptRoot '..\modules\Production.Operations.psm1') -Force

Describe 'native Windows production operations' {
    InModuleScope Production.Operations {
        BeforeAll {
            function New-ValidStartupTask {
                param([string]$ProgramDataRoot = 'C:\ProgramData\christopherbell.dev')
                [pscustomobject]@{
                    State = 'Ready'
                    Principal = [pscustomobject]@{ UserId='SYSTEM'; LogonType='ServiceAccount'; RunLevel='Highest' }
                    Triggers = @(
                        [pscustomobject]@{
                            Enabled=$true
                            CimClass=[pscustomobject]@{ CimClassName='MSFT_TaskBootTrigger' }
                        }
                        [pscustomobject]@{
                            Enabled=$true
                            Repetition=[pscustomobject]@{ Interval='PT1M' }
                            CimClass=[pscustomobject]@{ CimClassName='MSFT_TaskTimeTrigger' }
                        }
                    )
                    Actions = @([pscustomobject]@{
                        Execute = Join-Path $env:ProgramFiles 'PowerShell\7\pwsh.exe'
                        Arguments = "-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$ProgramDataRoot\tools\prod.ps1`" auto-deploy"
                    })
                    Settings = [pscustomobject]@{
                        Enabled=$true
                        Hidden=$true
                        StartWhenAvailable=$true
                        DisallowStartIfOnBatteries=$false
                        StopIfGoingOnBatteries=$false
                        ExecutionTimeLimit='PT2H'
                        RestartCount=3
                        RestartInterval='PT1M'
                        MultipleInstances='IgnoreNew'
                    }
                }
            }

            function New-ProductionMongoInventoryJson {
                param([scriptblock]$Mutate)

                $inventory = [ordered]@{
                    complete = $true
                    database = 'christopherbell'
                    generatedAt = '2026-08-09T12:00:00.000Z'
                    collections = @(
                        [ordered]@{
                            name = 'accounts'
                            type = 'collection'
                            options = [ordered]@{}
                            count = 1
                            sizeBytes = 1
                            storageSizeBytes = 1
                            totalIndexSizeBytes = 1
                            indexes = @(
                                [ordered]@{
                                    name = '_id_'
                                    key = [ordered]@{ _id = 1 }
                                    unique = $true
                                    sparse = $false
                                    expireAfterSeconds = $null
                                    partialFilterExpression = $null
                                }
                            )
                        }
                    )
                }
                if ($null -ne $Mutate) {
                    & $Mutate $inventory
                }
                return $inventory | ConvertTo-Json -Depth 20 -Compress
            }

            function Get-ProductionMongoInventoryScriptPolicyViolations {
                param([string]$Script)

                $violations = [Collections.Generic.List[string]]::new()
                foreach ($requiredCall in 'getCollectionInfos','collStats','getIndexes') {
                    if ($Script -notmatch $requiredCall) {
                        [void]$violations.Add("Missing required metadata call: $requiredCall")
                    }
                }
                if ($Script -match '\.find\s*\(') {
                    [void]$violations.Add('Document find calls are forbidden.')
                }
                if ($Script -match '\.aggregate\s*\(') {
                    [void]$violations.Add('Document aggregate calls are forbidden.')
                }
                if ($Script -match (
                        '\.(find|findOne|aggregate|watch|countDocuments|estimatedDocumentCount|distinct|' +
                        'mapReduce|insert|insertOne|insertMany|save|update|updateOne|updateMany|replaceOne|' +
                        'remove|deleteOne|deleteMany|findOneAndDelete|findOneAndReplace|findOneAndUpdate|' +
                        'bulkWrite|drop|renameCollection|compact|repairDatabase|createIndex|createIndexes|' +
                        'dropIndex|dropIndexes|createCollection|dropDatabase)\s*\(')) {
                    [void]$violations.Add('Document read or mutation calls are forbidden.')
                }
                if ($Script -match '\$(out|merge)') {
                    [void]$violations.Add('Aggregation write stages are forbidden.')
                }
                $runCommandReferenceCount = [regex]::Matches(
                    $Script,
                    '\brunCommand\b').Count
                $collStatsCommandCount = [regex]::Matches(
                    $Script,
                    'target\.runCommand\s*\(\s*\{\s*collStats\s*:\s*info\.name\s*\}\s*\)').Count
                if ($runCommandReferenceCount -ne 1 -or $collStatsCommandCount -ne 1) {
                    [void]$violations.Add(
                        'Generic MongoDB commands must be the single audited collStats call.')
                }
                return $violations.ToArray()
            }
        }

        It 'refuses rollback unless both release junctions exist' {
            Mock Read-ProductionConfig { [pscustomobject]@{ programDataRoot='C:\data' } }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            Mock Get-JunctionTarget { $null }
            { Invoke-ProductionRollback -WhatIf } | Should -Throw '*Both current and previous*'
        }

        It 'uses the controlled stop for rollback and restoration' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot = 'C:\data'
                    productionPort = 8080
                }
            }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            Mock Get-JunctionTarget {
                if ($Path -like '*\current') { return 'C:\data\releases\current' }
                return 'C:\data\releases\previous'
            }
            Mock Assert-ReleasePath { $Path }
            Mock Stop-Service { }
            Mock Stop-ProductionWebsiteService { }
            $junctionWrites = [System.Collections.Generic.List[string]]::new()
            Mock Set-AtomicJunction {
                param($Config, $Path, $Target)
                [void]$junctionWrites.Add("$Path=>$Target")
            }
            Mock Start-Service { }
            $script:rollbackVerification = 0
            Mock Test-ProductionEndpoints {
                if ($script:rollbackVerification++ -eq 0) {
                    throw 'rollback verification failed'
                }
            }

            {
                Invoke-ProductionRollback
            } | Should -Throw '*rollback verification failed*'

            Should -Invoke Stop-ProductionWebsiteService -Times 2 -Exactly -ParameterFilter {
                $ProductionPort -eq 8080
            }
            ($junctionWrites -join '|') | Should -Be (
                'C:\data\current=>C:\data\releases\previous|' +
                'C:\data\previous=>C:\data\releases\current|' +
                'C:\data\current=>C:\data\releases\current|' +
                'C:\data\previous=>C:\data\releases\previous')
        }

        It 'preserves rollback and restoration failures together' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot = 'C:\data'
                    productionPort = 8080
                }
            }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            Mock Get-JunctionTarget {
                if ($Path -like '*\current') { return 'C:\data\releases\current' }
                return 'C:\data\releases\previous'
            }
            Mock Assert-ReleasePath { $Path }
            Mock Stop-ProductionWebsiteService { }
            Mock Set-AtomicJunction { }
            Mock Start-Service { }
            $script:rollbackVerification = 0
            Mock Test-ProductionEndpoints {
                if ($script:rollbackVerification++ -eq 0) {
                    throw 'rollback verification failed'
                }
                throw 'release restoration failed'
            }

            $failure = $null
            try {
                Invoke-ProductionRollback
            } catch {
                $failure = $_.Exception
            }

            $failure.GetType().FullName | Should -Be 'System.AggregateException'
            $failure.Message | Should -Match '^Production rollback and release restoration both failed\.'
            @($failure.InnerExceptions).Count | Should -Be 2
            $failure.InnerExceptions[0].Message | Should -Be 'rollback verification failed'
            $failure.InnerExceptions[1].Message | Should -Be 'release restoration failed'
        }

        It 'restores both original junctions when the <Name> forward junction write fails' -TestCases @(
            @{ Name = 'first'; FailingWrite = 1; FailureMessage = 'forward current write failed' }
            @{ Name = 'second'; FailingWrite = 2; FailureMessage = 'forward previous write failed' }
        ) {
            param($Name, $FailingWrite, $FailureMessage)
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot = 'C:\data'
                    productionPort = 8080
                }
            }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            Mock Get-JunctionTarget {
                if ($Path -like '*\current') { return 'C:\data\releases\current' }
                return 'C:\data\releases\previous'
            }
            Mock Assert-ReleasePath { $Path }
            Mock Stop-ProductionWebsiteService { }
            $junctionWrites = [System.Collections.Generic.List[string]]::new()
            $script:junctionWriteAttempt = 0
            Mock Set-AtomicJunction {
                param($Config, $Path, $Target)
                $script:junctionWriteAttempt++
                [void]$junctionWrites.Add("$Path=>$Target")
                if ($script:junctionWriteAttempt -eq $FailingWrite) {
                    throw $FailureMessage
                }
            }
            Mock Start-Service { }
            Mock Test-ProductionEndpoints { }

            {
                Invoke-ProductionRollback
            } | Should -Throw "*$FailureMessage*"

            @($junctionWrites)[-2] | Should -Be (
                'C:\data\current=>C:\data\releases\current')
            @($junctionWrites)[-1] | Should -Be (
                'C:\data\previous=>C:\data\releases\previous')
            Should -Invoke Stop-ProductionWebsiteService -Times 2 -Exactly
            Should -Invoke Start-Service -Times 1 -Exactly
        }

        It 'preserves a forward junction failure and attempts both originals when restoration fails' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot = 'C:\data'
                    productionPort = 8080
                }
            }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            Mock Get-JunctionTarget {
                if ($Path -like '*\current') { return 'C:\data\releases\current' }
                return 'C:\data\releases\previous'
            }
            Mock Assert-ReleasePath { $Path }
            Mock Stop-ProductionWebsiteService { }
            $junctionWrites = [System.Collections.Generic.List[string]]::new()
            $script:junctionWriteAttempt = 0
            Mock Set-AtomicJunction {
                param($Config, $Path, $Target)
                $script:junctionWriteAttempt++
                [void]$junctionWrites.Add("$Path=>$Target")
                if ($script:junctionWriteAttempt -eq 2) {
                    throw 'forward previous write failed'
                }
                if ($Path -like '*\current' -and
                    $Target -eq 'C:\data\releases\current') {
                    throw 'restore current write failed'
                }
            }
            Mock Start-Service { }
            Mock Test-ProductionEndpoints { }

            $failure = try {
                Invoke-ProductionRollback
                $null
            } catch {
                $_.Exception
            }

            $failure.GetType().FullName | Should -Be 'System.AggregateException'
            @($failure.InnerExceptions).Count | Should -Be 2
            $failure.InnerExceptions[0].Message | Should -Be 'forward previous write failed'
            $failure.InnerExceptions[1].Message | Should -Match '^Failed to restore original release junctions\.'
            $failure.InnerExceptions[1].InnerException.Message | Should -Be 'restore current write failed'
            $junctionWrites | Should -Contain (
                'C:\data\previous=>C:\data\releases\previous')
            Should -Invoke Start-Service -Times 0
        }

        It 'blocks manual rollback junction changes and restart when the controlled stop fails' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot = 'C:\data'
                    productionPort = 8080
                }
            }
            Mock Enter-DeploymentLock { [IO.MemoryStream]::new() }
            Mock Get-JunctionTarget {
                if ($Path -like '*\current') { return 'C:\data\releases\current' }
                return 'C:\data\releases\previous'
            }
            Mock Assert-ReleasePath { $Path }
            Mock Stop-ProductionWebsiteService { throw 'recovery restoration failed' }
            Mock Set-AtomicJunction { }
            Mock Start-Service { }

            {
                Invoke-ProductionRollback
            } | Should -Throw '*recovery restoration failed*'

            Should -Invoke Set-AtomicJunction -Times 0
            Should -Invoke Start-Service -Times 0
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

        It 'rejects startup verification without protected sensor state' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60
                    publicUrl='https://www.christopherbell.dev/'; productionPort=8080
                }
            }
            Mock Get-Service { [pscustomobject]@{ Status='Running'; StartType='Automatic' } }
            Mock Get-ScheduledTask { New-ValidStartupTask }
            Mock Test-ProductionEndpoints {}
            Mock Wait-HttpStatus { 200 }

            { Test-ProductionStartup } | Should -Throw '*sensorLibrariesEnabled*'
        }

        It 'reports the protected sensor state during startup verification' -TestCases @(
            @{ Enabled=$false }
            @{ Enabled=$true }
        ) {
            param($Enabled)
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60
                    publicUrl='https://www.christopherbell.dev/'; productionPort=8080
                    sensorLibrariesEnabled=$Enabled
                }
            }
            Mock Get-Service { [pscustomobject]@{ Status='Running'; StartType='Automatic' } }
            Mock Get-ScheduledTask { New-ValidStartupTask }
            Mock Test-ProductionEndpoints {}
            Mock Test-ProductionPublicEndpoints {}
            Mock Assert-ProductionSensorReady { 61.5 }

            (Test-ProductionStartup).SensorLibrariesEnabled | Should -Be $Enabled
            Should -Invoke Test-ProductionPublicEndpoints -Times 1 -Exactly
        }

        It 'requires a live verified CPU temperature when sensors are enabled' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60
                    publicUrl='https://www.christopherbell.dev/'; productionPort=8080
                    sensorLibrariesEnabled=$true
                }
            }
            Mock Get-Service { [pscustomobject]@{ Status='Running'; StartType='Automatic' } }
            Mock Get-ScheduledTask { New-ValidStartupTask }
            Mock Test-ProductionEndpoints {}
            Mock Test-ProductionPublicEndpoints { 18 }
            Mock Assert-ProductionSensorReady { 61.5 }

            (Test-ProductionStartup).CpuTemperatureCelsius | Should -Be 61.5

            Should -Invoke Assert-ProductionSensorReady -Times 1 -ParameterFilter {
                $Root -eq 'C:\ProgramData\christopherbell.dev'
            }
        }

        It 'does not require the native provider while sensors are disabled' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60
                    publicUrl='https://www.christopherbell.dev/'; productionPort=8080
                    sensorLibrariesEnabled=$false
                }
            }
            Mock Get-Service { [pscustomobject]@{ Status='Running'; StartType='Automatic' } }
            Mock Get-ScheduledTask { New-ValidStartupTask }
            Mock Test-ProductionEndpoints {}
            Mock Test-ProductionPublicEndpoints { 18 }
            Mock Assert-ProductionSensorReady { throw 'must not run' }

            (Test-ProductionStartup).CpuTemperatureCelsius | Should -BeNullOrEmpty
            Should -Invoke Assert-ProductionSensorReady -Times 0
        }

        It 'accepts the complete hidden repeating automatic deployment contract' {
            $config = [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60 }
            { Assert-AutoDeployTaskContract -Task (New-ValidStartupTask) -Config $config } | Should -Not -Throw
        }

        It 'rejects an automatic deployment task with the wrong principal' {
            $task = New-ValidStartupTask
            $task.Principal.UserId = 'Christopher'
            $config = [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60 }
            { Assert-AutoDeployTaskContract -Task $task -Config $config } | Should -Throw '*SYSTEM*'
        }

        It 'rejects an automatic deployment task without a boot trigger' {
            $task = New-ValidStartupTask
            $task.Triggers[0].CimClass.CimClassName = 'MSFT_TaskLogonTrigger'
            $config = [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60 }
            { Assert-AutoDeployTaskContract -Task $task -Config $config } | Should -Throw '*startup and one-minute repeating triggers*'
        }

        It 'rejects an automatic deployment task without a one-minute repeating trigger' {
            $task = New-ValidStartupTask
            $task.Triggers = @($task.Triggers[0])
            $config = [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60 }
            { Assert-AutoDeployTaskContract -Task $task -Config $config } | Should -Throw '*repeating trigger*'
        }

        It 'rejects a visible or interactive automatic deployment task' {
            $task = New-ValidStartupTask
            $task.Settings.Hidden = $false
            $task.Actions[0].Arguments = $task.Actions[0].Arguments.Replace(' -NonInteractive -WindowStyle Hidden', '')
            $config = [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60 }
            { Assert-AutoDeployTaskContract -Task $task -Config $config } | Should -Throw '*hidden and noninteractive*'
        }

        It 'rejects an automatic deployment task with a disabled boot trigger' {
            $task = New-ValidStartupTask
            $task.Triggers[0].Enabled = $false
            $config = [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60 }
            { Assert-AutoDeployTaskContract -Task $task -Config $config } | Should -Throw '*enabled startup trigger*'
        }

        It 'rejects an automatic deployment task with the wrong action' {
            $task = New-ValidStartupTask
            $task.Actions[0].Execute = 'pwsh.exe'
            $config = [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60 }
            { Assert-AutoDeployTaskContract -Task $task -Config $config } | Should -Throw '*PowerShell 7 executable*'
        }

        It 'rejects an automatic deployment task without restart resilience' {
            $task = New-ValidStartupTask
            $task.Settings.RestartCount = 0
            $config = [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=60 }
            { Assert-AutoDeployTaskContract -Task $task -Config $config } | Should -Throw '*restart*'
        }

        It 'rejects automatic deployment polling slower than one minute' {
            $config = [pscustomobject]@{ programDataRoot='C:\ProgramData\christopherbell.dev'; autoDeployPollSeconds=61 }
            { Assert-AutoDeployTaskContract -Task (New-ValidStartupTask) -Config $config } | Should -Throw '*60 seconds*'
        }

        It 'builds a metadata-only MongoDB inventory script' {
            $script = Get-ProductionMongoCollectionInventoryScript

            @(Get-ProductionMongoInventoryScriptPolicyViolations -Script $script).Count |
                Should -Be 0
        }

        It 'rejects a generic MongoDB command that is not the audited collStats call' {
            $unsafeScript = (Get-ProductionMongoCollectionInventoryScript).Replace(
                'target.runCommand({ collStats: info.name })',
                'target.runCommand({ drop: info.name })')

            $unsafeScript | Should -Match 'target\.runCommand\(\{ drop: info\.name \}\)'
            @(Get-ProductionMongoInventoryScriptPolicyViolations -Script $unsafeScript).Count |
                Should -BeGreaterThan 0
        }

        It 'represents views without collection statistics or indexes' {
            $script = Get-ProductionMongoCollectionInventoryScript

            $script | Should -Match (
                '(?s)const stats = info.type === ''view''\s*\?\s*\{ ok: 1, count: null, size: null, storageSize: null, totalIndexSize: null \}\s*:\s*target\.runCommand')
            $script | Should -Match (
                'const indexes = info.type === ''view''\s*\?\s*\[\]')
        }

        It 'invokes mongosh against only the fixed loopback production database' {
            Mock Read-ProductionConfig {
                [pscustomobject]@{
                    mongoShellExe = 'C:\tools\mongosh.exe'
                    repositoryPath = 'C:\repo'
                }
            }
            Mock Invoke-CheckedProcess {
                '{"complete":true,"database":"christopherbell","generatedAt":"2026-08-09T12:00:00.000Z","collections":[]}'
            }

            $inventory = Get-ProductionMongoCollectionInventory

            $inventory.complete | Should -BeTrue
            $inventory.database | Should -Be 'christopherbell'
            Should -Invoke Invoke-CheckedProcess -Times 1 -Exactly -ParameterFilter {
                $FilePath -eq 'C:\tools\mongosh.exe' -and
                $WorkingDirectory -eq 'C:\repo' -and
                $ArgumentList.Count -eq 5 -and
                $ArgumentList[0] -eq '--quiet' -and
                $ArgumentList[1] -eq '--norc' -and
                $ArgumentList[2] -eq 'mongodb://127.0.0.1:27017/admin' -and
                $ArgumentList[3] -eq '--eval'
            }
        }

        It 'rejects unallowlisted properties at every MongoDB inventory level' -TestCases @(
            @{ Name = 'root'; Mutate = { param($inventory) $inventory['document'] = @{ secret = 'no' } } }
            @{ Name = 'collection'; Mutate = { param($inventory) $inventory['collections'][0]['document'] = @{ secret = 'no' } } }
            @{ Name = 'options'; Mutate = { param($inventory) $inventory['collections'][0]['options']['secret'] = 'no' } }
            @{ Name = 'index'; Mutate = { param($inventory) $inventory['collections'][0]['indexes'][0]['document'] = @{ secret = 'no' } } }
        ) {
            param($Name, $Mutate)

            { ConvertFrom-ProductionMongoCollectionInventory -Json (New-ProductionMongoInventoryJson $Mutate) } |
                Should -Throw '*unknown property*'
        }

        It 'rejects malformed scalar and array types at every MongoDB inventory level' -TestCases @(
            @{ Name = 'root'; Mutate = { param($inventory) $inventory['complete'] = 'true' } }
            @{ Name = 'collections'; Mutate = { param($inventory) $inventory['collections'] = [ordered]@{} } }
            @{ Name = 'collection'; Mutate = { param($inventory) $inventory['collections'][0]['name'] = 1 } }
            @{ Name = 'options'; Mutate = { param($inventory) $inventory['collections'][0]['options'] = @() } }
            @{ Name = 'index'; Mutate = { param($inventory) $inventory['collections'][0]['indexes'][0]['unique'] = 'true' } }
        ) {
            param($Name, $Mutate)

            { ConvertFrom-ProductionMongoCollectionInventory -Json (New-ProductionMongoInventoryJson $Mutate) } |
                Should -Throw '*invalid*'
        }

        It 'rejects invalid view metadata and malformed index ordering' -TestCases @(
            @{ Name = 'view statistics'; Mutate = {
                    param($inventory)
                    $collection = $inventory['collections'][0]
                    $collection['type'] = 'view'
                    $collection['indexes'] = @()
                }
            }
            @{ Name = 'duplicate indexes'; Mutate = {
                    param($inventory)
                    $inventory['collections'][0]['indexes'] += [ordered]@{
                        name = '_id_'; key = [ordered]@{ email = 1 }; unique = $false; sparse = $false
                        expireAfterSeconds = $null; partialFilterExpression = $null
                    }
                }
            }
            @{ Name = 'unsorted indexes'; Mutate = {
                    param($inventory)
                    $inventory['collections'][0]['indexes'] = @(
                        [ordered]@{ name = 'z'; key = [ordered]@{ z = 1 }; unique = $false; sparse = $false; expireAfterSeconds = $null; partialFilterExpression = $null }
                        [ordered]@{ name = 'a'; key = [ordered]@{ a = 1 }; unique = $false; sparse = $false; expireAfterSeconds = $null; partialFilterExpression = $null }
                    )
                }
            }
        ) {
            param($Name, $Mutate)

            { ConvertFrom-ProductionMongoCollectionInventory -Json (New-ProductionMongoInventoryJson $Mutate) } |
                Should -Throw '*must*'
        }

        It 'returns a canonical allowlisted MongoDB inventory object' {
            $inventory = ConvertFrom-ProductionMongoCollectionInventory -Json (
                New-ProductionMongoInventoryJson)

            @($inventory.PSObject.Properties.Name) | Should -Be @('complete','database','generatedAt','collections')
            @($inventory.collections[0].PSObject.Properties.Name) | Should -Be @(
                'name','type','options','count','sizeBytes','storageSizeBytes','totalIndexSizeBytes','indexes')
            @($inventory.collections[0].indexes[0].PSObject.Properties.Name) | Should -Be @(
                'name','key','unique','sparse','expireAfterSeconds','partialFilterExpression')
        }

        It 'preserves semantic compound index key order' {
            $inventory = ConvertFrom-ProductionMongoCollectionInventory -Json (
                New-ProductionMongoInventoryJson {
                    param($candidate)
                    $candidate['collections'][0]['indexes'][0]['key'] = [ordered]@{
                        z = 1
                        a = -1
                    }
                })

            @($inventory.collections[0].indexes[0].key.PSObject.Properties.Name) |
                Should -Be @('z','a')
        }

        It 'redacts nested validator and partial-index scalar literals' {
            $inventory = ConvertFrom-ProductionMongoCollectionInventory -Json (
                New-ProductionMongoInventoryJson {
                    param($candidate)
                    $candidate['collections'][0]['options']['validator'] = [ordered]@{
                        '$jsonSchema' = [ordered]@{
                            required = @('TOP-SECRET-VALIDATOR')
                            properties = [ordered]@{
                                accountToken = [ordered]@{ enum = @('TOP-SECRET-ENUM', 42, $true, $null) }
                            }
                        }
                    }
                    $candidate['collections'][0]['indexes'][0]['partialFilterExpression'] =
                        [ordered]@{ accountToken = [ordered]@{ '$eq' = 'TOP-SECRET-PARTIAL' } }
                })

            $canonicalJson = $inventory | ConvertTo-Json -Depth 20 -Compress
            $canonicalJson | Should -Not -Match 'TOP-SECRET'
            $schema = $inventory.collections[0].options.validator.PSObject.Properties['$jsonSchema'].Value
            @($schema.required) | Should -Be @('[redacted]')
            @($schema.properties.accountToken.enum) |
                Should -Be @('[redacted]','[redacted]','[redacted]','[redacted]')
            $inventory.collections[0].indexes[0].partialFilterExpression.accountToken.PSObject.Properties['$eq'].Value |
                Should -Be '[redacted]'
        }

        It 'accepts a strictly shaped time-series collection with statistics and indexes' {
            $inventory = ConvertFrom-ProductionMongoCollectionInventory -Json (
                New-ProductionMongoInventoryJson {
                    param($candidate)
                    $collection = $candidate['collections'][0]
                    $collection['name'] = 'weather_samples'
                    $collection['type'] = 'timeseries'
                    $collection['count'] = $null
                    $collection['options'] = [ordered]@{
                        timeseries = [ordered]@{
                            timeField = 'observedAt'
                            metaField = 'station'
                            granularity = 'minutes'
                            bucketMaxSpanSeconds = 86400
                            bucketRoundingSeconds = 86400
                        }
                        expireAfterSeconds = 604800
                    }
                })

            $inventory.collections[0].type | Should -Be 'timeseries'
            @($inventory.collections[0].options.timeseries.PSObject.Properties.Name) |
                Should -Be @('timeField','metaField','granularity','bucketMaxSpanSeconds','bucketRoundingSeconds')
            $inventory.collections[0].count | Should -BeNullOrEmpty
            $inventory.collections[0].indexes.Count | Should -Be 1
        }

        It 'rejects a fractional time-series maximum bucket span' {
            $json = New-ProductionMongoInventoryJson {
                param($candidate)
                $candidate['collections'][0]['type'] = 'timeseries'
                $candidate['collections'][0]['count'] = $null
                $candidate['collections'][0]['options'] = [ordered]@{
                    timeseries = [ordered]@{
                        timeField = 'observedAt'
                        bucketMaxSpanSeconds = 1.5
                    }
                }
            }

            { ConvertFrom-ProductionMongoCollectionInventory -Json $json } |
                Should -Throw '*bucketMaxSpanSeconds*'
        }

        It 'rejects a fractional time-series bucket rounding interval' {
            $json = New-ProductionMongoInventoryJson {
                param($candidate)
                $candidate['collections'][0]['type'] = 'timeseries'
                $candidate['collections'][0]['count'] = $null
                $candidate['collections'][0]['options'] = [ordered]@{
                    timeseries = [ordered]@{
                        timeField = 'observedAt'
                        bucketRoundingSeconds = 1.5
                    }
                }
            }

            { ConvertFrom-ProductionMongoCollectionInventory -Json $json } |
                Should -Throw '*bucketRoundingSeconds*'
        }

        It 'rejects fractional time-series expiration seconds' {
            $json = New-ProductionMongoInventoryJson {
                param($candidate)
                $candidate['collections'][0]['type'] = 'timeseries'
                $candidate['collections'][0]['count'] = $null
                $candidate['collections'][0]['options'] = [ordered]@{
                    timeseries = [ordered]@{ timeField = 'observedAt' }
                    expireAfterSeconds = 1.5
                }
            }

            { ConvertFrom-ProductionMongoCollectionInventory -Json $json } |
                Should -Throw '*expireAfterSeconds*'
        }

        It 'rejects fractional index expiration seconds' {
            $json = New-ProductionMongoInventoryJson {
                param($candidate)
                $candidate['collections'][0]['indexes'][0]['expireAfterSeconds'] = 1.5
            }

            { ConvertFrom-ProductionMongoCollectionInventory -Json $json } |
                Should -Throw '*expireAfterSeconds*'
        }

        It 'rejects fractional capped collection size metadata' {
            $json = New-ProductionMongoInventoryJson {
                param($candidate)
                $candidate['collections'][0]['options']['size'] = 1.5
            }

            { ConvertFrom-ProductionMongoCollectionInventory -Json $json } |
                Should -Throw '*options.size*'
        }

        It 'rejects fractional capped collection document limits' {
            $json = New-ProductionMongoInventoryJson {
                param($candidate)
                $candidate['collections'][0]['options']['max'] = 1.5
            }

            { ConvertFrom-ProductionMongoCollectionInventory -Json $json } |
                Should -Throw '*options.max*'
        }

        It 'rejects integer metadata beyond the JSON safe range' {
            $json = New-ProductionMongoInventoryJson {
                param($candidate)
                $candidate['collections'][0]['indexes'][0]['expireAfterSeconds'] =
                    [long]9007199254740992
            }

            { ConvertFrom-ProductionMongoCollectionInventory -Json $json } |
                Should -Throw '*expireAfterSeconds*'
        }

        It 'rejects exponent-form fractional integer metadata before decimal rounding' {
            $json = New-ProductionMongoInventoryJson {
                param($candidate)
                $candidate['collections'][0]['indexes'][0]['expireAfterSeconds'] = 42
            }
            $json = $json.Replace(
                '"expireAfterSeconds":42',
                '"expireAfterSeconds":9.999999999999999e14')

            $json.Contains('"expireAfterSeconds":9.999999999999999e14') |
                Should -BeTrue
            { ConvertFrom-ProductionMongoCollectionInventory -Json $json } |
                Should -Throw '*index.expireAfterSeconds*'
        }

        It 'accepts exponent-form metadata at the maximum safe integer' {
            $json = New-ProductionMongoInventoryJson {
                param($candidate)
                $candidate['collections'][0]['indexes'][0]['expireAfterSeconds'] = 42
            }
            $json = $json.Replace(
                '"expireAfterSeconds":42',
                '"expireAfterSeconds":9.007199254740991e15')

            $inventory = ConvertFrom-ProductionMongoCollectionInventory -Json $json

            $inventory.collections[0].indexes[0].expireAfterSeconds |
                Should -Be 9007199254740991
        }

        It 'rejects exponent-form unsafe integer metadata before decimal rounding' {
            $json = New-ProductionMongoInventoryJson {
                param($candidate)
                $candidate['collections'][0]['indexes'][0]['expireAfterSeconds'] = 42
            }
            $json = $json.Replace(
                '"expireAfterSeconds":42',
                '"expireAfterSeconds":9.007199254740992e15')

            $json.Contains('"expireAfterSeconds":9.007199254740992e15') |
                Should -BeTrue
            { ConvertFrom-ProductionMongoCollectionInventory -Json $json } |
                Should -Throw '*index.expireAfterSeconds*'
        }

        It 'preserves valid collation strength and neighboring fields' {
            $inventory = ConvertFrom-ProductionMongoCollectionInventory -Json (
                New-ProductionMongoInventoryJson {
                    param($candidate)
                    $candidate['collections'][0]['options']['collation'] = [ordered]@{
                        locale = 'en'
                        strength = 5
                        caseLevel = $true
                        alternate = 'shifted'
                    }
                })
            $collation = $inventory.collections[0].options.collation

            @($collation.PSObject.Properties.Name) |
                Should -Be @('locale','strength','caseLevel','alternate')
            $collation.locale | Should -Be 'en'
            $collation.strength | Should -Be 5
            $collation.caseLevel | Should -BeTrue
            $collation.alternate | Should -Be 'shifted'
        }

        It 'rejects invalid collation strength: <Name>' -TestCases @(
            @{ Name = 'fractional'; JsonValue = '1.5'; Message = '*options.collation.strength*' }
            @{ Name = 'negative'; JsonValue = '-1'; Message = '*options.collation.strength*' }
            @{ Name = 'zero'; JsonValue = '0'; Message = '*options.collation.strength*' }
            @{ Name = 'above maximum'; JsonValue = '6'; Message = '*options.collation.strength*' }
            @{ Name = 'string'; JsonValue = '"2"'; Message = '*options.collation.strength*' }
            @{ Name = 'non-finite exponent'; JsonValue = '1e309'; Message = $null }
        ) {
            param($Name, $JsonValue, $Message)

            $json = New-ProductionMongoInventoryJson {
                param($candidate)
                $candidate['collections'][0]['options']['collation'] = [ordered]@{
                    locale = 'en'
                    strength = 1
                }
            }
            $json = $json.Replace('"strength":1', '"strength":' + $JsonValue)
            $json.Contains('"strength":' + $JsonValue) | Should -BeTrue

            if ($null -eq $Message) {
                { ConvertFrom-ProductionMongoCollectionInventory -Json $json } |
                    Should -Throw
            } else {
                { ConvertFrom-ProductionMongoCollectionInventory -Json $json } |
                    Should -Throw $Message
            }
        }

        It 'rejects an unknown time-series option' {
            $json = New-ProductionMongoInventoryJson {
                param($candidate)
                $candidate['collections'][0]['type'] = 'timeseries'
                $candidate['collections'][0]['options'] = [ordered]@{
                    timeseries = [ordered]@{ timeField = 'observedAt'; secretOption = 'no' }
                }
            }

            { ConvertFrom-ProductionMongoCollectionInventory -Json $json } |
                Should -Throw '*unknown property*'
        }

        It 'rejects a blank time-series time field' {
            $json = New-ProductionMongoInventoryJson {
                param($candidate)
                $candidate['collections'][0]['type'] = 'timeseries'
                $candidate['collections'][0]['options'] = [ordered]@{
                    timeseries = [ordered]@{ timeField = ' ' }
                }
            }

            { ConvertFrom-ProductionMongoCollectionInventory -Json $json } |
                Should -Throw '*timeField*'
        }

        It 'rejects an invalid time-series granularity' {
            $json = New-ProductionMongoInventoryJson {
                param($candidate)
                $candidate['collections'][0]['type'] = 'timeseries'
                $candidate['collections'][0]['options'] = [ordered]@{
                    timeseries = [ordered]@{ timeField = 'observedAt'; granularity = 'days' }
                }
            }

            { ConvertFrom-ProductionMongoCollectionInventory -Json $json } |
                Should -Throw '*granularity*'
        }

        It 'preserves nested metadata array cardinality during canonicalization' -TestCases @(
            @{ Name = 'empty'; Values = [object[]]@(); ExpectedCount = 0 }
            @{ Name = 'singleton'; Values = [object[]]@('only'); ExpectedCount = 1 }
            @{ Name = 'multiple'; Values = [object[]]@('first','second'); ExpectedCount = 2 }
        ) {
            param($Name, $Values, $ExpectedCount)

            $inventory = ConvertFrom-ProductionMongoCollectionInventory -Json (
                New-ProductionMongoInventoryJson {
                    param($candidate)
                    $candidate['collections'][0]['options']['validator'] = [ordered]@{
                        allowedValues = $Values
                    }
                })
            $actual = $inventory.collections[0].options.validator.allowedValues

            ($actual -is [Array]) | Should -BeTrue
            $actual.Count | Should -Be $ExpectedCount
            $actual | Should -Be @($Values | ForEach-Object { '[redacted]' })
        }

        It 'rejects incomplete MongoDB inventory output' {
            $json = '{"complete":false,"database":"christopherbell","generatedAt":"2026-08-09T12:00:00.000Z","collections":[]}'

            { ConvertFrom-ProductionMongoCollectionInventory -Json $json } |
                Should -Throw '*complete*'
        }

        It 'rejects malformed, wrong-database, duplicate, and unsorted inventory output' {
            { ConvertFrom-ProductionMongoCollectionInventory -Json 'not-json' } |
                Should -Throw '*valid JSON*'
            $wrongDatabase = '{"complete":true,"database":"admin","generatedAt":"2026-08-09T12:00:00.000Z","collections":[]}'
            { ConvertFrom-ProductionMongoCollectionInventory -Json $wrongDatabase } |
                Should -Throw '*christopherbell*'
            $missingCollections = '{"complete":true,"database":"christopherbell","generatedAt":"2026-08-09T12:00:00.000Z"}'
            { ConvertFrom-ProductionMongoCollectionInventory -Json $missingCollections } |
                Should -Throw '*collections*'
            $systemCollection = '{"complete":true,"database":"christopherbell","generatedAt":"2026-08-09T12:00:00.000Z","collections":[{"name":"system.profile","type":"collection","options":{},"count":0,"sizeBytes":0,"storageSizeBytes":0,"totalIndexSizeBytes":0,"indexes":[]}]}'
            { ConvertFrom-ProductionMongoCollectionInventory -Json $systemCollection } |
                Should -Throw '*system*'
            $duplicates = '{"complete":true,"database":"christopherbell","generatedAt":"2026-08-09T12:00:00.000Z","collections":[{"name":"accounts","type":"collection","options":{},"count":1,"sizeBytes":1,"storageSizeBytes":1,"totalIndexSizeBytes":1,"indexes":[]},{"name":"accounts","type":"collection","options":{},"count":1,"sizeBytes":1,"storageSizeBytes":1,"totalIndexSizeBytes":1,"indexes":[]}]}'
            { ConvertFrom-ProductionMongoCollectionInventory -Json $duplicates } |
                Should -Throw '*unique*'
            $unsorted = '{"complete":true,"database":"christopherbell","generatedAt":"2026-08-09T12:00:00.000Z","collections":[{"name":"posts","type":"collection","options":{},"count":1,"sizeBytes":1,"storageSizeBytes":1,"totalIndexSizeBytes":1,"indexes":[]},{"name":"accounts","type":"collection","options":{},"count":1,"sizeBytes":1,"storageSizeBytes":1,"totalIndexSizeBytes":1,"indexes":[]}]}'
            { ConvertFrom-ProductionMongoCollectionInventory -Json $unsorted } |
                Should -Throw '*sorted*'
        }

        It 'uses attached IPv4 URI and archive arguments for native backups' {
            $dump = Get-NativeMongoDumpArguments 'A:\backups\native.archive.gz'
            $restore = Get-NativeMongoRestoreDryRunArguments 'A:\backups\native.archive.gz'
            $dump | Should -Contain '--uri=mongodb://127.0.0.1:27017'
            $dump | Should -Contain '--archive=A:\backups\native.archive.gz'
            $restore | Should -Contain '--uri=mongodb://127.0.0.1:27017'
            $restore | Should -Contain '--archive=A:\backups\native.archive.gz'
            $dump | Should -Not -Contain '--archive'
            $restore | Should -Not -Contain '--archive'
        }
    }
}
