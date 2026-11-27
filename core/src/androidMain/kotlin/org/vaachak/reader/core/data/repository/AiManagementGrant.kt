package org.vaachak.reader.core.data.repository

/** A management grant is not an authorization session for routine inference. */
enum class AiManagedSlot { GEMINI, CLOUDFLARE, GATEWAY }
enum class AiManagementAction { REPLACE, REMOVE, MIGRATE }
internal data class AiManagementBinding(
    val vault: String,
    val slot: AiManagedSlot,
    val action: AiManagementAction,
    val revision: Long,
    val hostEpoch: Long
)

/**
 * In-memory, one-operation grant ledger. The repository checks active-vault and
 * foreground eligibility for all mutations, plus successful native authentication
 * for migration, before completing a grant. This class is not an authenticator.
 */
internal class AiManagementGrantLedger(private val clock: () -> Long) {
    internal class Challenge internal constructor(internal val binding: AiManagementBinding)
    internal class Grant internal constructor(internal val binding: AiManagementBinding)

    private var pending: Challenge? = null
    private var pendingSince = 0L
    private var granted: Grant? = null
    private var grantedSince = 0L

    @Synchronized
    fun begin(binding: AiManagementBinding): Challenge {
        require(binding.vault.isNotBlank()) { "Missing vault" }
        revoke()
        return Challenge(binding).also { pending = it; pendingSince = clock() }
    }

    @Synchronized
    fun complete(challenge: Challenge, current: AiManagementBinding): Grant? {
        // An obsolete callback must not cancel a newer pending operation.
        if (pending !== challenge) return null
        pending = null
        if (challenge.binding != current || !within(pendingSince, 120_000L)) return null
        return Grant(current).also { granted = it; grantedSince = clock() }
    }

    @Synchronized
    fun consume(grant: Grant, current: AiManagementBinding): Boolean {
        if (granted !== grant) return false
        granted = null
        return grant.binding == current && within(grantedSince, 30_000L)
    }

    @Synchronized
    fun revoke() {
        pending = null
        granted = null
    }

    private fun within(start: Long, duration: Long): Boolean {
        val now = clock()
        return now >= start && now - start < duration
    }
}
