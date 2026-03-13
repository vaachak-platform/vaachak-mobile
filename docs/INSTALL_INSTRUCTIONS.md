# 📲 Installation Instructions

Since Vaachak is an open-source, privacy-first application, it is distributed directly via GitHub rather than the Google Play Store.

👉 **[Download the latest Vaachak Release APK here](https://github.com/vaachak-platform/vaachak-mobile/releases/)**

---

## ⚠️ Prerequisites
1. **Enable Unknown Sources:** On your Android/E-Ink device, you must allow installation from "Unknown Sources" (usually found in Settings > Security or by prompting your file manager).
2. **Uninstall Old Debug Versions:** If you previously installed a "Debug" version built locally via Android Studio, you **must uninstall it** before installing the official "Release" APK. They use different signing certificates.

---

## Method 1: Direct Install (Recommended)

1. **Download** the `Vaachak-release.apk` file directly onto your device.
2. Open your device's **File Manager** and navigate to your `Downloads` folder.
3. Tap the APK file.
4. If prompted with *"For your security, your phone is not allowed to install unknown apps..."*:
    * Tap **Settings**.
    * Toggle **Allow from this source**.
    * Press back and tap **Install**.

---

## Method 2: ADB Sideload (For Developers)

If you prefer using the command line and have [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) installed:

1. Connect your device via USB and ensure **USB Debugging** is enabled in Developer Options.
2. Open your terminal and navigate to the downloaded APK:
   ```bash
   cd path/to/downloads
   ```
3. Run the install command:
   ```bash
   adb install Vaachak-release.apk
   ```
   *(To upgrade an existing installation while retaining data, use `adb install -r Vaachak-release.apk`)*

---

## ❓ Troubleshooting

* **"App not installed" Error:** You likely have a signature conflict. Long-press your existing Vaachak app, tap **Uninstall**, and try the installation again.
* **"Blocked by Play Protect":** Google Play Protect frequently flags open-source apps not hosted on the Play Store. Tap **More Details** -> **Install Anyway**.