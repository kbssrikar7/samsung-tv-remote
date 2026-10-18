package com.kbs.tvremote.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Returns a function that opens the phone's speech recognizer (no microphone permission needed;
 * the system recognizer handles it) and delivers what was said to [onResult].
 */
@Composable
fun rememberVoiceInput(onResult: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val deliver by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) deliver(spoken)
    }
    return {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "What do you want to watch?")
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Voice search isn't available on this phone", Toast.LENGTH_SHORT).show()
        }
    }
}
