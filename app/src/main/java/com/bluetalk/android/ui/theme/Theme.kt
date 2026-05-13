package com.bluetalk.android.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

// Colors for the Ocean Blue theme
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4FC3F7),        // Ocean Light Blue
    onPrimary = Color(0xFF00344F),
    secondary = Color(0xFF03A9F4),
    onSecondary = Color.White,
    background = Color(0xFF010B13),     // Very dark ocean blue
    onBackground = Color(0xFFE1F5FE),   // Very light blue text
    surface = Color(0xFF0A1929),        // Dark blue gray surface
    onSurface = Color(0xFFE1F5FE),      // Very light blue text
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0277BD),        // Deep Ocean Blue
    onPrimary = Color.White,
    secondary = Color(0xFF0288D1),
    onSecondary = Color.White,
    background = Color(0xFFF0F7FA),     // Very light blue background
    onBackground = Color(0xFF010B13),   // Very dark blue text
    surface = Color.White,
    onSurface = Color(0xFF010B13),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

@Composable
fun BlueTalkTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    // App-level override from ThemePreferenceManager
    val themePref by ThemePreferenceManager.themeFlow.collectAsState(initial = ThemePreference.System)
    val shouldUseDark = when (darkTheme) {
        true -> true
        false -> false
        null -> when (themePref) {
            ThemePreference.Dark -> true
            ThemePreference.Light -> false
            ThemePreference.System -> isSystemInDarkTheme()
        }
    }

    val colorScheme = if (shouldUseDark) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    if (!shouldUseDark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (!shouldUseDark) {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else 0
            }
            window.navigationBarColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
