package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier

/** Desktop mouse navigation hooks; touch platforms intentionally do nothing. */
internal expect fun Modifier.handleMouseBackForward(
    onBack: () -> Unit,
    onForward: () -> Unit,
): Modifier

/** Prevents browser-style mouse buttons from escaping a modal player surface. */
internal expect fun Modifier.consumeMouseBackForwardButtons(): Modifier

/** Dismisses desktop hover previews when the pointer wheel scrolls. */
internal expect fun Modifier.dismissOnPointerScroll(onScroll: () -> Unit): Modifier
