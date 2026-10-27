package com.njdyson.blockbustertv.ui

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
import androidx.compose.runtime.remember
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
import com.njdyson.blockbustertv.linklab.qrCode

/** Shows the QR code for the phone editing page served by this stick. */
@Composable
fun PhoneEditScreen(app: AppState, onDone: () -> Unit) {
    val running = remember { app.server.startSafely() }
    val url = remember { app.server.url }
    val done = remember { FocusRequester() }
    Row(
        Modifier.fillMaxSize().background(NightNavy).padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (url != null && running) {
            Image(
                qrCode(url),
                contentDescription = "QR code for the editing page",
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(300.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Logo()
            Text("Edit your shelves on your phone", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            when {
                url == null -> Text("This stick isn't connected to a network.", color = Color.White, fontSize = 16.sp)
                !running -> Text("Couldn't start the editing server. Try closing and reopening the app.", color = Color.White, fontSize = 16.sp)
                else -> {
                    Text("1. Connect your phone to the same Wi-Fi as this TV.", color = Color.White, fontSize = 16.sp)
                    Text("2. Scan the code with your phone's camera.", color = Color.White, fontSize = 16.sp)
                    Text("3. Search, add, rename and reorder. The TV updates as you go.", color = Color.White, fontSize = 16.sp)
                    Text(url, color = TicketYellow, fontSize = 14.sp)
                    Text("The page works while BlockbusterTV is open on the TV.", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                }
            }
            OutlinedButton(onClick = onDone, modifier = Modifier.focusRequester(done)) { Text("Back to the shelves") }
        }
    }
    LaunchedEffect(Unit) { done.requestFocusWhenReady() }
}
