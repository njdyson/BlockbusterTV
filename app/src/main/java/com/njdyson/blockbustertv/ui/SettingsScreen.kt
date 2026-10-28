package com.njdyson.blockbustertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.njdyson.blockbustertv.data.AgeLimit
import com.njdyson.blockbustertv.linklab.deviceInfo
import com.njdyson.blockbustertv.update.UpdateButton
import com.njdyson.blockbustertv.update.Updater

@Composable
fun SettingsScreen(app: AppState, resumeCount: Int, onLinkAccount: () -> Unit, onLinkLab: () -> Unit, onPlexLink: () -> Unit) {
    val first = remember { FocusRequester() }
    var choosingAge by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(NightNavy).verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Logo()
        Text("Settings", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.focusRequester(first)) { UpdateButton(resumeCount) }
        }
        Text(
            "Build ${Updater.currentVersion} (${Updater.currentSha}) · ${deviceInfo()}",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
        )

        if (app.linked) {
            Text(
                "Linked to your account at ${app.serverUrl.removePrefix("https://")}: shelves sync with the phone app and your other TVs.",
                color = Color.White,
                fontSize = 14.sp,
            )
            OutlinedButton(onClick = { app.unlink() }) { Text("Unlink this TV") }
        } else {
            Text(
                "Not linked: shelves are saved on this TV only. Link it to your account to edit from anywhere and share shelves between TVs.",
                color = Color.White,
                fontSize = 14.sp,
            )
            OutlinedButton(onClick = onLinkAccount) { Text("Link to your account") }
        }

        OutlinedButton(onClick = { app.refresh() }) { Text("Refresh films and availability now") }
        (app.status ?: "Couldn't reach JustWatch last time: showing saved films.".takeIf { app.usingSavedFilms })
            ?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp) }

        val plexLibrary = app.plex.library
        if (app.plex.connected) {
            Text(
                app.plexStatus ?: plexLibrary?.let { "Plex: ${app.plexSummary(it)}." } ?: "Plex: connected, films not read yet.",
                color = Color.White,
                fontSize = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedButton(onClick = { app.refreshPlex() }) { Text("Refresh Plex films") }
                OutlinedButton(onClick = { app.disconnectPlex() }) { Text("Disconnect Plex") }
            }
        } else {
            Text(
                "Plex: connect your Plex server to see which films you already have, and play them in the Plex app.",
                color = Color.White,
                fontSize = 14.sp,
            )
            OutlinedButton(onClick = onPlexLink) { Text("Connect your Plex server") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onLinkLab) { Text("Link Lab: test how each streaming app opens") }
            // Hides films and series rated above it everywhere on this TV.
            OutlinedButton(onClick = { choosingAge = true }) {
                Text("Age limit: ${app.ageLimit?.let { "${it.label} and under" } ?: "None"}")
            }
        }

        Text(
            "Film data, availability and IMDb ratings from JustWatch. Trailers from TMDB and YouTube.",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
        )
    }
    LaunchedEffect(Unit) { first.requestFocusWhenReady() }

    if (choosingAge) {
        ChoiceDialog(
            "Show films and series rated",
            listOf("Anything (no limit)" to { app.chooseAgeLimit(null) }) +
                AgeLimit.entries.map { limit -> "${limit.label} and under" to { app.chooseAgeLimit(limit) } },
            onDismiss = { choosingAge = false },
        )
    }
}
