package com.njdyson.blockbustertv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.njdyson.blockbustertv.data.ShelfFilm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Quick add on the TV: type a title, pick a result, pick a shelf. */
@Composable
fun SearchScreen(app: AppState) {
    val shelves by app.shelfStore.shelves.collectAsState()
    var results by remember { mutableStateOf<List<ShelfFilm>>(emptyList()) }
    var message by remember { mutableStateOf<String?>("Tip: it's quicker from your phone. Home > Edit on phone.") }
    var picking by remember { mutableStateOf<ShelfFilm?>(null) }
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun search(text: String) {
        if (text.isBlank()) return
        message = "Searching…"
        message = try {
            results = app.justWatch.search(text)
            if (results.isEmpty()) "No films found." else null
        } catch (e: Exception) {
            "Search failed: ${e.message}"
        }
    }

    // Search as you type (after a short pause), since the Fire TV keyboard may have no Search key.
    LaunchedEffect(query) {
        delay(500)
        search(query)
    }

    Column(
        Modifier.fillMaxSize().background(NightNavy).padding(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Logo()
        Text("Add a film", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.fillMaxWidth(0.6f)) {
                TvTextField(
                    placeholder = "Film title",
                    onValueChange = { query = it },
                    onSubmit = { scope.launch { search(it) } },
                )
            }
            Button(onClick = { scope.launch { search(query) } }) { Text("Search") }
        }
        Text("Results appear as you type. Press Back to close the keyboard.", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        message?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp) }
        // Side padding leaves room for the focused poster to grow without being clipped.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp)) {
            items(results, key = { it.jwId }) { film ->
                Surface(
                    onClick = { picking = film },
                    modifier = Modifier.width(120.dp),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = ShelfGrey, focusedContainerColor = ShelfGrey),
                    border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, TicketYellow))),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                ) {
                    Column {
                        AsyncImage(film.poster, film.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(180.dp))
                        Column(Modifier.padding(6.dp)) {
                            Text(film.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(film.year?.toString().orEmpty(), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    picking?.let { film ->
        ShelfPicker(
            shelves,
            onPick = { shelfId, newName ->
                val id = shelfId ?: app.shelfStore.addShelf(newName.orEmpty())
                app.addToShelf(id, film)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}
