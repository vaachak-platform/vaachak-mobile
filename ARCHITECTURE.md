# 🏛️ Vaachak Mobile Architecture

This document outlines the high-level system design, module structure, and security model of the Vaachak Android application.

## 1. Module Structure

The application is structured to support future expansion to iOS via Kotlin Multiplatform (KMP), separating domain logic from platform-specific UI rendering.

* `:core` **(KMP Ready)**: Contains all business logic, local database definitions (Room KMP), networking clients (Ktor), and cryptographic interfaces.
* `:leisure` **(Android App)**: Contains the Android-specific entry points, Jetpack Compose UI layer, Dependency Injection wiring (Hilt), and Readium EPUB rendering engine.

## 2. Data Flow & State Management

Vaachak strictly adheres to **Unidirectional Data Flow (UDF)**.
1. **UI Layer (Compose):** Observes immutable state objects emitted by ViewModels.
2. **ViewModels:** Catch user intents, execute use cases/repository methods, and mutate `StateFlow` streams.
3. **Repositories:** Act as the single source of truth, managing the arbitration between the local `Room` database and the remote `Ktor` network client.

## 3. The Synchronization Engine (Phase 3)

E-ink devices often operate on constrained networks to save battery. The sync engine is designed to minimize payloads using a timestamp-anchored Delta Sync.

### The Sync Transaction
1. **Local Staging:** Modified books and new highlights are flagged as `isDirty = true` in the Room Database.
2. **Payload Generation:** The repository bundles these dirty entities into a `CleartextPayload`.
3. **Encryption:** The payload is passed to the `CryptoManager` for encryption.
4. **Push/Pull:** The app sends the encrypted data to the edge server alongside a `last_sync_timestamp`, and simultaneously receives any remote updates that occurred after that timestamp.
5. **Conflict Resolution:** Last-Write-Wins (LWW). If two devices modify the same record offline, the one with the highest `updated_at` timestamp prevails.

## 4. Cryptographic Security Model

To ensure absolute privacy, the cloud backend operates on a Zero-Knowledge basis. The client handles all encryption/decryption natively.

### PBKDF2 Key Derivation
Keys are not stored; they are derived dynamically in memory when the user initiates a sync.
* **Algorithm:** `PBKDF2WithHmacSHA256`
* **Iterations:** `65,536`
* **Salt:** The user's account username.
* **Secret:** The user's plaintext sync password.

### Authenticated Encryption (AES-GCM)
* Once the 256-bit symmetric key is derived, payloads are encrypted using `AES/GCM/NoPadding`.
* A cryptographically secure 12-byte Initialization Vector (IV) is generated for *every* update.
* The application transmits a Base64 encoded `IV:Ciphertext` string to the server, leveraging GCM's built-in authentication tag to prevent tampering.

## 5. Automated Quality & CI/CD

To maintain high software integrity, the Vaachak platform utilizes a multi-stage CI/CD pipeline:

* **Static Analysis:** Every Pull Request triggers a **Detekt** scan to enforce Kotlin performance rules and code style consistency.
* **Security Guardrails:** **GitHub CodeQL** performs deep semantic analysis of the codebase weekly to identify potential vulnerabilities in the cryptographic or networking layers.
* **Automated Supply Chain:** **Dependabot** monitors the Gradle Version Catalog (`libs.versions.toml`) to ensure third-party libraries (Timber, Ktor, Readium) are patched against known CVEs.
* **Release Orchestration:** Production artifacts (APKs) are automatically compiled, signed, and deployed to GitHub via tag-based workflows, ensuring a consistent and reproducible build environment.

## 6. Security & Compliance (OWASP Mobile Top 10)

The Vaachak architecture is engineered to mitigate the following primary mobile security risks:

* **M1: Cryptographic Failures:** Addressed via the implementation of AES-256-GCM and PBKDF2 with 65k iterations. All encryption is client-side (Zero-Knowledge).
* **M2: Insecure Data Storage:** Leverages encrypted DataStore for API keys and Room DB for local library data, ensuring no sensitive plaintext exists in the app's internal storage.
* **M4: Insecure Communication:** Enforced via Ktor TLS 1.3 configurations and strict certificate pinning possibilities for custom sync servers.
* **M8: Security Misconfiguration:** Automated scanning via CodeQL and MobSF ensures that debug features (like LeakCanary) and sensitive logging are stripped from production release artifacts.