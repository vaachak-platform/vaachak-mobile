package org.vaachak.reader.core.data.repository

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.PowerManager
import android.os.UserManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Only the real foreground host supplies lifecycle events; never stores plaintext. */
@Singleton
class AiCredentialRuntime @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val monitor = Any()
    private var host = WeakReference<Activity>(null)
    private var resumed = false
    private var managementGeneration = 0L
    private var requestGeneration = 0L
    private var prompt: CancellationSignal? = null
    internal val storage = AiPersistentCredentialStorage(context, ::eligible)

    init {
        // Screen-off invalidates even if the OEM delivers activity callbacks late.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) invalidate()
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }

    fun hostResumed(activity: Activity): Unit = synchronized(monitor) {
        if (host.get() !== activity) {
            invalidateLocked()
            host = WeakReference(activity)
        }
        resumed = true
    }

    fun hostPaused(activity: Activity): Unit = synchronized(monitor) {
        if (host.get() === activity) {
            resumed = false
            requestGeneration++
            // The OS credential UI can pause its caller. Stop/screen-off/rotation
            // still revoke the pending operation; no inference while paused.
            if (prompt == null) managementGeneration++
        }
    }

    fun hostStopped(activity: Activity): Unit = synchronized(monitor) {
        if (host.get() === activity) {
            resumed = false
            requestGeneration++
            // A full-screen native PIN confirmation can stop its caller. Keep only
            // the pending OS handoff, not a grant or foreground authorization.
            // User-leave, destruction, screen-off, cancellation and timeout revoke it.
            if (prompt == null || activity.isChangingConfigurations || activity.isFinishing) {
                host.clear()
                invalidateLocked()
            }
        }
    }

    fun hostUserLeft(activity: Activity): Unit = synchronized(monitor) {
        if (host.get() === activity) {
            resumed = false
            invalidateLocked()
        }
    }

    fun hostDestroyed(activity: Activity): Unit = synchronized(monitor) {
        if (host.get() === activity) {
            resumed = false
            host.clear()
            invalidateLocked()
        }
    }

    private fun invalidate() = synchronized(monitor) { invalidateLocked() }
    private fun invalidateLocked() {
        managementGeneration++
        requestGeneration++
        prompt?.cancel()
        prompt = null
    }

    internal fun managementEpoch(): Long = synchronized(monitor) { managementGeneration }
    internal fun requestEpoch(): Long = synchronized(monitor) { requestGeneration }
    internal fun eligible(): Boolean {
        if (!synchronized(monitor) { resumed && host.get()?.isFinishing == false }) return false
        val lock = context.getSystemService(KeyguardManager::class.java) ?: return false
        val user = context.getSystemService(UserManager::class.java) ?: return false
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return user.isUserUnlocked && lock.isDeviceSecure && !lock.isDeviceLocked &&
            !lock.isKeyguardLocked && power.isInteractive
    }

    internal fun eligibility(): Flow<Boolean> = flow {
        while (true) {
            emit(eligible())
            delay(200)
        }
    }.distinctUntilChanged()

    internal fun invalidated(epoch: Long): Flow<Boolean> = flow {
        while (true) {
            val invalid = requestEpoch() != epoch || !eligible()
            emit(invalid)
            if (invalid) return@flow
            delay(100)
        }
    }.distinctUntilChanged()

    /** No caller-supplied authentication result. Only the native callback can succeed. */
    internal suspend fun authenticateMigration(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (!eligible()) return@withContext false
        val activity = synchronized(monitor) { host.get() } ?: return@withContext false
        val epoch = managementEpoch()
        val signal = CancellationSignal()
        try {
        val ok =
            withTimeoutOrNull(110_000) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    synchronized(monitor) { prompt = signal }
                    continuation.invokeOnCancellation { signal.cancel() }
                    try {
                        BiometricPrompt.Builder(activity)
                            .setTitle("Migrate saved AI credential")
                            .setSubtitle("Confirm your identity to authorize this change")
                            .setAllowedAuthenticators(DEVICE_CREDENTIAL)
                            .build().authenticate(signal, activity.mainExecutor,
                                object : BiometricPrompt.AuthenticationCallback() {
                                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                                        val type = result?.authenticationType
                                        val valid = type == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL
                                        if (continuation.isActive) continuation.resume(valid)
                                    }
                                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                                        if (continuation.isActive) continuation.resume(false)
                                    }
                                })
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            } == true
            if (!ok || managementEpoch() != epoch) return@withContext false
            // The native PIN activity may return before onResume. Never commit in the background.
            withTimeoutOrNull(5_000) {
                while (managementEpoch() == epoch && !eligible()) delay(50)
                managementEpoch() == epoch && eligible() && synchronized(monitor) { host.get() === activity }
            } == true
        } finally {
            synchronized(monitor) { if (prompt === signal) prompt = null }
            signal.cancel()
        }
    }
}
