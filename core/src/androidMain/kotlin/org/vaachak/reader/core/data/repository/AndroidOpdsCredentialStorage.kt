package org.vaachak.reader.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/** Reuse the existing authenticated, no-backup AES-GCM backend without changing its key policy. */
@Singleton
internal class AndroidOpdsCredentialStorage @Inject constructor(secrets: PrivateGatewaySecrets) {
    val storage = OpdsCredentialStorage(object : OpdsSecretBackend {
        override fun read(id: String): String = secrets.read(id)
        override fun write(id: String, value: String) = secrets.write(id, value)
        override fun clear(id: String) = secrets.clear(id)
    })
}
