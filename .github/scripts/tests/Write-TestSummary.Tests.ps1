BeforeAll {
    $script:summaryScript = Join-Path $PSScriptRoot '..\Write-TestSummary.ps1'

    function Write-ResultFile {
        param(
            [Parameter(Mandatory)][string]$RelativePath,
            [Parameter(Mandatory)][string]$Content
        )
        $resultPath = Join-Path $TestDrive $RelativePath
        New-Item -ItemType Directory -Path (Split-Path -Parent $resultPath) -Force | Out-Null
        Set-Content -LiteralPath $resultPath -Value $Content -Encoding utf8
    }
}

Describe 'Write-TestSummary' {
    BeforeEach {
        Get-ChildItem -LiteralPath $TestDrive -Force | Remove-Item -Recurse -Force
        $script:summaryPath = Join-Path $TestDrive 'summary.md'
    }

    It 'totals JUnit and Pester NUnit results by result directory and lists failures' {
        Write-ResultFile 'website/build/test-results/test/TEST-dev.AccountServiceTest.xml' @'
<testsuite name="dev.AccountServiceTest" tests="3" failures="1" errors="0" skipped="1" time="2.5">
  <testcase name="createsAccount" classname="dev.AccountServiceTest" time="1.0"/>
  <testcase name="rejectsDuplicateEmail" classname="dev.AccountServiceTest" time="1.25">
    <failure message="expected 409">trace</failure>
  </testcase>
  <testcase name="sendsWelcomeMail" classname="dev.AccountServiceTest" time="0.25"><skipped/></testcase>
</testsuite>
'@
        Write-ResultFile 'website/build/test-results/test/TEST-dev.FeedServiceTest.xml' @'
<testsuite name="dev.FeedServiceTest" tests="1" failures="0" errors="1" skipped="0" time="9.0">
  <testcase name="pagesFeed" classname="dev.FeedServiceTest" time="9.0"><error message="boom"/></testcase>
</testsuite>
'@
        Write-ResultFile 'website/build/test-results/shared-folder-pester/worker-pwsh7.xml' @'
<test-results total="3" errors="0" failures="0" not-run="1">
  <test-suite type="Assembly" name="Pester" time="4.0">
    <results>
      <test-case name="worker.starts" executed="True" result="Success" time="1.5"/>
      <test-case name="worker.stops" executed="True" result="Success" time="2.0"/>
      <test-case name="worker.skips" executed="False" result="Ignored" time="0"/>
    </results>
  </test-suite>
</test-results>
'@

        & $script:summaryScript -ResultsRoot $TestDrive -SummaryPath $script:summaryPath

        $summary = Get-Content -LiteralPath $script:summaryPath -Raw
        $summary | Should -Match '\| website/build/test-results/test \| 4 \| 1 \| 2 \| 1 \| 11\.5 s \|'
        $summary | Should -Match '\| website/build/test-results/shared-folder-pester \| 3 \| 2 \| 0 \| 1 \| 3\.5 s \|'
        $summary | Should -Match '\| \*\*Total\*\* \| 7 \| 3 \| 2 \| 2 \| 15\.0 s \|'
        $summary | Should -Match '- dev\.AccountServiceTest > rejectsDuplicateEmail'
        $summary | Should -Match '- dev\.FeedServiceTest > pagesFeed'
        $summary.IndexOf('dev.FeedServiceTest | 1 | 9.0 s') |
            Should -BeLessThan $summary.IndexOf('dev.AccountServiceTest | 3 | 2.5 s')
    }

    It 'reports that no results exist when the build stopped before tests ran' {
        & $script:summaryScript -ResultsRoot $TestDrive -SummaryPath $script:summaryPath

        Get-Content -LiteralPath $script:summaryPath -Raw |
            Should -Match 'No test result files were found'
    }

    It 'lists an unreadable result file and still summarizes the readable ones' {
        Write-ResultFile 'website/build/test-results/test/TEST-broken.xml' '<testsuite name="broken"'
        Write-ResultFile 'website/build/test-results/jsTest/node.xml' @'
<testsuites>
  <testsuite name="composer">
    <testcase name="renders preview" classname="composer" time="0.5"/>
  </testsuite>
</testsuites>
'@

        & $script:summaryScript -ResultsRoot $TestDrive -SummaryPath $script:summaryPath

        $summary = Get-Content -LiteralPath $script:summaryPath -Raw
        $summary | Should -Match '\| website/build/test-results/jsTest \| 1 \| 1 \| 0 \| 0 \| 0\.5 s \|'
        $summary | Should -Match '### Unreadable result files'
        $summary | Should -Match 'website/build/test-results/test/TEST-broken\.xml'
    }

    It 'writes the summary to output when no summary path is supplied' {
        Write-ResultFile 'cbell-lib/build/test-results/test/TEST-lib.xml' @'
<testsuite name="lib.ClockTest"><testcase name="ticks" classname="lib.ClockTest" time="0.1"/></testsuite>
'@

        $output = & $script:summaryScript -ResultsRoot $TestDrive

        ($output -join "`n") | Should -Match '\| cbell-lib/build/test-results/test \| 1 \| 1 \| 0 \| 0 \| 0\.1 s \|'
    }
}
