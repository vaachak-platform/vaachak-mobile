package org.vaachak.reader.core.data.repository

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.vaachak.reader.core.data.local.OpdsDao
import org.vaachak.reader.core.domain.model.OpdsEntity

/** Production lifecycle over synthetic rows and ciphertext backend, with no external network. */
internal class OpdsRepositoryHarness {
    val rows = MemoryLifecycleDao()
    val backend = FakeOpdsSecretBackend()
    val lifecycle = OpdsCredentialLifecycle(rows, OpdsCredentialStorage(backend), MemoryMigrationJournal(rows))
    val visible = mockk<OpdsDao>()
    val repository: OpdsRepository
    init {
        every { visible.getAllFeeds() } returns flowOf(emptyList())
        repository = OpdsRepository(visible, lifecycle)
        repository.setAbsoluteUrlResolverForTest { mockk(relaxed = true) }
    }
    suspend fun authenticated(url: String, username: String, password: String): OpdsEntity {
        val id = lifecycle.create("Fixture", url, OpdsCredentialEdit.Replace(OpdsCredentialPair(username, password)))
        return checkNotNull(rows.rows[id])
    }
    suspend fun anonymous(url: String): OpdsEntity {
        val id = lifecycle.create("Public fixture", url, OpdsCredentialEdit.Remove)
        return checkNotNull(rows.rows[id])
    }
}
