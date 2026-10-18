package com.kbs.tvremote.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.kbs.tvremote.BuildConfig
import com.kbs.tvremote.TvApps
import com.kbs.tvremote.TvClient
import com.kbs.tvremote.TvShortcut
import com.kbs.tvremote.TvDiscovery
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Status { SEARCHING, NOT_FOUND, READY, CONNECTING, ALLOW, OFFLINE, DENIED }


data class RemoteState(
    val tvName: String,
    val status: Status,
    val ip: String?,
    val youtube: YouTubeState,
    /** Several TVs answered discovery; the user has to pick one. */
    val foundTvs: List<TvDiscovery.FoundTv> = emptyList(),
    /** Apps found installed on the TV, shown as shortcut buttons. */
    val apps: List<TvShortcut> = emptyList(),
    val volume: TvClient.Volume? = null,
    val notificationEnabled: Boolean = true,
    /** A YouTube video playing on the TV, when YouTube is linked. */
    val nowPlaying: NowPlaying? = null,
)

class RemoteActions(
    val key: (String) -> Unit,
    val launch: (TvShortcut) -> Unit,
    /** Play-store build: hands a search to the official YouTube app on the phone. */
    val searchOnPhone: (String) -> Unit,
    val power: () -> Unit,
    val retry: () -> Unit,
    val youtube: YouTubeActions,
    val findTv: () -> Unit,
    val setNotification: (Boolean) -> Unit,
    val nowPlaying: NowPlayingActions,
    val chooseTv: (TvDiscovery.FoundTv) -> Unit,
    val saveIp: (String) -> Unit,
    val forgetPairing: () -> Unit,
)

private val CardShape = RoundedCornerShape(20.dp)
private val TALL_LAYOUT_MIN_HEIGHT = 700.dp
private val MAX_CONTENT_WIDTH = 560.dp
private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 110L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(state: RemoteState, actions: RemoteActions) {
    var showMore by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var startWithVoice by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }

    val openSearch = { voice: Boolean -> startWithVoice = voice; showSearch = true }
    val header: @Composable () -> Unit = { Header(state, actions, onMore = { showMore = true }) }
    val searchAndApps: @Composable () -> Unit = {
        SearchBar(onOpen = { openSearch(false) }, onVoice = { openSearch(true) })
        state.nowPlaying?.let {
            Spacer(Modifier.height(12.dp))
            NowPlayingBar(it, actions.nowPlaying, onOpen = { showNowPlaying = true })
        }
        if (state.apps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            AppRow(state.apps, actions.launch)
        }
    }
    val top: @Composable () -> Unit = {
        header()
        Spacer(Modifier.height(18.dp))
        searchAndApps()
    }
    val bottom: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundKey("Back", Icons.AutoMirrored.Filled.ArrowBack, "KEY_RETURN", actions.key)
            RoundKey("Home", Icons.Filled.Home, "KEY_HOME", actions.key)
            RoundKey("Mute", Icons.AutoMirrored.Filled.VolumeOff, "KEY_MUTE", actions.key)
        }
        Spacer(Modifier.height(16.dp))
        VolumeRocker(state.volume, actions.key)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillKey("Play", Icons.Filled.PlayArrow, "KEY_PLAY", actions.key)
            PillKey("Pause", Icons.Filled.Pause, "KEY_PAUSE", actions.key)
        }
    }

    Surface(color = Palette.Background, contentColor = Palette.Text, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            val screenWidth = maxWidth
            val screenHeight = maxHeight
            val landscape = screenWidth > screenHeight
            when {
                // Landscape (phones turned sideways, tablets): header across the top, controls
                // on the left, d-pad on the right.
                landscape -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    header()
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        ) {
                            searchAndApps()
                            Spacer(Modifier.height(20.dp))
                            bottom()
                        }
                        BoxWithConstraints(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            DPad(size = min(min(maxWidth, maxHeight) - 8.dp, 320.dp), onKey = actions.key)
                        }
                    }
                }
                // Tall screens: everything fits, the d-pad takes the leftover space.
                screenHeight >= TALL_LAYOUT_MIN_HEIGHT -> Column(
                    Modifier
                        .widthIn(max = MAX_CONTENT_WIDTH)
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .padding(top = 8.dp, bottom = 16.dp)
                ) {
                    top()
                    BoxWithConstraints(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        DPad(size = min(min(maxWidth - 24.dp, maxHeight - 28.dp), 296.dp), onKey = actions.key)
                    }
                    bottom()
                }
                // Short screens or large fonts: scroll instead of squeezing the buttons.
                else -> Column(
                    Modifier
                        .widthIn(max = MAX_CONTENT_WIDTH)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 8.dp, bottom = 16.dp)
                ) {
                    top()
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        DPad(size = min(screenWidth - 64.dp, 270.dp), onKey = actions.key)
                    }
                    bottom()
                }
            }
        }
    }

    if (showMore) {
        ModalBottomSheet(
            onDismissRequest = { showMore = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Surface,
        ) {
            MoreSheet(actions.key, onSettings = { showMore = false; showSettings = true })
        }
    }
    val nowPlaying = state.nowPlaying
    if (showNowPlaying && nowPlaying != null) {
        ModalBottomSheet(
            onDismissRequest = { showNowPlaying = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Surface,
        ) {
            NowPlayingSheet(nowPlaying, actions.nowPlaying)
        }
    }
    if (showSearch) {
        ModalBottomSheet(
            onDismissRequest = { showSearch = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Surface,
        ) {
            if (BuildConfig.YOUTUBE_IN_APP) {
                YouTubeSheet(state.youtube, actions.youtube, startWithVoice)
            } else {
                PhoneSearchSheet(startWithVoice, onSearch = { query -> showSearch = false; actions.searchOnPhone(query) })
            }
        }
    }
    if (showSettings) SettingsDialog(state, actions, onDismiss = { showSettings = false })
    if (state.foundTvs.isNotEmpty()) ChooseTvDialog(state.foundTvs, actions.chooseTv)
}

// ---------------------------------------------------------------------------------------------
// Header

@Composable
private fun Header(state: RemoteState, actions: RemoteActions, onMore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .background(Palette.Surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Tv, null, tint = Palette.TextDim, modifier = Modifier.size(22.dp))
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                // Full model names ("Samsung BU8000 75 TV") don't fit next to the buttons.
                if (state.tvName.length <= 16) state.tvName else "Samsung TV",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusLine(state.status, actions.retry)
        }
        CircleIconButton(Icons.Filled.MoreHoriz, "More buttons", onClick = onMore)
        Spacer(Modifier.width(10.dp))
        CircleIconButton(Icons.Filled.PowerSettingsNew, "Power", tint = Palette.Power, onClick = actions.power)
    }
}

@Composable
private fun StatusLine(status: Status, onRetry: () -> Unit) {
    val (color, label) = when (status) {
        Status.SEARCHING -> Palette.Waiting to "Looking for your TV…"
        Status.NOT_FOUND -> Palette.Power to "No TV found · tap to search"
        Status.READY -> Palette.Ready to "Connected"
        Status.CONNECTING -> Palette.Waiting to "Connecting…"
        Status.ALLOW -> Palette.Waiting to "Choose Allow on the TV"
        Status.OFFLINE -> Palette.Power to "TV is off · retrying"
        Status.DENIED -> Palette.Power to "Not allowed · tap to retry"
    }
    Row(
        Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = status != Status.READY, onClick = onRetry),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Text(
            label,
            Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextDim,
        )
    }
}

@Composable
private fun CircleIconButton(icon: ImageVector, label: String, tint: Color = Palette.Text, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .composedPressScale(interaction)
            .clip(CircleShape)
            .background(Palette.Surface)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Search + apps

@Composable
private fun SearchBar(onOpen: () -> Unit, onVoice: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(50),
        color = Palette.Surface,
        border = BorderStroke(1.dp, Palette.Outline),
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = Palette.TextDim)
            Text(
                "Search YouTube",
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                color = Palette.TextDim,
                style = MaterialTheme.typography.bodyLarge,
            )
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Palette.Accent)
                    .clickable(role = Role.Button, onClickLabel = "Speak", onClick = onVoice)
                    .semantics { contentDescription = "Speak" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Mic, null, tint = Color.White)
            }
        }
    }
}

/** Shortcut buttons for the TV's apps: two fill the width, more scroll sideways. */
@Composable
private fun AppRow(apps: List<TvShortcut>, onLaunch: (TvShortcut) -> Unit) {
    if (apps.size <= 2) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            apps.forEach { AppChip(it, Modifier.weight(1f), onLaunch) }
        }
    } else {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            apps.forEach { AppChip(it, Modifier.widthIn(min = 150.dp), onLaunch) }
        }
    }
}

@Composable
private fun AppChip(app: TvShortcut, modifier: Modifier, onLaunch: (TvShortcut) -> Unit) {
    val (icon, color) = when (app.kind) {
        TvApps.Kind.YOUTUBE -> Icons.Filled.SmartDisplay to Palette.YouTube
        TvApps.Kind.MUSIC -> Icons.Filled.MusicNote to Palette.Ready
        else -> Icons.Filled.Movie to Palette.Hotstar
    }
    Surface(
        onClick = { onLaunch(app) },
        shape = CardShape,
        color = Palette.Surface,
        border = BorderStroke(1.dp, Palette.Outline),
        modifier = modifier.height(60.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
            Text(
                app.name,
                Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Key plumbing

/** Shrinks slightly while pressed - the only press feedback besides the ripple (no vibration). */
@Composable
private fun Modifier.composedPressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "press")
    return scale(scale)
}

/**
 * Sends [key] the moment the finger touches (so it feels instant) and, when [repeat] is set,
 * keeps sending while the finger stays down.
 */
@Composable
private fun Modifier.remoteKey(
    key: String,
    label: String,
    onKey: (String) -> Unit,
    interaction: MutableInteractionSource,
    repeat: Boolean,
): Modifier {
    val scope = rememberCoroutineScope()
    val send by rememberUpdatedState(onKey)
    return this
        .semantics {
            role = Role.Button
            contentDescription = label
            onClick { send(key); true }
        }
        .pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val press = PressInteraction.Press(down.position)
                interaction.tryEmit(press)
                send(key)
                val repeater: Job? = if (repeat) scope.launch {
                    delay(REPEAT_DELAY_MS)
                    while (true) {
                        send(key)
                        delay(REPEAT_INTERVAL_MS)
                    }
                } else null
                val up = waitForUpOrCancellation()
                repeater?.cancel()
                interaction.tryEmit(if (up != null) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            }
        }
}

@Composable
private fun RoundKey(label: String, icon: ImageVector, key: String, onKey: (String) -> Unit, repeat: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .composedPressScale(interaction)
                .clip(CircleShape)
                .background(Palette.Surface)
                .border(1.dp, Palette.Outline, CircleShape)
                .indication(interaction, ripple())
                .remoteKey(key, label, onKey, interaction, repeat),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, modifier = Modifier.size(26.dp))
        }
        Text(
            label,
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextDim,
        )
    }
}

@Composable
private fun RowScope.PillKey(label: String, icon: ImageVector, key: String, onKey: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .weight(1f)
            .height(56.dp)
            .composedPressScale(interaction)
            .clip(RoundedCornerShape(50))
            .background(Palette.Surface)
            .border(1.dp, Palette.Outline, RoundedCornerShape(50))
            .indication(interaction, ripple())
            .remoteKey(key, label, onKey, interaction, repeat = false),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.size(24.dp))
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
    }
}

// ---------------------------------------------------------------------------------------------
// D-pad + volume

@Composable
private fun DPad(size: Dp, onKey: (String) -> Unit) {
    Box(
        Modifier
            .size(size)
            .background(
                Brush.verticalGradient(listOf(Palette.SurfaceRaised, Palette.Surface)),
                CircleShape,
            )
            .border(1.dp, Palette.Outline, CircleShape)
    ) {
        val arrow = size * 0.3f
        ArrowKey(Icons.Filled.KeyboardArrowUp, "Up", "KEY_UP", arrow, Alignment.TopCenter, onKey)
        ArrowKey(Icons.Filled.KeyboardArrowDown, "Down", "KEY_DOWN", arrow, Alignment.BottomCenter, onKey)
        ArrowKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left", "KEY_LEFT", arrow, Alignment.CenterStart, onKey)
        ArrowKey(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right", "KEY_RIGHT", arrow, Alignment.CenterEnd, onKey)

        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(size * 0.42f)
                .composedPressScale(interaction)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color(0xFF34343E), Color(0xFF2A2A32))))
                .border(1.dp, Color(0xFF3C3C46), CircleShape)
                .indication(interaction, ripple())
                .remoteKey("KEY_ENTER", "OK", onKey, interaction, repeat = false),
            contentAlignment = Alignment.Center,
        ) {
            Text("OK", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BoxScope.ArrowKey(
    icon: ImageVector,
    label: String,
    key: String,
    size: Dp,
    alignment: Alignment,
    onKey: (String) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .align(alignment)
            .size(size)
            .clip(CircleShape)
            .indication(interaction, ripple())
            .remoteKey(key, label, onKey, interaction, repeat = true),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color(0xFFD1D1D6), modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun VolumeRocker(volume: TvClient.Volume?, onKey: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(50))
            .background(Palette.Surface)
            .border(1.dp, Palette.Outline, RoundedCornerShape(50)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RockerHalf(Icons.Filled.Remove, "Volume down", "KEY_VOLDOWN", onKey)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Palette.TextDim, modifier = Modifier.size(20.dp))
            Text(
                when {
                    volume == null -> "Volume"
                    volume.muted -> "Muted"
                    else -> "Volume ${volume.level}"
                },
                Modifier.padding(start = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = Palette.TextDim,
            )
        }
        RockerHalf(Icons.Filled.Add, "Volume up", "KEY_VOLUP", onKey)
    }
}

@Composable
private fun RowScope.RockerHalf(icon: ImageVector, label: String, key: String, onKey: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(50))
            .indication(interaction, ripple())
            .remoteKey(key, label, onKey, interaction, repeat = true),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, modifier = Modifier.size(30.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Sheets + dialogs

@Composable
private fun MoreSheet(onKey: (String) -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        Text("More buttons", Modifier.padding(start = 4.dp, bottom = 16.dp), style = MaterialTheme.typography.titleLarge)
        CompositionLocalProvider(LocalContentColor provides Palette.Text) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundKey("Source", Icons.AutoMirrored.Filled.Input, "KEY_SOURCE", onKey)
                RoundKey("Menu", Icons.Filled.Menu, "KEY_MENU", onKey)
                RoundKey("Info", Icons.Filled.Info, "KEY_INFO", onKey)
                RoundKey("Exit", Icons.Filled.Close, "KEY_EXIT", onKey)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoundKey("Channel +", Icons.Filled.KeyboardArrowUp, "KEY_CHUP", onKey, repeat = true)
                RoundKey("Channel −", Icons.Filled.KeyboardArrowDown, "KEY_CHDOWN", onKey, repeat = true)
                RoundKey("Rewind", Icons.Filled.FastRewind, "KEY_REWIND", onKey, repeat = true)
                RoundKey("Forward", Icons.Filled.FastForward, "KEY_FF", onKey, repeat = true)
            }
        }
        TextButton(
            onClick = onSettings,
            modifier = Modifier
                .padding(top = 18.dp)
                .align(Alignment.CenterHorizontally),
        ) {
            Icon(Icons.Filled.Tune, null, tint = Palette.TextDim)
            Text("Settings", Modifier.padding(start = 8.dp), color = Palette.TextDim)
        }
    }
}

@Composable
private fun SettingsDialog(state: RemoteState, actions: RemoteActions, onDismiss: () -> Unit) {
    val youtubeLinked = state.youtube.linked
    var value by rememberSaveable { mutableStateOf(state.ip.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        title = { Text("TV address") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RoundedCornerShape(14.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Buttons in notification bar", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Volume and play from the pull-down, even when the app is closed",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.TextDim,
                        )
                    }
                    Switch(
                        checked = state.notificationEnabled,
                        onCheckedChange = actions.setNotification,
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent),
                    )
                }
                TextButton(onClick = { actions.findTv(); onDismiss() }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Find my TV automatically")
                }
                TextButton(onClick = { actions.forgetPairing(); onDismiss() }) {
                    Text("Forget TV pairing", color = Palette.Power)
                }
                if (youtubeLinked) {
                    TextButton(onClick = { actions.youtube.unlink(); onDismiss() }) {
                        Text("Disconnect YouTube", color = Palette.Power)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { actions.saveIp(value); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChooseTvDialog(tvs: List<TvDiscovery.FoundTv>, onChoose: (TvDiscovery.FoundTv) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = Palette.Surface,
        title = { Text("Which TV?") },
        text = {
            Column {
                tvs.forEach { tv ->
                    TextButton(onClick = { onChoose(tv) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(tv.name, style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                            Text(tv.ip, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
}
