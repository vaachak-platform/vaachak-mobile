package org.vaachak.reader.core.data.repository

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.UserManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** OS service fakes test real lifecycle/epoch logic, not native prompt success. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class AiCredentialRuntimeTest {
    private class Fixture {
        val context = mockk<Context>(relaxed = true)
        val lock = mockk<KeyguardManager>()
        val user = mockk<UserManager>()
        val power = mockk<PowerManager>()
        val receiver = slot<BroadcastReceiver>()
        val host = mockk<Activity>(relaxed = true)
        val other = mockk<Activity>(relaxed = true)
        val runtime: AiCredentialRuntime
        init {
            every { context.getSystemService(KeyguardManager::class.java) } returns lock
            every { context.getSystemService(UserManager::class.java) } returns user
            every { context.getSystemService(PowerManager::class.java) } returns power
            every { context.registerReceiver(capture(receiver), any<IntentFilter>(), any<Int>()) } returns null
            every { lock.isDeviceSecure } returns true
            every { lock.isDeviceLocked } returns false
            every { lock.isKeyguardLocked } returns false
            every { user.isUserUnlocked } returns true
            every { power.isInteractive } returns true
            runtime = AiCredentialRuntime(context)
        }
    }

    @Test fun foregroundHostEpochsAndStaleCallbacks() = runTest {
        val f = Fixture(); val r = f.runtime
        assertFalse(r.eligible())
        r.hostResumed(f.host)
        assertTrue(r.eligible())
        val epoch = r.requestEpoch(); val management = r.managementEpoch()
        assertFalse(r.invalidated(epoch).first())
        r.hostPaused(f.other); r.hostStopped(f.other); r.hostUserLeft(f.other); r.hostDestroyed(f.other)
        assertEquals(epoch, r.requestEpoch()); assertTrue(r.eligible())
        r.hostPaused(f.host)
        assertFalse(r.eligible()); assertTrue(r.invalidated(epoch).first())
        assertTrue(r.managementEpoch() > management)
        r.hostResumed(f.host)
        assertTrue(r.eligibility().first())
        r.hostStopped(f.host)
        assertFalse(r.eligible())
        r.hostResumed(f.host)
        val old = r.requestEpoch()
        r.hostResumed(f.other)
        assertTrue(r.invalidated(old).first()); assertTrue(r.eligible())
        r.hostUserLeft(f.other)
        assertFalse(r.eligible())
        r.hostResumed(f.other); r.hostDestroyed(f.other)
        assertFalse(r.eligible())
    }

    @Test fun everyDeviceEligibilityConditionFailsClosed() = runTest {
        val f = Fixture(); val r = f.runtime
        r.hostResumed(f.host)
        assertTrue(r.eligible())
        every { f.host.isFinishing } returns true
        assertFalse(r.eligible())
        every { f.host.isFinishing } returns false
        every { f.user.isUserUnlocked } returns false
        assertFalse(r.eligible())
        every { f.user.isUserUnlocked } returns true
        every { f.lock.isDeviceSecure } returns false
        assertFalse(r.eligible())
        every { f.lock.isDeviceSecure } returns true
        every { f.lock.isDeviceLocked } returns true
        assertFalse(r.eligible())
        every { f.lock.isDeviceLocked } returns false
        every { f.lock.isKeyguardLocked } returns true
        assertFalse(r.eligible())
        every { f.lock.isKeyguardLocked } returns false
        every { f.power.isInteractive } returns false
        assertFalse(r.eligible())
        every { f.power.isInteractive } returns true
        every { f.context.getSystemService(PowerManager::class.java) } returns null
        assertFalse(r.eligible())
        every { f.context.getSystemService(PowerManager::class.java) } returns f.power
        every { f.context.getSystemService(UserManager::class.java) } returns null
        assertFalse(r.eligible())
        every { f.context.getSystemService(UserManager::class.java) } returns f.user
        every { f.context.getSystemService(KeyguardManager::class.java) } returns null
        assertFalse(r.eligible())
    }

    @Test fun screenOffInvalidatesAndIneligibleAuthenticationNeverStarts() = runTest {
        val f = Fixture(); val r = f.runtime
        r.hostResumed(f.host)
        val epoch = r.requestEpoch(); val management = r.managementEpoch()
        f.receiver.captured.onReceive(f.context, Intent(Intent.ACTION_SCREEN_ON))
        assertEquals(epoch, r.requestEpoch())
        every { f.power.isInteractive } returns false
        f.receiver.captured.onReceive(f.context, Intent(Intent.ACTION_SCREEN_OFF))
        assertTrue(r.invalidated(epoch).first())
        assertTrue(r.managementEpoch() > management)
        assertFalse(r.eligibility().first())
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            assertFalse(r.authenticateMigration())
            every { f.power.isInteractive } returns true
            r.hostPaused(f.host)
            assertFalse(r.authenticateMigration())
        } finally { Dispatchers.resetMain() }
    }
}
