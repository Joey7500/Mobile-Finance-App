package io.github.joey7500.vaultflow.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object VaultColors {
    val Background = Color(0xFF0B0F17)
    val Surface = Color(0xFF121826)
    val SurfaceHigh = Color(0xFF1A2233)
    val Outline = Color(0xFF263045)
    val Text = Color(0xFFE5E7EB)
    val Muted = Color(0xFF9CA3AF)
    val Emerald = Color(0xFF34D399)
    val Sky = Color(0xFF60A5FA)
    val Amber = Color(0xFFFBBF24)
    val Rose = Color(0xFFF87171)
    val Violet = Color(0xFFA78BFA)
    val Gold = Color(0xFFEAB308)

    /** Fixed order for net-worth pillars, readable on the dark background. */
    val Pillars = listOf(Sky, Gold, Emerald, Violet, Muted)
}

private val scheme = darkColorScheme(
    primary = VaultColors.Emerald,
    onPrimary = Color(0xFF052E1C),
    secondary = VaultColors.Sky,
    tertiary = VaultColors.Amber,
    error = VaultColors.Rose,
    background = VaultColors.Background,
    onBackground = VaultColors.Text,
    surface = VaultColors.Surface,
    onSurface = VaultColors.Text,
    surfaceVariant = VaultColors.SurfaceHigh,
    onSurfaceVariant = VaultColors.Muted,
    outline = VaultColors.Outline,
    surfaceContainer = VaultColors.Surface,
    surfaceContainerHigh = VaultColors.SurfaceHigh,
    surfaceContainerHighest = VaultColors.SurfaceHigh,
)

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

@Composable
fun VaultFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
