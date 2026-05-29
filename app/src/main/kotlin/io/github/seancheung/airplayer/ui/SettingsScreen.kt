package io.github.seancheung.airplayer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.seancheung.airplayer.R
import io.github.seancheung.airplayer.viewmodel.MainViewModel
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit = {},
    onOpenLogs: () -> Unit = {}
) {
    val serverName by viewModel.serverName.collectAsState()
    val h265Enabled by viewModel.h265Enabled.collectAsState()
    val hlsVideoEnabled by viewModel.hlsVideoEnabled.collectAsState()
    val maxCastResolution by viewModel.maxCastResolution.collectAsState()
    val proxyEnabled by viewModel.proxyEnabled.collectAsState()
    val proxyType by viewModel.proxyType.collectAsState()
    val proxyHost by viewModel.proxyHost.collectAsState()
    val proxyPort by viewModel.proxyPort.collectAsState()
    val enforceSdr by viewModel.enforceSdr.collectAsState()
    val alacEnabled by viewModel.alacEnabled.collectAsState()
    val aacEnabled by viewModel.aacEnabled.collectAsState()
    val resolution by viewModel.resolution.collectAsState()
    val videoDecoder by viewModel.videoDecoder.collectAsState()
    val idlePreview by viewModel.idlePreview.collectAsState()
    val autoFullscreen by viewModel.autoFullscreen.collectAsState()
    val autoAudioMode by viewModel.autoAudioMode.collectAsState()
    val launchOnConnect by viewModel.launchOnConnect.collectAsState()
    val maxFps by viewModel.maxFps.collectAsState()
    val overscanned by viewModel.overscanned.collectAsState()
    val requirePin by viewModel.requirePin.collectAsState()
    val allowNewConn by viewModel.allowNewConn.collectAsState()
    val autoStart by viewModel.autoStart.collectAsState()
    val bootAutoStart by viewModel.bootAutoStart.collectAsState()
    val serverPort by viewModel.serverPort.collectAsState()
    val audioLatencyMs by viewModel.audioLatencyMs.collectAsState()
    val swAlacEnabled by viewModel.swAlacEnabled.collectAsState()
    val debugEnabled by viewModel.debugEnabled.collectAsState()
    val developerOptions by viewModel.developerOptions.collectAsState()
    val keyAllowFrameDrop by viewModel.keyAllowFrameDrop.collectAsState()
    val realtimeDecoderPriority by viewModel.realtimeDecoderPriority.collectAsState()
    val operatingRateHint by viewModel.operatingRateHint.collectAsState()
    val scheduledOutputBufferRelease by viewModel.scheduledOutputBufferRelease.collectAsState()
    val benchmarkLog by viewModel.benchmarkLog.collectAsState()
    val audioBufferMultiplier by viewModel.audioBufferMultiplier.collectAsState()
    // default focus on the Back button so opening Settings doesn't pop the IME for the
    // first text field
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        // top bar: back + title + logs entry
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TvIconButton(
                onClick = onBack,
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.btn_ok),
                modifier = Modifier.focusRequester(backFocus)
            )
            Text(
                text = stringResource(R.string.tab_settings),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            TvIconButton(
                onClick = onOpenLogs,
                imageVector = Icons.AutoMirrored.Filled.Article,
                contentDescription = stringResource(R.string.tab_logs)
            )
        }

        SectionHeader(stringResource(R.string.section_server))

        TvTextSettingRow(
            title = stringResource(R.string.setting_server_name),
            description = stringResource(R.string.setting_server_name_desc),
            value = serverName,
            onSave = { if (it.isNotBlank()) viewModel.setServerName(it.trim()) }
        )
        TvTextSettingRow(
            title = stringResource(R.string.setting_server_port),
            description = stringResource(R.string.setting_server_port_desc),
            value = serverPort.toString(),
            keyboardType = KeyboardType.Number,
            onSave = { it.toIntOrNull()?.let { p -> if (p in 1..65535) viewModel.setServerPort(p) } }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_auto_start),
            description = stringResource(R.string.setting_auto_start_desc),
            checked = autoStart,
            onCheckedChange = { viewModel.setAutoStart(it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_boot_auto_start),
            description = stringResource(R.string.setting_boot_auto_start_desc),
            checked = bootAutoStart,
            onCheckedChange = { viewModel.setBootAutoStart(it) }
        )

        SectionHeader(stringResource(R.string.section_connection))

        SettingSwitch(
            title = stringResource(R.string.setting_require_pin),
            description = stringResource(R.string.setting_require_pin_desc),
            checked = requirePin,
            onCheckedChange = { viewModel.setRequirePin(it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_allow_new_conn),
            description = stringResource(R.string.setting_allow_new_conn_desc),
            checked = allowNewConn,
            onCheckedChange = { viewModel.setAllowNewConn(it) }
        )

        SectionHeader(stringResource(R.string.section_display))

        SettingSwitch(
            title = stringResource(R.string.setting_idle_preview),
            description = stringResource(R.string.setting_idle_preview_desc),
            checked = idlePreview,
            onCheckedChange = { viewModel.setIdlePreview(it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_auto_fullscreen),
            description = stringResource(R.string.setting_auto_fullscreen_desc),
            checked = autoFullscreen,
            onCheckedChange = { viewModel.setAutoFullscreen(it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_auto_audio_mode),
            description = stringResource(R.string.setting_auto_audio_mode_desc),
            checked = autoAudioMode,
            onCheckedChange = { viewModel.setAutoAudioMode(it) }
        )

        val ctx = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        var hasOverlayPermission by remember { mutableStateOf(canAutoLaunch(ctx)) }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) hasOverlayPermission = canAutoLaunch(ctx)
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val needsOverlayPermission = launchOnConnect && !hasOverlayPermission
        TvClickableRow(onClick = {
            if (needsOverlayPermission) {
                ctx.startActivity(_launchPermIntent(ctx))
            } else {
                val newVal = !launchOnConnect
                viewModel.setLaunchOnConnect(newVal)
                if (newVal && !canAutoLaunch(ctx)) ctx.startActivity(_launchPermIntent(ctx))
            }
        }) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.setting_launch_on_connect), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        if (needsOverlayPermission) R.string.setting_launch_on_connect_no_permission
                        else R.string.setting_launch_on_connect_desc
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = launchOnConnect, onCheckedChange = null)
        }

        SettingResolution(
            value = resolution,
            onValueChange = { viewModel.setResolution(it) }
        )

        SettingChipField(
            title = stringResource(R.string.setting_max_fps),
            description = stringResource(R.string.setting_max_fps_desc),
            value = maxFps.toString(),
            presets = listOf("24" to "24", "30" to "30", "60" to "60", "120" to "120"),
            placeholder = stringResource(R.string.setting_max_fps_placeholder),
            keyboard = KeyboardType.Number,
            onValueChange = { it.toIntOrNull()?.let { v -> viewModel.setMaxFps(v) } }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_overscanned),
            description = stringResource(R.string.setting_overscanned_desc),
            checked = overscanned,
            onCheckedChange = { viewModel.setOverscanned(it) }
        )

        SectionHeader(stringResource(R.string.section_decode))

        SettingChoice(
            title = stringResource(R.string.setting_video_decoder),
            description = stringResource(R.string.setting_video_decoder_desc),
            options = listOf(
                "auto" to stringResource(R.string.decoder_auto),
                "hardware" to stringResource(R.string.decoder_hardware),
                "software" to stringResource(R.string.decoder_software)
            ),
            value = videoDecoder,
            onValueChange = { viewModel.setVideoDecoder(it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_h265),
            description = stringResource(R.string.setting_h265_desc),
            checked = h265Enabled,
            onCheckedChange = { viewModel.setH265Enabled(it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_hls_video),
            description = stringResource(R.string.setting_hls_video_desc),
            checked = hlsVideoEnabled,
            onCheckedChange = { viewModel.setHlsVideoEnabled(it) }
        )

        if (hlsVideoEnabled) {
            SettingChoice(
                title = stringResource(R.string.setting_max_cast_resolution),
                description = stringResource(R.string.setting_max_cast_resolution_desc),
                options = listOf(
                    "1080" to stringResource(R.string.res_1080p),
                    "1440" to stringResource(R.string.res_1440p),
                    "2160" to stringResource(R.string.res_4k),
                    "auto" to stringResource(R.string.res_auto)
                ),
                value = maxCastResolution,
                onValueChange = { viewModel.setMaxCastResolution(it) }
            )
        }

        SettingSwitch(
            title = stringResource(R.string.setting_proxy),
            description = stringResource(R.string.setting_proxy_desc),
            checked = proxyEnabled,
            onCheckedChange = { viewModel.setProxyEnabled(it) }
        )

        if (proxyEnabled) {
            SettingChoice(
                title = stringResource(R.string.setting_proxy_type),
                description = stringResource(R.string.setting_proxy_type_desc),
                options = listOf(
                    "http" to stringResource(R.string.proxy_http),
                    "socks" to stringResource(R.string.proxy_socks)
                ),
                value = proxyType,
                onValueChange = { viewModel.setProxyType(it) }
            )
            TvTextSettingRow(
                title = stringResource(R.string.setting_proxy_host),
                description = stringResource(R.string.setting_proxy_host_desc),
                value = proxyHost,
                onSave = { viewModel.setProxyHost(it.trim()) }
            )
            TvTextSettingRow(
                title = stringResource(R.string.setting_proxy_port),
                description = stringResource(R.string.setting_proxy_port_desc),
                value = proxyPort.toString(),
                keyboardType = KeyboardType.Number,
                onSave = { it.toIntOrNull()?.let { p -> if (p in 1..65535) viewModel.setProxyPort(p) } }
            )
        }

        SettingSwitch(
            title = stringResource(R.string.setting_alac),
            description = stringResource(R.string.setting_alac_desc),
            checked = alacEnabled,
            onCheckedChange = { viewModel.setAlacEnabled(it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_sw_alac),
            description = stringResource(R.string.setting_sw_alac_desc),
            checked = swAlacEnabled,
            onCheckedChange = { viewModel.setSwAlacEnabled(it) }
        )

        SettingSwitch(
            title = stringResource(R.string.setting_aac),
            description = stringResource(R.string.setting_aac_desc),
            checked = aacEnabled,
            onCheckedChange = { viewModel.setAacEnabled(it) }
        )

        SectionHeader(stringResource(R.string.section_developer))

        SettingSwitch(
            title = stringResource(R.string.setting_developer_options),
            description = stringResource(R.string.setting_developer_options_desc),
            checked = developerOptions,
            onCheckedChange = { viewModel.setDeveloperOptions(it) }
        )

        if (developerOptions) {
            SettingSwitch(
                title = stringResource(R.string.setting_key_allow_frame_drop),
                description = stringResource(R.string.setting_key_allow_frame_drop_desc),
                checked = keyAllowFrameDrop,
                onCheckedChange = { viewModel.setKeyAllowFrameDrop(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_enforce_sdr),
                description = stringResource(R.string.setting_enforce_sdr_desc),
                checked = enforceSdr,
                onCheckedChange = { viewModel.setEnforceSdr(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_realtime_decoder_priority),
                description = stringResource(R.string.setting_realtime_decoder_priority_desc),
                checked = realtimeDecoderPriority,
                onCheckedChange = { viewModel.setRealtimeDecoderPriority(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_operating_rate_hint),
                description = stringResource(R.string.setting_operating_rate_hint_desc),
                checked = operatingRateHint,
                onCheckedChange = { viewModel.setOperatingRateHint(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_scheduled_output_buffer_release),
                description = stringResource(R.string.setting_scheduled_output_buffer_release_desc),
                checked = scheduledOutputBufferRelease,
                onCheckedChange = { viewModel.setScheduledOutputBufferRelease(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_audio_delay),
                description = stringResource(R.string.setting_audio_delay_desc),
                checked = audioLatencyMs >= 0,
                onCheckedChange = { viewModel.setAudioLatencyMs(if (it) 250 else -1) }
            )

            if (audioLatencyMs >= 0) {
                var sliderVal by remember(audioLatencyMs) { mutableFloatStateOf(audioLatencyMs.toFloat()) }
                SettingSlider(
                    label = stringResource(R.string.setting_audio_delay),
                    value = sliderVal,
                    valueRange = 0f..1000f,
                    steps = 19,
                    valueText = stringResource(R.string.audio_delay_value, sliderVal.roundToInt()),
                    onValueChange = { sliderVal = it },
                    onValueChangeFinished = { viewModel.setAudioLatencyMs(sliderVal.roundToInt()) }
                )
            }

            var audioBufferSliderVal by remember(audioBufferMultiplier) {
                mutableFloatStateOf(audioBufferMultiplier.toFloat())
            }
            SettingSlider(
                label = stringResource(R.string.setting_audio_buffer_multiplier),
                description = stringResource(R.string.setting_audio_buffer_multiplier_desc),
                value = audioBufferSliderVal,
                valueRange = 4f..8f,
                steps = 3,
                valueText = stringResource(R.string.setting_audio_buffer_multiplier_value, audioBufferSliderVal.roundToInt()),
                onValueChange = { audioBufferSliderVal = it },
                onValueChangeFinished = { viewModel.setAudioBufferMultiplier(audioBufferSliderVal.roundToInt()) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_debug_overlay),
                description = stringResource(R.string.setting_debug_overlay_desc),
                checked = debugEnabled,
                onCheckedChange = { viewModel.setDebugEnabled(it) }
            )

            SettingSwitch(
                title = stringResource(R.string.setting_benchmark_log),
                description = stringResource(R.string.setting_benchmark_log_desc),
                checked = benchmarkLog,
                onCheckedChange = { viewModel.setBenchmarkLog(it) }
            )
        }
    }
}

private fun canAutoLaunch(ctx: Context): Boolean {
    // Full-screen-intent is the mechanism that lifts MainActivity from a backgrounded
    // foreground service. API <34 grants it implicitly via the manifest permission; API
    // 34+ requires the user to opt in per-app.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    return nm.canUseFullScreenIntent()
}

private fun _launchPermIntent(ctx: Context): Intent {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } else {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = TvIdleFg.copy(alpha = 0.6f),
        modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 8.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingResolution(
    value: String,
    onValueChange: (String) -> Unit
) {
    val presets = listOf(
        "auto" to stringResource(R.string.setting_resolution_auto),
        "1280x720" to "1280x720",
        "1920x1080" to "1920x1080",
        "3840x2160" to "3840x2160"
    )
    val isPreset = presets.any { it.first == value }
    var editing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.setting_resolution), style = MaterialTheme.typography.titleMedium, color = TvIdleFg)
        Text(stringResource(R.string.setting_resolution_desc), style = MaterialTheme.typography.bodySmall, color = TvIdleFg.copy(alpha = 0.7f))
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (key, label) ->
                TvChip(label = label, selected = value == key, onClick = { onValueChange(key) })
            }
            TvChip(label = stringResource(R.string.chip_custom), selected = !isPreset, onClick = { editing = true })
        }
        if (!isPreset && value.contains("x")) {
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = TvIdleFg)
        }
    }
    if (editing) {
        var width by remember { mutableStateOf("") }
        var height by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.setting_resolution)) },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { width = it.filter { c -> c.isDigit() }.take(5) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.setting_resolution_width)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it.filter { c -> c.isDigit() }.take(5) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.setting_resolution_height)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val w = width.toIntOrNull(); val h = height.toIntOrNull()
                    if (w != null && w > 0 && h != null && h > 0) { onValueChange("${w}x${h}"); editing = false }
                }) { Text(stringResource(R.string.btn_save)) }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingChipField(
    title: String,
    description: String,
    value: String,
    presets: List<Pair<String, String>>,
    placeholder: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    val isPreset = presets.any { it.first == value }
    var editing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = TvIdleFg)
        Text(description, style = MaterialTheme.typography.bodySmall, color = TvIdleFg.copy(alpha = 0.7f))
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (key, label) ->
                TvChip(label = label, selected = value == key, onClick = { onValueChange(key) })
            }
            TvChip(label = stringResource(R.string.chip_custom), selected = !isPreset, onClick = { editing = true })
        }
        if (!isPreset && value.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = TvIdleFg)
        }
    }
    if (editing) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(placeholder) },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done)
                )
            },
            confirmButton = {
                TextButton(onClick = { if (text.isNotBlank()) { onValueChange(text); editing = false } }) {
                    Text(stringResource(R.string.btn_save))
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingChoice(
    title: String,
    description: String,
    options: List<Pair<String, String>>,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = TvIdleFg)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = TvIdleFg.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (key, label) ->
                TvChip(label = label, selected = value == key, onClick = { onValueChange(key) })
            }
        }
    }
}

@Composable
private fun TvTextSettingRow(
    title: String,
    description: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onSave: (String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    TvClickableRow(onClick = { editing = true }) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.7f)
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
    if (editing) {
        var text by remember { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = if (keyboardType == KeyboardType.Number) it.filter { c -> c.isDigit() }.take(5) else it
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done)
                )
            },
            confirmButton = {
                TextButton(onClick = { onSave(text); editing = false }) {
                    Text(stringResource(R.string.btn_save))
                }
            }
        )
    }
}

@Composable
private fun SettingSlider(
    label: String,
    description: String? = null,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = TvIdleFg, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.titleMedium, color = TvIdleFg)
        }
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = TvIdleFg.copy(alpha = 0.7f))
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    TvClickableRow(onClick = { onCheckedChange(!checked) }) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = 0.7f)
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
