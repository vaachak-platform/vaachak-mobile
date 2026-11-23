package org.vaachak.reader.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelCoverageTest {
    @Test fun aiConfigPreservesGatewayFieldsAcrossProviderChanges() {
        val empty = AiConfig()
        assertEquals(AiTextProvider.GEMINI, empty.textProvider)
        assertEquals("", empty.gatewayUrl)
        assertEquals("local-chat", empty.gatewayModel)
        val privateConfig = empty.copy(
            textProvider = AiTextProvider.PRIVATE_GATEWAY,
            gatewayUrl = "https://gateway.example.test/v1",
            gatewayModel = "small-model"
        )
        assertEquals(AiTextProvider.PRIVATE_GATEWAY, privateConfig.textProvider)
        assertEquals("small-model", privateConfig.gatewayModel)
        assertEquals(AiTextProvider.GEMINI, privateConfig.copy(textProvider = AiTextProvider.GEMINI).textProvider)
    }

    @Test fun profileAndEditSectionsExposeSafeDefaults() {
        val profile = UserProfile()
        assertNull(profile.username)
        assertFalse(profile.isAuthenticated)
        assertEquals(0L, profile.lastSyncTime)
        val authenticated = profile.copy(username = "reader", isAuthenticated = true)
        assertTrue(authenticated.isAuthenticated)
        assertEquals(SettingsSection.NONE, SettingsSection.valueOf("NONE"))
        assertTrue(SettingsSection.entries.contains(SettingsSection.AI_CONFIG))
        assertTrue(SettingsSection.entries.contains(SettingsSection.SYNC_CONFIG))
    }
}
