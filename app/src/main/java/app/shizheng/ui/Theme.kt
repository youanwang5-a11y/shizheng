package app.shizheng.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shizheng.R

private val porcelain = Color(0xFFFAF9F8)
private val ink = Color(0xFF211F1D)
private val warmGray = Color(0xFF544D47)
private val palette = lightColorScheme(
    primary = Color(0xFF302D29), onPrimary = Color.White,
    primaryContainer = Color(0xFFEAE4DD), onPrimaryContainer = ink,
    secondary = warmGray, onSecondary = Color.White,
    secondaryContainer = Color(0xFFECE6DF), onSecondaryContainer = ink,
    tertiary = Color(0xFF77522F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3E7D8), onTertiaryContainer = Color(0xFF462C15),
    background = porcelain, onBackground = ink,
    surface = Color(0xFFFFFEFD), onSurface = ink, surfaceTint = Color.Transparent,
    surfaceVariant = Color(0xFFF3F1EF), onSurfaceVariant = warmGray,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFCFBFA),
    surfaceContainer = Color(0xFFF5F3F1), surfaceContainerHigh = Color(0xFFEFECE9),
    surfaceContainerHighest = Color(0xFFE9E5E1),
    outline = Color(0xFF756D66), outlineVariant = Color(0xFFDAD5D1)
)

private val defaultTypography = Typography()
private val typography = defaultTypography.copy(
    headlineMedium = defaultTypography.headlineMedium.copy(fontWeight = FontWeight.Bold),
    titleLarge = defaultTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = defaultTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = defaultTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
)
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(13.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

internal val recordMetadataStyle = typography.bodyMedium.copy(
    fontFamily = FontFamily(Font(R.font.source_han_serif_cn_regular)),
    lineHeight = 19.sp,
    letterSpacing = 0.sp
)

@Composable
internal fun ShizhengTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = palette, typography = typography, shapes = shapes, content = content)

@Composable
internal fun inputColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
)
