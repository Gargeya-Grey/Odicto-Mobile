<#
Creates the Odicto release signing key once and stores it as GitHub Actions secrets.

The owner runs this; no agent should. The key never enters the repository.
Every future release must use this same key, or installed copies cannot update.
Back up the folder it prints (key + password) to a password manager or another safe place.

Safe to run again: an existing key is reused, never replaced, and the secrets are set again.

Usage: powershell -ExecutionPolicy Bypass -File tools/release/create-signing-key.ps1
Needs: JDK 21 keytool (first run only), and gh signed in as -Account (it need not be the active account).
#>
param(
    [string]$OutDir = (Join-Path $HOME '.odicto-release'),
    [string]$Repo = 'Gargeya-Grey/Odicto-Mobile',
    [string]$Account = 'Gargeya-Grey',
    [string]$Alias = 'odicto'
)
$ErrorActionPreference = 'Stop'

$keystore = Join-Path $OutDir 'odicto-release.p12'
$passwordFile = Join-Path $OutDir 'odicto-release-password.txt'

# Use the repository owner's login for this script only; the active gh account stays unchanged.
$token = gh auth token --user $Account
if ($LASTEXITCODE -ne 0 -or -not $token) { throw "gh has no login for $Account. Run: gh auth login" }
$env:GH_TOKEN = $token

if (Test-Path $keystore) {
    if (-not (Test-Path $passwordFile)) { throw "$keystore exists but $passwordFile is missing. Restore it from your backup." }
    $saved = @{}
    foreach ($line in Get-Content $passwordFile) { $k, $v = $line -split '=', 2; $saved[$k] = $v }
    if (-not $saved['password'] -or -not $saved['alias']) { throw "$passwordFile is not in alias=/password= form." }
    $password = $saved['password']
    $Alias = $saved['alias']
    Write-Host "Reusing the existing key at $keystore."
} else {
    $keytool = Get-Command keytool -ErrorAction SilentlyContinue
    if (-not $keytool -and $env:JAVA_HOME) { $keytool = Get-Command (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -ErrorAction SilentlyContinue }
    if (-not $keytool) { throw 'keytool not found. Put JDK 21 bin on PATH or set JAVA_HOME.' }

    New-Item -ItemType Directory -Force $OutDir | Out-Null
    # PKCS12 uses one password for the store and the key.
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $password = [Convert]::ToBase64String($bytes) -replace '[+/=]', 'x'

    & $keytool.Source -genkeypair -storetype PKCS12 -keystore $keystore -alias $Alias `
        -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=Odicto, O=Odicto' `
        -storepass $password -keypass $password
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
    Set-Content -Path $passwordFile -Value "alias=$Alias`npassword=$password" -Encoding ascii -NoNewline
}

# --body keeps the exact value (piped stdin can add a trailing newline in Windows PowerShell).
$secrets = [ordered]@{
    ODICTO_KEYSTORE_BASE64   = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore))
    ODICTO_KEYSTORE_PASSWORD = $password
    ODICTO_KEY_PASSWORD      = $password
    ODICTO_KEY_ALIAS         = $Alias
}
foreach ($name in $secrets.Keys) {
    gh secret set $name --repo $Repo --body $secrets[$name]
    if ($LASTEXITCODE -ne 0) { throw "gh secret set $name failed. The key is saved in $OutDir; run this script again." }
}

Write-Host ""
Write-Host "Done. Four secrets are set on $Repo."
Write-Host "BACK UP this folder now (key + password): $OutDir"
Write-Host "Losing it means testers must uninstall to get later versions."
