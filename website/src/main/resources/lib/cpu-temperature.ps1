param(
  [Parameter(Mandatory = $true)]
  [string]$LibreHardwareMonitorPath
)

$ErrorActionPreference = 'Stop'

function Select-CpuTemperature {
  param([Parameter(Mandatory)][object[]]$Sensors)

  $actual = @($Sensors | Where-Object {
    $value = [double]$_.Value
    -not [double]::IsNaN($value) -and
    -not [double]::IsInfinity($value) -and
    $value -gt 0 -and
    $value -le 125 -and
    [string]$_.Name -notmatch '(?i)\s+Distance to TjMax$'
  })
  foreach ($preferredName in 'CPU Package','Core Max') {
    $preferred = @($actual | Where-Object Name -eq $preferredName)
    if ($preferred.Count -gt 0) {
      return ($preferred | Measure-Object -Property Value -Maximum).Maximum -as [double]
    }
  }
  if ($actual.Count -gt 0) {
    return ($actual | Measure-Object -Property Value -Maximum).Maximum -as [double]
  }
  return $null
}

$pawnIo = Get-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\PawnIO' -ErrorAction SilentlyContinue
if (-not $pawnIo -or [string]$pawnIo.DisplayVersion -ne '2.2.0.0') {
  [Console]::Error.Write('PawnIO 2.2.0.0 is unavailable.')
  exit 3
}

$computer = $null
try {
  Add-Type -Path $LibreHardwareMonitorPath
  $computer = [LibreHardwareMonitor.Hardware.Computer]::new()
  $computer.IsCpuEnabled = $true
  $computer.Open()
  $sensors = @()
  foreach ($hardware in $computer.Hardware) {
    if ($hardware.HardwareType -eq [LibreHardwareMonitor.Hardware.HardwareType]::Cpu) {
      $hardware.Update()
      $sensors += $hardware.Sensors |
          Where-Object {
            $_.SensorType -eq [LibreHardwareMonitor.Hardware.SensorType]::Temperature
          } | ForEach-Object {
            [pscustomobject]@{ Name=[string]$_.Name; Value=$_.Value }
          }
      foreach ($subHardware in $hardware.SubHardware) {
        $subHardware.Update()
        $sensors += $subHardware.Sensors |
            Where-Object {
              $_.SensorType -eq [LibreHardwareMonitor.Hardware.SensorType]::Temperature
            } | ForEach-Object {
              [pscustomobject]@{ Name=[string]$_.Name; Value=$_.Value }
            }
      }
    }
  }
  $selected = Select-CpuTemperature $sensors
  if ($null -ne $selected) {
    [Console]::Write($selected.ToString(
        [Globalization.CultureInfo]::InvariantCulture))
  }
} finally {
  if ($null -ne $computer) { $computer.Close() }
}
