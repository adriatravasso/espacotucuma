param(
    [string] $OutputDirectory = (Join-Path $env:TEMP ("espacotucuma-installer-smoke-" + [guid]::NewGuid().ToString('N')))
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$output = [System.IO.Path]::GetFullPath($OutputDirectory, $repoRoot)
$work = Join-Path $output 'validation'
$installDirectory = Join-Path $output 'installed-app'
$dataRoot = Join-Path $output 'external-user-data'
$javaHome = $env:JAVA_HOME
$java = Join-Path $javaHome 'bin\java.exe'
$javac = Join-Path $javaHome 'bin\javac.exe'
$jars = Join-Path $repoRoot 'desktop-app\target\jpackage-input'
$verifierSource = Join-Path $work 'PackageDatabaseVerifier.java'
$marker = Join-Path $dataRoot 'test\tucuma.sqlite'
$appName = 'Tucumã — Gestão'
$msiOne = Join-Path $output "$appName-1.0.0.msi"
$msiTwo = Join-Path $output "$appName-1.0.1.msi"
$installed = $false
$installedMsi = $null

function Assert-NativeExit([string] $Action) {
    if ($LASTEXITCODE -ne 0) { throw "$Action terminou com código $LASTEXITCODE" }
}

function Install-Package([string] $Path) {
    $args = @('/i', $Path, '/quiet', '/norestart', 'INSTALLDIR=' + $installDirectory)
    & msiexec.exe @args
    if ($LASTEXITCODE -notin @(0, 3010)) { throw "Instalação de $Path falhou: $LASTEXITCODE" }
    $script:installed = $true
    $script:installedMsi = $Path
}

function Invoke-PackagedSmoke {
    $exe = Join-Path $installDirectory "$appName.exe"
    if (-not (Test-Path $exe)) { throw "Executável instalado não encontrado: $exe" }
    $env:TUCUMA_PROFILE = 'test'
    $env:TUCUMA_DATA_ROOT = $dataRoot
    $process = Start-Process -FilePath $exe -ArgumentList '--smoke-test' -PassThru -Wait
    if ($process.ExitCode -ne 0) { throw "Aplicação instalada falhou: $($process.ExitCode)" }
    if (-not (Test-Path $marker)) { throw 'Aplicação não criou o banco no diretório externo de dados.' }
    $clientId = & $java -cp "$jars\*" PackageDatabaseVerifier $marker
    Assert-NativeExit 'Verificação do banco após abertura offline'
    return $clientId.Trim()
}

New-Item -ItemType Directory -Path $work -Force | Out-Null
New-Item -ItemType Directory -Path $output -Force | Out-Null

@'
import java.sql.DriverManager;
public final class PackageDatabaseVerifier {
  public static void main(String[] args) throws Exception {
    String clientId;
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + args[0]);
         var s = c.createStatement();
         var r = s.executeQuery("SELECT id FROM cliente WHERE nome = 'Cliente de validação do pacote'")) {
      if (!r.next()) throw new IllegalStateException("Cadastro sintético ausente após reinício/upgrade");
      clientId = r.getString(1);
      if (r.next()) throw new IllegalStateException("Cadastro sintético duplicado após reinício/upgrade");
    }
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + args[0]);
         var s = c.createStatement();
         var r = s.executeQuery("PRAGMA quick_check")) {
      if (!r.next() || !"ok".equals(r.getString(1))) throw new IllegalStateException("SQLite quick_check falhou");
    }
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + args[0]);
         var s = c.createStatement();
         var r = s.executeQuery("SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success = 1")) {
      if (!r.next() || r.getInt(1) < 22) throw new IllegalStateException("Migrações SQLite esperadas não foram preservadas");
    }
    System.out.println(clientId);
  }
}
'@ | ForEach-Object {
    [System.IO.File]::WriteAllText($verifierSource, $_, [System.Text.UTF8Encoding]::new($false))
}

Push-Location $work
try {
    & (Join-Path $PSScriptRoot 'package-windows.ps1') -AppVersion '1.0.0' -OutputDirectory $output
    & $javac -cp "$jars\*" $verifierSource
    Assert-NativeExit 'Compilação do verificador de SQLite'
} finally { Pop-Location }

try {
    Install-Package $msiOne
    $clientIdBeforeUpgrade = Invoke-PackagedSmoke

    & (Join-Path $PSScriptRoot 'package-windows.ps1') -AppVersion '1.0.1' -OutputDirectory $output
    Install-Package $msiTwo
    $clientIdAfterUpgrade = Invoke-PackagedSmoke
    if ($clientIdAfterUpgrade -ne $clientIdBeforeUpgrade) {
        throw 'Upgrade alterou o cadastro ou substituiu o banco SQLite externo.'
    }

    & msiexec.exe /x $msiTwo /quiet /norestart
    if ($LASTEXITCODE -notin @(0, 3010)) { throw "Desinstalação falhou: $LASTEXITCODE" }
    $installed = $false
    if (-not (Test-Path $marker)) { throw 'Desinstalação removeu inadvertidamente o banco externo.' }
    $clientIdAfterUninstall = & $java -cp "$jars\*" PackageDatabaseVerifier $marker
    Assert-NativeExit 'Verificação do banco após desinstalação'
    if ($clientIdAfterUninstall.Trim() -ne $clientIdBeforeUpgrade) {
        throw 'Desinstalação alterou os dados do usuário.'
    }
    Write-Output 'Instalação, abertura offline, persistência, upgrade e desinstalação validados.'
} finally {
    if ($installed -and $installedMsi) {
        & msiexec.exe /x $installedMsi /quiet /norestart | Out-Null
    }
    Remove-Item Env:TUCUMA_PROFILE -ErrorAction SilentlyContinue
    Remove-Item Env:TUCUMA_DATA_ROOT -ErrorAction SilentlyContinue
}
