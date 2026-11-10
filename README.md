# VaultFlow

**Private, automated personal finance for Android.** A monthly budget with a
hard cash-reserve floor, automatic capture of Google Wallet and bank
notifications, and a net-worth view across Trading 212, physical gold, bank
accounts and building savings. Everything stays encrypted on the phone and
nothing is sent to a cloud service.

[![CI](https://github.com/Joey7500/Mobile-Finance-App/actions/workflows/ci.yml/badge.svg)](https://github.com/Joey7500/Mobile-Finance-App/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF) ![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4) ![minSdk](https://img.shields.io/badge/Android-10%2B-34A853)

## Features

**Budget tab**
- Budget vs. actual per category (salary, gifts, rent, subscriptions, groceries,
  eating out, travel, fun, holiday, other, investments), all editable
- **Reserve watchdog**: shows what's *safe to spend or invest* after keeping
  your floor (default 5 000 Kč) and the rest of this month's budget
- **Needs a category** queue: one tap to assign, and *always classify* teaches it the merchant
- Reimbursements (a friend pays you back) reduce the category spend
- Transfers between your own accounts are excluded from totals

**Automatic capture**
- Reads payment notifications from Google Wallet and the bank apps you enable
- Czech + English formats (`1 234,50 Kč`, `CZK 1,234.50`, `€12.50`), in/out detection
- Ignores OTP codes, 3-D Secure prompts, declined payments and promos
- Merges the Wallet and bank reports of one payment; foreign currency is converted with ČNB rates
- **Bank e-mails via Gmail** (read-only IMAP): every payment confirmed, balances checked
- **Fusion**: Wallet + bank app + e-mail reports merge into one transaction; own-account transfers are booked on both sides
- Capture log so you can see exactly what happened to each notification

**Wealth tab**
- Live **Trading 212** value (read-only API key)
- **Physical gold**: grams × XAU/USD spot × ČNB USD/CZK
- Bank balances, **building savings** and other manual assets
- Allocation donut + net-worth history

**Security**
- Biometric / PIN lock, auto-relock, `FLAG_SECURE`
- SQLCipher-encrypted database, key wrapped by the hardware Android Keystore
- In-app API key vault: no keys in code, config or git
- Cloud/adb backup disabled; password-encrypted export instead
- Pre-commit guard + CI gitleaks scan against leaking keys, IBANs or account numbers

**Demo mode**: synthetic data in a separate in-memory database, for screenshots and trying the app.

## Getting started

New to Android? Follow **[docs/SETUP.md](docs/SETUP.md)**. It goes from
installing Android Studio to filling in your vault on the phone.

```powershell
git clone https://github.com/Joey7500/Mobile-Finance-App.git
cd Mobile-Finance-App
powershell -ExecutionPolicy Bypass -File scripts\install-hooks.ps1   # secret guard
.\gradlew :core:domain:test :app:assembleDebug
```

## Documentation

| Doc | What's inside |
|---|---|
| [docs/SETUP.md](docs/SETUP.md) | Step-by-step: PC setup, phone setup, first run, signed release build |
| [docs/EMAIL.md](docs/EMAIL.md) | Connecting bank e-mails (Gmail) to confirm payments and balances |
| [docs/SECURITY.md](docs/SECURITY.md) | Where every secret lives, leak protection layers, GitHub signing secrets |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules, data flow, design decisions |
| [docs/PLAN_REVIEW.md](docs/PLAN_REVIEW.md) | Weaknesses found in the original plan and how they were fixed |

## Tech stack

Kotlin · Jetpack Compose (Material 3) · Coroutines/Flow · Room + SQLCipher ·
Android Keystore · AndroidX Biometric · OkHttp · kotlinx.serialization ·
NotificationListenerService · GitHub Actions · gitleaks

```
core/domain   pure Kotlin business rules + unit tests
app           Android: security, persistence, network, Compose UI
```

## Cost

$0. Trading 212's API, the ČNB exchange-rate feed and the gold spot API are
free, and there is no backend.

## Roadmap

- Recurring items (rent, standing orders) that arrive without a notification
- Fio banka REST API as an optional second capture source
- Split transactions
- Home-screen widget with "safe to spend"

## License

Apache 2.0, see [LICENSE](LICENSE).
