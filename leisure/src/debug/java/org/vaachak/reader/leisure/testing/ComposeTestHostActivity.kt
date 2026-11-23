package org.vaachak.reader.leisure.testing

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity

/**
 * Debug-only, isolated host for Compose instrumentation tests.
 *
 * The Compose test rule owns setContent() and the Activity lifecycle; this
 * Activity deliberately does not install any application UI or depend on Hilt.
 *
 * The complete Settings/AI test suite can outlast a phone's normal screen-off
 * timeout. An invisible/stopped window has no registered Compose test roots,
 * which produces the misleading "No compose hierarchies found" error. Keeping
 * this *debug-only* host's screen on prevents that avoidable transition while
 * the instrumentation Activity is visible. It does not bypass the keyguard.
 */
class ComposeTestHostActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "onCreate; test host keeps its visible screen awake")
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "onResume")
    }

    override fun onPause() {
        Log.d(TAG, "onPause; finishing=$isFinishing, changingConfig=$isChangingConfigurations")
        super.onPause()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy; finishing=$isFinishing, changingConfig=$isChangingConfigurations")
        super.onDestroy()
    }

    private companion object {
        const val TAG = "VaachakComposeHost"
    }
}
