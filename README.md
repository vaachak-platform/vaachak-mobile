# Leisure Vaachak (वाचक)

[![Android CI](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/android.yml/badge.svg)](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/android.yml)
[![Lint & Static Analysis](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/lint.yml/badge.svg)](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/lint.yml)
[![Build & Release APK](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/release.yml/badge.svg)](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/release.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=vaachak-platform_vaachak-mobile&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=vaachak-platform_vaachak-mobile)
[![CodeQL Security Scan](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/codeql.yml/badge.svg)](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/codeql.yml)
[![OWASP MobSF Scan](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/security-scan.yml/badge.svg)](https://github.com/vaachak-platform/vaachak-mobile/actions/workflows/security-scan.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Platform: Android](https://img.shields.io/badge/Platform-Android_11+-green.svg)]()
[![Target: E-Ink](https://img.shields.io/badge/Optimized_for-E--Ink-black.svg)]()


Privacy-first Android reading app for distraction-free leisure reading, designed for phones, tablets, and e-ink devices.

Leisure Vaachak is part of the **Vaachak Platform**, a reading ecosystem built on a simple product principle:

> **Leisure reading and study/work reading should not compete inside the same experience.**

Most reading apps mix books, productivity, notifications, research, and work-like flows into one interface. Leisure Vaachak takes a different approach by focusing on calm, immersive reading with offline-first behavior, privacy-aware sync, and hardware-aware UX for e-ink-friendly devices.

---

## Why this project stands out

Leisure Vaachak is not just an EPUB reader. It is a product-led engineering project built around four deliberate constraints:

- **Distraction-free by design**  
  Optimized for leisure reading instead of trying to be a general-purpose content app.

- **Offline-first architecture**  
  Core reading workflows remain useful without connectivity.

- **Privacy-first sync**  
  Reading state, bookmarks, and highlights are designed to sync without turning user data into a product.

- **Hardware-aware UX**  
  Special attention is given to e-ink devices, low-refresh displays, and low-friction reading interactions.

It also supports a broader platform direction around **Indian language accessibility**, with Android TTS support already working for languages such as **Hindi** and **Gujarati** where available on-device.

---

## Product status

### Available now
- Android app
- EPUB reading
- Bookmarks, highlights, and reading progress tracking
- Privacy-aware sync support
- Android TTS support
- E-ink-friendly reading experience

### Planned
- PDF support
- Audiobook support
- iOS expansion
- Continued expansion of Indian language support
- Future alignment with the broader Vaachak Platform roadmap

---

## Key capabilities

### E-Ink-aware reading experience
- Zero-animation, low-distraction UI behavior tuned for e-paper refresh characteristics
- Contrast controls to improve readability on Android e-ink hardware
- Reader preferences for typography, spacing, layout, and visual comfort
- Support for specialized reading fonts such as **OpenDyslexic** and **IA Writer Duospace**

### Privacy-preserving sync
- Client-side encryption model for reading progress, highlights, and bookmarks
- Sync integration through the [Vaachak Sync Server](https://github.com/vaachak-platform/vaachak-sync-server)
- Offline-first queueing and deferred synchronization when connectivity returns

### Bring-your-own-key AI features
- Context-aware reading recap using the user’s own Gemini API key
- Text actions such as explain, investigate, and visualize
- Offline Mode to disable cloud-dependent AI behavior instantly
- Local-first settings storage with user-controlled configuration

---

## Engineering highlights

This repository is designed to demonstrate product thinking, mobile engineering depth, and platform-level architecture decisions.

### Architecture
- Modular Android app structure with `:leisure` and shared `:core` modules
- Kotlin Multiplatform-oriented foundation for future platform expansion
- Unidirectional data flow and state-driven UI
- Separation of domain, persistence, sync, and reading-engine concerns

### Core technologies
- **UI:** Jetpack Compose, Material 3
- **Language:** Kotlin
- **Shared foundation:** Kotlin Multiplatform principles
- **Reading engine:** Readium Kotlin Toolkit
- **Persistence:** Room, SQLite, Jetpack DataStore
- **Networking:** Ktor Client, Retrofit, OkHttp
- **Dependency Injection:** Dagger Hilt
- **Security:** AES-GCM and PBKDF2-based client-side encryption primitives

### Quality and release posture
- Android CI
- static analysis and linting
- SonarCloud quality gate
- CodeQL security scanning
- OWASP MobSF workflow
- GitHub Actions-based APK release pipeline

👉 **[Read the full architecture document](ARCHITECTURE.md)**

---

## Repository structure

```text
.
├── core/                  # Shared platform, data, sync, persistence, and domain logic
├── leisure/               # Android application module for Leisure Vaachak
├── docs/                  # User-facing and technical documentation
├── .github/               # CI, release, security, and automation workflows
└── ARCHITECTURE.md        # System-level architecture overview
```

---

## Download

Prebuilt APKs are available from GitHub Releases:

👉 **[Download the latest APK](https://github.com/vaachak-platform/vaachak-mobile/releases)**

Need help side-loading?

📲 **[Installation Instructions](docs/Install_Instructions.md)**

---

## Local development

### Prerequisites
- Android Studio
- JDK 21 recommended
- Android SDK configured
- Git

### Setup
1. Clone the repository:
   ```bash
   git clone https://github.com/vaachak-platform/vaachak-mobile.git
   cd vaachak-mobile
   ```

2. Open the project in Android Studio.

3. Sync Gradle.

4. Build the Android app module:
   ```bash
   ./gradlew :leisure:assembleDebug
   ```

### Release build
Release builds require local signing configuration and keystore setup.

```bash
./gradlew :leisure:assembleRelease
```

---

## Documentation

- [User Guide](docs/user_guide.md)  
  Setup, reader configuration, AI configuration, and dictionary support

- [Architecture](ARCHITECTURE.md)  
  Module structure, sync model, and system design

---

## Platform context

Leisure Vaachak is the flagship Android reading app within **Vaachak Platform**.

**Vaachak Platform** aims to build:
- focused reading experiences instead of one overloaded app
- privacy-first sync and local ownership of data
- better support for e-ink hardware
- stronger support for Indian languages over time

Current platform repositories:
- [vaachak-mobile](https://github.com/vaachak-platform/vaachak-mobile)
- [vaachak-sync-server](https://github.com/vaachak-platform/vaachak-sync-server)

---

## Roadmap

Near-term priorities:
- strengthen EPUB experience
- add PDF support
- add audiobook support
- continue refining e-ink UX
- expand Indian language support
- prepare for broader Vaachak Platform expansion

---

## License

Leisure Vaachak is open-source software licensed under the **MIT License**.

See [LICENSE](LICENSE).

---

## Author

Built by [Piyush Daiya](https://www.linkedin.com/in/piyush-daiya)

If you are a recruiter, hiring manager, or engineer evaluating this project, the best starting points are:
- the product framing above
- the [architecture document](ARCHITECTURE.md)
- the release pipeline and security workflows
- the modular `core` + `leisure` structure