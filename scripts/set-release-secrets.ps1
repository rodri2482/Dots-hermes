# Stores the Claydot release signing key as GitHub Actions secrets, once.
# Run it yourself in PowerShell (it asks for the passwords; nothing is printed or saved to disk):
#   powershell -ExecutionPolicy Bypass -File scripts\set-release-secrets.ps1
# Needs the GitHub CLI signed in as the repository owner (gh auth status).
param(
    [string]$Keystore = "$env:USERPROFILE\Documents\Claydot-signing\claydot-release.jks",
    [string]$Alias = "claydot",
    [string]$Repo = "rodri2482/Dots-hermes"
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path $Keystore)) { throw "Keystore not found: $Keystore" }

function Read-Secret([string]$prompt) {
    $secure = Read-Host -AsSecureString $prompt
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

$store = Read-Secret "Keystore password"
$key = Read-Secret "Key password (press Enter if it is the same)"
if ([string]::IsNullOrEmpty($key)) { $key = $store }

# --body, not a pipe: PowerShell would add a line break to each value.
gh secret set SIGNING_KEYSTORE_BASE64 -R $Repo --body ([Convert]::ToBase64String([IO.File]::ReadAllBytes($Keystore)))
gh secret set SIGNING_STORE_PASSWORD -R $Repo --body $store
gh secret set SIGNING_KEY_ALIAS -R $Repo --body $Alias
gh secret set SIGNING_KEY_PASSWORD -R $Repo --body $key
Remove-Variable store, key

gh secret list -R $Repo
Write-Host "Done. Publish a release with: git tag -a v0.3.0.N -m 'What changed'; git push origin v0.3.0.N"
