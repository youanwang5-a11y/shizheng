[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$InputApk,
    [Parameter(Mandatory)][string]$OutputApk,
    [Parameter(Mandatory)][string]$AndroidSdk,
    [string]$SigningDirectory = (Join-Path $env:LOCALAPPDATA 'Shizheng/signing')
)

$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17 before signing.' }
$buildTools = Join-Path $AndroidSdk 'build-tools/35.0.0'
$signer = Join-Path $buildTools 'apksigner.bat'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$source = (Resolve-Path -LiteralPath $InputApk).Path
$output = [IO.Path]::GetFullPath($OutputApk)
if (Test-Path -LiteralPath $output) { throw 'Choose a new output file to preserve the previous APK.' }
$keystore = (Resolve-Path -LiteralPath (Join-Path $SigningDirectory 'shizheng-release.p12')).Path
$encoded = Get-Content -LiteralPath (Join-Path $SigningDirectory 'password.dpapi') -Raw
$secret = ConvertTo-SecureString $encoded.Trim()
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
$aligned = Join-Path ([IO.Path]::GetTempPath()) ('shizheng-aligned-' + [guid]::NewGuid() + '.apk')
try {
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($output)) -Force | Out-Null
    & $zipalign -f -P 16 4 $source $aligned
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed.' }
    $env:SHIZHENG_RELEASE_STORE_PASS = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    & $signer sign --ks $keystore --ks-type PKCS12 --ks-key-alias shizheng-release --ks-pass env:SHIZHENG_RELEASE_STORE_PASS --key-pass env:SHIZHENG_RELEASE_STORE_PASS --out $output $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & $signer verify --verbose --print-certs $output
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    Get-FileHash -LiteralPath $output -Algorithm SHA256
} finally {
    Remove-Item Env:SHIZHENG_RELEASE_STORE_PASS -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $secret.Dispose()
    if (Test-Path -LiteralPath $aligned) { Remove-Item -LiteralPath $aligned }
}
