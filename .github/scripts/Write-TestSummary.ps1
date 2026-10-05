<#
.SYNOPSIS
Summarizes JUnit and Pester NUnit result files as a Markdown table for a CI job summary.

.DESCRIPTION
Reads every XML file under each build/test-results directory beneath ResultsRoot and reports
test, pass, failure, skip and duration totals per results directory, the failed test names and
the slowest suites. A result file that cannot be parsed is listed instead of failing the step,
because the summary must still appear when the build itself failed.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$ResultsRoot,

    [string]$SummaryPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$maximumListedFailures = 20
$maximumListedSlowSuites = 10
$invariantCulture = [Globalization.CultureInfo]::InvariantCulture

function Get-RelativeResultPath {
    param(
        [Parameter(Mandatory)][string]$RootPath,
        [Parameter(Mandatory)][string]$Path
    )
    return [IO.Path]::GetRelativePath($RootPath, $Path).Replace('\', '/')
}

function ConvertTo-DurationInSeconds {
    param([AllowNull()][AllowEmptyString()][string]$TimeAttribute)
    $durationInSeconds = 0.0
    if ([double]::TryParse($TimeAttribute, [Globalization.NumberStyles]::Float,
            $invariantCulture, [ref]$durationInSeconds)) {
        return $durationInSeconds
    }
    return 0.0
}

function Read-JUnitTestCases {
    param([Parameter(Mandatory)][xml]$ResultDocument)
    foreach ($testCase in $ResultDocument.SelectNodes('//testcase')) {
        $outcome = if ($testCase.SelectSingleNode('failure|error')) {
            'Failed'
        } elseif ($testCase.SelectSingleNode('skipped')) {
            'Skipped'
        } else {
            'Passed'
        }
        [pscustomobject]@{
            Name = '{0} > {1}' -f $testCase.GetAttribute('classname'), $testCase.GetAttribute('name')
            Outcome = $outcome
            DurationInSeconds = ConvertTo-DurationInSeconds $testCase.GetAttribute('time')
        }
    }
}

function Read-NUnitTestCases {
    param([Parameter(Mandatory)][xml]$ResultDocument)
    foreach ($testCase in $ResultDocument.SelectNodes('//test-case')) {
        $result = $testCase.GetAttribute('result')
        $outcome = if ($result -in @('Failure', 'Error')) {
            'Failed'
        } elseif ($testCase.GetAttribute('executed') -eq 'False' -or $result -ne 'Success') {
            'Skipped'
        } else {
            'Passed'
        }
        [pscustomobject]@{
            Name = $testCase.GetAttribute('name')
            Outcome = $outcome
            DurationInSeconds = ConvertTo-DurationInSeconds $testCase.GetAttribute('time')
        }
    }
}

function Read-TestSuiteResult {
    param(
        [Parameter(Mandatory)][IO.FileInfo]$ResultFile,
        [Parameter(Mandatory)][string]$ResultsDirectory
    )
    $resultDocument = [xml](Get-Content -LiteralPath $ResultFile.FullName -Raw)
    $rootName = $resultDocument.DocumentElement.LocalName
    $testCases = switch ($rootName) {
        'testsuite' { @(Read-JUnitTestCases $resultDocument) }
        'testsuites' { @(Read-JUnitTestCases $resultDocument) }
        'test-results' { @(Read-NUnitTestCases $resultDocument) }
        default { throw "Unrecognized test result root element '$rootName'." }
    }
    $suiteName = if ($rootName -eq 'testsuite' -and $resultDocument.DocumentElement.GetAttribute('name')) {
        $resultDocument.DocumentElement.GetAttribute('name')
    } else {
        $ResultFile.BaseName
    }
    return [pscustomobject]@{
        ResultsDirectory = $ResultsDirectory
        SuiteName = $suiteName
        TestCases = $testCases
        DurationInSeconds = ($testCases | Measure-Object -Property DurationInSeconds -Sum).Sum ?? 0.0
    }
}

function Format-Duration {
    param([Parameter(Mandatory)][double]$DurationInSeconds)
    return $DurationInSeconds.ToString('0.0', $invariantCulture) + ' s'
}

function Format-TotalsRow {
    param(
        [Parameter(Mandatory)][string]$Label,
        [Parameter(Mandatory)][AllowEmptyCollection()][object[]]$TestCases
    )
    $passedCount = @($TestCases | Where-Object Outcome -eq 'Passed').Count
    $failedCount = @($TestCases | Where-Object Outcome -eq 'Failed').Count
    $skippedCount = @($TestCases | Where-Object Outcome -eq 'Skipped').Count
    $durationInSeconds = ($TestCases | Measure-Object -Property DurationInSeconds -Sum).Sum ?? 0.0
    return '| {0} | {1} | {2} | {3} | {4} | {5} |' -f $Label, $TestCases.Count, $passedCount,
        $failedCount, $skippedCount, (Format-Duration $durationInSeconds)
}

$rootPath = (Resolve-Path -LiteralPath $ResultsRoot).Path
$resultFiles = @(
    Get-ChildItem -LiteralPath $rootPath -Directory -Recurse -Filter 'test-results' |
        Where-Object { $_.Parent.Name -eq 'build' } |
        ForEach-Object { Get-ChildItem -LiteralPath $_.FullName -File -Recurse -Filter '*.xml' } |
        Sort-Object -Property FullName
)

$suiteResults = [Collections.Generic.List[object]]::new()
$unreadableResultPaths = [Collections.Generic.List[string]]::new()
foreach ($resultFile in $resultFiles) {
    $resultsDirectory = Get-RelativeResultPath $rootPath $resultFile.DirectoryName
    try {
        $suiteResults.Add((Read-TestSuiteResult -ResultFile $resultFile -ResultsDirectory $resultsDirectory))
    } catch {
        $unreadableResultPaths.Add((Get-RelativeResultPath $rootPath $resultFile.FullName))
    }
}

$summaryLines = [Collections.Generic.List[string]]::new()
$summaryLines.Add('## Test results')
$summaryLines.Add('')
if ($resultFiles.Count -eq 0) {
    $summaryLines.Add('No test result files were found under build/test-results; the build stopped before tests reported.')
} else {
    $allTestCases = @($suiteResults | ForEach-Object { $_.TestCases })
    $summaryLines.Add('| Results | Tests | Passed | Failed | Skipped | Duration |')
    $summaryLines.Add('|---|---|---|---|---|---|')
    foreach ($directoryGroup in $suiteResults | Group-Object -Property ResultsDirectory | Sort-Object Name) {
        $directoryTestCases = @($directoryGroup.Group | ForEach-Object { $_.TestCases })
        $summaryLines.Add((Format-TotalsRow -Label $directoryGroup.Name -TestCases $directoryTestCases))
    }
    $summaryLines.Add((Format-TotalsRow -Label '**Total**' -TestCases $allTestCases))

    $failedTestNames = @($allTestCases | Where-Object Outcome -eq 'Failed' | ForEach-Object Name)
    if ($failedTestNames.Count -gt 0) {
        $summaryLines.Add('')
        $summaryLines.Add("### Failed tests ($($failedTestNames.Count))")
        $summaryLines.Add('')
        foreach ($failedTestName in $failedTestNames | Select-Object -First $maximumListedFailures) {
            $summaryLines.Add("- $failedTestName")
        }
        if ($failedTestNames.Count -gt $maximumListedFailures) {
            $summaryLines.Add("- ...and $($failedTestNames.Count - $maximumListedFailures) more; see the uploaded reports.")
        }
    }

    $slowestSuites = @($suiteResults | Sort-Object -Property DurationInSeconds -Descending |
        Select-Object -First $maximumListedSlowSuites)
    if ($slowestSuites.Count -gt 0) {
        $summaryLines.Add('')
        $summaryLines.Add("### Slowest suites")
        $summaryLines.Add('')
        $summaryLines.Add('| Suite | Tests | Duration |')
        $summaryLines.Add('|---|---|---|')
        foreach ($slowSuite in $slowestSuites) {
            $summaryLines.Add(('| {0} | {1} | {2} |' -f $slowSuite.SuiteName, $slowSuite.TestCases.Count,
                (Format-Duration $slowSuite.DurationInSeconds)))
        }
    }

    if ($unreadableResultPaths.Count -gt 0) {
        $summaryLines.Add('')
        $summaryLines.Add('### Unreadable result files')
        $summaryLines.Add('')
        foreach ($unreadableResultPath in $unreadableResultPaths) {
            $summaryLines.Add("- $unreadableResultPath")
        }
    }
}

$summaryMarkdown = $summaryLines -join [Environment]::NewLine
if ([string]::IsNullOrWhiteSpace($SummaryPath)) {
    Write-Output $summaryMarkdown
} else {
    Add-Content -LiteralPath $SummaryPath -Value $summaryMarkdown -Encoding utf8
}
