package org.vaachak.reader.leisure.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLayoutPolicyTest {
    @Test fun compactPhoneUsesOnePane() {
        assertFalse(SettingsLayoutPolicy.useTwoPane(393f, 1f))
    }

    @Test fun tabletLandscapeUsesTwoPanes() {
        assertTrue(SettingsLayoutPolicy.useTwoPane(980f, 1f))
    }

    @Test fun tabletWithLargeTextDoesNotCrowdTwoPanes() {
        assertFalse(SettingsLayoutPolicy.useTwoPane(820f, 1.4f))
        assertTrue(SettingsLayoutPolicy.useTwoPane(1024f, 1.4f))
    }

    @Test fun resizableWindowIsHandledWithoutDeviceChecks() {
        assertFalse(SettingsLayoutPolicy.useTwoPane(690f, 1f))
        assertTrue(SettingsLayoutPolicy.useTwoPane(740f, 1f))
    }
}
