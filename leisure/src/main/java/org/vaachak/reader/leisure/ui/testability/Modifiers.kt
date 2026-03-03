package org.vaachak.reader.leisure.ui.testability

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

fun Modifier.tid(id: String): Modifier = semantics {
    contentDescription = id
}

fun Modifier.testState(state: String): Modifier = semantics {
    stateDescription = state
}
