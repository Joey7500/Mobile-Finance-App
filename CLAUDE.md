# VaultFlow – project notes for Claude Code

Personal-finance Android app (Kotlin, Jetpack Compose). The owner is new to
Android: explain steps plainly, give exact PowerShell commands (Windows PC),
and answer in Czech when asked in Czech.

## Absolute rules (security)

- **Never commit personal data or secrets.** No real account numbers, card
  numbers, IBANs, names, API keys, passwords or e-mails – not in code, tests,
  comments, docs or commit messages. In tests and examples use fake account
  numbers with the bank codes **9999 / 9998** (e.g. `1234567890/9999`).
- Never commit `keystore.properties`, `local.properties`, `*.jks`, `*.keystore`,
  `*.db`, `*.vfbackup`, `*.csv`, `.env`. They are git-ignored and blocked by the
  pre-commit hook (`.githooks/pre-commit`).
- **Never use `git commit --no-verify`** and never weaken `.gitleaks.toml` or the
  hook to make a commit pass – fix the content instead.
- Secrets live only in the on-device Keystore vault (`SecretVault`): Trading 212
  keys, Gmail address + app password, backup password. Never add them to
  Gradle, BuildConfig, resources or logs.
- Never read or print the contents of `keystore.properties` or any `.jks`.
- Before pushing, run `gitleaks git --redact -v` (full history) and make sure it
  reports no leaks.

## Layout

- `core/domain` – pure Kotlin (no Android): money, budget periods, reserve,
  notification/e-mail parsing, dedup/fusion pipeline, classifier, backup crypto.
  All business logic and its unit tests live here.
- `app` – Android: Room + SQLCipher DB (`data/local`, schemas in `app/schemas`,
  migrations required – no destructive fallback), repositories, notification
  listener, Gmail IMAP sync, Google Drive backup (`backup/`), Glance widget
  (`widget/`), Compose UI (`ui/`), manual DI in `di/AppContainer.kt`.
- `docs/` – SETUP, SECURITY, ARCHITECTURE, EMAIL, BACKUP, RELEASE.

## Commands (PowerShell, project root)

```powershell
.\gradlew :core:domain:test          # fast unit tests (run after any domain change)
.\gradlew :app:assembleDebug         # debug APK
.\gradlew :app:assembleRelease       # signed release (needs keystore.properties)
.\gradlew :app:signingReport         # SHA-1 fingerprints for Google Cloud OAuth
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\release\app-release.apk
gitleaks git --redact -v             # secret scan of the whole history
```

## Conventions

- Room schema change → bump DB version, add a `Migration`, commit the new JSON
  in `app/schemas`.
- New parsing/dedup behaviour → add a test in `core/domain/src/test` first,
  using fake data only.
- Raise `versionCode` / `versionName` in `app/build.gradle.kts` for every
  release build installed on the phone.
- Small, focused commits with conventional prefixes (`feat:`, `fix:`, `docs:`,
  `build:`). CI (`.github/workflows/ci.yml`) runs gitleaks + build + tests on
  every push; keep it green.
