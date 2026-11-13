package dev.bookah.photos.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours from docs/design-spec.md, section 1. */
@Immutable
data class PhotoColors(
    val background: Color,
    val surface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val barFill: Color,
    val hairline: Color,
    val viewerBackground: Color = Color.Black,
)

private val LightColors = PhotoColors(
    background = Color(0xFFFFFFFF), surface = Color(0xFFF2F2F5),
    textPrimary = Color(0xFF111114), textSecondary = Color(0xFF6B6B73),
    accent = Color(0xFF2F6FED), barFill = Color(0xD9FFFFFF), hairline = Color(0x14000000),
)
private val DarkColors = PhotoColors(
    background = Color(0xFF0B0B0D), surface = Color(0xFF1C1C1F),
    textPrimary = Color(0xFFF5F5F7), textSecondary = Color(0xFFA0A0A8),
    accent = Color(0xFF5B8DF5), barFill = Color(0xD91C1C1F), hairline = Color(0x1FFFFFFF),
)

val LocalPhotoColors = staticCompositionLocalOf { LightColors }

/** Spacing and radii from the spec (dp). */
object Dims {
    val sideMargin = 20.dp
    val sheetMargin = 16.dp
    val hit = 44.dp
    val roundButton = 44.dp
    val cardRadius = 20.dp
    val largeCardRadius = 24.dp
}

object PhotoTheme {
    val colors: PhotoColors @Composable get() = LocalPhotoColors.current
}

/** The type scale of the spec: large title 34, title 22, section 22, body 17, secondary 15, caption 13 (sp). System font. */
private val PhotoTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 16.sp),
)

@Composable
fun BookahPhotosTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) darkColorScheme(
        primary = c.accent, background = c.background, surface = c.background, surfaceVariant = c.surface,
        onBackground = c.textPrimary, onSurface = c.textPrimary, onSurfaceVariant = c.textSecondary,
    ) else lightColorScheme(
        primary = c.accent, background = c.background, surface = c.background, surfaceVariant = c.surface,
        onBackground = c.textPrimary, onSurface = c.textPrimary, onSurfaceVariant = c.textSecondary,
    )
    CompositionLocalProvider(LocalPhotoColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = PhotoTypography, content = content)
    }
}
