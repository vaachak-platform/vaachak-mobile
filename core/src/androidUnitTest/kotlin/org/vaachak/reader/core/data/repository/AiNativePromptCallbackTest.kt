package org.vaachak.reader.core.data.repository

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.PowerManager
import android.os.UserManager
import io.mockk.*
import java.util.concurrent.Executor
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Simulated framework callbacks exercise production gating; not native biometric qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class AiNativePromptCallbackTest {
    private class Fixture {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = mockk<Context>(relaxed = true)
        val lock = mockk<KeyguardManager>()
        val user = mockk<UserManager>()
        val power = mockk<PowerManager>()
        val callback = slot<BiometricPrompt.AuthenticationCallback>()
        val signal = slot<CancellationSignal>()
        val authenticators = slot<Int>()
        val builder = mockk<BiometricPrompt.Builder>()
        val native = mockk<BiometricPrompt>()
        val runtime: AiCredentialRuntime
        init {
            every { context.getSystemService(KeyguardManager::class.java) } returns lock
            every { context.getSystemService(UserManager::class.java) } returns user
            every { context.getSystemService(PowerManager::class.java) } returns power
            every { lock.isDeviceSecure } returns true
            every { lock.isDeviceLocked } returns false
            every { lock.isKeyguardLocked } returns false
            every { user.isUserUnlocked } returns true
            every { power.isInteractive } returns true
            runtime = AiCredentialRuntime(context)
            runtime.hostResumed(activity)
            mockkConstructor(BiometricPrompt.Builder::class)
            every { anyConstructed<BiometricPrompt.Builder>().setTitle(any()) } returns builder
            every { builder.setSubtitle(any()) } returns builder
            every { builder.setAllowedAuthenticators(capture(authenticators)) } returns builder
            every { builder.build() } returns native
            every { native.authenticate(capture(signal), any<Executor>(), capture(callback)) } just Runs
        }
        fun success(type: Int) {
            val result = mockk<BiometricPrompt.AuthenticationResult>()
            every { result.authenticationType } returns type
            callback.captured.onAuthenticationSucceeded(result)
        }
    }
    private fun withPrompt(body: suspend TestScope.(Fixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { body(Fixture()) }
        finally { unmockkConstructor(BiometricPrompt.Builder::class); Dispatchers.resetMain() }
    }

    @Test fun migrationAcceptsOnlyDeviceCredential() = withPrompt { f ->
        for ((type, expected) in listOf(
            BiometricPrompt.AUTHENTICATION_RESULT_TYPE_BIOMETRIC to false,
            BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL to true
        )) {
            val work = async { f.runtime.authenticateMigration() }
            runCurrent()
            assertEquals(DEVICE_CREDENTIAL, f.authenticators.captured)
            f.success(type)
            runCurrent()
            assertEquals(expected, work.await())
            assertTrue(f.signal.captured.isCanceled)
        }
    }

    @Test fun nullResultAndNativeErrorCannotAuthorize() = withPrompt { f ->
        val first = async { f.runtime.authenticateMigration() }; runCurrent()
        f.callback.captured.onAuthenticationSucceeded(null); runCurrent()
        assertFalse(first.await())
        val second = async { f.runtime.authenticateMigration() }; runCurrent()
        f.callback.captured.onAuthenticationError(5, "synthetic cancellation"); runCurrent()
        assertFalse(second.await())
        assertTrue(f.signal.captured.isCanceled)
    }

    @Test fun nativePinHandoffWaitsForTheSameHostToResume() = withPrompt { f ->
        val work = async { f.runtime.authenticateMigration() }; runCurrent()
        f.runtime.hostPaused(f.activity); f.runtime.hostStopped(f.activity)
        assertFalse(f.runtime.eligible())
        f.success(BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL); runCurrent()
        assertFalse(work.isCompleted)
        f.runtime.hostResumed(f.activity)
        advanceTimeBy(51); runCurrent()
        assertTrue(work.await())
    }

    @Test fun leavingHostRevokesEvenALateSuccess() = withPrompt { f ->
        val work = async { f.runtime.authenticateMigration() }; runCurrent()
        val old = f.callback.captured
        f.runtime.hostUserLeft(f.activity)
        assertTrue(f.signal.captured.isCanceled)
        f.runtime.hostResumed(f.activity)
        val result = mockk<BiometricPrompt.AuthenticationResult>()
        every { result.authenticationType } returns BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL
        old.onAuthenticationSucceeded(result); runCurrent()
        assertFalse(work.await())
    }

    @Test fun nativeFailureAndTimeoutCancelSignalWithoutGrant() = withPrompt { f ->
        every { f.builder.build() } throws IllegalStateException("synthetic native failure")
        assertFalse(f.runtime.authenticateMigration())
        every { f.builder.build() } returns f.native
        val work = async { f.runtime.authenticateMigration() }; runCurrent()
        advanceTimeBy(110_001); runCurrent()
        assertFalse(work.await()); assertTrue(f.signal.captured.isCanceled)
        f.callback.captured.onAuthenticationError(5, null)
    }

    @Test fun successfulCallbackWithoutForegroundTimesOutAndCancellationPropagates() = withPrompt { f ->
        val work = async { f.runtime.authenticateMigration() }; runCurrent()
        f.runtime.hostPaused(f.activity)
        f.success(BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL); runCurrent()
        advanceTimeBy(5_001); runCurrent()
        assertFalse(work.await())
        f.runtime.hostResumed(f.activity)
        val canceled = async { f.runtime.authenticateMigration() }; runCurrent()
        canceled.cancelAndJoin(); assertTrue(f.signal.captured.isCanceled)
        assertTrue(canceled.isCancelled)
        f.callback.captured.onAuthenticationError(5, null)
    }
}
