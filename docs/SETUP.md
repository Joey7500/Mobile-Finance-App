# Getting VaultFlow onto your phone — step by step

This guide assumes **no prior Android experience** and a **Windows PC**.
Total time: about 1 hour, most of it waiting for downloads.

You need: an Android phone with **Android 10 or newer**, a USB cable that can
transfer data (not a charge-only cable), and about 15 GB free on the PC.

---

## Part 1 — Get the code onto your PC

1. **Install Git** (skip if `git --version` already works in PowerShell):
   ```powershell
   winget install --id Git.Git -e
   ```
   Close and reopen PowerShell afterwards.

2. **Get the project.** The repo is already cloned into
   git pull
   ```
   > `git clone https://github.com/Joey7500/Mobile-Finance-App.git C:\dev\Mobile-Finance-App`

3. **Turn on the secret guard** (blocks commits that contain keys, IBANs or
   account numbers):
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\install-hooks.ps1
   ```
   Optionally install gitleaks for deeper scanning: `winget search gitleaks`,
   then `winget install <the id shown>`. Or download it from
   https://github.com/gitleaks/gitleaks/releases and put `gitleaks.exe` on your PATH.

---

## Part 2 — Install Android Studio

1. Download Android Studio from https://developer.android.com/studio and install
   it with the default options.
2. On first launch pick **Standard** setup. It downloads the Android SDK (a few GB).
3. **File → Open…** → select the project folder (the one containing
   `settings.gradle.kts`) → **Trust Project**.
4. Wait for **Gradle sync** to finish (bottom status bar; the first time takes
   5–15 minutes). If Android Studio offers to *upgrade the Android Gradle
   Plugin*, you can click **Don't ask again**: the project works as is.

Android Studio creates `local.properties` with your SDK path. It is git-ignored;
never commit it.

---

## Part 3 — Prepare your phone

1. **Enable Developer options:** Settings → About phone → tap **Build number**
   7 times (on Xiaomi: *MIUI/HyperOS version*; on Samsung: Software information →
   Build number). Enter your PIN when asked.
2. **Enable USB debugging:** Settings → System → Developer options → **USB debugging** ON.
   (Xiaomi also needs **Install via USB** ON.)
3. Plug the phone into the PC. On the phone, accept **"Allow USB debugging?"**
   (tick *Always allow from this computer*).
4. In Android Studio, your phone appears in the device dropdown at the top.

## Part 4 — Install and run

Click the green **Run ▶** button. Android Studio builds the app and installs
**VaultFlow** on the phone. (This is the *debug* build; its package name ends
in `.debug`.)

> **No Android Studio?** Every push to GitHub builds a debug APK:
> GitHub → repo → **Actions** → latest green run → *Artifacts* →
> `vaultflow-debug-apk`. Copy the APK to the phone and open it (allow
> "Install unknown apps" for your file manager). Note: each CI build is
> signed with a different temporary key, so installing a newer CI build
> means uninstalling the old one first and **losing its data**. For daily use,
> follow Part 6.

---

## Part 5 — Fill in your vault (on the phone)

Everything below is typed **into the app on your phone**. None of it goes
into the code, Gradle files or GitHub.

1. **Unlock** with fingerprint/face/PIN. (If your phone has no screen lock,
   set one first: VaultFlow relies on it.)
2. *(Optional)* Settings → **Demo mode** to look around with fake data, then
   switch it off again. Your real vault is separate and untouched.
3. **Accounts** (Settings → Accounts → + Add account) for each bank account:
   - Name, e.g. "Air Bank – main".
   - **Balance right now**, exactly as your bank app shows it today.
   - Keep *Counts towards the reserve floor* ticked for everyday/savings accounts.
   - *Your account number digits* (optional): e.g. the last 6 digits of each
     of your own accounts, one per line. Payments that mention them are treated
     as **internal transfers** and don't count as income/expense.
4. **Reserve floor**: 5 000 Kč is pre-filled. Change it if you like.
5. **Budgets**: Budget tab → tap each category → set the monthly amount. For
   *Salary* enter your expected net salary.
6. **Trading 212** (optional):
   - Trading 212 app → **Settings → API (Beta)** → *Generate API key*.
   - Give it **only "Account data"** permission (read-only; nothing can trade).
   - Copy the **API key** and **API secret** into VaultFlow → Settings → Vault → **Save** → **Test**.
   - You can't view the secret again in Trading 212 later. If you lose it, delete the key and make a new one.
7. **Gold**: Wealth tab → *Physical gold* → enter grams (1 oz = 31.10 g).
8. **Building savings**: Wealth tab → *+ Add asset* → current balance from your
   last statement. Update it once a year.
9. **Automatic capture**:
   1. Settings → Automatic capture → **Notification access** → switch VaultFlow ON.
   2. **Greyed out / "Restricted setting"?** (Android 13+ does this for apps not
      installed from the Play Store.) Tap **App info** in VaultFlow → **⋮** (top
      right) → **Allow restricted settings** → confirm with your PIN → go back
      and enable Notification access again.
   3. **Battery**: tap the battery button and set VaultFlow to **Unrestricted**.
      Xiaomi/HyperOS: also App info → *Autostart* ON. Samsung: Settings →
      Battery → Background usage limits → add VaultFlow to *Never sleeping apps*.
   4. Make sure **push notifications are ON** in your bank apps, and Google
      Wallet → Settings → Notifications → transaction notifications ON.
   5. Pay for something. Then open Settings → **Capture log** to see what happened.
   6. Your bank apps show up under **"Apps that sent money notifications"**
      after their first payment notification. Switch each one **ON** and pick
      the account it belongs to. Google Wallet is on by default.
10. **Back up**: Settings → Backup → **Export**. Choose a strong password
    (store it in your password manager) and save the file, e.g. to Google Drive.
    The file is AES-256 encrypted.

Classifying: anything VaultFlow can't classify lands in **Needs a category**
on the Budget tab. Tap it, tick *Always classify this merchant like this*, pick
the category. Next time it's automatic.

---

## Part 6 — A "daily driver" build that keeps your data across updates

Android only lets you update an app with an APK signed by the **same key**.
The debug key lives on your PC (`%USERPROFILE%\.android\debug.keystore`).
That's fine as long as you always build on this PC. For a permanent install,
use your own release key:

1. **Create a keystore** (once): Android Studio → **Build → Generate Signed
   App Bundle / APK…** → **APK** → *Create new…*
   - Key store path: **outside the project**, e.g. `C:\Users\josef\keys\vaultflow-release.jks`
   - Choose passwords, alias `vaultflow`, validity 25+ years, fill in your name.
   - **Back up the .jks file and the passwords** (password manager + a USB
     stick). Lose them = you can never update the app again without wiping it.
2. In the project root, copy `keystore.properties.example` to
   **`keystore.properties`** and fill in the path, passwords and alias.
   This file is git-ignored and the pre-commit hook refuses to commit it.
3. Build:
   ```powershell
   .\gradlew :app:assembleRelease
   ```
   The APK is at `app\build\outputs\apk\release\app-release.apk`.
4. Install with the phone connected:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\release\app-release.apk
   ```
   (or copy the APK to the phone and open it).

The release app (`io.github.joey7500.vaultflow`) and the debug app
(`…vaultflow.debug`) are **separate apps with separate data**. To move your
data: Export in one → Restore in the other.

### Optional: let GitHub build signed releases

See [SECURITY.md → Signing secrets on GitHub](SECURITY.md#4-signing-secrets-on-github-optional).
After that, `git tag v1.0.0 && git push origin v1.0.0` produces a signed APK
under *Releases*.

---

## Updating later

```powershell
git pull
.\gradlew :app:assembleRelease     # or press Run in Android Studio
```
Install over the existing app: your data stays.

## Troubleshooting

| Problem | Fix |
|---|---|
| Phone not listed in Android Studio | Use a data cable, accept the USB-debugging prompt, try another USB port. Xiaomi: enable *Install via USB*. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed app was signed with a different key. Export a backup, uninstall, install, restore. |
| Notification access switch greyed out | Allow restricted settings (Part 5, step 9.2). |
| Captures stop after a few hours | Battery → Unrestricted, and Autostart on Xiaomi. Toggle Notification access off/on once. |
| A bank's notifications are "skipped" in the log | Open an issue with the *pattern* of the text (replace amounts/names). The parser lives in `core/domain/.../ingest`. |
| Trading 212 "401" | Wrong key/secret pair. Regenerate in Trading 212 and paste both again. |
| Trading 212 "403" | The key lacks the *Account data* permission. |
| Gradle: "Unable to delete directory … build" | OneDrive locking files. Pause OneDrive or clone outside OneDrive. |
