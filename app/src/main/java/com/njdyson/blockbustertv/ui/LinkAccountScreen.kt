package com.njdyson.blockbustertv.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.njdyson.blockbustertv.data.Pairing
import com.njdyson.blockbustertv.linklab.qrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Links this TV to an account: shows a code and a QR code that opens the phone app
 * ready to approve it, and waits for the approval.
 */
@Composable
fun LinkAccountScreen(app: AppState, onDone: () -> Unit) {
    val context = LocalContext.current
    var code by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("Getting a code…") }
    var finished by remember { mutableStateOf(false) }
    val button = remember { FocusRequester() }
    val deviceName = remember {
        // The name set in Fire TV / Android TV settings, e.g. "Nick's Fire TV Stick".
        // ("device_name" is Settings.Global.DEVICE_NAME, which needs API 25 as a constant.)
        Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    LaunchedEffect(Unit) {
        while (!finished) {
            val started = try {
                withContext(Dispatchers.IO) { Pairing.start(app.serverUrl, deviceName) }
            } catch (e: Exception) {
                message = "Couldn't reach ${app.serverUrl.removePrefix("https://")}. Check the internet connection."
                code = null
                delay(10_000)
                continue
            }
            code = started.code
            message = "Waiting for you to approve it on your phone…"
            // Poll until approved, or the code expires and a new one is fetched.
            while (true) {
                delay(3_000)
                val poll = runCatching { withContext(Dispatchers.IO) { Pairing.poll(app.serverUrl, started) } }.getOrNull() ?: continue
                if (poll.status == "expired") break
                if (poll.status == "approved" && poll.deviceKey != null) {
                    message = "Linked! Loading your shelves…"
                    try {
                        app.completeLink(poll.deviceKey)
                        finished = true
                        message = "Linked. This TV now shows your account's shelves."
                    } catch (e: Exception) {
                        message = "Linked, but couldn't load your shelves yet: ${e.message}"
                        finished = true
                    }
                    break
                }
            }
        }
    }

    Row(
        Modifier.fillMaxSize().background(NightNavy).padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val current = code
        if (current != null && !finished) {
            Image(
                qrCode(Pairing.approveUrl(app.serverUrl, current)),
                contentDescription = "QR code to link this TV",
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(280.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Logo()
            Text("Link this TV to your account", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (current != null && !finished) {
                Text("1. Scan the code with your phone, or open ${app.serverUrl.removePrefix("https://")}", color = Color.White, fontSize = 16.sp)
                Text("2. Sign in, then choose Link this TV.", color = Color.White, fontSize = 16.sp)
                Text(current.chunked(3).joinToString(" "), color = TicketYellow, fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
                Text("If your account has no shelves yet, it gets this TV's.", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            }
            Text(message, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
            OutlinedButton(onClick = onDone, modifier = Modifier.focusRequester(button)) { Text(if (finished) "Done" else "Cancel") }
        }
    }
    LaunchedEffect(finished) { button.requestFocusWhenReady() }
}
