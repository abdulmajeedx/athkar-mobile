package com.athkar.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

/**
 * The app's own palette rather than Material's defaults or a dynamic-colour scheme: an athkar app is
 * read slowly and often at night, so the surfaces are warm and low-contrast by day and genuinely
 * dark — not grey — after sunset, with a single gold accent reserved for the element that matters
 * on each screen (the next prayer, the qibla needle, a completed dhikr).
 */
private val EmeraldDeep = Color(0xFF0B5D4A)
private val EmeraldMid = Color(0xFF11785F)
private val EmeraldSoft = Color(0xFFD7EFE6)
private val EmeraldBright = Color(0xFF5FD3B0)

private val GoldDeep = Color(0xFF9A7B12)
private val GoldMid = Color(0xFFC9A227)
private val GoldSoft = Color(0xFFF6ECCB)
private val GoldBright = Color(0xFFE5C158)

private val Ivory = Color(0xFFFBF8F1)
private val IvorySurface = Color(0xFFFFFFFF)
private val IvoryMuted = Color(0xFFF1EDE3)

private val NightBackground = Color(0xFF0D1411)
private val NightSurface = Color(0xFF16201C)
private val NightMuted = Color(0xFF1F2C27)

private val InkPrimary = Color(0xFF17211D)
private val InkSecondary = Color(0xFF5C6B65)
private val MoonPrimary = Color(0xFFE8F1ED)
private val MoonSecondary = Color(0xFF9BB0A8)

private val DangerLight = Color(0xFFB3261E)
private val DangerDark = Color(0xFFFFB4AB)

private val LightColors = lightColorScheme(
    primary = EmeraldDeep,
    onPrimary = Color.White,
    primaryContainer = EmeraldSoft,
    onPrimaryContainer = EmeraldDeep,
    secondary = GoldDeep,
    onSecondary = Color.White,
    secondaryContainer = GoldSoft,
    onSecondaryContainer = GoldDeep,
    tertiary = EmeraldMid,
    onTertiary = Color.White,
    background = Ivory,
    onBackground = InkPrimary,
    surface = IvorySurface,
    onSurface = InkPrimary,
    surfaceVariant = IvoryMuted,
    onSurfaceVariant = InkSecondary,
    // Dialogs, menus and the unchecked track of every switch are painted from these three roles,
    // and Material's baseline for them is lavender — the same colour the navigation bar had to be
    // named explicitly to escape. Unset, they put it back on half the surfaces in the app.
    surfaceContainer = IvoryMuted,
    surfaceContainerHigh = IvorySurface,
    surfaceContainerHighest = Ivory,
    outline = Color(0xFFD3CDC0),
    outlineVariant = Color(0xFFE6E1D6),
    error = DangerLight,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = EmeraldBright,
    onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF11463A),
    onPrimaryContainer = EmeraldSoft,
    secondary = GoldBright,
    onSecondary = Color(0xFF3A2E00),
    secondaryContainer = Color(0xFF4A3D0D),
    onSecondaryContainer = GoldSoft,
    tertiary = EmeraldMid,
    onTertiary = Color.White,
    background = NightBackground,
    onBackground = MoonPrimary,
    surface = NightSurface,
    onSurface = MoonPrimary,
    surfaceVariant = NightMuted,
    onSurfaceVariant = MoonSecondary,
    surfaceContainer = NightMuted,
    surfaceContainerHigh = Color(0xFF25332D),
    surfaceContainerHighest = Color(0xFF2C3B34),
    outline = Color(0xFF3B4A44),
    outlineVariant = Color(0xFF27332E),
    error = DangerDark,
    onError = Color(0xFF690005),
)

/**
 * How every style in the app is set, before size and weight.
 *
 * Three things here are specifically about Arabic, and all three are wrong by default:
 *
 * - **No tracking.** Material's scale spaces letters apart by a fraction of a point, which is right
 *   for Latin and damaging for a joined script — it stretches the connections between letters of the
 *   same word and makes the word look broken apart.
 * - **No font padding.** The framework reserves vertical room from the font's own ascent and
 *   descent, computed for Latin. Combined with the tall leading here it pushed each line down inside
 *   its own box, so the gaps between lines came out uneven.
 * - **Centred leading, untrimmed.** Tashkeel sits above and below the baseline, so the extra room a
 *   line is given has to go to *both* ends. Left to the default, the first and last lines had theirs
 *   trimmed away and their marks clipped by the edge of the text box.
 */
private val ArabicText = PlatformTextStyle(includeFontPadding = false)
private val ArabicLeading = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun arabic(
    size: Int,
    leading: Int,
    weight: FontWeight = FontWeight.Normal,
) = TextStyle(
    fontSize = size.sp,
    lineHeight = leading.sp,
    fontWeight = weight,
    letterSpacing = 0.sp,
    platformStyle = ArabicText,
    lineHeightStyle = ArabicLeading,
)

/**
 * Arabic with full tashkeel needs far more vertical room than Latin text: marks sit above and below
 * the baseline, and at Material's default line heights they collide between lines. Every style here
 * is therefore leaded well beyond the usual 1.2-1.4x.
 */
private val AthkarTypography = Typography(
    displaySmall = arabic(34, 48, FontWeight.Bold),
    headlineMedium = arabic(26, 40, FontWeight.Bold),
    headlineSmall = arabic(22, 36, FontWeight.SemiBold),
    titleLarge = arabic(20, 34, FontWeight.SemiBold),
    titleMedium = arabic(17, 30, FontWeight.SemiBold),
    titleSmall = arabic(15, 26, FontWeight.Medium),
    bodyLarge = arabic(19, 38),
    bodyMedium = arabic(16, 32),
    bodySmall = arabic(14, 26),
    labelLarge = arabic(15, 24, FontWeight.Medium),
    labelMedium = arabic(13, 22, FontWeight.Medium),
    labelSmall = arabic(12, 20, FontWeight.Medium),
)

/** Accents that Material's scheme has no slot for, but that the screens need consistently. */
data class AthkarAccents(
    val gold: Color,
    val onGold: Color,
    val goldContainer: Color,
    val nightGradientTop: Color,
    val nightGradientBottom: Color,
)

private val LightAccents = AthkarAccents(
    gold = GoldDeep,
    onGold = Color.White,
    goldContainer = GoldSoft,
    nightGradientTop = EmeraldDeep,
    nightGradientBottom = Color(0xFF0A4438),
)

private val DarkAccents = AthkarAccents(
    gold = GoldBright,
    onGold = Color(0xFF3A2E00),
    goldContainer = Color(0xFF4A3D0D),
    nightGradientTop = Color(0xFF11463A),
    nightGradientBottom = Color(0xFF0A2620),
)

val LocalAthkarAccents = staticCompositionLocalOf { LightAccents }

/**
 * The hour of the sky the app is currently painted in.
 *
 * Provided once at the root and read by every patterned surface, so the header of the adhkar, the
 * face of the compass and the ground of the tasbih are all the same hour — and all of them move
 * together at Maghrib. Each screen naming its own was how they came to be stuck at night while the
 * prayer screen alone knew it was noon.
 */
val LocalSkyPhase = staticCompositionLocalOf { SkyPhase.NIGHT }

/**
 * Wraps content in the app's colours, type scale and layout direction.
 *
 * The direction is pinned to RTL rather than inherited: the content is Arabic on every screen, so a
 * device set to English would otherwise mirror the layout away from the text it is laying out.
 *
 * @param sky the hour to paint the patterned surfaces in — and, under [AppTheme.BY_TIME], what
 *   decides whether the page behind them is light or dark.
 */
@Composable
fun AthkarTheme(
    theme: AppTheme = AppTheme.DEFAULT,
    sky: SkyPhase = SkyPhase.NIGHT,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (theme) {
        AppTheme.BY_TIME -> sky.isNight
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
        AppTheme.SYSTEM -> systemDark
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalAthkarAccents provides if (darkTheme) DarkAccents else LightAccents,
        LocalSkyPhase provides sky,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AthkarTypography,
            shapes = AthkarShapes,
            content = content,
        )
    }
}
