$ErrorActionPreference = 'Stop'
$root = 'C:\ProgramData\christopherbell.dev'
$config = Get-Content -LiteralPath (Join-Path $root 'config\deploy.json') -Raw | ConvertFrom-Json
$sensorProperty = $config.PSObject.Properties['sensorLibrariesEnabled']
if (-not $sensorProperty -or $sensorProperty.Value -isnot [bool]) {
    throw 'deploy.json sensorLibrariesEnabled must be a Boolean.'
}
$sensorLibrariesEnabled = if ($sensorProperty.Value) { 'true' } else { 'false' }
[Environment]::SetEnvironmentVariable(
    'COMMAND_CENTER_SENSOR_LIBRARIES_ENABLED', $sensorLibrariesEnabled, 'Process')
$allowed = @('APP_JWT_SECRET','RESEND_API_KEY','APP_MAIL_FROM','SPRING_MONGODB_URI')
foreach ($line in Get-Content -LiteralPath (Join-Path $root 'config\app.env')) {
    if ($line -match '^([A-Z0-9_]+)=(.*)$' -and $allowed -contains $Matches[1]) {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
& $config.javaExe '-Xrs' '-jar' (Join-Path $root 'current\app.jar') '--spring.profiles.active=prod' "--server.port=$($config.productionPort)"
exit $LASTEXITCODE
