BeforeAll {
    . (Join-Path $PSScriptRoot '..\Test-ProductionSite.ps1')

    $script:repository = 'azurras/christopherbell.dev'
    $script:liveCommit = 'a9d205890123456789abcdef0123456789abcdef'
    $script:newerCommit = 'b126b64241737995d9127d0849dcf62c68f987c0'
    $script:now = [datetimeoffset]::Parse('2026-10-05T15:00:00Z')

    function New-SiteResponse {
        param([int]$StatusCode, [object]$Content = '')
        [pscustomobject]@{ StatusCode = $StatusCode; Content = $Content }
    }

    function Set-GitHubState {
        param(
            [string]$MainCommit = $script:liveCommit,
            [string]$MainCommittedAt = '2026-10-05T14:40:00Z',
            [AllowNull()][string]$CiConclusion = 'success',
            [string]$CiStatus = 'completed',
            [string]$CiEvent = 'push',
            [string]$CiUpdatedAt = '2026-10-05T14:50:00Z',
            [AllowNull()][string]$DeploymentState = 'success',
            [AllowNull()][string]$TokenExpiresAt,
            [string[]]$ComparedFiles = @('website/src/main/java/App.java')
        )
        $script:gitHubState = @{
            MainCommit = $MainCommit
            MainCommittedAt = $MainCommittedAt
            CiConclusion = $CiConclusion
            CiStatus = $CiStatus
            CiEvent = $CiEvent
            CiUpdatedAt = $CiUpdatedAt
            DeploymentState = $DeploymentState
            TokenExpiresAt = $TokenExpiresAt
            ComparedFiles = $ComparedFiles
        }
        Mock Invoke-GitHubApi {
            param([string]$Path, [string]$Token)
            $state = $script:gitHubState
            switch -Regex ($Path) {
                '/commits/main$' {
                    return [pscustomobject]@{
                        sha = $state.MainCommit
                        commit = [pscustomobject]@{ committer = [pscustomobject]@{ date = $state.MainCommittedAt } }
                    }
                }
                '/actions/workflows/ci\.yml/runs' {
                    $script:requestedRunsPath = $Path
                    $runs = if ($state.CiConclusion -or $state.CiStatus -ne 'completed') {
                        @([pscustomobject]@{
                            run_number = 12
                            event = $state.CiEvent
                            status = $state.CiStatus
                            conclusion = $state.CiConclusion
                            updated_at = $state.CiUpdatedAt
                        })
                    } else { @() }
                    return [pscustomobject]@{ workflow_runs = $runs }
                }
                '/deployments/42/statuses' {
                    return ,@([pscustomobject]@{ state = $state.DeploymentState; description = 'Candidate smoke failed for blog.' })
                }
                '/deployments\?' {
                    if ($null -eq $state.DeploymentState) { return ,@() }
                    $deployment = [pscustomobject]@{ id = 42; sha = $state.MainCommit }
                    if ($state.TokenExpiresAt) {
                        $deployment | Add-Member -NotePropertyName payload `
                            -NotePropertyValue ([pscustomobject]@{ tokenExpiresAt = $state.TokenExpiresAt })
                    }
                    return ,@($deployment)
                }
                '/compare/' {
                    return [pscustomobject]@{
                        status = 'ahead'
                        files = @($state.ComparedFiles | ForEach-Object { [pscustomobject]@{ filename = $_ } })
                    }
                }
                default { throw "Unexpected GitHub API path $Path" }
            }
        }
    }

    function Get-TestVerdict {
        Get-ProductionWatchVerdict -Repository $script:repository -SiteUrl 'https://www.christopherbell.dev' `
            -Token 'test-token' -DeployLagThresholdMinutes 45 -ProbeAttempts 3 -ProbeRetryDelaySeconds 10 -Now $script:now
    }

    function Get-FailedCheckNames {
        param($Verdict)
        @($Verdict.Checks | Where-Object { -not $_.Passed } | ForEach-Object Name)
    }
}

Describe 'Production Watch verdict' {
    BeforeEach {
        Mock Start-Sleep {}
        Mock Invoke-WebRequest {
            param([uri]$Uri)
            if ($Uri.AbsolutePath -eq '/actuator/info') {
                return New-SiteResponse 200 "{`"build`":{`"version`":`"0.0.0-dev.$($script:liveCommit)`"}}"
            }
            return New-SiteResponse 200 'ok'
        }
        Set-GitHubState
    }

    It 'is healthy when every route answers, the deployment succeeded and main is live' {
        $verdict = Get-TestVerdict

        $verdict.Healthy | Should -BeTrue
        $verdict.LiveCommit | Should -Be $script:liveCommit
        Get-FailedCheckNames $verdict | Should -BeNullOrEmpty
    }

    It 'fails the readiness route after three refused attempts and names the cause' {
        Mock Invoke-WebRequest {
            param([uri]$Uri)
            if ($Uri.AbsolutePath -eq '/actuator/health/readiness') { return New-SiteResponse 503 }
            if ($Uri.AbsolutePath -eq '/actuator/info') {
                return New-SiteResponse 200 "{`"build`":{`"version`":`"0.0.0-dev.$($script:liveCommit)`"}}"
            }
            return New-SiteResponse 200 'ok'
        }

        $verdict = Get-TestVerdict

        $verdict.Healthy | Should -BeFalse
        Get-FailedCheckNames $verdict | Should -Be @('GET /actuator/health/readiness')
        ($verdict.Checks | Where-Object Name -eq 'GET /actuator/health/readiness').Detail |
            Should -Match 'failed after 3 attempts: HTTP 503'
        Should -Invoke Start-Sleep -Times 2 -Exactly
    }

    It 'recovers when a route fails once and then answers' {
        $script:homeAttempts = 0
        Mock Invoke-WebRequest {
            param([uri]$Uri)
            if ($Uri.AbsolutePath -eq '/' -and $script:homeAttempts++ -eq 0) { throw 'connection reset' }
            if ($Uri.AbsolutePath -eq '/actuator/info') {
                return New-SiteResponse 200 "{`"build`":{`"version`":`"0.0.0-dev.$($script:liveCommit)`"}}"
            }
            return New-SiteResponse 200 'ok'
        }

        (Get-TestVerdict).Healthy | Should -BeTrue
    }

    It 'fails when the latest Production deployment failed' {
        Set-GitHubState -DeploymentState 'failure'

        $verdict = Get-TestVerdict

        Get-FailedCheckNames $verdict | Should -Be @('Latest Production deployment')
        ($verdict.Checks | Where-Object Name -eq 'Latest Production deployment').Detail |
            Should -Be 'Deployment of a9d2058 is failure: Candidate smoke failed for blog.'
    }

    It 'passes when no Production deployment has been recorded' {
        Set-GitHubState -DeploymentState $null

        (Get-TestVerdict).Healthy | Should -BeTrue
    }

    It 'fails when main passed CI more than the threshold ago and is still not live' {
        Set-GitHubState -MainCommit $script:newerCommit -CiUpdatedAt '2026-10-05T14:10:00Z'

        $verdict = Get-TestVerdict

        Get-FailedCheckNames $verdict | Should -Be @('Deployment lag')
        ($verdict.Checks | Where-Object Name -eq 'Deployment lag').Detail |
            Should -Be 'main b126b64 passed CI 50 minutes ago but a9d2058 is still live.'
    }

    It 'measures lag in UTC when the API client already converted the timestamp to a DateTime' {
        Set-GitHubState -MainCommit $script:newerCommit
        $script:gitHubState.CiUpdatedAt = ('{"updated_at":"2026-10-05T14:10:00Z"}' | ConvertFrom-Json).updated_at

        ((Get-TestVerdict).Checks | Where-Object Name -eq 'Deployment lag').Detail |
            Should -Be 'main b126b64 passed CI 50 minutes ago but a9d2058 is still live.'
    }

    It 'tolerates a newer main within the deploy window or before CI passes' {
        Set-GitHubState -MainCommit $script:newerCommit -CiUpdatedAt '2026-10-05T14:30:00Z'
        (Get-TestVerdict).Healthy | Should -BeTrue

        Set-GitHubState -MainCommit $script:newerCommit -CiConclusion 'failure' -CiUpdatedAt '2026-10-05T10:00:00Z'
        (Get-TestVerdict).Healthy | Should -BeTrue

        Set-GitHubState -MainCommit $script:newerCommit -CiConclusion $null
        (Get-TestVerdict).Healthy | Should -BeTrue
    }

    It 'reads the live commit from actuator JSON delivered as bytes' {
        Mock Invoke-WebRequest {
            param([uri]$Uri)
            if ($Uri.AbsolutePath -eq '/actuator/info') {
                $actuatorJson = "{`"build`":{`"version`":`"0.0.0-dev.$($script:liveCommit)`"}}"
                return New-SiteResponse 200 ([Text.Encoding]::UTF8.GetBytes($actuatorJson))
            }
            return New-SiteResponse 200 'ok'
        }

        $verdict = Get-TestVerdict

        $verdict.Healthy | Should -BeTrue
        $verdict.LiveCommit | Should -Be $script:liveCommit
    }

    It 'fails when main has had no CI run for longer than the threshold' {
        Set-GitHubState -MainCommit $script:newerCommit -MainCommittedAt '2026-10-05T14:10:00Z' -CiConclusion $null

        $verdict = Get-TestVerdict

        Get-FailedCheckNames $verdict | Should -Be @('Deployment lag')
        ($verdict.Checks | Where-Object Name -eq 'Deployment lag').Detail |
            Should -Be ('main b126b64 has had no passing CI run for 50 minutes; auto-deploy is waiting for one. ' +
                'Run CI Build on main from the Actions tab.')
        $script:requestedRunsPath | Should -Match '\?head_sha=b126b64241737995d9127d0849dcf62c68f987c0&branch=main&per_page=10$'
    }

    It 'fails when the CI run for main has been unfinished for longer than the threshold' {
        Set-GitHubState -MainCommit $script:newerCommit -MainCommittedAt '2026-10-05T14:10:00Z' `
            -CiConclusion $null -CiStatus 'queued'

        Get-FailedCheckNames (Get-TestVerdict) | Should -Be @('Deployment lag')
    }

    It 'accepts a manual CI run and ignores pull request runs for main' {
        Set-GitHubState -MainCommit $script:newerCommit -CiEvent 'workflow_dispatch' -CiUpdatedAt '2026-10-05T14:10:00Z'
        ((Get-TestVerdict).Checks | Where-Object Name -eq 'Deployment lag').Detail |
            Should -Be 'main b126b64 passed CI 50 minutes ago but a9d2058 is still live.'

        Set-GitHubState -MainCommit $script:newerCommit -MainCommittedAt '2026-10-05T14:10:00Z' -CiEvent 'pull_request'
        ((Get-TestVerdict).Checks | Where-Object Name -eq 'Deployment lag').Detail |
            Should -Match '^main b126b64 has had no passing CI run for 50 minutes'
    }

    It 'reports a red main as refused rather than stalled' {
        Set-GitHubState -MainCommit $script:newerCommit -MainCommittedAt '2026-10-05T10:00:00Z' -CiConclusion 'failure'

        $verdict = Get-TestVerdict

        $verdict.Healthy | Should -BeTrue
        ($verdict.Checks | Where-Object Name -eq 'Deployment lag').Detail |
            Should -Be 'main b126b64 failed CI; auto-deploy refuses it.'
    }

    It 'warns about the deployment token <Case>' -ForEach @(
        @{ Case = 'within 14 days'; ExpiresAt = '2026-10-15T15:00:00Z'; Passed = $false; Detail = 'expires in 10 days (2026-10-15); renew it' }
        @{ Case = 'after it expired'; ExpiresAt = '2026-10-01T00:00:00Z'; Passed = $false; Detail = 'expired on 2026-10-01' }
        @{ Case = 'not when it is far off'; ExpiresAt = '2027-01-01T00:00:00Z'; Passed = $true; Detail = 'expires in 87 days (2027-01-01)' }
    ) {
        Set-GitHubState -TokenExpiresAt $ExpiresAt

        $check = (Get-TestVerdict).Checks | Where-Object Name -eq 'Deployment token expiry'

        $check.Passed | Should -Be $Passed
        $check.Detail | Should -Match ([regex]::Escape($Detail))
    }

    It 'passes the token check when no expiry was recorded' {
        ((Get-TestVerdict).Checks | Where-Object Name -eq 'Deployment token expiry').Detail |
            Should -Be 'No token expiry has been recorded.'
    }

    It 'treats a main that differs from live only by operations requests as live' {
        Set-GitHubState -MainCommit $script:newerCommit -CiUpdatedAt '2026-10-05T12:00:00Z' `
            -ComparedFiles @('ops/requests/verify-now.json')

        $check = (Get-TestVerdict).Checks | Where-Object Name -eq 'Deployment lag'

        $check.Passed | Should -BeTrue
        $check.Detail | Should -Match 'only by operations requests'
    }

    It 'still reports lag when main also changed deployable code' {
        Set-GitHubState -MainCommit $script:newerCommit -CiUpdatedAt '2026-10-05T12:00:00Z' `
            -ComparedFiles @('ops/requests/verify-now.json', 'website/src/main/java/App.java')

        ((Get-TestVerdict).Checks | Where-Object Name -eq 'Deployment lag').Passed | Should -BeFalse
    }

    It 'fails build info that does not identify a release commit' {
        Mock Invoke-WebRequest {
            param([uri]$Uri)
            if ($Uri.AbsolutePath -eq '/actuator/info') { return New-SiteResponse 200 '{"build":{"version":"1.2.3"}}' }
            return New-SiteResponse 200 'ok'
        }

        $verdict = Get-TestVerdict

        Get-FailedCheckNames $verdict | Should -Contain 'GET /actuator/info'
        $verdict.LiveCommit | Should -BeNullOrEmpty
    }
}

Describe 'Production alert issue' {
    BeforeEach {
        $script:gitHubCliCalls = [Collections.Generic.List[string]]::new()
        $script:openIssueJson = '[]'
        Mock Invoke-GitHubCli {
            param([string[]]$Arguments)
            $script:gitHubCliCalls.Add(($Arguments[0..1] -join ' '))
            if ($Arguments[0] -eq 'issue' -and $Arguments[1] -eq 'list') { return $script:openIssueJson }
        }
        $script:unhealthy = [pscustomobject]@{ Healthy = $false; Checks = @() }
        $script:healthy = [pscustomobject]@{ Healthy = $true; Checks = @() }
    }

    It 'opens one labeled issue when production becomes unhealthy' {
        Update-ProductionAlertIssue -Repository $script:repository -Verdict $script:unhealthy -Report 'down' |
            Should -Be 'Opened'
        $script:gitHubCliCalls | Should -Be @('issue list', 'label create', 'issue create')
    }

    It 'comments on the open issue while production stays unhealthy' {
        $script:openIssueJson = '[{"number":17}]'

        Update-ProductionAlertIssue -Repository $script:repository -Verdict $script:unhealthy -Report 'still down' |
            Should -Be 'Commented'
        $script:gitHubCliCalls | Should -Be @('issue list', 'issue comment')
    }

    It 'comments and closes the open issue on recovery' {
        $script:openIssueJson = '[{"number":17}]'

        Update-ProductionAlertIssue -Repository $script:repository -Verdict $script:healthy -Report 'up' |
            Should -Be 'Closed'
        $script:gitHubCliCalls | Should -Be @('issue list', 'issue comment', 'issue close')
    }

    It 'does nothing when healthy with no open issue' {
        Update-ProductionAlertIssue -Repository $script:repository -Verdict $script:healthy -Report 'up' |
            Should -Be 'None'
        $script:gitHubCliCalls | Should -Be @('issue list')
    }
}

Describe 'Production Watch report' {
    It 'renders each check and escapes table separators in details' {
        $verdict = [pscustomobject]@{
            Healthy = $false
            Checks = @(
                [pscustomobject]@{ Name = 'GET /'; Passed = $true; Detail = 'HTTP 200' }
                [pscustomobject]@{ Name = 'Deployment lag'; Passed = $false; Detail = 'a | b' }
            )
        }

        $report = Format-WatchReport -Verdict $verdict -RunUrl 'https://github.com/run/1'

        $report | Should -Match 'Production is unhealthy\.'
        $report | Should -Match '\| GET / \| Passed \| HTTP 200 \|'
        $report | Should -Match '\| Deployment lag \| \*\*Failed\*\* \| a \\\| b \|'
        $report | Should -Match '\[Production Watch\]\(https://github.com/run/1\)'
    }
}
