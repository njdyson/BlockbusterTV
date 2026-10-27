package com.njdyson.blockbustertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.njdyson.blockbustertv.linklab.deviceInfo
import com.njdyson.blockbustertv.update.UpdateButton
import com.njdyson.blockbustertv.update.Updater

@Composable
fun SettingsScreen(app: AppState, resumeCount: Int, onLinkAccount: () -> Unit, onLinkLab: () -> Unit) {
    val first = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().background(NightNavy).padding(horizontal = 48.dp, vertical = 32.dp),
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
        app.status?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp) }

        OutlinedButton(onClick = onLinkLab) { Text("Link Lab: test how each streaming app opens") }

        Text(
            "Film data, availability and IMDb ratings from JustWatch. Trailers from TMDB and YouTube.",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
        )
    }
    LaunchedEffect(Unit) { first.requestFocusWhenReady() }
}
