package io.github.seancheung.airplayer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Android TV design language: focus = a light rounded card with dark text (inverse
// highlight), over a dark blue-grey background. These constants drive that look.
val TvBackground = Color(0xFF16191D)
val TvFocusBg = Color(0xFFE8EAED)   // light card when focused
val TvFocusFg = Color(0xFF202124)   // dark text on the focused card
val TvIdleFg = Color(0xFFE3E3E6)    // light text when not focused
private val TvIdleContainer = Color(0xFF2A2E35) // resting fill for buttons/chips

/** Inverse focus highlight for icon-only / container elements. Combine with clickable. */
@Composable
fun Modifier.tvFocusHighlight(
    shape: Shape = RoundedCornerShape(12.dp),
    scaleUp: Boolean = false
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && scaleUp) 1.1f else 1f, label = "tvFocusScale")
    return this
        .onFocusChanged { focused = it.isFocused }
        .scale(scale)
        .clip(shape)
        .background(if (focused) TvFocusBg else Color.Transparent)
}

/** Full-width, D-pad-focusable settings row. Focused = light card + dark content. */
@Composable
fun TvClickableRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) TvFocusBg else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalContentColor provides if (focused) TvFocusFg else TvIdleFg) {
            content()
        }
    }
}

/** Circular, D-pad-focusable icon button (top-bar actions). */
@Composable
fun TvIconButton(
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = TvIdleFg
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) TvFocusBg else Color.Transparent)
            .clickable { onClick() }
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = imageVector, contentDescription = contentDescription,
            tint = if (focused) TvFocusFg else tint)
    }
}

/** Prominent pill button (home Start/Stop). Focused = light card + dark content. */
@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "tvButtonScale")
    Row(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(28.dp))
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) TvFocusBg else TvIdleContainer)
            .clickable { onClick() }
            .padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides if (focused) TvFocusFg else TvIdleFg) {
            content()
        }
    }
}

/** Selectable chip (multi-choice settings). Focused = light card; selected = accent ring. */
@Composable
fun TvChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) TvFocusBg else TvIdleContainer)
            .then(
                if (selected && !focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            color = if (focused) TvFocusFg else TvIdleFg,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
