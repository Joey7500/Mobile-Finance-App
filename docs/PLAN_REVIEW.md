# Review of the original plan

The project started from an AI-generated architecture ("Automated Mobile
Budgeting Architecture"). Its direction was sound (native Kotlin + Compose,
on-device data, notification capture, $0 running cost), but several parts
would have failed in practice. This is what changed and why.

| # | Original plan | Problem | What VaultFlow does |
|---|---|---|---|
| 1 | `Surplus = Income − Expenses − Investments − 5000` | Treats the reserve as a monthly expense; the app also never knew real balances. | Reserve is a balance constraint on anchored account balances: `safe = liquid − floor − remaining budget`. |
| 2 | `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` with minSdk 29 | That combination is unsupported on Android 10 and throws. | Per-API authenticators (WEAK+credential on 10, STRONG+credential on 11+). |
| 3 | "Keystore key requires biometric authentication" | The code never set it, and binding it would break background capture. | Documented decision: UI behind biometrics, data behind the Keystore. |
| 4 | `net.sqlcipher.database.SupportOpenHelperFactory` | That's the deprecated `android-database-sqlcipher` API, not `sqlcipher-android`. | `net.zetetic.database.sqlcipher.SupportOpenHelperFactory`. |
| 5 | `fallbackToDestructiveMigration()` | Wipes all financial data on the first schema change. | Removed; schemas exported and committed. |
| 6 | `EncryptedSharedPreferences` (`security-crypto` alpha) | Deprecated library. | Direct Keystore AES-GCM (`KeystoreCipher`). |
| 7 | Dedup: same amount within 300 s | Merges two genuine identical purchases. | Merge only across different apps, with merchant similarity and FX tolerance. |
| 8 | Custom `.gitleaks.toml` without `[extend]` | Replaces all default rules (AWS, GitHub, private keys...). Hook only warned without gitleaks; hooks don't travel with clones. | `useDefault = true`, tool-free IBAN/file checks in a versioned hook, gitleaks in CI on full history. |
| 9 | Notification Access "just works" | Android 13+ blocks it for sideloaded apps ("Restricted setting"). | In-app guidance + SETUP.md steps. |
| 10 | Hard-coded bank parsing | Wording differs per bank/language; OTP, 3-D Secure, declined and balance alerts would become fake transactions. | Bank-agnostic parser with ignore rules, balance detection, sender discovery and a capture log. |
| 11 | Tier-2 LLM classifier | Sends transactions to a cloud API, contradicting the privacy and $0 goals. | On-device rules that learn from your corrections + built-in Czech merchant list. |
| 12 | No backup | Uninstall/reset destroys the Keystore key and all data. | Password-encrypted export/import. |
| 13 | `goldprice.dev` free tier | Couldn't verify it exists. | xaus.com spot (no key) × ČNB USD/CZK, manual price fallback. |
| 14 | Demo mode default ON in the secrets store | Confusing, and demo data could mix with real data. | Separate in-memory demo DB; capture paused in demo mode. |
| 15 | No tests / CI | Money logic untested. | Domain module with unit tests; CI builds, tests and scans every push. |

Verified external facts (Nov 2026): the Trading 212 Public API v0 uses HTTP
Basic auth with API key + secret, and `GET /api/v0/equity/account/summary` is
rate-limited to 1 request per 5 s and only available for Invest and Stocks ISA accounts.
