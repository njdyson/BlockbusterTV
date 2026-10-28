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
import com.njdyson.blockbustertv.data.onlyFilms
import com.njdyson.blockbustertv.data.shuffled
import com.njdyson.blockbustertv.data.FilmSet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.OutlinedIconButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.njdyson.blockbustertv.data.Film
import com.njdyson.blockbustertv.data.FilmLauncher

/**
 * The store floor: a preview of the focused film at the top ("the back of the box"),
 * then a shelf of VHS boxes per collection. Hold Select (or press Menu) on a box to
 * move or remove it; press Play/Pause to add it to the shortlist.
 */
@Composable
fun HomeScreen(
    app: AppState,
    onOpen: (Film) -> Unit,
    onOpenSet: (shelfId: String, setId: String) -> Unit,
    onSearch: () -> Unit,
    onPhoneEdit: () -> Unit,
    onSettings: () -> Unit,
    onShortlist: () -> Unit,
) {
    // Film shelves, or series shelves in series mode.
    val savedShelves by app.currentShelves.collectAsState()
    // Shelves, and the films on them, in a random order that's new each session (AppState.shuffleSeed).
    val allShelves = remember(savedShelves, app.shuffleSeed) { savedShelves.shuffled(app.shuffleSeed) }
    val owned by app.shelfStore.owned.collectAsState()
    // With Free to watch on, each shelf keeps only its free films, and shelves left empty are hidden.
    val shelves = remember(allShelves, app.freeOnly, app.films, owned) {
        if (!app.freeOnly) allShelves
        else allShelves.map { shelf -> shelf.onlyFilms(app::isFreeToWatch) }.filter { it.films.isNotEmpty() }
    }
    fun saved(shelf: Shelf) = savedShelves.firstOrNull { it.id == shelf.id } ?: shelf
    val yearOf = { film: ShelfFilm -> app.films[film.jwId]?.year ?: film.year }
    // The focused box is "shelfId:itemKey"; a set's preview and backdrop come from its first film.
    // If the filter hides it, focus falls back to the first box.
    val focusedItem = app.focusedKey?.let { key ->
        shelves.firstOrNull { it.id == key.substringBefore(':') }?.items(yearOf)?.firstOrNull { it.key == key.substringAfter(':') }
    }
    val focusedFilm = focusedItem?.cover?.let { app.films[it.jwId] }
    val focusKey = app.focusedKey?.takeIf { focusedItem != null }
    var menuFor by remember { mutableStateOf<Pair<Shelf, ShelfItem>?>(null) }
    var copying by remember { mutableStateOf<ShelfFilm?>(null) }
    var choosingSet by remember { mutableStateOf<Pair<Shelf, ShelfFilm>?>(null) }
    var gathering by remember { mutableStateOf<SetGathering?>(null) }
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

    // One shelf fills the space under the preview: when focus moves to another shelf, snap it to the top.
    val focusedShelf = app.focusedKey?.substringBefore(':')
    LaunchedEffect(focusedShelf, app.overview) {
        if (app.overview) return@LaunchedEffect
        val index = shelves.indexOfFirst { it.id == focusedShelf }
        if (index >= 0) app.homeListState.animateScrollToItem(index)
    }

    Box(Modifier.fillMaxSize().background(NightNavy)) {
        Backdrop(focusedFilm?.backdrop)
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Logo()
                Spacer(Modifier.width(8.dp))
                ModeToggle(app.seriesMode) { app.showSeries(!app.seriesMode) }
                // The status takes whatever room the buttons leave and is cut short rather than squeezing them.
                Box(Modifier.weight(1f)) {
                    app.status?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                HeaderButton(if (app.freeOnly) "Show all ${app.noun}" else "Free to watch") { app.showFreeOnly(!app.freeOnly) }
                val shortlisted = app.currentShortlist.size
                HeaderButton(if (shortlisted == 0) "Shortlist" else "Shortlist ($shortlisted)", onShortlist)
                HeaderButton(if (app.seriesMode) "Add series" else "Add film", onSearch)
                HeaderButton("Edit on phone", onPhoneEdit)
                // Shows the view the press switches to.
                HeaderIconButton(
                    if (app.overview) HeaderIcons.Shelves else HeaderIcons.Tiles,
                    if (app.overview) "Shelves" else "Overview",
                ) { app.showOverview(!app.overview) }
                HeaderIconButton(HeaderIcons.Settings, "Settings", onSettings)
            }
            if (app.overview && shelves.isNotEmpty()) {
                ShelfOverview(app, shelves, onPick = { shelf ->
                    // Back to the shelves, on the first box of the chosen one.
                    app.focusedKey = shelf.displayItems(app.shuffleSeed, yearOf).firstOrNull()?.let { "${shelf.id}:${it.key}" }
                    pendingShelf = shelf.id
                    app.showOverview(false)
                })
                return@Column
            }
            Preview(focusedFilm, focusedItem as? SetItem, Modifier.padding(horizontal = 48.dp))
            if (shelves.isEmpty()) {
                Text(
                    when {
                        app.freeOnly && allShelves.isNotEmpty() -> "Nothing on your shelves is free to watch right now. Choose Show all ${app.noun} to see everything."
                        app.seriesMode -> "No series shelves yet. Choose Add series, or switch to Series in the phone app, to start one."
                        else -> "No shelves yet. Choose Edit on phone or Add film to start one."
                    },
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(48.dp),
                )
            }
            LazyColumn(
                state = app.homeListState,
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(shelves, key = { _, shelf -> shelf.id }) { shelfIndex, shelf ->
                    Column(Modifier.fillParentMaxHeight().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ShelfSign(shelf.name)
                        if (shelf.films.isEmpty()) {
                            Text(
                                if (app.seriesMode) "Empty shelf: add series with Add series or from your phone."
                                else "Empty shelf: add films with Add film or from your phone.",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 14.sp,
                                modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                            )
                        }
                        LazyRow(
                            state = app.shelfState(shelf.id),
                            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            val items = shelf.displayItems(app.shuffleSeed, yearOf)
                            itemsIndexed(items, key = { _, item -> item.key }) { i, item ->
                                val key = "${shelf.id}:${item.key}"
                                // Focus the first box on launch, or the last one used when coming Back.
                                val wantsFocus = focusKey == key ||
                                    (focusKey == null && shelfIndex == 0 && i == 0)
                                when (item) {
                                    is FilmItem -> VhsBox(
                                        film = app.films[item.film.jwId],
                                        title = item.film.title,
                                        fallbackPoster = item.film.poster,
                                        ownedOnYouTube = item.film.jwId in owned,
                                        shortlisted = app.isShortlisted(item.film.jwId),
                                        requestFocus = wantsFocus,
                                        onFocused = { app.focusedKey = key },
                                        onClick = { app.films[item.film.jwId]?.let(onOpen) },
                                        onMenu = { menuFor = saved(shelf) to item },
                                        onShortlist = { app.toggleShortlist(item.film) },
                                    )
                                    is SetItem -> VhsBox(
                                        film = item.cover?.let { app.films[it.jwId] },
                                        title = item.set.name,
                                        fallbackPoster = item.cover?.poster,
                                        setCount = item.films.size,
                                        requestFocus = wantsFocus,
                                        onFocused = { app.focusedKey = key },
                                        onClick = { onOpenSet(shelf.id, item.set.id) },
                                        onMenu = { menuFor = saved(shelf) to item },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Text(
                (if (app.freeOnly) "Showing ${app.noun} included on your services or owned on YouTube · " else "") +
                    "Availability and ratings from JustWatch · Hold Select on a box to " +
                    (if (app.seriesMode) "remove it" else "make sets or remove it") + " · Press ⏯ on a box to shortlist it",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 48.dp, end = 48.dp, bottom = 12.dp),
            )
        }
    }

    menuFor?.let { (shelf, item) ->
        val store = app.shelfStore
        val loose = shelf.items(yearOf).filterIsInstance<FilmItem>().map { it.film }
        ChoiceDialog(
            when (item) {
                is FilmItem -> item.film.title
                is SetItem -> "${item.set.name} (box set)"
            },
            buildList {
                when (item) {
                    is FilmItem -> {
                        add((if (app.isShortlisted(item.film.jwId)) "Take off the shortlist" else "Add to the shortlist") to { app.toggleShortlist(item.film) })
                        // Box sets are for films; series shelves don't offer them.
                        if (!app.seriesMode && loose.size > 1) add("Make a set with other films…" to { gathering = SetGathering(shelf, item.film, null) })
                        if (!app.seriesMode && shelf.sets.isNotEmpty()) add("Add to a set…" to { choosingSet = shelf to item.film })
                        add("Add to another shelf…" to { copying = item.film })
                        add("Remove from ${shelf.name}" to {
                            app.focusedKey = null
                            store.removeFilm(shelf.id, item.film.jwId)
                        })
                    }
                    is SetItem -> {
                        add("Open the set" to { onOpenSet(shelf.id, item.set.id) })
                        if (loose.isNotEmpty()) add("Add films to this set…" to { gathering = SetGathering(shelf, null, item.set) })
                        add("Rename the set…" to {
                            naming = Naming("Rename the set", item.set.name, "Rename") { store.renameSet(shelf.id, item.set.id, it) }
                        })
                        add("Ungroup (keep the films)" to {
                            app.focusedKey = null
                            store.ungroupSet(shelf.id, item.set.id)
                        })
                    }
                }
            },
            onDismiss = { menuFor = null },
        )
    }
    // Ticking films for a new set (started from one film) or for an existing set.
    gathering?.let { request ->
        val shelf = request.shelf
        val loose = shelf.items(yearOf).filterIsInstance<FilmItem>().map { it.film }.filterNot { it.jwId == request.film?.jwId }
        MultiChoiceDialog(
            title = request.set?.let { "Add films to ${it.name}" } ?: "Make a set with ${request.film?.title}",
            options = loose.map { it.jwId to (it.title + (yearOf(it)?.let { y -> " ($y)" } ?: "")) },
            confirmLabel = if (request.set != null) "Add to the set" else "Next: name the set",
            onDone = { chosen ->
                gathering = null
                val set = request.set
                if (set != null) {
                    chosen.forEach { app.shelfStore.addToSet(shelf.id, set.id, it) }
                    app.focusedKey = "${shelf.id}:set:${set.id}"
                } else {
                    val films = listOfNotNull(request.film?.jwId) + chosen
                    naming = Naming("Name the set", "", "Create set") { name ->
                        val id = app.shelfStore.createSet(shelf.id, name, films)
                        app.focusedKey = "${shelf.id}:set:$id"
                    }
                }
            },
            onDismiss = { gathering = null },
        )
    }
    choosingSet?.let { (shelf, film) ->
        ChoiceDialog(
            "Add ${film.title} to a set",
            shelf.sets.map { set ->
                set.name to {
                    app.shelfStore.addToSet(shelf.id, set.id, film.jwId)
                    app.focusedKey = "${shelf.id}:set:${set.id}"
                }
            },
            onDismiss = { choosingSet = null },
        )
    }
    naming?.let { request ->
        TextEntryDialog(request.title, request.initial, request.confirm, onDone = { request.onDone(it); naming = null }, onDismiss = { naming = null })
    }
    copying?.let { film ->
        ShelfPicker(
            allShelves,
            onPick = { shelfId, newName ->
                app.addToShelf(shelfId ?: app.shelfStore.addShelf(newName.orEmpty(), app.seriesMode), film)
                copying = null
            },
            onDismiss = { copying = null },
        )
    }
}

/** Choosing films for a set: a new one started from [film], or more for an existing [set]. */
private class SetGathering(val shelf: Shelf, val film: ShelfFilm?, val set: FilmSet?)

/** A text-entry request: "Name the new set", "Rename the set"… */
private class Naming(val title: String, val initial: String, val confirm: String, val onDone: (String) -> Unit)

@Composable
private fun Preview(film: Film?, set: SetItem?, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            modifier = Modifier.fillMaxWidth(0.75f),
        )
    }
}

@Composable
private fun ShelfSign(name: String) {
    Row(Modifier.padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 5.dp, height = 18.dp).background(TicketYellow))
        Spacer(Modifier.width(8.dp))
        Text(name.uppercase(), color = TicketYellow, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
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
    shortlisted: Boolean = false,
    setCount: Int? = null,
    requestFocus: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onShortlist: (() -> Unit)? = null,
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
                // The remote's Menu (☰) button opens the same menu as holding Select,
                // and Play/Pause adds the film to the shortlist (or takes it off).
                when {
                    event.type != KeyEventType.KeyUp -> false
                    event.key == Key.Menu -> { onMenu(); true }
                    event.key == Key.MediaPlayPause && onShortlist != null -> { onShortlist(); true }
                    else -> false
                }
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
                Poster(film?.poster ?: fallbackPoster, title, cover.clip(RoundedCornerShape(3.dp)))
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
                if (shortlisted) ShortlistTag(Modifier.align(Alignment.BottomStart).padding(4.dp))
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
 * Zoomed out: every shelf as a tile showing its first four posters, seven to a row and
 * sized so two full rows fit on screen. Selecting a tile goes back to that shelf.
 */
@Composable
private fun ShelfOverview(app: AppState, shelves: List<Shelf>, onPick: (Shelf) -> Unit) {
    val currentShelf = app.focusedKey?.substringBefore(':')
    Column(Modifier.padding(top = 4.dp)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
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
                    Column(Modifier.padding(6.dp)) {
                        val covers = shelf.displayItems(app.shuffleSeed, { f -> app.films[f.jwId]?.year ?: f.year }).take(4).map { it.cover }
                        for (row in 0..1) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(bottom = 3.dp)) {
                                for (col in 0..1) {
                                    val cover = covers.getOrNull(row * 2 + col)
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .aspectRatio(2f / 3f)
                                            .background(Color.Black.copy(alpha = if (cover == null) 0.25f else 1f), RoundedCornerShape(3.dp)),
                                    ) {
                                        if (cover != null) {
                                            Poster(app.films[cover.jwId]?.poster ?: cover.poster, cover.title, Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)))
                                        }
                                    }
                                }
                            }
                        }
                        Text(shelf.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                app.seriesMode -> "${shelf.films.size} series"
                                shelf.films.size == 1 -> "1 film"
                                else -> "${shelf.films.size} films"
                            },
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Films | Series: shows both, with the current one highlighted; pressing it switches.
 * The two are kept apart: each has its own shelves, search and shortlist.
 */
@Composable
private fun ModeToggle(series: Boolean, onToggle: () -> Unit) {
    OutlinedButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ModeLabel("Films", on = !series)
            ModeLabel("Series", on = series)
        }
    }
}

@Composable
private fun ModeLabel(label: String, on: Boolean) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        color = if (on) NightNavy else LocalContentColor.current.copy(alpha = 0.7f),
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .background(if (on) TicketYellow else Color.Transparent, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

/** A compact header button whose label always stays on one line. */
@Composable
private fun HeaderButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

/** An icon-only header button, used where the meaning is clear without a label. */
@Composable
private fun HeaderIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    OutlinedIconButton(onClick = onClick) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}
