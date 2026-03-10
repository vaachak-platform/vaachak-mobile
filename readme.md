# 📱 Leisure Vaachak (वाचक)

[![Android CI](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/android.yml/badge.svg)](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/android.yml)
[![Lint & Static Analysis](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/lint.yml/badge.svg)](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/lint.yml)
[![Build & Release APK](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/release.yml/badge.svg)](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/release.yml)

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=vaachak-platform_vaachak-mobile&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=vaachak-platform_vaachak-mobile)
[![CodeQL Security Scan](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/codeql.yml/badge.svg)](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/codeql.yml)
[![OWASP MobSF Scan](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/security-scan.yml/badge.svg)](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/security-scan.yml)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Platform: Android](https://img.shields.io/badge/Platform-Android_8.0+-green.svg)]()
[![Target: E-Ink](https://img.shields.io/badge/Optimized_for-E--Ink-black.svg)]()

**Leisure Vaachak** (Sanskrit for "Reader") is an offline-first, privacy-centric e-reader application built natively for Android. It is heavily optimized for E-Ink displays (like the ONYX BOOX series) and features an advanced, Zero-Knowledge encrypted synchronization engine.

## ✨ Key Features

### ✒️ Hardware-Aware E-Ink Optimization
* **Ghosting Prevention:** Zero-animation transitions, solid-state UI components, and a strict bitonal theme designed specifically for e-paper refresh rates.
* **Sharpness Engine:** Custom contrast sliders to sharpen secondary text and dividers, mitigating the washed-out look common on Android E-Ink devices.
* **Pro-Level Typography:** Dual-tab controls for granular layout adjustments (Letter Spacing, Line Height, Paragraph Indents) and specialized fonts (*OpenDyslexic*, *IA Writer Duospace*).

### 🔐 Zero-Knowledge Cross-Device Sync
* **End-to-End Encryption (E2EE):** Reading progress, bookmarks, and highlights are encrypted *on the device* using AES-256-GCM and PBKDF2 derived keys.
* **Blind Cloud Storage:** Syncs seamlessly via the [Vaachak Sync Server](https://github.com/vaachak-platform/vaachak-sync-server). The server acts as a blind relay and cannot read your data.
* **Offline-First Delta Sync:** Read anywhere. Changes are queued locally in Room DB and pushed automatically via timestamp-based delta syncs when a connection is restored.

### 🧠 Bring-Your-Own-Key (BYOK) AI Intelligence
* **The Story So Far:** Context-aware, spoiler-free summaries generated locally using your personal Gemini API key.
* **Contextual Actions:** Select text to *Explain* archaic terms, *Investigate* character lore, or *Visualize* scenes.
* **Privacy by Default:** Your API keys are stored securely on-device in encrypted DataStore preferences. Toggle "Offline Mode" to instantly air-gap the reader.

## 🏗️ Architecture & Tech Stack

Vaachak is built using **Modern Android Development (MAD)** standards, Clean Architecture, and Kotlin Multiplatform (KMP) principles.

* **UI Layer:** Jetpack Compose (Material 3) with Unidirectional Data Flow.
* **Reading Engine:** Readium Kotlin Toolkit.
* **Networking:** Ktor Client & Kotlinx Serialization.
* **Persistence:** Room (SQLite) & Jetpack DataStore.
* **Dependency Injection:** Dagger Hilt.
* **Security:** `javax.crypto` (AES-GCM / PBKDF2WithHmacSHA256).

👉 **[Read the full Architecture Document here](ARCHITECTURE.md)**

## 🚀 Getting Started

### Download & Install
Pre-compiled APKs for the latest release can be downloaded directly:
👉 **[Download Vaachak APK](https://github.com/vaachak-platform/vaachak-mobile/releases)**

Need help side-loading? 📲 **[Read the Installation Instructions](docs/Install_Instructions.md)**

### Local Development
1. Clone the repository:
   ```bash
   git clone [https://github.com/vaachak-platform/vaachak-mobile.git](https://github.com/vaachak-platform/vaachak-mobile.git)
   ```
2. Open the project in **Android Studio** (Koala Feature Drop or newer).
3. Ensure JDK 17+ is installed.
4. Sync Gradle and build the `:leisure` module.

## 📖 Documentation
* [**User Guide**](docs/user_guide.md) - Configuration, AI setup, and StarDict dictionary integration.
* [**System Architecture**](ARCHITECTURE.md) - Deep dive into the E2EE sync model and module structure.

## 📄 License & Attribution
Vaachak is open-source software licensed under the **MIT License**.
*Developed by [Piyush Daiya](https://www.linkedin.com/in/piyush-daiya)*