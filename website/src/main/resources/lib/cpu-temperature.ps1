param(
  [Parameter(Mandatory = $true)]
  [string]$LibreHardwareMonitorPath
)

$computer = $null
try {
  Add-Type -Path $LibreHardwareMonitorPath
  $computer = [LibreHardwareMonitor.Hardware.Computer]::new()
  $computer.IsCpuEnabled = $true
  $computer.Open()
  $values = @()
  foreach ($hardware in $computer.Hardware) {
    if ($hardware.HardwareType -eq [LibreHardwareMonitor.Hardware.HardwareType]::Cpu) {
      $hardware.Update()
      $values += $hardware.Sensors |
          Where-Object {
            $_.SensorType -eq [LibreHardwareMonitor.Hardware.SensorType]::Temperature -and
            $null -ne $_.Value -and
            [double]$_.Value -gt 0
          } |
          ForEach-Object { [double]$_.Value }
      foreach ($subHardware in $hardware.SubHardware) {
        $subHardware.Update()
        $values += $subHardware.Sensors |
            Where-Object {
              $_.SensorType -eq [LibreHardwareMonitor.Hardware.SensorType]::Temperature -and
              $null -ne $_.Value -and
              [double]$_.Value -gt 0
            } |
            ForEach-Object { [double]$_.Value }
      }
    }
  }
  if ($values.Count -gt 0) {
    [Console]::Write(
        ($values | Measure-Object -Maximum).Maximum.ToString(
            [Globalization.CultureInfo]::InvariantCulture))
  }
} finally {
  if ($null -ne $computer) {
    $computer.Close()
  }
}
