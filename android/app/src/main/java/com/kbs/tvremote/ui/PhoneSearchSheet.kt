package com.kbs.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Play-store search: type or speak, then the official YouTube app opens with the results. From
 * there the YouTube app's own cast button plays the video on the TV.
 */
@Composable
fun PhoneSearchSheet(startWithVoice: Boolean, onSearch: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val submit = { q: String -> if (q.isNotBlank()) onSearch(q.trim()) }
    val listen = rememberVoiceInput { spoken ->
        query = spoken
        submit(spoken)
    }
    LaunchedEffect(Unit) { if (startWithVoice) listen() else focus.requestFocus() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .imePadding()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SmartDisplay, null, tint = Palette.YouTube, modifier = Modifier.size(30.dp))
            Text("Search YouTube", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "Results open in the YouTube app. Tap the cast icon there to play on the TV.",
            Modifier.padding(top = 6.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextDim,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search videos") },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = Palette.TextDim) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Accent,
                    unfocusedBorderColor = Palette.Outline,
                    focusedContainerColor = Palette.SurfaceRaised,
                    unfocusedContainerColor = Palette.SurfaceRaised,
                ),
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
        Button(
            onClick = { submit(query) },
            enabled = query.isNotBlank(),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
            modifier = Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("Search in YouTube", style = MaterialTheme.typography.titleMedium)
        }
    }
}
