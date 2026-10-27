package com.njdyson.blockbustertv.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.layout.aspectRatio
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
import com.njdyson.blockbustertv.data.ShelfItem
import com.njdyson.blockbustertv.data.FilmItem
import com.njdyson.blockbustertv.data.SetItem
import com.njdyson.blockbustertv.data.displayItems
import com.njdyson.blockbustertv.data.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.njdyson.blockbustertv.data.FilmLauncher

/**
 * The store floor: a preview of the focused film at the top ("the back of the box"),
 * then a shelf of VHS boxes per collection. Hold Select (or press Menu) on a box to
 * move or remove it.
 */
@Composable
fun HomeScreen(
    app: AppState,
    onOpen: (Film) -> Unit,
    onOpenSet: (shelfId: String, setId: String) -> Unit,
    onSearch: () -> Unit,
    onPhoneEdit: () -> Unit,
    onSettings: () -> Unit,
) {
    val shelves by app.shelfStore.shelves.collectAsState()
    val owned by app.shelfStore.owned.collectAsState()
    // The focused box is "shelfId:itemKey"; a set's preview and backdrop come from its first film.
    val focusedItem = app.focusedKey?.let { key ->
        shelves.firstOrNull { it.id == key.substringBefore(':') }?.items()?.firstOrNull { it.key == key.substringAfter(':') }
    }
    val focusedFilm = focusedItem?.cover?.let { app.films[it.jwId] }
    var menuFor by remember { mutableStateOf<Pair<Shelf, ShelfItem>?>(null) }
    var copying by remember { mutableStateOf<ShelfFilm?>(null) }
    var choosingSet by remember { mutableStateOf<Pair<Shelf, ShelfFilm>?>(null) }
    var naming by remember { mutableStateOf<Naming?>(null) }
    var pendingShelf by remember { mutableStateOf<String?>(null) }

    // Back in the overview returns to the shelves rather than leaving the app.
    BackHandler(enabled = app.overview) { app.showOverview(false) }

    // After picking a shelf in the overview, scroll to it so its first box can take focus.
    LaunchedEffect(pendingShelf, app.overview) {
        val id = pendingShelf ?: return@LaunchedEffect
        if (app.overview) return@LaunchedEffect
        val index = shelves.indexOfFirst { it.id == id }
        if (index >= 0) {
            app.homeListState.scrollToItem(index)
            app.shelfState(id).scrollToItem(0)
        }
        pendingShelf = null
    }

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
                OutlinedButton(onClick = { app.showOverview(!app.overview) }) { Text(if (app.overview) "Shelves" else "Overview") }
                OutlinedButton(onClick = onSearch) { Text("Add film") }
                OutlinedButton(onClick = onPhoneEdit) { Text("Edit on phone") }
                OutlinedButton(onClick = onSettings) { Text("Settings") }
            }
            if (app.overview && shelves.isNotEmpty()) {
                ShelfOverview(app, shelves, onPick = { shelf ->
                    // Back to the shelves, on the first box of the chosen one.
                    app.focusedKey = shelf.displayItems(app.shuffleSeed).firstOrNull()?.let { "${shelf.id}:${it.key}" }
                    pendingShelf = shelf.id
                    app.showOverview(false)
                })
                return@Column
            }
            Preview(focusedFilm, focusedItem as? SetItem, Modifier.padding(horizontal = 48.dp).height(128.dp))
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
                            // Shuffled shelves get a new order each session (see AppState.shuffleSeed).
                            val items = shelf.displayItems(app.shuffleSeed)
                            itemsIndexed(items, key = { _, item -> item.key }) { i, item ->
                                val key = "${shelf.id}:${item.key}"
                                // Focus the first box on launch, or the last one used when coming Back.
                                val wantsFocus = app.focusedKey == key ||
                                    (app.focusedKey == null && shelfIndex == 0 && i == 0)
                                when (item) {
                                    is FilmItem -> VhsBox(
                                        film = app.films[item.film.jwId],
                                        title = item.film.title,
                                        fallbackPoster = item.film.poster,
                                        ownedOnYouTube = item.film.jwId in owned,
                                        requestFocus = wantsFocus,
                                        onFocused = { app.focusedKey = key },
                                        onClick = { app.films[item.film.jwId]?.let(onOpen) },
                                        onMenu = { menuFor = shelf to item },
                                    )
                                    is SetItem -> VhsBox(
                                        film = item.cover?.let { app.films[it.jwId] },
                                        title = item.set.name,
                                        fallbackPoster = item.cover?.poster,
                                        setCount = item.films.size,
                                        requestFocus = wantsFocus,
                                        onFocused = { app.focusedKey = key },
                                        onClick = { onOpenSet(shelf.id, item.set.id) },
                                        onMenu = { menuFor = shelf to item },
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Availability and ratings from JustWatch · Hold Select on a box to move it, make sets or shuffle the shelf",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 48.dp),
                    )
                }
            }
        }
    }

    menuFor?.let { (shelf, item) ->
        val store = app.shelfStore
        val index = shelf.items().indexOfFirst { it.key == item.key }
        val count = shelf.items().size
        ChoiceDialog(
            when (item) {
                is FilmItem -> item.film.title
                is SetItem -> "${item.set.name} (box set)"
            },
            buildList {
                // Moving makes no sense on a shuffled shelf.
                if (!shelf.shuffle && index > 0) add("Move left" to { store.moveItem(shelf.id, item.key, -1) })
                if (!shelf.shuffle && index < count - 1) add("Move right" to { store.moveItem(shelf.id, item.key, 1) })
                when (item) {
                    is FilmItem -> {
                        add("Add to a set…" to { choosingSet = shelf to item.film })
                        add("Add to another shelf…" to { copying = item.film })
                        add("Remove from ${shelf.name}" to {
                            app.focusedKey = null
                            store.removeFilm(shelf.id, item.film.jwId)
                        })
                    }
                    is SetItem -> {
                        add("Open the set" to { onOpenSet(shelf.id, item.set.id) })
                        add("Rename the set…" to {
                            naming = Naming("Rename the set", item.set.name, "Rename") { store.renameSet(shelf.id, item.set.id, it) }
                        })
                        add("Ungroup (keep the films)" to {
                            app.focusedKey = null
                            store.ungroupSet(shelf.id, item.set.id)
                        })
                    }
                }
                add((if (shelf.shuffle) "Stop shuffling ${shelf.name}" else "Shuffle ${shelf.name} each time") to {
                    store.setShuffle(shelf.id, !shelf.shuffle)
                })
            },
            onDismiss = { menuFor = null },
        )
    }
    choosingSet?.let { (shelf, film) ->
        ChoiceDialog(
            "Add ${film.title} to a set",
            shelf.sets.map { set -> set.name to { app.shelfStore.addToSet(shelf.id, set.id, film.jwId) } } +
                ("+ New set…" to {
                    naming = Naming("Name the new set", "", "Create set") { name ->
                        val id = app.shelfStore.createSet(shelf.id, name, listOf(film.jwId))
                        app.focusedKey = "${shelf.id}:set:$id"
                    }
                }),
            onDismiss = { choosingSet = null },
        )
    }
    naming?.let { request ->
        TextEntryDialog(request.title, request.initial, request.confirm, onDone = { request.onDone(it); naming = null }, onDismiss = { naming = null })
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

/** A text-entry request: "Name the new set", "Rename the set"… */
private class Naming(val title: String, val initial: String, val confirm: String, val onDone: (String) -> Unit)

@Composable
private fun Preview(film: Film?, set: SetItem?, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (set != null) {
            Text(set.set.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Box set · ${set.films.size} films", color = TicketYellow, fontSize = 15.sp)
            Text(set.films.joinToString(" · ") { it.title }, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.6f))
            return@Column
        }
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

/**
 * A film as a rental box: poster in a black case, rating sticker, services underneath.
 * With [setCount] it's a box set instead: stacked cases, a "5 FILMS" sticker, the set's name.
 */
@Composable
internal fun VhsBox(
    film: Film?,
    title: String,
    fallbackPoster: String?,
    ownedOnYouTube: Boolean = false,
    setCount: Int? = null,
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
                // A box set: two more cases peeking out behind the cover.
                val cover = if (setCount != null) {
                    Box(Modifier.matchParentSize().padding(start = 10.dp, bottom = 10.dp).background(Color(0xFF262C55), RoundedCornerShape(3.dp)))
                    Box(Modifier.matchParentSize().padding(start = 5.dp, end = 5.dp, top = 5.dp, bottom = 5.dp).background(Color(0xFF3A4170), RoundedCornerShape(3.dp)))
                    Modifier.matchParentSize().padding(end = 10.dp, top = 10.dp)
                } else {
                    Modifier.fillMaxSize()
                }
                AsyncImage(
                    model = film?.poster ?: fallbackPoster,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = cover.background(ShelfGrey, RoundedCornerShape(3.dp)),
                )
                if (film?.poster == null && fallbackPoster == null) {
                    Text(title, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center).padding(8.dp))
                }
                if (setCount != null) {
                    Text(
                        "$setCount FILMS",
                        color = NightNavy,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 16.dp, start = 4.dp)
                            .background(TicketYellow, RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                } else {
                    RatingSticker(film?.imdbScore, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                }
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.height(20.dp)) {
                    when {
                        setCount != null -> Text("Box set", fontSize = 12.sp, color = TicketYellow)
                        film == null -> Unit
                        ownedOnYouTube || film.watchServices.isNotEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (ownedOnYouTube) {
                                AsyncImage(FilmLauncher.YOUTUBE_ICON, "Owned on YouTube", Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)))
                            }
                            ServiceIcons(film.watchServices, max = if (ownedOnYouTube) 3 else 4)
                        }
                        film.cheapestRent != null -> Text("Rent ${film.cheapestRent?.price.orEmpty()}", fontSize = 12.sp, color = TicketYellow)
                        else -> Text("Not streaming", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

/**
 * Zoomed out: every shelf as a tile showing its first four posters, five to a row.
 * Selecting a tile goes back to the shelves, at that shelf.
 */
@Composable
private fun ShelfOverview(app: AppState, shelves: List<Shelf>, onPick: (Shelf) -> Unit) {
    val currentShelf = app.focusedKey?.substringBefore(':')
    Column(Modifier.padding(top = 16.dp)) {
        ShelfSign("All shelves")
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            itemsIndexed(shelves, key = { _, shelf -> shelf.id }) { i, shelf ->
                val focusRequester = remember { FocusRequester() }
                if (shelf.id == currentShelf || (i == 0 && shelves.none { it.id == currentShelf })) {
                    LaunchedEffect(Unit) { focusRequester.requestFocusWhenReady() }
                }
                Surface(
                    onClick = { onPick(shelf) },
                    modifier = Modifier.focusRequester(focusRequester),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = ShelfGrey,
                        contentColor = Color.White,
                        focusedContainerColor = ShelfGrey,
                        focusedContentColor = Color.White,
                    ),
                    border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, TicketYellow))),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        val posters = shelf.displayItems(app.shuffleSeed).take(4).map { item ->
                            item.cover?.let { app.films[it.jwId]?.poster ?: it.poster }
                        }
                        for (row in 0..1) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                                for (col in 0..1) {
                                    val url = posters.getOrNull(row * 2 + col)
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .aspectRatio(2f / 3f)
                                            .background(Color.Black.copy(alpha = if (url == null) 0.25f else 1f), RoundedCornerShape(3.dp)),
                                    ) {
                                        if (url != null) {
                                            AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)))
                                        }
                                    }
                                }
                            }
                        }
                        Text(shelf.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (shelf.films.size == 1) "1 film" else "${shelf.films.size} films",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}
