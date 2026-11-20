$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$uvCandidate = Join-Path $env:APPDATA 'Python\Python312\Scripts\uv.exe'
$uvCommand = Get-Command uv -ErrorAction SilentlyContinue
$uvExecutable = if ($uvCommand) { $uvCommand.Source } elseif (Test-Path $uvCandidate) { $uvCandidate } else { $null }
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
$mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
$licenseFile = $env:RAG_MINIO_LICENSE_FILE

if (-not $uvExecutable) {
    throw 'uv was not found. Install uv 0.12.18 or add uv to PATH.'
}
if (-not $dockerCommand) {
    throw 'Docker CLI was not found. Install Docker Desktop and add docker to PATH.'
}
if ([string]::IsNullOrWhiteSpace($licenseFile) -or -not (Test-Path -LiteralPath $licenseFile -PathType Leaf)) {
    throw 'Set RAG_MINIO_LICENSE_FILE to an existing AIStor license file outside the repository.'
}

& $dockerCommand.Source info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker daemon is unavailable. Start Docker Desktop before running infrastructure tests.'
}

Push-Location $projectRoot

try {
    & $dockerCommand.Source compose --env-file infra/compose/.env.example -f infra/compose/compose.yaml config --quiet
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    if ($mavenCommand) {
        & $mavenCommand.Source -B clean verify
    } else {
        & .\mvnw.cmd -B clean verify
    }
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    $groundedQaAcceptanceReports = @(
        'com.yunjihai.rag.acceptance.GroundedQaAcceptanceTest.txt',
        'com.yunjihai.rag.acceptance.GroundedQaSecurityIntegrationTest.txt',
        'com.yunjihai.rag.acceptance.GroundedQaModelGatewayIntegrationTest.txt'
    )
    foreach ($report in $groundedQaAcceptanceReports) {
        $reportPath = Join-Path $projectRoot "apps\core-api\target\surefire-reports\$report"
        if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
            throw "Required Grounded QA acceptance report is missing: $report"
        }
    }

    corepack pnpm test
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    corepack pnpm build
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    corepack pnpm lint
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & $uvExecutable sync --project workers/document-parser --extra parsing --frozen
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & $uvExecutable run --project workers/document-parser pytest -W error
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & $uvExecutable run --project workers/document-parser ruff check workers/document-parser
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    & $uvExecutable run --project workers/document-parser mypy workers/document-parser/src
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
finally {
    Pop-Location
}
