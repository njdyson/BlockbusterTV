package com.njdyson.blockbustertv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.njdyson.blockbustertv.data.Film
import com.njdyson.blockbustertv.data.Offer
import com.njdyson.blockbustertv.data.OfferKind
import com.njdyson.blockbustertv.data.Tmdb

/** The back of the box: synopsis and details, with a button for each way to watch. */
@Composable
fun DetailScreen(app: AppState, film: Film) {
    var trailerId by remember(film.jwId) { mutableStateOf<String?>(null) }
    LaunchedEffect(film.tmdbId) {
        trailerId = film.tmdbId?.let { runCatching { Tmdb.trailerId(it) }.getOrNull() }
    }

    val watch = film.watchOffers
    // One rent/buy button per service, for services whose link opens the film's store page.
    val stores = film.offers
        .filter { it.kind != OfferKind.WATCH && app.services.byId(it.serviceId)?.opensTitle == true }
        .groupBy { it.serviceId }
    val firstButton = remember { FocusRequester() }

    Box(Modifier.fillMaxSize().background(NightNavy)) {
        Backdrop(film.backdrop, alpha = 0.6f)
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Box(
                Modifier
                    .width(220.dp)
                    .height(330.dp)
                    .background(Color(0xFF0D0D12), RoundedCornerShape(8.dp))
                    .padding(6.dp),
            ) {
                AsyncImage(
                    model = film.posterLarge,
                    contentDescription = film.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)),
                )
                RatingSticker(film.imdbScore, size = 52.dp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(film.title, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(film.metaLine(), color = TicketYellow, fontSize = 16.sp)
                if (film.genres.isNotEmpty()) {
                    Text(film.genres.joinToString(" / "), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
                Text(
                    film.synopsis.orEmpty(),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.85f),
                )
                Spacer(Modifier.height(6.dp))

                // Initial focus: the first Watch button, else the first store, else the trailer.
                val focusTarget = when {
                    watch.isNotEmpty() -> "watch"
                    stores.isNotEmpty() -> "store"
                    else -> "trailer"
                }
                fun Modifier.focusIf(target: String, isFirst: Boolean = true): Modifier =
                    if (target == focusTarget && isFirst) focusRequester(firstButton) else this

                if (watch.isNotEmpty()) {
                    SectionLabel("WATCH NOW")
                    ButtonRow(watch) { offer ->
                        ServiceButton(
                            app, offer,
                            label = app.services.byId(offer.serviceId)?.name ?: offer.providerName,
                            note = if (offer.isChannel) offer.providerName else null,
                            primary = true,
                            modifier = Modifier.focusIf("watch", offer == watch.first()),
                        ) { app.launcher.watch(film, offer) }
                    }
                } else {
                    Text("Not included with any streaming service right now.", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
                if (stores.isNotEmpty()) {
                    SectionLabel("RENT OR BUY")
                    ButtonRow(stores.values.toList()) { offers ->
                        val note = listOfNotNull(
                            offers.firstOrNull { it.kind == OfferKind.RENT }?.price?.let { "Rent $it" },
                            offers.firstOrNull { it.kind == OfferKind.BUY }?.price?.let { "Buy $it" },
                        ).joinToString(" · ")
                        ServiceButton(
                            app, offers.first(),
                            label = app.services.byId(offers.first().serviceId)?.name.orEmpty(),
                            note = note,
                            primary = false,
                            modifier = Modifier.focusIf("store", offers == stores.values.first()),
                        ) { app.launcher.watch(film, offers.first()) }
                    }
                }
                trailerId?.let { id ->
                    SectionLabel("EXTRAS")
                    OutlinedButton(onClick = { app.launcher.trailer(id) }, modifier = Modifier.focusIf("trailer")) {
                        Text("▶  Play trailer")
                    }
                }
            }
        }
    }
    LaunchedEffect(film.jwId, trailerId) { firstButton.requestFocusWhenReady() }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TicketYellow, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
}

/** A plain Row, not a LazyRow: lazy lists clip, which cut off the focused button as it grows. */
@Composable
private fun <T> ButtonRow(items: List<T>, content: @Composable (T) -> Unit) {
    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items.take(5).forEach { content(it) }
    }
}

@Composable
private fun ServiceButton(
    app: AppState,
    offer: Offer,
    label: String,
    note: String?,
    primary: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(model = offer.icon, contentDescription = null, modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)))
            Column {
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                val opensHome = app.services.byId(offer.serviceId)?.opensTitle == false
                val detail = listOfNotNull(note, if (opensHome) "opens the app" else null).joinToString(" · ")
                if (detail.isNotEmpty()) Text(detail, fontSize = 11.sp)
            }
        }
    }
    if (primary) Button(onClick = onClick, modifier = modifier) { content() }
    else OutlinedButton(onClick = onClick, modifier = modifier) { content() }
}
