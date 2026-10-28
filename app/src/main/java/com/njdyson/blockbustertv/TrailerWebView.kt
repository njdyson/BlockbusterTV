package com.njdyson.blockbustertv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The WebView holding YouTube's embedded player, made ahead of time so a trailer starts
 * without the wait for the WebView, YouTube's scripts and the first seconds of video.
 *
 * The film page calls [preload] once it knows the trailer: the player loads muted, plays
 * until it starts, then pauses at the start, which has YouTube buffer the opening seconds.
 * [TrailerActivity] then [take]s it and plays from there. A trailer that isn't watched costs
 * only those few seconds of video.
 */
@SuppressLint("StaticFieldLeak") // Made with the application context, never an activity.
object TrailerWebView {

    /** Player events, as YouTube's states (1 playing, 2 paused, 3 buffering, 0 ended) or an error. */
    interface Listener {
        fun onState(state: Int)
        fun onError(code: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    private var videoId: String? = null
    private var listener: Listener? = null
    private var preloading = false
    // An error while preloading, e.g. the trailer doesn't allow embedding; passed on by take().
    private var error: Int? = null

    /** Starts loading [id] in the background, replacing any other preloaded trailer. */
    fun preload(context: Context, id: String) {
        if (id == videoId) return
        release()
        preloading = true
        load(context, id)
    }

    /**
     * The player for [id] with [listener] attached: the preloaded one if it's for this trailer
     * (the caller then plays it), else a new one that plays as soon as it loads.
     */
    fun take(context: Context, id: String, listener: Listener): WebView {
        if (id != videoId) {
            release()
            load(context, id)
        }
        preloading = false
        this.listener = listener
        error?.let { code -> main.post { listener.onError(code) } }
        return view!!
    }

    /** Throws away the player, e.g. when leaving the film page or closing the trailer. */
    fun release() {
        view?.apply {
            (parent as? ViewGroup)?.removeView(this)
            loadUrl("about:blank")
            destroy()
        }
        view = null
        videoId = null
        listener = null
        preloading = false
        error = null
    }

    fun js(code: String) = view?.evaluateJavascript("if (window.player && player.getPlayerState) { $code }", null)

    @SuppressLint("SetJavaScriptEnabled")
    private fun load(context: Context, id: String) {
        videoId = id
        error = null
        view = WebView(context.applicationContext).apply {
            // The remote is handled by TrailerActivity, not the page.
            isFocusable = false
            isFocusableInTouchMode = false
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Events(), "Android")
            // YouTube refuses embeds with no referrer, so the page gets our server's address.
            loadDataWithBaseURL("$ORIGIN/", playerPage(id), "text/html", "utf-8", null)
        }
    }

    private class Events {
        @JavascriptInterface
        fun onReady() {
            // Preloading: buffer the opening seconds silently. Otherwise play straight away.
            main.post { js(if (preloading) "player.mute(); player.playVideo();" else "player.playVideo();") }
        }

        @JavascriptInterface
        fun onState(value: Int) {
            main.post {
                if (preloading && value == 1) js("player.pauseVideo(); player.seekTo(0, true);")
                listener?.onState(value)
            }
        }

        @JavascriptInterface
        fun onError(code: Int) {
            main.post {
                error = code
                listener?.onError(code)
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
      playerVars: { controls: 0, disablekb: 1, fs: 0, rel: 0, iv_load_policy: 3, playsinline: 1, modestbranding: 1, origin: '$ORIGIN' },
      events: {
        onReady: function () { Android.onReady(); },
        onStateChange: function (e) { Android.onState(e.data); },
        onError: function (e) { Android.onError(e.data); }
      }
    });
  }
</script>
<script src="https://www.youtube.com/iframe_api"></script>
</body></html>
""".trimIndent()
}
