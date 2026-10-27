package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.features.settings.NavBarStyle
import dev.chrisbanes.haze.HazeState

internal actual val floatingNavigationGlowSupported: Boolean
    get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU

@Composable
internal actual fun FloatingNavigationBar(
    items: List<FloatingNavigationItem>,
    modifier: Modifier,
    scrollState: NuvioNavBarScrollState?,
    hazeState: HazeState?,
    contentPadding: PaddingValues,
    compactSize: Boolean,
    glowEnabled: Boolean,
) {
    NuvioNavigationBar(
        modifier = modifier,
        scrollState = scrollState,
        hazeState = hazeState,
        navBarStyle = NavBarStyle.ADAPTIVE,
        contentPadding = contentPadding,
        compactSize = compactSize,
    ) {
        items.forEach { item ->
            when {
                item.icon != null -> NavItem(
                    selected = item.selected,
                    onClick = item.onClick,
                    icon = item.icon,
                    contentDescription = item.label,
                    label = item.label,
                )
                item.drawable != null -> NavItem(
                    selected = item.selected,
                    onClick = item.onClick,
                    icon = item.drawable,
                    contentDescription = item.label,
                    label = item.label,
                )
                item.content != null -> NavItem(
                    selected = item.selected,
                    onClick = item.onClick,
                    label = item.label,
                ) {
                    item.content(item.onClick)
                }
            }
        }
    }
}
