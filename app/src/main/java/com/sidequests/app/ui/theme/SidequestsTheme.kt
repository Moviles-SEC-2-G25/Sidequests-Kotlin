package com.sidequests.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.sidequests.app.R

val ExplorerIndigo = Color(0xFF5B4BDB)
val ExplorerIndigoLight = Color(0xFF7B6DE8)
val QuestAmber = Color(0xFFFFB347)
val DiscoveryTeal = Color(0xFF2AB7A9)
val DiscoveryTealDark = Color(0xFF1E9E92)

val LightBackground = Color(0xFFF6F5FF)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceAlt = Color(0xFFEEEAFF)
val LightText = Color(0xFF1A1830)
val LightBorder = Color(0xFFDDD9FF)

val DarkBackground = Color(0xFF0E0D1A)
val DarkSurface = Color(0xFF19182D)
val DarkSurfaceAlt = Color(0xFF232140)
val DarkText = Color(0xFFEDE9FF)
val DarkBorder = Color(0xFF2C2A4A)

private val LightColors = lightColorScheme(
    primary = ExplorerIndigo,
    secondary = DiscoveryTeal,
    tertiary = QuestAmber,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceAlt,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = LightText,
    onSurface = LightText,
    onSurfaceVariant = Color(0xFF6B6990),
    primaryContainer = LightSurfaceAlt,
    onPrimaryContainer = LightText,
    secondaryContainer = Color(0xFFF0FFFE),
    onSecondaryContainer = Color(0xFF113D36),
    outline = LightBorder,
)

private val DarkColors = darkColorScheme(
    primary = ExplorerIndigoLight,
    secondary = DiscoveryTeal,
    tertiary = QuestAmber,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceAlt,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = DarkText,
    onSurface = DarkText,
    onSurfaceVariant = Color(0xFFA6A3BF),
    primaryContainer = DarkSurfaceAlt,
    onPrimaryContainer = DarkText,
    secondaryContainer = Color(0xFF142824),
    onSecondaryContainer = Color(0xFF91E6D9),
    outline = DarkBorder,
)

@OptIn(ExperimentalTextApi::class)
private fun variableFont(resId: Int) = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black)
        .map { weight ->
            Font(resId, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
        },
)

private val Outfit = variableFont(R.font.outfit)
private val DmSans = variableFont(R.font.dm_sans)
private val MaterialTypography = Typography()

val SidequestsTypography = with(MaterialTypography) { Typography(
    displayLarge = displayLarge.copy(fontFamily = Outfit),
    displayMedium = displayMedium.copy(fontFamily = Outfit),
    displaySmall = displaySmall.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineLarge = headlineLarge.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    headlineMedium = headlineMedium.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    headlineSmall = headlineSmall.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    titleLarge = titleLarge.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    titleMedium = titleMedium.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    titleSmall = titleSmall.copy(fontFamily = Outfit, fontWeight = FontWeight.SemiBold),
    bodyLarge = bodyLarge.copy(fontFamily = DmSans),
    bodyMedium = bodyMedium.copy(fontFamily = DmSans),
    bodySmall = bodySmall.copy(fontFamily = DmSans),
    labelLarge = labelLarge.copy(fontFamily = DmSans, fontWeight = FontWeight.Bold),
    labelMedium = labelMedium.copy(fontFamily = DmSans, fontWeight = FontWeight.SemiBold),
    labelSmall = labelSmall.copy(fontFamily = DmSans, fontWeight = FontWeight.SemiBold),
)
}

@Composable
fun SidequestsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = SidequestsTypography,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
    }
}
