package com.njdyson.blockbustertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.njdyson.blockbustertv.data.Film
import com.njdyson.blockbustertv.data.ShelfFilm

/**
 * Inside a box set: its films in the set's own order. Hold Select on one to move it
 * earlier or later, take it out of the set, or remove it from the shelf.
 */
@Composable
fun BoxSetScreen(app: AppState, shelfId: String, setId: String, onOpen: (Film) -> Unit) {
    val shelves by app.shelfStore.shelves.collectAsState()
    val owned by app.shelfStore.owned.collectAsState()
    val shelf = shelves.firstOrNull { it.id == shelfId }
    val set = shelf?.sets?.firstOrNull { it.id == setId }
    // In release order, earliest first.
    val films = set?.films.orEmpty().mapNotNull { id -> shelf?.films?.firstOrNull { it.jwId == id } }
        .sortedBy { app.films[it.jwId]?.year ?: it.year ?: Int.MAX_VALUE }
    var focused by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<ShelfFilm?>(null) }

    Box(Modifier.fillMaxSize().background(NightNavy)) {
        Backdrop((focused ?: films.firstOrNull()?.jwId)?.let { app.films[it]?.backdrop })
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Logo()
            if (shelf == null || set == null) {
                Text("This set no longer exists. Press Back.", color = Color.White, fontSize = 18.sp)
                return@Column
            }
            Text(set.name, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Box set · ${films.size} films · on ${shelf.name}", color = TicketYellow, fontSize = 15.sp)
            focused?.let { app.films[it] }?.let { film ->
                Text(film.metaLine(), color = Color.White, fontSize = 14.sp)
                Text(
                    film.synopsis.orEmpty(),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.6f),
                )
            }
            LazyRow(
                // Side padding leaves room for the focused box to grow without being clipped.
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                itemsIndexed(films, key = { _, film -> film.jwId }) { i, film ->
                    VhsBox(
                        film = app.films[film.jwId],
                        title = film.title,
                        fallbackPoster = film.poster,
                        ownedOnYouTube = film.jwId in owned,
                        shortlisted = app.isShortlisted(film.jwId),
                        requestFocus = i == 0 && focused == null,
                        onFocused = { focused = film.jwId },
                        onClick = { app.films[film.jwId]?.let(onOpen) },
                        onMenu = { menuFor = film },
                        onShortlist = { app.toggleShortlist(film) },
                    )
                }
            }
            Text("In release order · Hold Select on a film to take it out of the set · Press ⏯ to shortlist it.", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
        }
    }

    val currentSet = set
    menuFor?.let { film ->
        if (shelf == null || currentSet == null) return@let
        ChoiceDialog(
            film.title,
            buildList {
                add((if (app.isShortlisted(film.jwId)) "Take off the shortlist" else "Add to the shortlist") to { app.toggleShortlist(film) })
                add("Take out of the set (keep on the shelf)" to { app.shelfStore.removeFromSet(shelf.id, film.jwId) })
                add("Remove from ${shelf.name}" to { app.shelfStore.removeFilm(shelf.id, film.jwId) })
            },
            onDismiss = { menuFor = null },
        )
    }
}
