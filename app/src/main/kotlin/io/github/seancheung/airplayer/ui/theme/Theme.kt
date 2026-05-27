package io.github.seancheung.airplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Always use a dark color scheme. This is a TV app rendered over a black video
// background; following the system (often light) theme made dark-on-black text and
// white ListItem surfaces unreadable.
@Composable
fun AirPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
