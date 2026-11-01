$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot '..\mvnw.cmd') --batch-mode clean verify
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
