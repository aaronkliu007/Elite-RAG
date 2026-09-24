$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$uvCandidate = Join-Path $env:APPDATA 'Python\Python312\Scripts\uv.exe'
$uvCommand = Get-Command uv -ErrorAction SilentlyContinue
$uvExecutable = if ($uvCommand) { $uvCommand.Source } elseif (Test-Path $uvCandidate) { $uvCandidate } else { $null }

if (-not $uvExecutable) {
    throw 'uv was not found. Install uv 0.12.18 or add uv to PATH.'
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
