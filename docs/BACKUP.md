# Encrypted backup to Google Drive

VaultFlow can back itself up once a day to **your own Google Drive**.

- The backup is encrypted **on the phone** (AES-256-GCM, key derived from your
  backup password with PBKDF2) before it leaves the device. Google only ever
  sees an unreadable `.vfbackup` file.
- The app asks only for the `drive.file` permission: it can see **only the files
  it created itself** (the `VaultFlow backups` folder), nothing else in your Drive.
- The newest 30 backups are kept; older ones are deleted automatically.
- The backup password lives only in the phone's Keystore vault. **If you forget
  it, the backups cannot be opened** – not by you, not by Google, not by anyone.
  Write it into your password manager.

## One-time setup in Google Cloud (about 10 minutes, free)

Google requires every app that talks to Drive to be registered. You register
*your own copy* of VaultFlow – nothing about this goes to GitHub.

1. Open <https://console.cloud.google.com/> and sign in with the Google account
   that should hold the backups.
2. Top bar → project picker → **New project** → name `VaultFlow` → **Create**.
   Make sure the new project is selected.
3. **APIs & Services → Library** → search **Google Drive API** → **Enable**.
4. **APIs & Services → OAuth consent screen** (in newer consoles: *Google Auth
   Platform → Branding / Audience*):
   - User type **External** → app name `VaultFlow`, your e-mail as support and
     developer contact → save.
   - **Audience → Test users → Add users** → add your own Gmail address.
     (While the app is in "Testing", only test users can sign in. That is fine
     for personal use. Testing-mode authorizations may expire after 7 days; if
     backups start failing with "Reconnect", either tap *Connect* again or press
     **Publish app** – for the `drive.file` scope no Google review is needed.)
5. Find the **SHA-1 fingerprints** of your signing keys. In Android Studio's
   *Terminal* tab, in the project folder:
   ```powershell
   .\gradlew :app:signingReport
   ```
   Look for `Variant: debug` → `SHA1: AA:BB:...` and, once you have a release
   key (see [RELEASE.md](RELEASE.md)), `Variant: release` → `SHA1: ...`.
   The fingerprint is public information, it is not a secret.
6. **APIs & Services → Credentials → Create credentials → OAuth client ID**:
   - Application type **Android**
   - Package name `io.github.joey7500.vaultflow` (the signed release app)
   - SHA-1 = the **release** fingerprint → **Create**.
7. Repeat step 6 for the debug app (installed from Android Studio):
   package `io.github.joey7500.vaultflow.debug`, SHA-1 = the **debug** fingerprint.

There is no client secret and nothing to paste into the app – Google recognises
the app by package name + signature.

## In the app

Settings → **Backup** → *Google Drive backup*:

1. **Set password** – choose a long backup password (and save it in your
   password manager).
2. **Connect Google Drive** – pick your account, allow access. The first backup
   runs immediately, then once a day (on Wi-Fi or mobile data, not on low battery).
3. **Back up now** any time; **Restore…** lists the backups in Drive, asks for
   the password and replaces the data on this phone, then restarts the app.

## Troubleshooting

| Message | Meaning / fix |
|---|---|
| `10: …` / *Developer error* | The OAuth client does not match the app: wrong package name or SHA-1 (debug vs release!), or the client was created in a different project. Re-check steps 5–7. Changes can take a few minutes to apply. |
| `Access blocked: app not verified` / *access denied* | Your account is not listed as a test user (step 4). |
| *Not connected – reconnect* | The authorization expired or was revoked in your Google account. Tap **Connect** again. |
| *Wrong password* on restore | The backup was made with a different backup password. |

## What is in a backup

Accounts, categories, budgets, transactions, learned rules, settings and
holdings. **Not** included: API keys, the Gmail app password, the backup
password itself – these stay in the Keystore vault of each phone and must be
entered again after moving to a new phone.
