package org.vaachak.reader.core.data.repository

import org.junit.Test

class AiManagementGrantLedgerTest {
    private var now = 100L
    private val ledger = AiManagementGrantLedger { now }
    private val binding = AiManagementBinding("fixture-vault", AiManagedSlot.GEMINI,
        AiManagementAction.REPLACE, 1, 1)

    private fun grant(): AiManagementGrantLedger.Grant =
        requireNotNull(ledger.complete(ledger.begin(binding), binding))

    @Test fun grantCanOnlyBeConsumedOnce() {
        val grant = grant()
        check(ledger.consume(grant, binding))
        check(!ledger.consume(grant, binding))
    }

    @Test fun changedVaultSlotActionRevisionOrHostCannotUseGrant() {
        val changes = listOf(binding.copy(vault = "other-vault"), binding.copy(slot = AiManagedSlot.CLOUDFLARE),
            binding.copy(action = AiManagementAction.REMOVE), binding.copy(revision = 2), binding.copy(hostEpoch = 2))
        for (changed in changes) {
            val grant = grant()
            check(!ledger.consume(grant, changed))
            check(!ledger.consume(grant, binding))
        }
    }

    @Test fun obsoleteCallbackDoesNotInvalidateNewChallenge() {
        val old = ledger.begin(binding)
        val current = ledger.begin(binding)
        check(ledger.complete(old, binding) == null)
        check(ledger.complete(current, binding) != null)
    }

    @Test fun revokedPromptOrGrantCannotBeReused() {
        val challenge = ledger.begin(binding)
        ledger.revoke()
        check(ledger.complete(challenge, binding) == null)
        val grant = grant()
        ledger.revoke()
        check(!ledger.consume(grant, binding))
    }

    @Test fun challengeExpiresAtTwoMinutes() {
        val challenge = ledger.begin(binding)
        now += 120_000L
        check(ledger.complete(challenge, binding) == null)
    }

    @Test fun grantExpiresAtThirtySeconds() {
        val grant = grant()
        now += 30_000L
        check(!ledger.consume(grant, binding))
    }

    @Test fun grantRemainsUsableBeforeDeadline() {
        val grant = grant()
        now += 29_999L
        check(ledger.consume(grant, binding))
    }

    @Test fun clockRegressionFailsClosed() {
        val grant = grant()
        now--
        check(!ledger.consume(grant, binding))
    }

    @Test fun changedContextCannotCompletePrompt() {
        val challenge = ledger.begin(binding)
        check(ledger.complete(challenge, binding.copy(revision = 2)) == null)
        check(ledger.complete(challenge, binding) == null)
    }

    @Test fun fabricatedGrantIsNotAccepted() {
        val valid = grant()
        val fabricated = AiManagementGrantLedger.Grant(binding)
        check(!ledger.consume(fabricated, binding))
        check(ledger.consume(valid, binding))
    }

    @Test fun newChallengeRevokesUnusedGrant() {
        val grant = grant()
        ledger.begin(binding.copy(action = AiManagementAction.MIGRATE))
        check(!ledger.consume(grant, binding))
    }
}
