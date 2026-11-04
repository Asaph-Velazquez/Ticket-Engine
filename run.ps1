param(
    [switch]$NoBuild,
    [switch]$Logs,
    [switch]$Down
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

function Invoke-DockerCompose {
    param([string[]]$Arguments)
    & docker compose @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose terminó con código $LASTEXITCODE"
    }
}

if ($Down) {
    Invoke-DockerCompose @('down')
    exit 0
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker no está disponible en PATH. Instala Docker Desktop y vuelve a ejecutar este script.'
}

Write-Host 'Inicializando secretos y llaves persistentes...' -ForegroundColor Cyan
Invoke-DockerCompose @('--profile', 'tools', 'run', '--rm', 'secrets-init')

$upArgs = @('up', '-d', '--wait')
if (-not $NoBuild) {
    $upArgs += '--build'
}
$upArgs += @('db', 'migrate', 'app', 'web')

Write-Host 'Construyendo y levantando Ticket Engine...' -ForegroundColor Cyan
Invoke-DockerCompose $upArgs

Write-Host ''
Write-Host 'Ticket Engine está disponible:' -ForegroundColor Green
Write-Host '  Panel web: http://localhost:8085'
Write-Host '  API:       http://localhost:8084'
Write-Host '  Health:    http://localhost:8084/actuator/health'
Write-Host '  Admin:     .secrets/admin-token (no se muestra por seguridad)'

if ($Logs) {
    Invoke-DockerCompose @('logs', '-f', 'app', 'web')
}
