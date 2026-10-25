package com.fishese.moneyfly.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DayPalette = lightColorScheme(
    primary = Color(0xFF205C46), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBD8), onPrimaryContainer = Color(0xFF163E2B),
    secondary = Color(0xFF5B6C50), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDF1DC), onSecondaryContainer = Color(0xFF303F28),
    tertiary = Color(0xFF526977), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCEAF1), onTertiaryContainer = Color(0xFF273D49),
    background = Color(0xFFF7F8F2), onBackground = Color(0xFF202B24),
    surface = Color(0xFFFEFFF9), onSurface = Color(0xFF202B24),
    surfaceVariant = Color(0xFFEBEFE5), onSurfaceVariant = Color(0xFF586259),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF2F5EC),
    surfaceContainer = Color(0xFFEDF1E7), surfaceContainerHigh = Color(0xFFE6ECDF),
    surfaceContainerHighest = Color(0xFFDFE7D8),
    outline = Color(0xFF788477), outlineVariant = Color(0xFFDCE3D5),
    error = Color(0xFFAC3E36), errorContainer = Color(0xFFFFE7E1), onErrorContainer = Color(0xFF74251E),
)

private val NightPalette = darkColorScheme(
    primary = Color(0xFFA5D6B4), onPrimary = Color(0xFF123B26),
    primaryContainer = Color(0xFF284F37), onPrimaryContainer = Color(0xFFD3ECD3),
    secondary = Color(0xFFC2CCAA), onSecondary = Color(0xFF293B23),
    secondaryContainer = Color(0xFF3D4B32), onSecondaryContainer = Color(0xFFE1E8CD),
    tertiary = Color(0xFFAECBD9), onTertiary = Color(0xFF173441),
    background = Color(0xFF111812), onBackground = Color(0xFFE5EBDF),
    surface = Color(0xFF151D17), onSurface = Color(0xFFE5EBDF),
    surfaceVariant = Color(0xFF2C392E), onSurfaceVariant = Color(0xFFB8C4B5),
    surfaceContainerLowest = Color(0xFF0E150F), surfaceContainerLow = Color(0xFF19231B),
    surfaceContainer = Color(0xFF1E2A21), surfaceContainerHigh = Color(0xFF263329),
    surfaceContainerHighest = Color(0xFF303D32),
    outline = Color(0xFF8B9A88), outlineVariant = Color(0xFF3C4A3D),
    error = Color(0xFFFFB4A7), errorContainer = Color(0xFF68312A), onErrorContainer = Color(0xFFFFDAD2),
)

private val JournalTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp, fontFeatureSettings = "tnum"),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 29.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp),
)

@Composable
internal fun MoneyFlyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) NightPalette else DayPalette,
        typography = JournalTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
