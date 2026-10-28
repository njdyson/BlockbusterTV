package com.njdyson.blockbustertv.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Text
import com.njdyson.blockbustertv.BuildConfig
import kotlinx.coroutines.delay

/**
 * Plays a YouTube trailer full screen inside the app, using YouTube's embedded player in a
 * WebView. The remote is handled here rather than by the page: OK pauses, left and right
 * skip 10 seconds, Back closes. If YouTube won't play it embedded (some trailers block
 * embedding) or it never starts, [onFallback] hands it to the YouTube app instead.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrailerPlayer(videoId: String, title: String, onClose: () -> Unit, onFallback: () -> Unit) {
    val close by rememberUpdatedState(onClose)
    val fallback by rememberUpdatedState(onFallback)
    var webView by remember { mutableStateOf<WebView?>(null) }
    // -1 until the player loads; then YouTube's player states (1 playing, 2 paused, 3 buffering, 0 ended).
    var state by remember { mutableIntStateOf(-1) }
    var keyPresses by remember { mutableIntStateOf(0) }
    var hintVisible by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }

    fun js(code: String) = webView?.evaluateJavascript("if (window.player && player.getPlayerState) { $code }", null)

    BackHandler { close() }
    LaunchedEffect(Unit) { focus.requestFocusWhenReady() }
    // A trailer that hasn't started within 20 seconds isn't going to: try the YouTube app.
    LaunchedEffect(Unit) {
        delay(20_000)
        if (state == -1 || state == 3) fallback()
    }
    LaunchedEffect(keyPresses, state) {
        hintVisible = true
        if (state == 1) {
            delay(4_000)
            hintVisible = false
        }
    }
    LaunchedEffect(state) { if (state == 0) close() }

    // Pause when the TV goes to the home screen or sleeps, so the trailer isn't heard in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) js("player.pauseVideo();")
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                // Back goes on to the BackHandler; every other key stays here, so focus can't
                // wander to the detail screen's buttons underneath.
                if (event.key == Key.Back) return@onKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    keyPresses++
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause ->
                            js("if (player.getPlayerState() == 1) player.pauseVideo(); else player.playVideo();")
                        Key.MediaPlay -> js("player.playVideo();")
                        Key.MediaPause -> js("player.pauseVideo();")
                        Key.DirectionLeft, Key.MediaRewind -> js("player.seekTo(Math.max(0, player.getCurrentTime() - 10), true);")
                        Key.DirectionRight, Key.MediaFastForward -> js("player.seekTo(player.getCurrentTime() + 10, true);")
                    }
                }
                true
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val main = Handler(Looper.getMainLooper())
                WebView(context).apply {
                    // The remote is handled by the Box above, not the page.
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = WebViewClient()
                    webChromeClient = WebChromeClient()
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onState(value: Int) {
                                main.post { state = value }
                            }

                            @JavascriptInterface
                            fun onError(code: Int) {
                                main.post { fallback() }
                            }
                        },
                        "Android",
                    )
                    // YouTube refuses embeds with no referrer, so the page gets our server's address.
                    loadDataWithBaseURL("$ORIGIN/", playerPage(videoId), "text/html", "utf-8", null)
                    webView = this
                }
            },
            onRelease = { view ->
                view.loadUrl("about:blank")
                view.destroy()
                webView = null
            },
        )

        if (hintVisible || state != 1) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .padding(horizontal = 48.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, color = TicketYellow, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    when (state) {
                        -1, 3 -> "Loading trailer…"
                        2 -> "Paused  ·  OK to play  ·  ◀ ▶ skip 10s  ·  BACK to close"
                        else -> "OK to pause  ·  ◀ ▶ skip 10s  ·  BACK to close"
                    },
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 15.sp,
                )
            }
        }
    }
}

private val ORIGIN = BuildConfig.SERVER_URL.trimEnd('/')

private fun playerPage(videoId: String) = """
<!doctype html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>html, body { margin: 0; height: 100%; background: #000; overflow: hidden; } #player { width: 100%; height: 100%; }</style>
</head><body>
<div id="player"></div>
<script>
  var player;
  function onYouTubeIframeAPIReady() {
    player = new YT.Player('player', {
      width: '100%', height: '100%', videoId: '$videoId',
      playerVars: { autoplay: 1, controls: 0, disablekb: 1, fs: 0, rel: 0, iv_load_policy: 3, playsinline: 1, modestbranding: 1, origin: '$ORIGIN' },
      events: {
        onReady: function (e) { e.target.playVideo(); },
        onStateChange: function (e) { Android.onState(e.data); },
        onError: function (e) { Android.onError(e.data); }
      }
    });
  }
</script>
<script src="https://www.youtube.com/iframe_api"></script>
</body></html>
""".trimIndent()
