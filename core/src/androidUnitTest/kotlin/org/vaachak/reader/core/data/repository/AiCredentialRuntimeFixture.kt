package org.vaachak.reader.core.data.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex

/** JVM-only fake for the Android OS boundary. The real repository/ledger/markers are exercised. */
internal class AiCredentialRuntimeFixture {
    val runtime = mockk<AiCredentialRuntime>()
    val storage = mockk<AiPersistentCredentialStorage>()
    val values = mutableMapOf<Pair<String, AiManagedSlot>, String>()
    var authenticated = true
    var eligible = true
    var failWrite = false
    var failRemove = false
    var managementEpoch = 0L
    var requestEpoch = 0L
    var beforeAuth: (() -> Unit)? = null
    var authCalls = 0
    init {
        every { runtime.storage } returns storage
        every { runtime.eligible() } answers { eligible }
        every { runtime.eligibility() } answers { flowOf(eligible) }
        every { runtime.managementEpoch() } answers { managementEpoch }
        every { runtime.requestEpoch() } answers { requestEpoch }
        every { runtime.invalidated(any()) } answers { flowOf(!eligible || firstArg<Long>() != requestEpoch) }
        coEvery { runtime.authenticateMigration() } coAnswers {
            authCalls++
            beforeAuth?.invoke()
            authenticated
        }
        every { storage.presence(any(), any()) } answers {
            if (!values.containsKey(firstArg<String>() to secondArg<AiManagedSlot>())) AiPersistentCredentialStorage.Presence.MISSING
            else if (!eligible) AiPersistentCredentialStorage.Presence.UNAVAILABLE else AiPersistentCredentialStorage.Presence.SAVED
        }
        every { storage.read(any(), any()) } answers {
            if (!eligible) throw GatewayCredentialUnavailableException("Fixture locked")
            values[firstArg<String>() to secondArg<AiManagedSlot>()]
        }
        every { storage.writeVerified(any(), any(), any(), any(), any()) } answers {
            val binding = firstArg<AiManagementBinding>()
            check(thirdArg<AiManagementGrantLedger>().consume(arg(3), binding) && arg<() -> Boolean>(4).invoke())
            if (failWrite) throw GatewayCredentialUnavailableException("Fixture write failed")
            values[binding.vault to binding.slot] = secondArg<String>()
            Unit
        }
        every { storage.removeVerified(any(), any(), any(), any()) } answers {
            val binding = firstArg<AiManagementBinding>()
            check(secondArg<AiManagementGrantLedger>().consume(thirdArg(), binding) && arg<() -> Boolean>(3).invoke())
            if (failRemove) throw GatewayCredentialUnavailableException("Fixture remove failed")
            values.remove(binding.vault to binding.slot)
            Unit
        }
    }
}

internal fun configureAiVault(vault: VaultRepository, identity: MutableStateFlow<String>) {
    every { vault.persistedVaultIdentity } returns identity
    every { vault.aiMutationMutex } returns Mutex()
    every { vault.aiVaultEpoch } returns MutableStateFlow(0L)
}

internal fun configureLegacyAiStorage(secrets: PrivateGatewaySecrets, values: MutableMap<String, String>) {
    every { secrets.hasStoredCredential(any()) } answers { values.containsKey(firstArg<String>()) }
    every { secrets.retireAiCredential(any()) } answers { values.remove(firstArg<String>()); Unit }
}
