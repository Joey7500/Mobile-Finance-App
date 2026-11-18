---
name: phone-logs
description: Read the crash or error log of the VaultFlow app from the USB-connected phone. Use when the app crashed, froze, or a feature (Drive backup, Gmail sync, widget, capture) silently did nothing.
---

# VaultFlow logs from the phone

Only ever read the log of VaultFlow's own process – never the whole logcat,
other apps' data or notification contents.

1. `adb devices` – a device must be listed (phone on USB, debugging allowed).
2. If the app is running:
   `adb logcat -d --pid=$(adb shell pidof io.github.joey7500.vaultflow) -t 500`
   (in PowerShell: `$id = adb shell pidof io.github.joey7500.vaultflow; adb logcat -d --pid=$id -t 500`).
3. If it crashed (no process): `adb logcat -d -b crash -t 200` and keep only the
   blocks that mention `io.github.joey7500.vaultflow`.
4. Summarise the exception, the first stack frame in `io.github.joey7500.vaultflow`
   and the likely cause. Do not paste account numbers, amounts or e-mail
   addresses that may appear in the log into commits, tests or docs.
5. For capture problems, hand over to the `capture-debugger` agent.
