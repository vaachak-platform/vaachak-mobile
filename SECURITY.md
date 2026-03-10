# 🛡️ Security Policy & Architecture

This document outlines the security model, vulnerability reporting process, and automated testing standards for the Vaachak Mobile project.

## 🔒 Security Architecture (Zero-Knowledge)

Vaachak is engineered with a **Zero-Knowledge** architecture. This means that your data is encrypted on your device before it ever reaches our servers, and we do not hold the keys to decrypt it.

### 1. Cryptographic Implementation (OWASP M1)
To prevent cryptographic failures, Vaachak uses industry-standard authenticated encryption:
* **Algorithm:** `AES-256-GCM` (Galois/Counter Mode) for confidentiality and integrity.
* **Key Derivation:** `PBKDF2WithHmacSHA256` with **65,536 iterations**.
* **Salt:** Deterministic salts derived from the user's unique username to prevent rainbow table attacks.
* **IV Management:** A cryptographically secure 12-byte Initialization Vector (IV) is generated for every single data sync operation.

### 2. Secure Storage (OWASP M2)
* **API Keys:** Sensitive keys (Gemini, Cloudflare) are stored using **Jetpack DataStore** with encryption enabled, rather than insecure `SharedPreferences`.
* **Local Database:** Book metadata and highlights are stored in a private **Room (SQLite)** database accessible only by the application.

### 3. Supply Chain Security (OWASP M2)
* We utilize **GitHub Dependabot** to monitor the Gradle Version Catalog (`libs.versions.toml`) for known vulnerabilities in third-party libraries like Readium, Ktor, and Timber.
* Automated **CodeQL** scanning runs on every push to detect semantic security flaws in the Kotlin and TypeScript codebases.

## 🧪 Security Testing & Quality Gates

The Vaachak CI/CD pipeline enforces several security-focused quality gates before any code is merged into `main`:

### Automated Analysis
* **Static Analysis:** [Detekt](https://detekt.dev/) is used to identify code smells and potential security misconfigurations.
* **Memory Leak Detection:** [LeakCanary](https://square.github.io/leakcanary/) is integrated into debug builds to ensure sensitive data does not linger in memory and to prevent Denial of Service (DoS) via resource exhaustion.
* **Dependency Auditing:** Automated scans for the **OWASP Top 10 Mobile** risks are performed during the build process using `mobsfscan`.

### Local Verification
To run a local security audit, ensure you have the environment configured and execute:
```bash
./gradlew detekt       # Runs static analysis
./gradlew test         # Runs unit tests for cryptographic logic
```

## 🐛 Reporting a Vulnerability

If you discover a security vulnerability within this project, please do not open a public issue. Instead, follow these steps:

1. **Email:** Send a detailed report to [piyush.daiya@example.com] (Update with your actual email).
2. **Details:** Include a description of the vulnerability, steps to reproduce, and any potential impact.
3. **Response:** We aim to acknowledge receipt of your report within 48 hours and provide a timeline for a fix.

## 🚫 Out of Scope
* Attacks requiring physical access to an unlocked device.
* Issues related to the security of the user's chosen Sync Server provider (unless using the default Vaachak Sync Server).
* Vulnerabilities in the underlying Android OS.

---
*Last Updated: March 2026*