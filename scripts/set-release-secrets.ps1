# Stores the Claydot release signing key as GitHub Actions secrets, once.
# Run it yourself in PowerShell. It reads the passwords from README-PASSWORDS.txt next to the keystore
# ("Store password: …", "Key password: …"), or asks for them if that file is missing; nothing is printed or saved:
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

# Typing 28 random characters into a hidden prompt went wrong twice ("keystore password was incorrect" on
# GitHub): read them from the owner's password file when it is there.
$notes = Join-Path (Split-Path $Keystore) "README-PASSWORDS.txt"
function From-Notes([string]$label) {
    if (-not (Test-Path $notes)) { return $null }
    $line = Get-Content $notes | Where-Object { $_ -match "^$label\s*:" } | Select-Object -First 1
    if ($line) { return ($line -replace "^$label\s*:\s*", "").Trim() } else { return $null }
}
$store = From-Notes "Store password"
$key = From-Notes "Key password"
if ($store) { Write-Host "Passwords read from $notes" }
else {
    $store = Read-Secret "Keystore password"
    $key = Read-Secret "Key password (press Enter if it is the same)"
}
if ([string]::IsNullOrEmpty($key)) { $key = $store }
$fromNotes = From-Notes "Alias"
if ($fromNotes) { $Alias = $fromNotes }

# Check the passwords against the keystore before sending them, with the JDK's keytool when it is found.
$keytool = Get-ChildItem -Path "$env:JAVA_HOME\bin\keytool.exe", "$env:USERPROFILE\Documents\Codex\*\*\*\tools\jdk\*\bin\keytool.exe", "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($keytool) {
    $ErrorActionPreference = "Continue"
    & $keytool.FullName -list -keystore $Keystore -storepass $store -alias $Alias *> $null
    $ok = $LASTEXITCODE -eq 0
    $ErrorActionPreference = "Stop"
    if (-not $ok) { throw "The keystore password does not open $Keystore (alias $Alias): nothing was sent to GitHub." }
    Write-Host "Keystore password checked."
}

# --body, not a pipe: PowerShell would add a line break to each value.
gh secret set SIGNING_KEYSTORE_BASE64 -R $Repo --body ([Convert]::ToBase64String([IO.File]::ReadAllBytes($Keystore)))
gh secret set SIGNING_STORE_PASSWORD -R $Repo --body $store
gh secret set SIGNING_KEY_ALIAS -R $Repo --body $Alias
gh secret set SIGNING_KEY_PASSWORD -R $Repo --body $key
Remove-Variable store, key

gh secret list -R $Repo
Write-Host "Done. Publish a release with: git tag -a v0.3.0.N -m 'What changed'; git push origin v0.3.0.N"
