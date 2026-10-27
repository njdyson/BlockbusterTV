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
import androidx.compose.runtime.remember
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
 * then a shelf of VHS boxes per collection.
 */
@Composable
fun HomeScreen(app: AppState, onOpen: (Film) -> Unit, onSettings: () -> Unit) {
    val collections = app.repository.collections
    val focusedFilm = app.focusedKey?.substringAfter(':')?.let { app.films[it] }

    Box(Modifier.fillMaxSize().background(NightNavy)) {
        Backdrop(focusedFilm?.backdrop)
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Logo()
                Spacer(Modifier.width(24.dp))
                app.status?.let { Text(it, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onSettings) { Text("Settings") }
            }
            Preview(focusedFilm, Modifier.padding(horizontal = 48.dp).height(128.dp))
            LazyColumn(
                state = app.homeListState,
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(collections) { shelfIndex, collection ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ShelfSign(collection.name)
                        LazyRow(
                            state = app.shelfStates[shelfIndex],
                            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            itemsIndexed(collection.films, key = { _, entry -> entry.jwId }) { i, entry ->
                                val key = "$shelfIndex:${entry.jwId}"
                                // Focus the first box on launch, or the last one used when coming Back.
                                val wantsFocus = app.focusedKey == key || (app.focusedKey == null && shelfIndex == 0 && i == 0)
                                VhsBox(
                                    film = app.films[entry.jwId],
                                    fallbackTitle = entry.title,
                                    requestFocus = wantsFocus,
                                    onFocused = { app.focusedKey = key },
                                    onClick = { app.films[entry.jwId]?.let(onOpen) },
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Availability and ratings from JustWatch",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 48.dp),
                    )
                }
            }
        }
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
private fun VhsBox(film: Film?, fallbackTitle: String, requestFocus: Boolean, onFocused: () -> Unit, onClick: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    if (requestFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocusWhenReady() }
    }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(128.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() },
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
                    model = film?.poster,
                    contentDescription = film?.title ?: fallbackTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(ShelfGrey, RoundedCornerShape(3.dp)),
                )
                if (film?.poster == null) {
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
