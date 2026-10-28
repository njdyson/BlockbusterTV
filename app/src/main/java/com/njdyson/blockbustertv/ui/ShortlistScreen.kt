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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import com.njdyson.blockbustertv.data.Plex
import com.njdyson.blockbustertv.data.ShelfFilm

/**
 * The shortlist: films picked while browsing, side by side so they're easy to compare.
 * Select opens a film; Play/Pause takes it off the list, and holding Select (or Menu) offers both.
 */
@Composable
fun ShortlistScreen(app: AppState, onOpen: (Film) -> Unit) {
    // Films and series are shortlisted separately: this shows the current mode's.
    val films = app.currentShortlist
    val owned by app.shelfStore.owned.collectAsState()
    var focused by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<ShelfFilm?>(null) }
    var confirmingClear by remember { mutableStateOf(false) }
    val firstCard = remember { FocusRequester() }
    val topRated = films.mapNotNull { app.films[it.jwId]?.imdbScore }.maxOrNull()?.takeIf { films.size > 1 }

    Box(Modifier.fillMaxSize().background(NightNavy)) {
        Backdrop((focused ?: films.firstOrNull()?.jwId)?.let { app.films[it]?.backdrop })
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Logo()
                Spacer(Modifier.width(12.dp))
                Text("Shortlist", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (films.isNotEmpty()) {
                    OutlinedButton(onClick = { confirmingClear = true }) {
                        Text("Clear shortlist")
                    }
                }
            }
            if (films.isEmpty()) {
                Text(
                    "Nothing shortlisted yet. While browsing, press ⏯ on a box (or hold Select and choose Add to the shortlist) " +
                        "to add it here, then compare your picks side by side.",
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier.fillMaxWidth(0.7f).padding(top = 24.dp),
                )
                return@Column
            }
            Text(
                when {
                    films.size == 1 -> "1 ${if (app.seriesMode) "series" else "film"}"
                    else -> "${films.size} ${app.noun} · side by side"
                },
                color = TicketYellow,
                fontSize = 15.sp,
            )
            LazyRow(
                // Room for the focused card to grow without being clipped.
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                itemsIndexed(films, key = { _, film -> film.jwId }) { i, film ->
                    ShortlistCard(
                        film = app.films[film.jwId],
                        entry = film,
                        ownedOnYouTube = film.jwId in owned,
                        onPlex = app.plexCopy(film) != null,
                        topRated = topRated != null && app.films[film.jwId]?.imdbScore == topRated,
                        modifier = if (i == 0) Modifier.focusRequester(firstCard) else Modifier,
                        onFocused = { focused = film.jwId },
                        onClick = { app.films[film.jwId]?.let(onOpen) },
                        onMenu = { menuFor = film },
                        onRemove = { app.toggleShortlist(film) },
                    )
                }
            }
            Text(
                "Select a film to see where to watch it · Hold Select or press ⏯ to take it off the list",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp,
            )
        }
    }
    // Focus the first film on arrival, and again when one is taken off the list.
    LaunchedEffect(films.size) { if (films.isNotEmpty()) firstCard.requestFocusWhenReady() }

    menuFor?.let { film ->
        ChoiceDialog(
            film.title,
            buildList {
                app.films[film.jwId]?.let { data -> add("Open" to { onOpen(data) }) }
                add("Take off the shortlist" to { app.removeFromShortlist(film.jwId) })
            },
            onDismiss = { menuFor = null },
        )
    }
    if (confirmingClear) {
        ChoiceDialog(
            "Clear the shortlist?",
            listOf((if (films.size == 1) "Clear 1 ${if (app.seriesMode) "series" else "film"}" else "Clear all ${films.size} ${app.noun}") to { app.clearShortlist() }),
            onDismiss = { confirmingClear = false },
        )
    }
}

/** One column of the comparison: poster, the key facts, where to watch, and the synopsis. */
@Composable
private fun ShortlistCard(
    film: Film?,
    entry: ShelfFilm,
    ownedOnYouTube: Boolean,
    onPlex: Boolean,
    topRated: Boolean,
    modifier: Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onMenu,
        modifier = modifier
            .width(196.dp)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .onKeyEvent { event ->
                when {
                    event.type != KeyEventType.KeyUp -> false
                    event.key == Key.Menu -> { onMenu(); true }
                    event.key == Key.MediaPlayPause -> { onRemove(); true }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ShelfGrey.copy(alpha = 0.85f),
            contentColor = Color.White,
            focusedContainerColor = ShelfGrey,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, TicketYellow))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(114.dp).height(170.dp).background(Color(0xFF0D0D12), RoundedCornerShape(4.dp)).padding(3.dp)) {
                    Poster(film?.poster ?: entry.poster, entry.title, Modifier.fillMaxSize().clip(RoundedCornerShape(3.dp)))
                }
                RatingSticker(film?.imdbScore, size = 40.dp, modifier = Modifier.align(Alignment.TopEnd))
                if (topRated) {
                    Text(
                        "TOP RATED",
                        color = NightNavy,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .background(TicketYellow, RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Text(entry.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val facts = listOfNotNull(
                (film?.year ?: entry.year)?.toString(),
                film?.lengthLabel(),
                film?.ageCertification,
            ).joinToString("  ·  ")
            if (facts.isNotEmpty()) Text(facts, fontSize = 12.sp, color = TicketYellow)
            film?.genres?.takeIf { it.isNotEmpty() }?.let {
                Text(it.take(3).joinToString(" / "), fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.height(22.dp)) {
                when {
                    film == null -> Unit
                    onPlex || ownedOnYouTube || film.watchServices.isNotEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (onPlex) {
                            AsyncImage(Plex.ICON, "On your Plex server", Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)))
                        }
                        if (ownedOnYouTube) {
                            AsyncImage(FilmLauncher.YOUTUBE_ICON, "Owned on YouTube", Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)))
                        }
                        ServiceIcons(film.watchServices.filterNot { onPlex && it.serviceId == "plex" }, size = 22.dp, max = 6 - listOf(onPlex, ownedOnYouTube).count { it })
                    }
                    film.cheapestRent != null -> Text("Rent ${film.cheapestRent?.price.orEmpty()}", fontSize = 12.sp, color = TicketYellow)
                    else -> Text("Not streaming", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
                }
            }
            Text(
                film?.synopsis.orEmpty(),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
