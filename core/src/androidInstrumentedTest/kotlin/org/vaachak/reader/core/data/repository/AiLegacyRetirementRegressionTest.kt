package org.vaachak.reader.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only synthetic fixture files; no shared aliases created or deleted. */
@RunWith(AndroidJUnit4::class)
class AiLegacyRetirementRegressionTest {
    @Test fun retirementIsIdempotentAndRejectsSyncAndOpdsNamespaces() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secrets = PrivateGatewaySecrets(context)
        val vault = "d90-retirement-${UUID.randomUUID()}"
        fun file(id: String): File {
            val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            return File(context.noBackupFilesDir,"private_ai_$digest.enc")
        }
        val ai = listOf(vault,"vaachak_credential_v1:GEMINI:\u0000$vault","vaachak_credential_v1:CLOUDFLARE:\u0000$vault")
        val protected = listOf("vaachak_credential_v1:SYNC_PASSWORD:\u0000$vault","vaachak_credential_v1:OPDS:$vault:generation")
        val bytes = byteArrayOf(1,2,3,4)
        try {
            for (id in ai + protected) file(id).writeBytes(bytes)
            for (id in ai) {
                assertTrue(secrets.hasStoredCredential(id))
                secrets.retireAiCredential(id); secrets.retireAiCredential(id)
                assertFalse(secrets.hasStoredCredential(id))
            }
            for (id in protected) {
                assertTrue(runCatching { secrets.retireAiCredential(id) }.exceptionOrNull() is IllegalArgumentException)
                assertArrayEquals(bytes,file(id).readBytes())
            }
        } finally { for (id in ai + protected) file(id).delete() }
    }
}
