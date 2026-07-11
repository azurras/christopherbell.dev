$ErrorActionPreference = 'Stop'
$root = 'C:\ProgramData\christopherbell.dev'
$config = Get-Content -LiteralPath (Join-Path $root 'config\deploy.json') -Raw | ConvertFrom-Json
$allowed = @('APP_JWT_SECRET','RESEND_API_KEY','APP_MAIL_FROM','SPRING_MONGODB_URI')
foreach ($line in Get-Content -LiteralPath (Join-Path $root 'config\app.env')) {
    if ($line -match '^([A-Z0-9_]+)=(.*)$' -and $allowed -contains $Matches[1]) {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
& $config.javaExe '-Xrs' '-jar' (Join-Path $root 'current\app.jar') '--spring.profiles.active=prod' "--server.port=$($config.productionPort)"
exit $LASTEXITCODE
