<#
Creates the Odicto release signing key once and stores it as GitHub Actions secrets.

The owner runs this; no agent should. The key never enters the repository.
Every future release must use this same key, or installed copies cannot update.
Back up the folder it prints (key + password) to a password manager or another safe place.

Usage: powershell -ExecutionPolicy Bypass -File tools/release/create-signing-key.ps1
Needs: JDK 21 keytool, and gh signed in with access to the repository.
#>
param(
    [string]$OutDir = (Join-Path $HOME '.odicto-release'),
    [string]$Repo = 'Gargeya-Grey/Odicto-Mobile',
    [string]$Alias = 'odicto'
)
$ErrorActionPreference = 'Stop'

$keystore = Join-Path $OutDir 'odicto-release.p12'
if (Test-Path $keystore) {
    throw "A key already exists at $keystore. Do not replace it: releases signed with a new key cannot update existing installs."
}

$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if (-not $keytool -and $env:JAVA_HOME) { $keytool = Get-Command (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -ErrorAction SilentlyContinue }
if (-not $keytool) { throw 'keytool not found. Put JDK 21 bin on PATH or set JAVA_HOME.' }
gh auth status | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'gh is not signed in. Run gh auth login first.' }

New-Item -ItemType Directory -Force $OutDir | Out-Null
# PKCS12 uses one password for the store and the key.
$bytes = New-Object byte[] 24
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$password = [Convert]::ToBase64String($bytes) -replace '[+/=]', 'x'

& $keytool.Source -genkeypair -storetype PKCS12 -keystore $keystore -alias $Alias `
    -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=Odicto, O=Odicto' `
    -storepass $password -keypass $password
if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }

$passwordFile = Join-Path $OutDir 'odicto-release-password.txt'
Set-Content -Path $passwordFile -Value "alias=$Alias`npassword=$password" -Encoding ascii -NoNewline

# --body keeps the exact value (piped stdin can add a trailing newline in Windows PowerShell).
$secrets = [ordered]@{
    ODICTO_KEYSTORE_BASE64   = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore))
    ODICTO_KEYSTORE_PASSWORD = $password
    ODICTO_KEY_PASSWORD      = $password
    ODICTO_KEY_ALIAS         = $Alias
}
foreach ($name in $secrets.Keys) {
    gh secret set $name --repo $Repo --body $secrets[$name]
    if ($LASTEXITCODE -ne 0) { throw "gh secret set $name failed. The key is saved in $OutDir; rerun the four gh secret set commands by hand." }
}

Write-Host ""
Write-Host "Done. Four secrets are set on $Repo."
Write-Host "BACK UP this folder now (key + password): $OutDir"
Write-Host "Losing it means testers must uninstall to get later versions."
