package com.fishese.moneyfly.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** Full-screen dialogs have their own window; match its icons to the actual theme. */
@Composable
internal fun DialogSystemBars() {
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    val window = (view.parent as? DialogWindowProvider)?.window
    SideEffect {
        if (window != null) WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
