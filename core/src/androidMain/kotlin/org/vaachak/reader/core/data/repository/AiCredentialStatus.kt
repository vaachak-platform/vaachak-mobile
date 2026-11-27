package org.vaachak.reader.core.data.repository

/** Local metadata only; SAVED is not proof of provider validity. */
enum class AiCredentialStatus {
    MISSING, SAVED, UNAVAILABLE, MIGRATION_REQUIRED, RECOVERY_REQUIRED, CLEANUP_REQUIRED, REMOVAL_PENDING
}

class AiConfigurationUpdate(
    val provider: org.vaachak.reader.core.domain.model.AiTextProvider,
    val cloudflareUrl: String,
    val gatewayUrl: String,
    val gatewayModel: String,
    val autoSaveRecaps: Boolean,
    // Null means preserve the existing model, not clear it.
    val geminiModel: String? = null,
    // The grouped editor must never rewrite the inactive provider’s settings.
    val preserveInactive: Boolean = false
)

/** One vault's nonsecret editor metadata from a single preference emission. */
data class AiEditorSnapshot(
    val configuration: org.vaachak.reader.core.domain.model.AiConfig,
    val geminiModel: String,
    val offline: Boolean
)
