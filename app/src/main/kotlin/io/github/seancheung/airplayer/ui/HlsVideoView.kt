package io.github.seancheung.airplayer.ui

import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import io.github.seancheung.airplayer.R

/**
 * Renders the AirPlay-video (HLS) ExoPlayer. Inflated from XML as a TextureView-backed PlayerView:
 * a plain SurfaceView decodes-but-shows-black (audio only) on this MTK/Sony panel, while a
 * TextureView composites as a normal view and renders correctly. PlayerView still handles
 * aspect-ratio letterboxing (resize_mode=fit).
 */
@OptIn(UnstableApi::class)
@Composable
fun HlsVideoView(player: Player, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            LayoutInflater.from(ctx).inflate(R.layout.hls_player_view, null) as PlayerView
        },
        update = { it.player = player },
        modifier = modifier.fillMaxSize()
    )
}
