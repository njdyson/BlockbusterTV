package com.njdyson.blockbustertv.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: String) : UpdateState
    data class Available(val version: Int) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks GitHub quietly on launch and whenever the app comes back to the front. Pressing
 * it checks again, and if a newer build exists, downloads it and opens the installer.
 */
@Composable
fun UpdateButton(resumeCount: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    suspend fun check(): Int? = try {
        state = UpdateState.Checking
        val latest = Updater.latest()
        if (latest.versionCode > Updater.currentVersion) {
            state = UpdateState.Available(latest.versionCode)
            latest.versionCode
        } else {
            state = UpdateState.UpToDate(SimpleDateFormat("HH:mm", Locale.UK).format(Date()))
            null
        }
    } catch (e: Exception) {
        state = UpdateState.Failed(e.message ?: e.javaClass.simpleName)
        null
    }

    LaunchedEffect(resumeCount) {
        if (state !is UpdateState.Downloading && state !is UpdateState.Available) check()
    }

    val onClick: () -> Unit = {
        if (state !is UpdateState.Checking && state !is UpdateState.Downloading) {
            scope.launch {
                if (state !is UpdateState.Available && check() == null) return@launch
                try {
                    val apk = Updater.download(context) { state = UpdateState.Downloading(it) }
                    state = UpdateState.Idle
                    Updater.install(context, apk)
                } catch (e: Exception) {
                    state = UpdateState.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    val label = when (val s = state) {
        UpdateState.Idle -> "Check for update"
        UpdateState.Checking -> "Checking…"
        is UpdateState.UpToDate -> "Up to date: build ${Updater.currentVersion} (checked ${s.checkedAt})"
        is UpdateState.Available -> "Install update: build ${s.version}"
        is UpdateState.Downloading -> "Downloading… ${(s.progress * 100).toInt()}%"
        is UpdateState.Failed -> "Update check failed: ${s.message.take(40)}"
    }
    if (state is UpdateState.Available) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}
