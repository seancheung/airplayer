package io.github.seancheung.airplayer.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.seancheung.airplayer.R
import io.github.seancheung.airplayer.service.AirPlayService.ServerState
import io.github.seancheung.airplayer.viewmodel.DebugInfo
import io.github.seancheung.airplayer.viewmodel.MainViewModel
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay

private enum class Screen { HOME, SETTINGS, LOGS }

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    isInPip: Boolean = false,
    onSurfaceAvailable: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onPip: () -> Unit = {}
) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    val pin by viewModel.pinCode.collectAsState()
    val videoAspect by viewModel.videoAspect.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val audioOnly by viewModel.audioOnly.collectAsState()
    val videoCasting by viewModel.videoCasting.collectAsState()
    val hlsPlayer by viewModel.hlsPlayer.collectAsState()
    val photo by viewModel.photo.collectAsState()
    val autoAudioMode by viewModel.autoAudioMode.collectAsState()
    val launchOnConnect by viewModel.launchOnConnect.collectAsState()
    val launchPermPrompted by viewModel.launchPermPrompted.collectAsState()
    var showModePrompt by remember { mutableStateOf(false) }
    var showStopConfirm by remember { mutableStateOf(false) }
    var showLaunchPermPrompt by remember { mutableStateOf(false) }

    // auto audio mode: skip prompt if preference is on
    LaunchedEffect(audioOnly) {
        if (audioOnly && !autoAudioMode) showModePrompt = true
    }

    // First-launch one-shot: nudge the user to grant the overlay permission so that the
    // service can lift the activity to the foreground when a client connects (the only
    // BAL exemption that works on Android TV, where FSI notifications get demoted).
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (launchOnConnect && !launchPermPrompted && !canAutoLaunch(ctx)) {
            showLaunchPermPrompt = true
        }
    }

    // a pending PIN must stay visible on the home screen, not hidden behind settings/logs
    LaunchedEffect(pin) { if (pin != null) screen = Screen.HOME }

    // screen mirroring active (video, not audio-only)
    val mirroring = connections > 0 && !audioOnly
    // immersive: video shown full-screen with system bars hidden
    val immersive = mirroring && screen == Screen.HOME && pin == null && !isInPip

    val activity = LocalContext.current as? Activity
    LaunchedEffect(immersive) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Single, stable video surface at the bottom of the z-stack. It is mounted once while
    // a client is connected and is NOT remounted when switching tabs / entering fullscreen
    // / PiP. Remounting would destroy & recreate the SurfaceView, restarting the decoder
    // and freezing mirroring on the first frame. UI layers are drawn on top and simply
    // hide (in fullscreen/PiP) to reveal the video underneath.
    Surface(
        modifier = Modifier.fillMaxSize(),
        // pure black behind video (clean letterbox); dark blue-grey for home/settings (TV look)
        color = if (immersive) Color.Black else TvBackground,
        contentColor = TvIdleFg
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        // During an HLS video cast the mirror stream is idle; unmount its SurfaceView so the
        // ExoPlayer surface is the only one on screen (two overlapping SurfaceViews can lose the
        // hardware overlay and show black on this MTK/Sony panel).
        if (connections > 0 && !videoCasting) {
            MirroringView(
                onSurfaceAvailable = onSurfaceAvailable,
                onSurfaceDestroyed = onSurfaceDestroyed,
                aspectRatio = videoAspect,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // AirPlay video (HLS) cast renders on top of the idle mirror surface
        hlsPlayer?.let { player ->
            if (videoCasting) {
                HlsVideoView(player = player, modifier = Modifier.align(Alignment.Center))
            }
        }

        // AirPlay photo (Photos app / slideshow): full-screen with a crossfade between photos
        Crossfade(targetState = photo, label = "airplay-photo") { bmp ->
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        when {
            isInPip -> { /* show only the video surface */ }
            screen == Screen.SETTINGS -> {
                BackHandler { screen = Screen.HOME }
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { screen = Screen.HOME },
                    onOpenLogs = { screen = Screen.LOGS }
                )
            }
            screen == Screen.LOGS -> {
                BackHandler { screen = Screen.SETTINGS }
                LogsScreen(viewModel = viewModel, onBack = { screen = Screen.SETTINGS })
            }
            else -> {
                // Back on the home screen normally closes the activity, sending the
                // receiver to the background while the client keeps streaming silently.
                // Intercept it during any active session so back means "end the cast".
                val anyCasting = mirroring || audioOnly || videoCasting || photo != null
                BackHandler(enabled = anyCasting && pin == null) { showStopConfirm = true }
                HomeScreen(
                    viewModel = viewModel,
                    mirroring = mirroring,
                    audioOnly = audioOnly,
                    onOpenSettings = { screen = Screen.SETTINGS },
                    onPip = onPip
                )
            }
        }
      }
    }

    // pin dialog
    if (pin != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPin() },
            title = { Text(stringResource(R.string.dialog_pin_title)) },
            text = {
                Text(
                    text = pin!!,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissPin() }) { Text(stringResource(R.string.btn_ok)) }
            }
        )
    }

    // first-launch overlay-permission nudge
    if (showLaunchPermPrompt) {
        AlertDialog(
            onDismissRequest = {
                showLaunchPermPrompt = false
                viewModel.markLaunchPermPrompted()
            },
            title = { Text(stringResource(R.string.dialog_launch_perm_title)) },
            text = { Text(stringResource(R.string.dialog_launch_perm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showLaunchPermPrompt = false
                    viewModel.markLaunchPermPrompted()
                    runCatching { ctx.startActivity(_launchPermIntent(ctx)) }
                }) { Text(stringResource(R.string.btn_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLaunchPermPrompt = false
                    viewModel.markLaunchPermPrompted()
                }) { Text(stringResource(R.string.btn_not_now)) }
            }
        )
    }

    // back-during-cast confirmation
    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text(stringResource(R.string.dialog_stop_cast_title)) },
            text = { Text(stringResource(R.string.dialog_stop_cast_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirm = false
                    viewModel.restartServer()
                }) { Text(stringResource(R.string.btn_end)) }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    // audio mode notification
    if (showModePrompt) {
        AlertDialog(
            onDismissRequest = { showModePrompt = false },
            title = { Text(stringResource(R.string.dialog_audio_mode_title)) },
            text = { Text(stringResource(R.string.dialog_audio_mode_text)) },
            confirmButton = {
                TextButton(onClick = { showModePrompt = false }) { Text(stringResource(R.string.btn_ok)) }
            }
        )
    }
}

@Composable
private fun HomeScreen(
    viewModel: MainViewModel,
    mirroring: Boolean,
    audioOnly: Boolean,
    onOpenSettings: () -> Unit,
    onPip: () -> Unit
) {
    val debugEnabled by viewModel.debugEnabled.collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            // video is rendered by the stable surface layer below; just overlay diagnostics
            mirroring -> {
                if (debugEnabled) {
                    DebugOverlay(debugInfo, Modifier.align(Alignment.TopStart).padding(8.dp))
                }
            }
            audioOnly -> NowPlayingContent(viewModel)
            else -> IdleContent(viewModel)
        }

        // settings entry, top-right — hidden while video is on screen so it doesn't sit on the picture
        if (!mirroring) {
            TvIconButton(
                onClick = onOpenSettings,
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.tab_settings),
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            )
        }
    }
}

@Composable
private fun IdleContent(viewModel: MainViewModel) {
    val state by viewModel.serverState.collectAsState()
    val serverName by viewModel.serverName.collectAsState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Cast,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = if (state == ServerState.RUNNING) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = serverName.ifEmpty { stringResource(R.string.app_name) },
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = when (state) {
                ServerState.RUNNING -> stringResource(R.string.waiting_for_connection)
                ServerState.STOPPED -> stringResource(R.string.server_stopped)
                ServerState.ERROR -> stringResource(R.string.error_starting_server)
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state == ServerState.RUNNING) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.idle_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        Spacer(Modifier.height(40.dp))
        TvButton(
            onClick = {
                if (state == ServerState.RUNNING) viewModel.stopServer() else viewModel.startServer()
            },
            modifier = Modifier.focusRequester(focusRequester)
        ) {
            Icon(
                imageVector = if (state == ServerState.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null
            )
            Text(if (state == ServerState.RUNNING) stringResource(R.string.btn_stop) else stringResource(R.string.btn_start))
        }
    }
}

@Composable
private fun NowPlayingContent(viewModel: MainViewModel) {
    val track by viewModel.trackInfo.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val playing by viewModel.playing.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // cover art
        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .aspectRatio(1f)
                .fillMaxWidth(0.7f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            if (track.coverArt != null) {
                Image(
                    bitmap = track.coverArt!!.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_cover_art),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // track info
        Text(
            text = track.title.ifEmpty { stringResource(R.string.unknown_track) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (track.artist.isNotEmpty()) {
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (track.album.isNotEmpty()) {
            Text(
                text = track.album,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(16.dp))

        // progress bar (read-only, seeking not supported by AirPlay receiver)
        if (durationMs > 0) {
            LinearProgressIndicator(
                progress = { (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(_formatTime(positionMs), style = MaterialTheme.typography.labelSmall)
                Text(_formatTime(durationMs), style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(Modifier.height(8.dp))

        // playback controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = { viewModel.dacpPrev() }) {
                Icon(Icons.Default.SkipPrevious, stringResource(R.string.cd_previous), modifier = Modifier.size(36.dp))
            }
            FilledIconButton(
                onClick = { viewModel.dacpPlayPause() },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(R.string.cd_play_pause), modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = { viewModel.dacpNext() }) {
                Icon(Icons.Default.SkipNext, stringResource(R.string.cd_next), modifier = Modifier.size(36.dp))
            }
        }
    }
}

private fun _formatTime(ms: Long): String {
    val s = (ms / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun DebugOverlay(info: DebugInfo, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val style = MaterialTheme.typography.labelSmall
        val color = Color.White.copy(alpha = 0.9f)

        if (info.videoCodec.isNotEmpty()) {
            Text("Video: ${info.videoCodec} ${info.videoRes}", style = style, color = color)
            Text("FPS: ${info.videoFps}  Bitrate: ${info.bitrateStr}", style = style, color = color)
            Text("Frames: ${info.videoFrames}  Drops: ${info.droppedFrames}", style = style, color = color)
            Text("Jitter: ${info.jitterStr}", style = style, color = color)
        }
        if (info.audioCodec.isNotEmpty()) {
            Text("Audio: ${info.audioCodec}  Vol: ${info.audioVolume}%", style = style, color = color)
        }
        Text("Clients: ${info.connections}", style = style, color = color)
    }
}
