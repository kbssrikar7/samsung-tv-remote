package com.kbs.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kbs.tvremote.youtube.Video

data class YouTubeState(
    val linked: Boolean,
    val linking: Boolean = false,
    val searching: Boolean = false,
    val results: List<Video> = emptyList(),
    val searchedFor: String? = null,
    /** Video currently being sent to the TV. */
    val sendingId: String? = null,
    /** Video most recently started on the TV. */
    val playingId: String? = null,
    val error: String? = null,
    val favourites: List<Video> = emptyList(),
    val recentSearches: List<String> = emptyList(),
)

class YouTubeActions(
    val search: (String) -> Unit,
    /** Leaves the results and goes back to favourites and recent searches. */
    val clear: () -> Unit,
    val play: (Video) -> Unit,
    val playNext: (Video) -> Unit,
    val addToQueue: (Video) -> Unit,
    val toggleFavourite: (Video) -> Unit,
    val link: (String) -> Unit,
    val unlink: () -> Unit,
)

private val FieldShape = RoundedCornerShape(18.dp)

@Composable
fun YouTubeSheet(state: YouTubeState, actions: YouTubeActions, startWithVoice: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .padding(horizontal = 20.dp)
            .imePadding()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SmartDisplay, null, tint = Palette.YouTube, modifier = Modifier.size(30.dp))
            Text("YouTube", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(16.dp))
        if (state.linked) SearchContent(state, actions, startWithVoice) else LinkContent(state, actions)
    }
}

@Composable
private fun LinkContent(state: YouTubeState, actions: YouTubeActions) {
    var code by rememberSaveable { mutableStateOf("") }
    val digits = code.filter(Char::isDigit)
    // Open the number pad straight away; the user is reading digits off the TV.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Text("Connect to YouTube on the TV (one time only)", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(14.dp))
    Step(1, "On the TV, open YouTube and go to Settings → Link with TV code.")
    Step(2, "Type the 12 blue numbers shown on the TV below.")
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { new -> code = new.filter { it.isDigit() || it == ' ' }.take(15) },
        placeholder = { Text("123 456 789 012") },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(letterSpacing = 2.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (digits.length == 12) actions.link(digits) }),
        shape = FieldShape,
        colors = fieldColors(),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus),
    )
    state.error?.let { ErrorText(it) }
    Button(
        onClick = { actions.link(digits) },
        enabled = digits.length == 12 && !state.linking,
        shape = FieldShape,
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
        modifier = Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .height(56.dp),
    ) {
        if (state.linking) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text("Connect", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(28.dp)
                .background(Palette.SurfaceRaised, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelLarge)
        }
        Text(text, Modifier.padding(start = 12.dp, top = 3.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SearchContent(state: YouTubeState, actions: YouTubeActions, startWithVoice: Boolean) {
    var query by rememberSaveable { mutableStateOf(state.searchedFor.orEmpty()) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val submit = { q: String ->
        if (q.isNotBlank()) {
            focusManager.clearFocus()
            actions.search(q.trim())
        }
    }
    val listen = rememberVoiceInput { spoken ->
        query = spoken
        submit(spoken)
    }
    LaunchedEffect(Unit) {
        when {
            startWithVoice -> listen()
            state.results.isEmpty() -> focus.requestFocus()
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search videos") },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Palette.TextDim) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; actions.clear(); focus.requestFocus() }) {
                        Icon(Icons.Filled.Close, "Clear", tint = Palette.TextDim)
                    }
                }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit(query) }),
            shape = FieldShape,
            colors = fieldColors(),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Palette.Accent)
                .clickable(role = Role.Button, onClickLabel = "Speak", onClick = listen)
                .semantics { contentDescription = "Speak" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Mic, null, tint = Color.White)
        }
    }
    state.error?.let { ErrorText(it) }
    Spacer(Modifier.height(12.dp))

    when {
        state.searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Palette.Accent)
        }
        state.searchedFor == null -> HomeContent(state, actions, onRecent = { query = it; submit(it) })
        state.results.isEmpty() -> Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 40.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                "Nothing found for \"${state.searchedFor}\"",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.TextDim,
            )
        }
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.results, key = { it.id }) { video -> VideoRow(video, state, actions) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Shown before searching: recent searches as chips, then favourite videos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeContent(state: YouTubeState, actions: YouTubeActions, onRecent: (String) -> Unit) {
    if (state.recentSearches.isEmpty() && state.favourites.isEmpty()) {
        Text(
            "Type or tap the mic, then pick a video.\nIt plays on the TV.\n\nTap ☆ on a video to keep it here.",
            Modifier
                .fillMaxWidth()
                .padding(top = 40.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.TextDim,
            textAlign = TextAlign.Center,
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.recentSearches.isNotEmpty()) {
            item { SectionTitle("Recent searches") }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.recentSearches.forEach { recent ->
                        Surface(
                            onClick = { onRecent(recent) },
                            shape = RoundedCornerShape(50),
                            color = Palette.SurfaceRaised,
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.History, null, tint = Palette.TextDim, modifier = Modifier.size(18.dp))
                                Text(recent, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        if (state.favourites.isNotEmpty()) {
            item { SectionTitle("Favourites") }
            items(state.favourites, key = { "fav-" + it.id }) { video -> VideoRow(video, state, actions) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(
    text,
    Modifier.padding(top = 12.dp, bottom = 4.dp),
    style = MaterialTheme.typography.titleMedium,
    color = Palette.TextDim,
)

@Composable
private fun VideoRow(video: Video, state: YouTubeState, actions: YouTubeActions) {
    val sending = state.sendingId == video.id
    val playing = state.playingId == video.id
    val favourite = state.favourites.any { it.id == video.id }
    Surface(
        onClick = { actions.play(video) },
        shape = RoundedCornerShape(16.dp),
        color = if (playing) Palette.SurfaceRaised else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(136.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.SurfaceRaised)
            ) {
                AsyncImage(
                    model = video.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (video.duration.isNotEmpty()) {
                    Text(
                        video.duration,
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(5.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(5.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                    )
                }
                if (sending || playing) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0x99000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (sending) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
                        } else {
                            Icon(Icons.Filled.CheckCircle, null, tint = Palette.Ready, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    video.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        sending -> "Opening on TV…"
                        playing -> "Playing on TV"
                        else -> video.channel
                    },
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (playing) Palette.Ready else Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { actions.toggleFavourite(video) }) {
                    Icon(
                        if (favourite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        if (favourite) "Remove from favourites" else "Add to favourites",
                        tint = if (favourite) Palette.Waiting else Palette.TextDim,
                    )
                }
                QueueMenu(video, actions)
            }
        }
    }
}

@Composable
private fun QueueMenu(video: Video, actions: YouTubeActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, "More options", tint = Palette.TextDim)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.SurfaceRaised) {
            DropdownMenuItem(
                text = { Text("Play now") },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                onClick = { open = false; actions.play(video) },
            )
            DropdownMenuItem(
                text = { Text("Play next") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) },
                onClick = { open = false; actions.playNext(video) },
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                onClick = { open = false; actions.addToQueue(video) },
            )
        }
    }
}

@Composable
private fun ErrorText(text: String) = Text(
    text,
    Modifier.padding(top = 10.dp),
    style = MaterialTheme.typography.bodyMedium,
    color = Palette.Power,
)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Accent,
    unfocusedBorderColor = Palette.Outline,
    focusedContainerColor = Palette.SurfaceRaised,
    unfocusedContainerColor = Palette.SurfaceRaised,
    cursorColor = Palette.Accent,
)
