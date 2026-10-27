package com.njdyson.blockbustertv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.njdyson.blockbustertv.data.Film
import com.njdyson.blockbustertv.data.Offer
import java.util.Locale

/**
 * Requests focus, retrying for a few frames: items inside lazy rows attach to the
 * layout a frame or two after the screen first composes.
 */
suspend fun FocusRequester.requestFocusWhenReady() {
    repeat(10) {
        if (runCatching { requestFocus() }.isSuccess) return
        withFrameNanos { }
    }
}

/** The yellow ticket logo. */
@Composable
fun Logo() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "BLOCKBUSTER",
            color = NightNavy,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
            modifier = Modifier
                .background(TicketYellow, RoundedCornerShape(6.dp))
                .padding(horizontal = 14.dp, vertical = 4.dp),
        )
        Text("TV", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}

/** Big backdrop behind a screen, fading between films, darkened so text stays readable. */
@Composable
fun Backdrop(url: String?, alpha: Float = 0.45f) {
    Crossfade(targetState = url, label = "backdrop") { current ->
        Box(Modifier.fillMaxSize()) {
            if (current != null) {
                AsyncImage(
                    model = current,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = alpha,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to NightNavy, 0.55f to NightNavy.copy(alpha = 0.6f), 1f to Color.Transparent),
                ),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.5f to Color.Transparent, 1f to NightNavy),
                ),
            )
        }
    }
}

/**
 * A film's poster filling [modifier]. With no art, or art that fails to load, it shows a
 * same-size stand-in instead: the title on a store-blue case with yellow label stripes.
 */
@Composable
fun Poster(url: String?, title: String, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    if (url == null || failed) {
        PosterPlaceholder(title, modifier)
    } else {
        AsyncImage(
            model = url,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            modifier = modifier,
        )
    }
}

@Composable
private fun PosterPlaceholder(title: String, modifier: Modifier) {
    BoxWithConstraints(
        modifier.background(Brush.linearGradient(listOf(StoreBlue, NightNavy))),
        contentAlignment = Alignment.Center,
    ) {
        val inset = maxHeight * 0.06f
        val stripe = Modifier.fillMaxWidth().height(maxHeight * 0.04f).background(TicketYellow)
        Box(Modifier.align(Alignment.TopCenter).padding(top = inset).then(stripe))
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = inset).then(stripe))
        Text(
            title,
            color = Color.White,
            fontSize = (maxWidth.value * 0.11f).coerceIn(7f, 22f).sp,
            lineHeight = (maxWidth.value * 0.13f).coerceIn(8f, 26f).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = maxWidth * 0.08f, vertical = maxHeight * 0.14f),
        )
    }
}

/** IMDb rating as a round yellow price sticker, like the ones on rental boxes. */
@Composable
fun RatingSticker(score: Double?, size: Dp = 38.dp, modifier: Modifier = Modifier) {
    if (score == null) return
    Box(
        modifier.size(size).background(TicketYellow, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            String.format(Locale.UK, "%.1f", score),
            color = NightNavy,
            fontSize = (size.value * 0.36f).sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
fun ServiceIcons(offers: List<Offer>, size: Dp = 20.dp, max: Int = 4) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        offers.take(max).forEach { offer ->
            AsyncImage(
                model = offer.icon,
                contentDescription = offer.providerName,
                modifier = Modifier.size(size).clip(RoundedCornerShape(4.dp)),
            )
        }
    }
}

fun Film.metaLine(): String = listOfNotNull(
    year?.toString(),
    runtimeMinutes?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
    ageCertification,
    imdbScore?.let { String.format(Locale.UK, "IMDb %.1f/10", it) },
).joinToString("  ·  ")
