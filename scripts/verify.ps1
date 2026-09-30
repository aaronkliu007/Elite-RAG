$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$uvCandidate = Join-Path $env:APPDATA 'Python\Python312\Scripts\uv.exe'
$uvCommand = Get-Command uv -ErrorAction SilentlyContinue
$uvExecutable = if ($uvCommand) { $uvCommand.Source } elseif (Test-Path $uvCandidate) { $uvCandidate } else { $null }
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
$licenseFile = if ($env:RAG_MINIO_LICENSE_FILE) {
    $env:RAG_MINIO_LICENSE_FILE
} else {
    'E:\git_secrets\Elite-RAG\minio.license'
}

if (-not $uvExecutable) {
    throw 'uv was not found. Install uv 0.12.18 or add uv to PATH.'
}
if (-not $dockerCommand) {
    throw 'Docker CLI was not found. Install Docker Desktop and add docker to PATH.'
}
if (-not (Test-Path -LiteralPath $licenseFile -PathType Leaf)) {
    throw "AIStor license file was not found at the configured path: $licenseFile"
}

& $dockerCommand.Source info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker daemon is unavailable. Start Docker Desktop before running infrastructure tests.'
}

Push-Location $projectRoot

try {
    & .\mvnw.cmd -B -pl apps/core-api test
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    corepack pnpm --dir apps/web test --run
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    corepack pnpm --dir apps/web build
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    corepack pnpm --dir apps/web lint
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & $uvExecutable sync --project workers/document-parser --extra parsing --frozen
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & $uvExecutable run --project workers/document-parser pytest -W error
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & $uvExecutable run --project workers/document-parser ruff check .
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & $uvExecutable run --project workers/document-parser mypy workers/document-parser/src
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
finally {
    Pop-Location
}
