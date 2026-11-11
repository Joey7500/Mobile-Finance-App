# Security & the "vault": where every secret lives

VaultFlow is in a **public** repository. The rule is simple: **the repository
contains code only.** Every key, password and piece of financial data lives
on your phone or in a git-ignored file on your PC.

## 1. Inventory of secrets

| Secret / personal data | Where it lives | Can it reach GitHub? |
|---|---|---|
| Trading 212 API key + secret | Typed into the app → encrypted with the phone's hardware **Android Keystore** | No: not in code, Gradle, BuildConfig, logs or backups |
| Gmail address + App Password | Same Keystore vault; IMAP read-only. Revoke at myaccount.google.com/apppasswords | No |
| Transactions, balances, budgets, rules, own account digits | **SQLCipher** AES-256 database on the phone; its key is wrapped by the Keystore | No |
| Backup password | Same Keystore vault; needed to open any backup – keep a copy in your password manager | No: not in code, backups or logs |
| Google Drive access | A `drive.file` token held by Google Play services; the app sees only its own backup folder. Revoke at myaccount.google.com/permissions | No |
| Raw notification text (capture log) | Same encrypted DB, auto-deleted after 1 year, can be switched off or cleared | No |
| Release signing key `*.jks` | Your PC, **outside** the repo folder + password manager backup | No: `.gitignore` + pre-commit hook block `*.jks` |
| `keystore.properties` (signing passwords) | Repo root on your PC | No: git-ignored + hook blocks it |
| `local.properties` (SDK path) | Repo root on your PC, created by Android Studio | No: git-ignored + hook blocks it |
| Encrypted backups `*.vfbackup` | Your Google Drive (`VaultFlow backups`, encrypted on the phone before upload) or wherever you export them | No: git-ignored + hook blocks it |
| GitHub Actions signing secrets | GitHub → Settings → Secrets (encrypted by GitHub) | Never written to files or logs |

## 2. Layers that stop leaks

1. **Nothing to leak by design**: the app has no build-time keys. API keys are
   entered at runtime, so the source code never needs them.
2. **`.gitignore`**: keystores, properties files, DB files, CSV/XLSX statement
   exports, backups and `*.local.json`.
3. **Pre-commit hook** (`.githooks/pre-commit`, enable with `scripts/install-hooks.ps1`):
   - refuses forbidden file types *by name*,
   - refuses staged lines that look like a **Czech/Slovak IBAN** or **Czech account number**,
   - runs **gitleaks** with `.gitleaks.toml` when installed (default rules +
     IBAN, account number, Trading 212 key, Basic-auth header and signing-password rules).
4. **CI secret scan**: every push runs gitleaks over the **entire git
   history** on GitHub. A red *Secret & PII scan* job means stop and fix.
5. **Demo Mode** uses an in-memory DB with synthetic data, so screenshots for
   the README never show real numbers. On top of that, `FLAG_SECURE` blocks
   screenshots of the real app entirely.

## 3. Your checklist before every `git push`

```powershell
git status          # nothing like keystore.properties, *.jks, *.csv, *.vfbackup listed?
git diff --cached   # skim what you are about to commit
git commit -m "..." # the hook runs automatically
git push            # then check the Actions tab: both jobs green
```

Never use `git commit --no-verify`: it skips the guard.

## 4. Signing secrets on GitHub (optional)

Only needed if you want GitHub to build signed release APKs
(`.github/workflows/release.yml`).

1. Create your keystore first (SETUP.md, Part 6).
2. Convert it to text and copy it to the clipboard (PowerShell):
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\Users\josef\keys\vaultflow-release.jks")) | Set-Clipboard
   ```
3. GitHub → your repo → **Settings → Secrets and variables → Actions → New repository secret**.
   Create these four:

   | Name | Value |
   |---|---|
   | `VAULTFLOW_KEYSTORE_BASE64` | paste the clipboard from step 2 |
   | `VAULTFLOW_KEYSTORE_PASSWORD` | the keystore password |
   | `VAULTFLOW_KEY_ALIAS` | `vaultflow` (or the alias you chose) |
   | `VAULTFLOW_KEY_PASSWORD` | the key password |

4. Clear the clipboard (copy any other text). Don't paste the base64 anywhere else.
5. Release: `git tag v1.0.0` then `git push origin v1.0.0`. The workflow restores
   the keystore into a temporary folder, builds, deletes it, and attaches the
   APK to a GitHub Release.

The released APK is public (the repo is public) but contains **no personal
data and no keys**. Your data only exists on your phone.

## 5. If something leaks anyway

1. **Rotate first.** Trading 212: delete the API key in the app and create a
   new one. A leaked signing keystore: create a new one (you'll need to
   reinstall the app via backup → uninstall → install → restore).
2. Then clean the history (e.g. `git filter-repo`) and force-push. Assume
   anything that was public for even a minute has been copied.

## 6. Threat model (what this does and doesn't protect against)

| Threat | Protection |
|---|---|
| Someone browses your GitHub | No secrets or data in the repo; CI scans history |
| Lost/stolen phone, locked | Data encrypted at rest; app needs biometrics/PIN |
| Someone picks up your unlocked phone | App relocks 30 s after leaving it; biometric prompt |
| Malware reading app files (rooted phone) | DB and vault ciphertext are useless without the hardware-bound key |
| Shoulder-surfing via Recents/screenshots | `FLAG_SECURE` |
| Android auto cloud backup / adb backup | Disabled (`allowBackup=false`, data-extraction rules); VaultFlow's own Drive backup is end-to-end encrypted with your backup password |
| Transactions sent to an AI/cloud service | Never: classification is 100 % on-device |

Not covered: a fully compromised phone OS while the app is unlocked, or a
weak phone PIN. Use a strong screen lock.

Note: the database key is deliberately **not** bound to biometrics, because
the background notification listener must store transactions while the app
UI is locked. The biometric prompt guards the UI; the Keystore guards the data.
