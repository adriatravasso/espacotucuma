param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string] $AppVersion,
    [string] $OutputDirectory = (Join-Path $PSScriptRoot '..\desktop-app\target\windows-installer')
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome 'bin\jpackage.exe'))) {
    throw 'JDK 21 com jpackage não encontrado. Configure JAVA_HOME.'
}
$jpackage = Join-Path $javaHome 'bin\jpackage.exe'
$jpackageVersion = & $jpackage --version
if ($LASTEXITCODE -ne 0 -or $jpackageVersion -notmatch '^21\.') {
    throw "O empacotamento requer JDK 21; encontrado: $jpackageVersion"
}

$wixTools = @('candle.exe', 'light.exe') | ForEach-Object { Get-Command $_ -ErrorAction SilentlyContinue }
if ($wixTools.Count -ne 2) {
    throw 'WiX Toolset 3.x (candle.exe e light.exe no PATH) é necessário para gerar o MSI.'
}

$output = [System.IO.Path]::GetFullPath($OutputDirectory, $repoRoot)
$input = Join-Path $repoRoot 'desktop-app\target\jpackage-input'
$appName = 'Tucumã — Gestão'
$mainClass = 'br.com.espacotucuma.desktop.DesktopLauncher'
$icon = Join-Path $repoRoot 'desktop-app\src\main\deploy\windows\tucuma.ico'
if (-not (Test-Path $icon)) { throw "Ícone não encontrado: $icon" }
New-Item -ItemType Directory -Path $output -Force | Out-Null
if (Test-Path $input) { Remove-Item -LiteralPath $input -Recurse -Force }
New-Item -ItemType Directory -Path $input -Force | Out-Null

Push-Location $repoRoot
try {
    & .\mvnw.cmd --batch-mode --no-transfer-progress -pl desktop-app -am clean package dependency:copy-dependencies "-DincludeScope=runtime" "-DoutputDirectory=$input"
    if ($LASTEXITCODE -ne 0) { throw "Build Maven falhou com código $LASTEXITCODE" }
} finally {
    Pop-Location
}

$appJar = Get-ChildItem (Join-Path $repoRoot 'desktop-app\target') -File -Filter 'desktop-app-*.jar' |
    Where-Object { $_.Name -notmatch '(-sources|-javadoc)\.jar$' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $appJar) { throw 'JAR desktop-app não foi produzido.' }
Copy-Item -LiteralPath $appJar.FullName -Destination $input

$jpackageArgs = @(
    '--type', 'msi',
    '--dest', $output,
    '--input', $input,
    '--name', $appName,
    '--app-version', $AppVersion,
    '--vendor', 'Espaço Tucumã',
    '--description', 'Gestão local offline-first para o Espaço Tucumã',
    '--main-jar', $appJar.Name,
    '--main-class', $mainClass,
    '--icon', $icon,
    '--add-modules', 'java.base,java.desktop,java.logging,java.naming,java.net.http,java.prefs,java.sql,java.xml,jdk.crypto.ec,jdk.unsupported',
    '--win-upgrade-uuid', '4f9323ec-ec1e-4d5b-9edc-5bfbac7a62ab',
    '--win-per-user-install',
    '--win-menu',
    '--win-shortcut'
)
& $jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage falhou com código $LASTEXITCODE" }

$msi = Join-Path $output "$appName-$AppVersion.msi"
if (-not (Test-Path $msi)) { throw "MSI não foi gerado: $msi" }
Write-Output "MSI gerado: $msi"
