---
name: release
description: Build the signed release of VaultFlow and install it on the USB-connected phone, keeping all app data. Use when the owner says "release", "new version", "install it on my phone" or "update the app".
---

# Release and install on the phone

1. Remind the owner to tap **Settings → Backup → Back up now** in the app if the
   change touches the database, ingestion or backup. Wait for "done".
2. Make sure the working tree is clean except the local `gradle.properties` line
   and that `main` is pushed or committed.
3. Bump `versionCode` by 1 and `versionName` (semver: fix → patch, feature →
   minor) in `app/build.gradle.kts`; commit as `build: release <version>`.
4. Java 21 for Gradle (see CLAUDE.md): if `JAVA_HOME` is not already set to
   `%USERPROFILE%\.jdks\jbr-21.0.11`, set it for this shell.
5. Run `.\gradlew :core:domain:test` then `.\gradlew :app:assembleRelease`.
   Stop on any failure and fix it first.
6. `adb devices` (adb is in `%LOCALAPPDATA%\Android\Sdk\platform-tools`). If no
   device: ask the owner to plug in the phone and allow USB debugging.
7. `adb install -r app\build\outputs\apk\release\app-release.apk`.
   The Xiaomi may ask "Install via USB" on screen – tell the owner to confirm.
   - `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: signing key differs. Never uninstall
     (that deletes the data). Check `keystore.properties` points to the release
     key via `.\gradlew :app:signingReport` (do not print the file).
   - `INSTALL_FAILED_VERSION_DOWNGRADE`: versionCode was not raised.
8. Verify: `adb shell dumpsys package io.github.joey7500.vaultflow | findstr versionName`
   shows the new version. Then run the `security-reviewer` agent and push.
