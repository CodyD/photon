package dev.photon.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color(0xFFFFB74D),
    onPrimary = Color.Black,
    background = Color.Black,
    surface = Color(0xFF121212),
)

@Composable
fun PhotonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
