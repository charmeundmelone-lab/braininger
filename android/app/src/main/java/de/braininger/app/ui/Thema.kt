package de.braininger.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import de.braininger.app.R

val Schwarz = Color(0xFF000000)
val Weiss = Color(0xFFFFFFFF)
val Grau = Color(0xFF8A8A8A)
val Linie = Color(0xFF2A2A2A)
val Rot = Color(0xFFD71921)

/** Punktmatrix-Schrift „Doto“ (SIL Open Font License), wird beim Bauen geladen. */
@OptIn(ExperimentalTextApi::class)
val Punktschrift = FontFamily(
    Font(R.font.doto, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
    Font(R.font.doto, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

private val Farben = darkColorScheme(
    primary = Weiss,
    onPrimary = Schwarz,
    secondary = Grau,
    background = Schwarz,
    onBackground = Weiss,
    surface = Schwarz,
    onSurface = Weiss,
    surfaceVariant = Color(0xFF111111),
    onSurfaceVariant = Grau,
    outline = Linie,
    error = Rot,
)

@Composable
fun BrainingerThema(inhalt: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Farben, content = inhalt)
}
