package com.kbs.tvremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun NoRemoteHelp(state: RemoteState, actions: RemoteActions, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Text("No TV remote?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(if (state.status == Status.READY) "Your Wi-Fi remote is connected. You can close this guide."
            else "First turn the TV on. Then choose Allow on its screen so this phone can connect.")
        Spacer(Modifier.height(16.dp))
        Text("Use the TV's own button", style = MaterialTheme.typography.titleMedium)
        Text("Look underneath the TV or near its power light for a button or small joystick. Use it to turn the TV on. Some models also let you move and select on screen; others only offer limited controls. Check your TV's manual.")
        Spacer(Modifier.height(16.dp))
        Text("Try a wired USB keyboard", style = MaterialTheme.typography.titleMedium)
        Text("If your TV supports it, plug a keyboard into the TV's USB port and try the arrow keys and Enter. Keyboard support for the Allow prompt varies by model.")
        Spacer(Modifier.height(16.dp))
        if (state.infraredAvailable) {
            Text("Use this phone's infrared", style = MaterialTheme.typography.titleMedium)
            Text("Point the phone's IR emitter at the TV. These setup buttons send ordinary remote presses without Wi-Fi pairing. Samsung TV support is experimental.")
            Text("Power toggles on or off. Watch the TV before pressing it again.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.infraredKey("KEY_POWER") }) { Text("IR Power") }
                Button(onClick = { actions.infraredKey("KEY_UP") }) { Text("IR Up") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.weight(1f), onClick = { actions.infraredKey("KEY_LEFT") }) { Text("IR Left") }
                    Button(modifier = Modifier.weight(1f), onClick = { actions.infraredKey("KEY_ENTER") }) { Text("IR OK") }
                    Button(modifier = Modifier.weight(1f), onClick = { actions.infraredKey("KEY_RIGHT") }) { Text("IR Right") }
                }
                Button(onClick = { actions.infraredKey("KEY_DOWN") }) { Text("IR Down") }
            }
        } else {
            Text("Infrared is unavailable on this phone", style = MaterialTheme.typography.titleMedium)
            Text("No supported IR emitter was detected. An app cannot add this hardware to the phone.")
        }
        Spacer(Modifier.height(16.dp))
        Text("Already connected another remote?", style = MaterialTheme.typography.titleMedium)
        Text("An already connected SmartThings remote or another authorized remote can be useful. New SmartThings setup can still need interaction on the TV. A compatible replacement remote is the fallback if your TV's button and keyboard cannot select Allow.")
        Spacer(Modifier.height(12.dp))
        Text("Once the TV is on, find it here. When its Allow message appears, use one of the controls above to select it.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions.findTv) { Text("Find my TV") }
            TextButton(onClick = actions.retry) { Text("Retry pairing") }
        }
        TextButton(onClick = onDone) { Text("Done") }
    }
}
