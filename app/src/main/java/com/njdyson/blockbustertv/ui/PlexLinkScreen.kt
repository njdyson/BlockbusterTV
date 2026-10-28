package com.njdyson.blockbustertv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.njdyson.blockbustertv.data.Plex
import com.njdyson.blockbustertv.data.PlexLibrary
import com.njdyson.blockbustertv.linklab.qrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connects the user's Plex server: shows a code to enter at plex.tv/link, then finds the
 * server on the home network and reads its films. Or the address and token typed in by hand.
 */
@Composable
fun PlexLinkScreen(app: AppState, onDone: () -> Unit) {
    var code by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("Getting a code from Plex…") }
    var finished by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val button = remember { FocusRequester() }

    fun connected(library: PlexLibrary) {
        finished = true
        message = "Connected. ${app.plexSummary(library)}."
    }

    if (!manual) LaunchedEffect(Unit) {
        while (!finished && !manual) {
            val pin = try {
                withContext(Dispatchers.IO) { Plex.startPin(app.plex.clientId) }
            } catch (e: Exception) {
                message = "Couldn't reach plex.tv. Check the internet connection."
                code = null
                delay(10_000)
                continue
            }
            code = pin.code
            message = "Waiting for you to enter the code…"
            val expires = System.currentTimeMillis() + pin.expiresIn * 1000L
            // Poll until the code is entered, or it expires and a new one is fetched.
            while (System.currentTimeMillis() < expires) {
                delay(3_000)
                val accountToken = runCatching { withContext(Dispatchers.IO) { Plex.checkPin(app.plex.clientId, pin) } }.getOrNull() ?: continue
                code = null
                message = "Signed in. Finding your Plex server and reading its films…"
                try {
                    connected(withContext(Dispatchers.IO) { app.plex.connectWithAccount(accountToken) })
                } catch (e: Exception) {
                    // Signed in, but the server didn't answer at any address plex.tv knows: offer typing it in.
                    message = "${e.message ?: "Couldn't reach your Plex server"} You can enter its address and token instead."
                    manual = true
                }
                break
            }
        }
    }

    Row(
        Modifier.fillMaxSize().background(NightNavy).padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val current = code
        if (current != null && !finished && !manual) {
            Image(
                qrCode(Plex.LINK_URL),
                contentDescription = "QR code for plex.tv/link",
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(240.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp),
            )
        }
        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Logo()
            Text("Connect your Plex server", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (manual && !finished) {
                Text("Your server's address on the home network, e.g. 192.168.1.20", color = Color.White, fontSize = 15.sp)
                TvTextField(placeholder = "NAS address", autoFocus = true, value = address, onValueChange = { address = it }, onSubmit = { address = it })
                Text("Your Plex token (Plex Web: a film's ⋯ menu, Get Info, View XML: it's after X-Plex-Token= in the address)", color = Color.White, fontSize = 15.sp)
                TvTextField(placeholder = "Plex token", autoFocus = false, value = token, onValueChange = { token = it }, onSubmit = { token = it })
                OutlinedButton(
                    onClick = {
                        if (working || address.isBlank() || token.isBlank()) return@OutlinedButton
                        working = true
                        message = "Connecting…"
                        scope.launch {
                            try {
                                connected(withContext(Dispatchers.IO) { app.plex.connectManually(address, token) })
                            } catch (e: Exception) {
                                message = e.message ?: "Couldn't connect"
                            }
                            working = false
                        }
                    },
                ) { Text("Connect") }
            } else if (current != null && !finished) {
                Text("1. On your phone, go to plex.tv/link (or scan the code) and sign in to Plex.", color = Color.White, fontSize = 16.sp)
                Text("2. Enter this code:", color = Color.White, fontSize = 16.sp)
                Text(current, color = TicketYellow, fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
                Text("This TV must be on the same home network as the Plex server.", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            }
            Text(message, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedButton(onClick = onDone, modifier = Modifier.focusRequester(button)) { Text(if (finished) "Done" else "Cancel") }
                if (!manual && !finished) {
                    OutlinedButton(onClick = { manual = true; message = "" }) { Text("Enter address and token instead") }
                }
            }
        }
    }
    LaunchedEffect(finished, manual) { if (!manual || finished) button.requestFocusWhenReady() }
}
