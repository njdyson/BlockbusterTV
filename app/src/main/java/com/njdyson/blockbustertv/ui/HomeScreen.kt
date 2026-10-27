package com.njdyson.blockbustertv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.njdyson.blockbustertv.data.Shelf
import com.njdyson.blockbustertv.data.ShelfFilm
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.njdyson.blockbustertv.data.Film

/**
 * The store floor: a preview of the focused film at the top ("the back of the box"),
 * then a shelf of VHS boxes per collection. Hold Select (or press Menu) on a box to
 * move or remove it.
 */
@Composable
fun HomeScreen(app: AppState, onOpen: (Film) -> Unit, onSearch: () -> Unit, onPhoneEdit: () -> Unit, onSettings: () -> Unit) {
    val shelves by app.shelfStore.shelves.collectAsState()
    val focusedFilm = app.focusedKey?.substringAfter(':')?.let { app.films[it] }
    var menuFor by remember { mutableStateOf<Pair<Shelf, ShelfFilm>?>(null) }
    var copying by remember { mutableStateOf<ShelfFilm?>(null) }

    Box(Modifier.fillMaxSize().background(NightNavy)) {
        Backdrop(focusedFilm?.backdrop)
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Logo()
                Spacer(Modifier.width(12.dp))
                app.status?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onSearch) { Text("Add film") }
                OutlinedButton(onClick = onPhoneEdit) { Text("Edit on phone") }
                OutlinedButton(onClick = onSettings) { Text("Settings") }
            }
            Preview(focusedFilm, Modifier.padding(horizontal = 48.dp).height(128.dp))
            if (shelves.isEmpty()) {
                Text(
                    "No shelves yet. Choose Edit on phone or Add film to start one.",
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(48.dp),
                )
            }
            LazyColumn(
                state = app.homeListState,
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(shelves, key = { _, shelf -> shelf.id }) { shelfIndex, shelf ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ShelfSign(shelf.name)
                        if (shelf.films.isEmpty()) {
                            Text(
                                "Empty shelf: add films with Add film or from your phone.",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                            )
                        }
                        LazyRow(
                            state = app.shelfState(shelf.id),
                            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            itemsIndexed(shelf.films, key = { _, entry -> entry.jwId }) { i, entry ->
                                val key = "${shelf.id}:${entry.jwId}"
                                // Focus the first box on launch, or the last one used when coming Back.
                                val wantsFocus = app.focusedKey == key ||
                                    (app.focusedKey == null && shelfIndex == 0 && i == 0)
                                VhsBox(
                                    film = app.films[entry.jwId],
                                    fallbackTitle = entry.title,
                                    fallbackPoster = entry.poster,
                                    requestFocus = wantsFocus,
                                    onFocused = { app.focusedKey = key },
                                    onClick = { app.films[entry.jwId]?.let(onOpen) },
                                    onMenu = { menuFor = shelf to entry },
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Availability and ratings from JustWatch · Hold Select on a box to move or remove it",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 48.dp),
                    )
                }
            }
        }
    }

    menuFor?.let { (shelf, film) ->
        val index = shelf.films.indexOfFirst { it.jwId == film.jwId }
        ChoiceDialog(
            film.title,
            buildList {
                if (index > 0) add("Move left" to { app.shelfStore.moveFilm(shelf.id, film.jwId, -1) })
                if (index < shelf.films.lastIndex) add("Move right" to { app.shelfStore.moveFilm(shelf.id, film.jwId, 1) })
                add("Add to another shelf…" to { copying = film })
                add("Remove from ${shelf.name}" to {
                    app.focusedKey = null
                    app.shelfStore.removeFilm(shelf.id, film.jwId)
                })
            },
            onDismiss = { menuFor = null },
        )
    }
    copying?.let { film ->
        ShelfPicker(
            shelves,
            onPick = { shelfId, newName ->
                app.addToShelf(shelfId ?: app.shelfStore.addShelf(newName.orEmpty()), film)
                copying = null
            },
            onDismiss = { copying = null },
        )
    }
}

@Composable
private fun Preview(film: Film?, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (film == null) return@Column
        Text(film.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(film.metaLine(), color = TicketYellow, fontSize = 15.sp)
        Text(
            film.synopsis.orEmpty(),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(0.6f),
        )
    }
}

@Composable
private fun ShelfSign(name: String) {
    Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 6.dp, height = 22.dp).background(TicketYellow))
        Spacer(Modifier.width(10.dp))
        Text(name.uppercase(), color = TicketYellow, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
    }
}

/** A film as a rental box: poster in a black case, rating sticker, services underneath. */
@Composable
private fun VhsBox(
    film: Film?,
    fallbackTitle: String,
    fallbackPoster: String?,
    requestFocus: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    if (requestFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocusWhenReady() }
    }
    Surface(
        onClick = onClick,
        onLongClick = onMenu,
        modifier = Modifier
            .width(128.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onKeyEvent { event ->
                // The remote's Menu (☰) button opens the same menu as holding Select.
                if (event.key == Key.Menu && event.type == KeyEventType.KeyUp) { onMenu(); true } else false
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF0D0D12),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFF0D0D12),
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, TicketYellow))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(184.dp).padding(5.dp)) {
                AsyncImage(
                    model = film?.poster ?: fallbackPoster,
                    contentDescription = film?.title ?: fallbackTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(ShelfGrey, RoundedCornerShape(3.dp)),
                )
                if (film?.poster == null && fallbackPoster == null) {
                    Text(fallbackTitle, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center).padding(8.dp))
                }
                RatingSticker(film?.imdbScore, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(film?.title ?: fallbackTitle, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.height(20.dp)) {
                    when {
                        film == null -> Unit
                        film.watchServices.isNotEmpty() -> ServiceIcons(film.watchServices)
                        film.cheapestRent != null -> Text("Rent ${film.cheapestRent?.price.orEmpty()}", fontSize = 12.sp, color = TicketYellow)
                        else -> Text("Not streaming", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}
