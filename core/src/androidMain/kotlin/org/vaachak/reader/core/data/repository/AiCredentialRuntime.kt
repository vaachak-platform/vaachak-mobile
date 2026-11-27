package org.vaachak.reader.core.data.repository

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.UserManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/** Only the real foreground host supplies lifecycle events; never stores plaintext. */
@Singleton
class AiCredentialRuntime @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val monitor = Any()
    private var host = WeakReference<Activity>(null)
    private var resumed = false
    private var managementGeneration = 0L
    private var requestGeneration = 0L
    internal val storage = AiPersistentCredentialStorage(context, ::eligible)

    init {
        // Screen-off invalidates even if the OEM delivers activity callbacks late.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) invalidate()
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
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
            invalidateLocked()
        }
    }

    fun hostStopped(activity: Activity): Unit = synchronized(monitor) {
        if (host.get() === activity) {
            resumed = false
            host.clear()
            invalidateLocked()
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
}
