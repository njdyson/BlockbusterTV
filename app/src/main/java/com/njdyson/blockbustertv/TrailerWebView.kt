package com.njdyson.blockbustertv

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * The WebView holding YouTube's embedded player, made ahead of time so a trailer starts
 * without the wait for the WebView, YouTube's scripts and the first seconds of video.
 *
 * The film page calls [preload] once it knows the trailer: the player loads muted, plays
 * until it starts, then pauses at the start, which has YouTube buffer the opening seconds.
 * [TrailerActivity] then [take]s it and plays from there. A trailer that isn't watched costs
 * only those few seconds of video.
 *
 * Where the WebView allows it, [FRAME_SCRIPT] runs inside YouTube's own frame so it streams
 * H.264, which every Fire Stick decodes in hardware, rather than VP9 or AV1, which it may
 * decode in software and stutter on at 1080p.
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
            // Lay it out at screen size now: a preloaded player has no window yet, and with no
            // size YouTube picks a low quality, then switches up a few seconds into playing.
            val screen = context.resources.displayMetrics
            measure(
                View.MeasureSpec.makeMeasureSpec(screen.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(screen.heightPixels, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, screen.widthPixels, screen.heightPixels)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Events(), "Android")
            // YouTube's player is a cross-origin iframe our page can't reach, but a document-start
            // script can. Older WebViews without it just play as before.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, FRAME_SCRIPT, YOUTUBE_ORIGINS)
            }
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

    private val YOUTUBE_ORIGINS = setOf("https://www.youtube.com", "https://www.youtube-nocookie.com")

    /**
     * Runs at the start of YouTube's frame. YouTube picks VP9 or AV1 whenever the browser says
     * it can play them, and the embed has no codec or quality setting, so this says VP9, AV1
     * and VP8 aren't supported and YouTube falls back to H.264 (which tops out at 1080p, fine
     * for a 1080p TV). It also hides YouTube's overlays: title, logo, pause screen, end tiles.
     * The overlay style comes last and waits for the page: at document start there may be no
     * document element yet, and a throw there would skip the codec overrides.
     */
    private val FRAME_SCRIPT = """
(function () {
  var blocked = /av01|vp0?9|vp8/i;
  var MS = window.MediaSource;
  if (MS) {
    var supported = MS.isTypeSupported.bind(MS);
    MS.isTypeSupported = function (type) { return blocked.test(type) ? false : supported(type); };
  }
  var canPlay = HTMLMediaElement.prototype.canPlayType;
  HTMLMediaElement.prototype.canPlayType = function (type) { return blocked.test(type) ? '' : canPlay.call(this, type); };
  var caps = navigator.mediaCapabilities;
  if (caps && caps.decodingInfo) {
    var info = caps.decodingInfo.bind(caps);
    caps.decodingInfo = function (config) {
      var type = config && config.video && config.video.contentType;
      if (type && blocked.test(type)) return Promise.resolve({ supported: false, smooth: false, powerEfficient: false });
      return info(config);
    };
  }

  var hide = document.createElement('style');
  hide.textContent = '.html5-video-player > :not(.html5-video-container):not(.video-ads):not(.ytp-ad-module),' +
    ' .ytp-ce-element, .ytp-cards-teaser, .ytp-endscreen-content, .ytp-pause-overlay, .ytp-watermark, .iv-branding' +
    ' { display: none !important; }';
  if (document.documentElement) document.documentElement.appendChild(hide);
  else document.addEventListener('DOMContentLoaded', function () { document.head.appendChild(hide); });
})();
""".trimIndent()

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
