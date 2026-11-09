# Enables the versioned git hooks in .githooks/ (pre-commit secret guard).
# Run once after cloning, from the repo folder in PowerShell:
#   powershell -ExecutionPolicy Bypass -File scripts\install-hooks.ps1
$ErrorActionPreference = "Stop"
$root = git rev-parse --show-toplevel
Set-Location $root
git config core.hooksPath .githooks
Write-Host "Pre-commit secret guard enabled (core.hooksPath=.githooks)."
if (-not (Get-Command gitleaks -ErrorAction SilentlyContinue)) {
    Write-Host "Tip: install gitleaks for full scanning:  winget install gitleaks.gitleaks"
}
