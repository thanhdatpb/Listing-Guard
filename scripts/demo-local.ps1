[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[^\s,"]+@[^\s,"]+$')]
    [string] $AdminEmail,
    [ValidateRange(1, 12)]
    [int] $Weeks = 6,
    [long] $Seed = 42,
    [switch] $Reset
)

$ErrorActionPreference = 'Stop'

function Invoke-Compose {
    param([string[]] $Arguments)
    & docker compose --env-file .env.demo @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose thất bại (exit $LASTEXITCODE)." }
}

Push-Location (Join-Path $PSScriptRoot '..')
try {
    if (-not (Test-Path .env.demo)) { throw 'Tạo .env.demo bằng cách sao chép deploy/env.demo-local.example.' }
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Cài Docker Desktop rồi mở lại PowerShell.' }
    & docker info --format '{{.OSType}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Mở Docker Desktop, chờ Docker Engine sẵn sàng rồi chạy lại.' }

    $drive = Get-Item .
    if ($drive.PSDrive.Free -lt 5GB) { throw 'Ổ chứa repository cần tối thiểu 5 GB trống. Kiểm tra thêm ổ đĩa chứa Docker Desktop.' }
    $effective = ((Invoke-Compose -Arguments @('config', '--format', 'json')) -join "`n") | ConvertFrom-Json
    $api = $effective.services.api
    $databasePorts = @($effective.services.postgres.ports)
    $apiPorts = @($api.ports)
    if ($effective.name -ne 'listing-guard-demo' -or $apiPorts.Count -ne 1 -or
        $apiPorts[0].host_ip -ne '127.0.0.1' -or $databasePorts.Count -ne 1 -or
        $databasePorts[0].host_ip -ne '127.0.0.1' -or $api.environment.SPRING_PROFILES_ACTIVE -ne 'dev' -or
        $api.environment.LISTING_GUARD_DEMO_ENABLED -ne 'true') {
        throw 'Cấu hình phải dùng project listing-guard-demo, chỉ bind localhost và bật demo với profile dev.'
    }
    $base = "http://localhost:$($apiPorts[0].published)"

    Invoke-Compose -Arguments @('up', '-d', '--build')
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "$base/health" -TimeoutSec 3
            if ($response.StatusCode -eq 200) { $ready = $true; break }
        } catch {
            Start-Sleep -Seconds 3
        }
    }
    if (-not $ready) { throw 'API chưa sẵn sàng. Chạy docker compose --env-file .env.demo logs --tail=100 api.' }

    if ($Reset) {
        Invoke-Compose -Arguments @('--profile', 'tools', 'run', '--rm', 'admin', 'demo', 'reset')
    }
    Invoke-Compose -Arguments @('--profile', 'tools', 'run', '--rm', 'admin', 'demo', 'seed', "--weeks=$Weeks", "--seed=$Seed", "--admin-email=$AdminEmail")

    Write-Host "Console: $base/console"
    Write-Host 'Nhập email quản trị demo trên trang đăng nhập, rồi lấy magic link từ log cục bộ:'
    Write-Host '  docker compose --env-file .env.demo logs --tail=100 api'
} finally {
    Pop-Location
}
