package com.kbs.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kbs.tvremote.youtube.Playback
import com.kbs.tvremote.youtube.PlaybackState
import com.kbs.tvremote.youtube.Video
import kotlinx.coroutines.delay

/** A video playing in the TV's YouTube, with the moment its position was reported. */
data class NowPlaying(val video: Video?, val playback: Playback, val reportedAt: Long)

class NowPlayingActions(
    val togglePlay: () -> Unit,
    val seekTo: (Double) -> Unit,
)

/** Position extrapolated from the last report while playing, ticking once a second. */
@Composable
private fun livePosition(nowPlaying: NowPlaying): Double {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(nowPlaying) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val p = nowPlaying.playback
    val elapsed = if (p.state == PlaybackState.PLAYING) (now - nowPlaying.reportedAt) / 1000.0 else 0.0
    return (p.position + elapsed).coerceIn(0.0, p.duration.takeIf { it > 0 } ?: Double.MAX_VALUE)
}

@Composable
fun NowPlayingBar(nowPlaying: NowPlaying, actions: NowPlayingActions, onOpen: () -> Unit) {
    val video = nowPlaying.video
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        color = Palette.Surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(nowPlaying.playback.videoId, Modifier.width(96.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        video?.title ?: "Playing on TV",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!video?.channel.isNullOrEmpty()) {
                        Text(
                            video!!.channel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.TextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                PlayPauseButton(nowPlaying.playback.state, actions.togglePlay, size = 52.dp)
            }
            ProgressLine(nowPlaying)
        }
    }
}

@Composable
private fun ProgressLine(nowPlaying: NowPlaying) {
    val duration = nowPlaying.playback.duration
    if (duration <= 0) return
    val fraction = (livePosition(nowPlaying) / duration).toFloat().coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(Palette.SurfaceRaised)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(3.dp)
                .background(Palette.YouTube)
        )
    }
}

/** Full controls: big thumbnail, title, seek bar, skip back/forward and play/pause. */
@Composable
fun NowPlayingSheet(nowPlaying: NowPlaying, actions: NowPlayingActions) {
    val video = nowPlaying.video
    val duration = nowPlaying.playback.duration
    val position = livePosition(nowPlaying)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
    ) {
        Thumbnail(nowPlaying.playback.videoId, Modifier.fillMaxWidth(), corner = 20.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            video?.title ?: "Playing on TV",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (!video?.channel.isNullOrEmpty()) {
            Text(video!!.channel, style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim)
        }
        if (duration > 0) {
            Spacer(Modifier.height(12.dp))
            Slider(
                value = if (dragging) dragValue else (position / duration).toFloat().coerceIn(0f, 1f),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    actions.seekTo(dragValue * duration)
                    dragging = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Palette.YouTube,
                    inactiveTrackColor = Palette.SurfaceRaised,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val shown = if (dragging) dragValue * duration else position
                Text(clock(shown), style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
                Text(clock(duration), style = MaterialTheme.typography.labelMedium, color = Palette.TextDim)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(Icons.Filled.Replay10, "Back 10 seconds") { actions.seekTo((position - 10).coerceAtLeast(0.0)) }
            PlayPauseButton(nowPlaying.playback.state, actions.togglePlay, size = 76.dp)
            RoundIcon(Icons.Filled.Forward10, "Forward 10 seconds") { actions.seekTo(position + 10) }
        }
    }
}

@Composable
private fun Thumbnail(videoId: String, modifier: Modifier, corner: Dp = 12.dp) {
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(corner))
            .background(Palette.SurfaceRaised)
    ) {
        AsyncImage(
            model = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PlayPauseButton(state: PlaybackState, onClick: () -> Unit, size: Dp) {
    val playing = state == PlaybackState.PLAYING || state == PlaybackState.BUFFERING
    val label = if (playing) "Pause" else "Play"
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            null,
            tint = Palette.Background,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(Palette.SurfaceRaised)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, modifier = Modifier.size(30.dp))
    }
}

private fun clock(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
