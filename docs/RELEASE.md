# Signed release build ("ostrá verze")

The app you installed from Android Studio so far is the **debug** build
(`io.github.joey7500.vaultflow.debug`, name shows "-debug"). A **release** build
is signed with *your own* key, is faster, and is the version you keep on the
phone long-term (and could give to someone else).

> The release key is the identity of the app. Android installs an update only
> if it is signed with the **same key**. Lose the key → you can't update the
> app any more without uninstalling it (= losing the data on the phone unless
> you have a backup). So: keep the `.jks` file and its password safe and
> backed up (password manager + a copy of the file outside the PC).

Nothing in this guide goes to GitHub: `keystore.properties`, `*.jks` and
`local.properties` are git-ignored **and** blocked by the pre-commit hook.

## 1. Create the release key (once, on your PC)

Open **PowerShell** (not inside the repo folder is fine):

```powershell
mkdir $env:USERPROFILE\keys -Force
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v `
  -keystore $env:USERPROFILE\keys\vaultflow-release.jks `
  -alias vaultflow -keyalg RSA -keysize 4096 -validity 10000
```

- It asks for a **keystore password** twice – type a new strong password
  (nothing is shown while typing, that is normal). Save it in your password
  manager right away.
- Then name / organisation / city / country questions – anything is fine
  (e.g. your first name, Enter, Enter, …, `CZ`), confirm with `yes`.
- If it asks for a separate key password, press **Enter** to use the same one.

The key now lives in `C:\Users\<you>\keys\vaultflow-release.jks` – **outside**
the repository. Copy that file to a USB stick / your password manager as well.

## 2. Tell Gradle where the key is

In the project folder (`FinanceApp`), copy the example file:

```powershell
Copy-Item keystore.properties.example keystore.properties
notepad keystore.properties
```

Fill in (forward slashes in the path):

```properties
storeFile=C:/Users/josef/keys/vaultflow-release.jks
storePassword=<the password from step 1>
keyAlias=vaultflow
keyPassword=<the same password>
```

Save. Check that git ignores it – this must print `keystore.properties`:

```powershell
git check-ignore keystore.properties
```

## 3. Build and install

Phone connected with USB debugging (same as before), in Android Studio's
**Terminal** tab:

```powershell
.\gradlew :app:assembleRelease
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\release\app-release.apk
```

Xiaomi/HyperOS asks "Install via USB?" on the phone – allow it. A new
**VaultFlow** icon appears (without "-debug"). The debug app stays installed
next to it – they are two separate apps with separate data.

Alternative without the terminal: Android Studio → *Build → Generate Signed
App Bundle / APK → APK* → choose the same `.jks` → *release*.

## 4. Register the release key for Google Drive

```powershell
.\gradlew :app:signingReport
```

Copy the `SHA1` from `Variant: release` and create the OAuth client for
package `io.github.joey7500.vaultflow` as described in
[BACKUP.md](BACKUP.md), steps 6.

## 5. Move your data from the debug app to the release app

Data is encrypted per app, so it moves through a backup:

1. In the **debug** app: Settings → Backup → set the backup password →
   connect Google Drive → **Back up now**. (No Google Cloud set up yet? Use
   *Manual backup file → Export* and save the file to the phone.)
2. Open the **release** app, finish the lock/onboarding, then Settings → Backup
   → set **the same** backup password → connect Google Drive → **Restore…** →
   pick the newest backup (or *Manual backup file → Import*).
3. In the release app enter again what is **not** in backups: Trading 212 keys,
   Gmail address + app password, and grant **notification access** (Settings →
   Capture). Check the *notification senders* toggles and their accounts.
4. When everything works, **uninstall the debug app** (long-press icon →
   Uninstall) – otherwise both apps capture the same notifications and you
   would have to keep two copies up to date.

## 6. Updating later

Pull the new code, then repeat step 3 (`assembleRelease` + `adb install -r`).
`-r` keeps the data. Each new version must have a higher `versionCode` in
`app/build.gradle.kts` – this is already done in every release commit.

## Optional: build releases on GitHub instead

`.github/workflows/release.yml` can build the signed APK for you and attach it
to a GitHub Release (handy for installing on the phone straight from the
browser, or for giving the app to someone). It needs four repository secrets –
add them yourself on github.com → repo → *Settings → Secrets and variables →
Actions*, never paste them anywhere else:

| Secret | Value |
|---|---|
| `VAULTFLOW_KEYSTORE_BASE64` | output of `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\keys\vaultflow-release.jks")) \| Set-Clipboard` (copies it to the clipboard) |
| `VAULTFLOW_KEYSTORE_PASSWORD` | keystore password |
| `VAULTFLOW_KEY_ALIAS` | `vaultflow` |
| `VAULTFLOW_KEY_PASSWORD` | key password |

Then *Actions → Release APK → Run workflow*. Note: if the repository is public,
the released APK is downloadable by anyone (it contains no personal data –
that lives only on your phone).
