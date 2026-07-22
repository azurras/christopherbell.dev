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
[Environment]::SetEnvironmentVariable(
    'APP_SHARED_FOLDER_ENABLED', 'false', 'Process')
$allowed = @(
    'APP_JWT_SECRET',
    'RESEND_API_KEY',
    'APP_MAIL_FROM',
    'SPRING_MONGODB_URI',
    'APP_SHARED_FOLDER_ENABLED'
)
foreach ($line in Get-Content -LiteralPath (Join-Path $root 'config\app.env')) {
    if ($line -match '^([A-Z0-9_]+)=(.*)$' -and $allowed -contains $Matches[1]) {
        if ($Matches[1] -eq 'APP_SHARED_FOLDER_ENABLED' -and
            $Matches[2] -notin @('true','false')) {
            throw 'APP_SHARED_FOLDER_ENABLED must be a Boolean.'
        }
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
& $config.javaExe `
    '-Xrs' `
    '--enable-native-access=ALL-UNNAMED' `
    '-jar' `
    (Join-Path $root 'current\app.jar') `
    '--spring.profiles.active=prod' `
    "--server.port=$($config.productionPort)"
exit $LASTEXITCODE
