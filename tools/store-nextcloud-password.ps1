# Stores a Nextcloud app password in Windows Credential Manager.
# Run on BookahPC, in PowerShell as Bookah.  Usage:
#   powershell -ExecutionPolicy Bypass -File store-nextcloud-password.ps1 BookahPhotos-indexer
# The first argument is the Credential Manager target name (default BookahPhotos-nextcloud, the Phase 0 test password).
# The password is typed hidden and never written to a file.
param([string]$Target = "BookahPhotos-nextcloud")
$user = Read-Host "Nextcloud user name (type: <user>)"
$secure = Read-Host "Paste the app password (it will be hidden)" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    cmdkey /generic:$Target /user:$user /pass:$plain | Out-Null
    if ($LASTEXITCODE -eq 0) { Write-Host "Saved to Credential Manager as $Target." }
    else { Write-Host "cmdkey failed, exit code $LASTEXITCODE" }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    $plain = $null
}
