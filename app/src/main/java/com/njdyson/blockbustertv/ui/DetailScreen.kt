package com.njdyson.blockbustertv.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.njdyson.blockbustertv.TrailerActivity
import com.njdyson.blockbustertv.TrailerWebView
import com.njdyson.blockbustertv.data.Film
import com.njdyson.blockbustertv.data.FilmLauncher
import com.njdyson.blockbustertv.linklab.qrCode
import com.njdyson.blockbustertv.data.Offer
import com.njdyson.blockbustertv.data.OfferKind
import com.njdyson.blockbustertv.data.Plex
import com.njdyson.blockbustertv.data.ShelfFilm
import com.njdyson.blockbustertv.data.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The back of the box: synopsis and details, with a button for each way to watch. */
@Composable
fun DetailScreen(app: AppState, film: Film) {
    var trailerId by remember(film.jwId) { mutableStateOf<String?>(null) }
    LaunchedEffect(film.tmdbId) {
        trailerId = film.tmdbId?.let { runCatching { Tmdb.trailerId(it, series = film.isSeries) }.getOrNull() }
    }
    // Buffer the trailer's opening seconds after a moment on the page, so it starts smoothly.
    val context = LocalContext.current
    LaunchedEffect(trailerId) {
        val id = trailerId ?: return@LaunchedEffect
        delay(1_500)
        TrailerWebView.preload(context, id)
    }
    DisposableEffect(film.jwId) { onDispose { TrailerWebView.release() } }

    val watch = film.watchOffers
    // One rent/buy button per service, for services whose link opens the film's store page.
    val stores = film.offers
        .filter { it.kind != OfferKind.WATCH && app.services.byId(it.serviceId)?.opensTitle == true }
        .groupBy { it.serviceId }
    val firstButton = remember { FocusRequester() }
    val owned by app.shelfStore.owned.collectAsState()
    // Owning on YouTube is for films; series don't offer it.
    val ownedOnYouTube = !film.isSeries && film.jwId in owned
    val plexCopy = app.plexCopy(film)
    // The film's page in the Plex app, looked up when the page opens so the button is instant.
    var plexPage by remember(film.jwId) { mutableStateOf<String?>(null) }
    LaunchedEffect(plexCopy?.plexId) {
        plexPage = plexCopy?.plexId?.let { id -> withContext(Dispatchers.IO) { Plex.pageUrl(id) } }
    }
    var buying by remember { mutableStateOf(false) }
    val trailerButton = remember { FocusRequester() }
    var trailersClosed by remember(film.jwId) { mutableStateOf(0) }
    val trailerPlayer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        trailersClosed++
        // YouTube wouldn't play it embedded: hand it to the YouTube app.
        if (result.resultCode == TrailerActivity.RESULT_FALLBACK) trailerId?.let { app.launcher.trailer(it) }
    }

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
                Poster(film.posterLarge, film.title, Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)))
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

                // Initial focus: on Plex, else owned on YouTube, else the first Watch button, else the first store.
                val focusTarget = when {
                    plexCopy != null -> "plex"
                    ownedOnYouTube -> "owned"
                    watch.isNotEmpty() -> "watch"
                    else -> "store"
                }
                fun Modifier.focusIf(target: String, isFirst: Boolean = true): Modifier =
                    if (target == focusTarget && isFirst) focusRequester(firstButton) else this

                if (watch.isNotEmpty() || ownedOnYouTube || plexCopy != null) {
                    SectionLabel("WATCH NOW")
                    // Your own copies first: on your Plex server, then owned on YouTube.
                    val watchButtons: List<WatchButton> = listOfNotNull(
                        WatchButton.OnPlex.takeIf { plexCopy != null },
                        WatchButton.OwnedOnYouTube.takeIf { ownedOnYouTube },
                    ) + watch.map { WatchButton.Service(it) }
                    ButtonRow(watchButtons) { button ->
                        val offer = when (button) {
                            WatchButton.OnPlex -> {
                                IconButton(
                                    icon = Plex.ICON, label = "Plex", note = "On your server",
                                    primary = true, modifier = Modifier.focusIf("plex"),
                                ) { app.launcher.watchOnPlex(film, plexPage) }
                                return@ButtonRow
                            }
                            WatchButton.OwnedOnYouTube -> {
                                IconButton(
                                    icon = FilmLauncher.YOUTUBE_ICON, label = "YouTube", note = "You own this",
                                    primary = true, modifier = Modifier.focusIf("owned"),
                                ) { app.launcher.watchOwnedOnYouTube(film) }
                                return@ButtonRow
                            }
                            is WatchButton.Service -> button.offer
                        }
                        ServiceButton(
                            app, offer,
                            label = app.services.byId(offer.serviceId)?.name ?: offer.providerName,
                            // A series' service with only some seasons says which, e.g. "Season 1 only".
                            note = listOfNotNull(
                                offer.providerName.takeIf { offer.isChannel },
                                offer.seasons?.let { "$it only" },
                            ).joinToString(" · ").ifEmpty { null },
                            primary = true,
                            modifier = Modifier.focusIf("watch", offer == watch.first()),
                        ) { app.launcher.watch(film, offer) }
                    }
                } else {
                    Text("Not included with any streaming service right now.", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
                SectionLabel("RENT OR BUY")
                // YouTube's film store isn't in JustWatch's UK data, so it's always offered,
                // as a hand-off to the phone (there's no way to buy through the TV app).
                // null stands for the YouTube button, shown last. Series don't get it: the
                // owned-on-YouTube hand-off is for films.
                val storeButtons: List<List<Offer>?> = stores.values.toList() + if (film.isSeries) emptyList() else listOf(null)
                if (storeButtons.isEmpty()) {
                    Text("Not available to rent or buy right now.", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
                ButtonRow(storeButtons) { offers ->
                    if (offers == null) {
                        IconButton(
                            icon = FilmLauncher.YOUTUBE_ICON, label = "YouTube", note = if (ownedOnYouTube) "Owned" else "Buy on your phone",
                            primary = false, modifier = Modifier.focusIf("store", stores.isEmpty()),
                        ) { buying = true }
                        return@ButtonRow
                    }
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
                SectionLabel("EXTRAS")
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    trailerId?.let { id ->
                        OutlinedButton(onClick = { trailerPlayer.launch(TrailerActivity.intent(context, id, film.title)) }, modifier = Modifier.focusRequester(trailerButton)) {
                            Text("▶  Play trailer")
                        }
                    }
                    OutlinedButton(onClick = { app.toggleShortlist(ShelfFilm(film.jwId, film.imdbId, film.title, film.year, film.poster)) }) {
                        Text(if (app.isShortlisted(film.jwId)) "★  On the shortlist" else "☆  Add to shortlist")
                    }
                }
            }
        }
    }
    LaunchedEffect(film.jwId, ownedOnYouTube, plexCopy != null) { firstButton.requestFocusWhenReady() }

    // Back from a trailer: focus returns to its button.
    LaunchedEffect(trailersClosed) { if (trailersClosed > 0) trailerButton.requestFocusWhenReady() }

    if (buying) {
        BuyOnYouTubeDialog(
            film = film,
            owned = ownedOnYouTube,
            onOwned = { app.shelfStore.setOwned(film.jwId, it); buying = false },
            onOpenYouTube = { app.launcher.openYouTube() },
            onDismiss = { buying = false },
        )
    }
}

/**
 * The spec's purchase hand-off: a QR code opens YouTube's search for the film on the phone,
 * where it can be bought; then it's marked as owned here so its Watch button opens YouTube.
 */
@Composable
private fun BuyOnYouTubeDialog(
    film: Film,
    owned: Boolean,
    onOwned: (Boolean) -> Unit,
    onOpenYouTube: () -> Unit,
    onDismiss: () -> Unit,
) {
    val url = remember(film.jwId) { FilmLauncher.youTubeSearchUrl(film) }
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier.padding(48.dp).background(ShelfGrey, RoundedCornerShape(12.dp)).padding(28.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                qrCode(url),
                contentDescription = "QR code for YouTube",
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(240.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp),
            )
            Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Buy or rent on YouTube", color = TicketYellow, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(film.title + (film.year?.let { " ($it)" } ?: ""), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Scan with your phone to find it on YouTube, and buy or rent it there. " +
                        "Films bought on YouTube or Google TV appear in the YouTube app's Library on this TV.",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                )
                if (owned) {
                    Button(onClick = { onDismiss(); onOpenYouTube() }, modifier = Modifier.focusRequester(first)) { Text("Open YouTube") }
                    OutlinedButton(onClick = { onOwned(false) }) { Text("I don't own this on YouTube") }
                } else {
                    Button(onClick = { onOwned(true) }, modifier = Modifier.focusRequester(first)) { Text("I've bought it: mark as owned") }
                    OutlinedButton(onClick = { onDismiss(); onOpenYouTube() }) { Text("Open YouTube on the TV") }
                }
                OutlinedButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
    LaunchedEffect(Unit) { first.requestFocusWhenReady() }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TicketYellow, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
}

/** A plain Row, not a LazyRow: lazy lists clip, which cut off the focused button as it grows. */
/** A button under WATCH NOW: the user's own copy on Plex or YouTube, or a streaming service's offer. */
private sealed interface WatchButton {
    data object OnPlex : WatchButton
    data object OwnedOnYouTube : WatchButton
    data class Service(val offer: Offer) : WatchButton
}

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
    val opensHome = app.services.byId(offer.serviceId)?.opensTitle == false
    val detail = listOfNotNull(note, if (opensHome) "opens the app" else null).joinToString(" · ")
    IconButton(offer.icon, label, detail.ifEmpty { null }, primary, modifier, onClick)
}

@Composable
private fun IconButton(icon: String?, label: String, note: String?, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(model = icon, contentDescription = null, modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)))
            Column {
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (note != null) Text(note, fontSize = 11.sp)
            }
        }
    }
    if (primary) Button(onClick = onClick, modifier = modifier) { content() }
    else OutlinedButton(onClick = onClick, modifier = modifier) { content() }
}
