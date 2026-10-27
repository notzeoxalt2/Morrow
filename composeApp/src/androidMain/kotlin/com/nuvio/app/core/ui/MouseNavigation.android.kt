package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier

internal actual fun Modifier.handleMouseBackForward(onBack: () -> Unit, onForward: () -> Unit): Modifier = this
internal actual fun Modifier.consumeMouseBackForwardButtons(): Modifier = this
internal actual fun Modifier.dismissOnPointerScroll(onScroll: () -> Unit): Modifier = this
