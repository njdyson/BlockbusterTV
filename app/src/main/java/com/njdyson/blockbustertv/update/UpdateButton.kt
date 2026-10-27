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

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: Int) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks GitHub quietly on launch. Pressing it checks again, and if a newer build
 * exists, downloads it and opens the installer.
 */
@Composable
fun UpdateButton() {
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
            state = UpdateState.UpToDate
            null
        }
    } catch (e: Exception) {
        state = UpdateState.Failed(e.message ?: e.javaClass.simpleName)
        null
    }

    LaunchedEffect(Unit) { check() }

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
        UpdateState.UpToDate -> "Up to date (build ${Updater.currentVersion})"
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
