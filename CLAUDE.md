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

## Current state (v1.1.0, versionCode 2)

- The **signed release build** (`io.github.joey7500.vaultflow`) is installed on
  the owner's phone (Xiaomi 14T, HyperOS) with real data. The debug app was
  uninstalled. Do **not** suggest pressing Run in Android Studio for normal
  work: it installs the debug app next to the release one and both would
  capture the same notifications. Use the update workflow below.
- Google Drive backup is live (Google Cloud project "VaultFlow", OAuth consent
  in testing mode with the owner as test user, Android OAuth clients for the
  release and debug package names). Error 403 access_denied = account not a
  test user; error 10 = package/SHA-1 mismatch.
- Release key: `%USERPROFILE%\keys\vaultflow-release.jks`, referenced by the
  git-ignored `keystore.properties`. Losing it means no more updates.
- Done features: notification capture (George/ČS, KB, Revolut, Google Wallet),
  Gmail IMAP sync of bank e-mails, fusion/dedup of all sources, own-account
  transfers (debit one account, credit the other), balance reconciliation,
  payday-based budget periods, reserve floor + "safe to spend", category and
  account drill-downs, Trading 212 + gold + net worth, encrypted Drive backup,
  "Safe to spend" Glance widget, biometric lock.

## Owner's PC (Windows) – build environment

- Project folder: `%USERPROFILE%\OneDrive\Dokumenty\FinanceApp` (inside
  OneDrive; if git/Gradle hit "file in use" errors, suggest moving it out).
- **Gradle must run on Java 21**, not Android Studio's bundled JBR (Java 25,
  which fails with a bare "25.0.4" error). In every new terminal:
  `$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jbr-21.0.11"`
- adb: `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`
- `gradle.properties` has a local, uncommitted line
  (`org.gradle.tooling.parallel=true`) added by Android Studio – leave it
  uncommitted, don't revert it.
- gitleaks is installed (winget), so the pre-commit hook runs all three layers.

## Update workflow (release app keeps all data)

1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.
2. `.\gradlew :core:domain:test` then `.\gradlew :app:assembleRelease`
3. Phone on USB: `adb install -r app\build\outputs\apk\release\app-release.apk`
4. Before risky changes (DB schema, ingestion), remind the owner to tap
   *Back up now* in the app first.
5. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` = different signing key: never tell the
   owner to uninstall (that deletes data) – find out which key was used.

## How capture works (read before touching ingestion)

Details in `docs/ARCHITECTURE.md` and `docs/EMAIL.md`; tests in
`core/domain/src/test` encode every real-device bug that was fixed – keep
them green and add a new scenario for every new bug.

- Each notification sender / e-mail sender is mapped to an account in the app
  (George and ČS e-mails → the ČS account, KB e-mails → KB, Revolut app →
  Revolut). **Google Wallet is mapped to no account**: it is only a *hint*
  source; the account is found from the card's last digits stored on the
  account (`cardIdentifiers`). Wallet re-posts the same payment, so a Wallet
  record never creates a second transaction within its 90-min window, and
  `DuplicateRepair` folds Wallet-only records into bank-confirmed ones.
- Bank e-mails arrive ~6 min after the push notification; they confirm the
  existing transaction (evidence set) instead of creating a new one.
- Same bank source never merges twice (two real identical payments stay two).
  Merchant names are matched fuzzily (e.g. `NYX*VeryGoodies` vs `Very Goodies`).
- Own-account transfers: the counterparty account is recognised by account
  number, card digits or transfer alias; an INFERRED mirror transaction credits
  the other account. ČS→Revolut top-ups that mention the Revolut card must go
  to Revolut, not ČS.
- Balances from messages are used only when in CZK (foreign-currency pocket
  balances are ignored).

## Budget concepts

- Budget period starts on the payday (configurable start day, mid-month) with
  income grace days, not on the 1st.
- Reserve floor = minimum that must stay on liquid accounts at all times
  (overdraft excluded). Safe to spend = liquid − floor − remaining budget.
- Rent is shared: the budget holds only the owner's share; the friend's
  payment (arrives near month end) is assigned to Rent with an "always" rule.

## Owner's decisions / backlog

- **Not wanted:** recurring-payment tracking, alerts/notifications from the app.
- Possible next ideas (only on request): a "shared expense" type for rent,
  monthly investment plan view, GitHub-built release APKs (needs the owner to
  add signing secrets himself – never handle the passwords).
- Personal setup values (card digits, account numbers, amounts) live only in
  the app – ask the owner, never write them into the repo.

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
