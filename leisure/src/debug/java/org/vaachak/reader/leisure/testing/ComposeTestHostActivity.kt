package org.vaachak.reader.leisure.testing

import androidx.activity.ComponentActivity

/**
 * Debug-only host for isolated Compose instrumentation tests.
 *
 * This Activity deliberately does not call setContent(): the Compose rule owns
 * the content and lifecycle. It has no launcher intent filter, no credentials,
 * no navigation side effects, and is non-exported.
 */
class ComposeTestHostActivity : ComponentActivity()
