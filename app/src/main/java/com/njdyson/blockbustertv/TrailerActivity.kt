package com.njdyson.blockbustertv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Plays a YouTube trailer full screen inside the app, using the embedded player in
 * [TrailerWebView] (preloaded by the film page when it can be). It's a plain activity with
 * the WebView as its content, not a Compose screen: inside Compose, Fire TV played the
 * sound but showed no picture.
 *
 * The remote is handled here rather than by the page: OK pauses, left and right skip 10
 * seconds, Back closes. If YouTube won't play it embedded (some trailers block embedding)
 * or it never starts, it finishes with [RESULT_FALLBACK] so the caller can open the YouTube app.
 */
class TrailerActivity : Activity(), TrailerWebView.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var hintBar: View
    private lateinit var hint: TextView

    // -1 until the player loads; then YouTube's player states (1 playing, 2 paused, 3 buffering, 0 ended).
    private var state = -1

    private val hideHint = Runnable { if (state == 1) hintBar.visibility = View.GONE }

    // A trailer that hasn't started within 20 seconds isn't going to: try the YouTube app.
    private val giveUp = Runnable { if (state == -1 || state == 3) fallBack() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: return finish()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        val webView = TrailerWebView.take(this, videoId, this)
        // A preloaded player is paused (and muted) at the start: play it. A new one plays once it loads.
        js("player.unMute(); player.playVideo();")

        hint = TextView(this).apply {
            setTextColor(Color.argb(204, 255, 255, 255))
            textSize = 15f
        }
        hintBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.argb(217, 0, 0, 0)))
            val dp = resources.displayMetrics.density
            setPadding((48 * dp).toInt(), (28 * dp).toInt(), (48 * dp).toInt(), (28 * dp).toInt())
            addView(TextView(context).apply {
                text = title
                setTextColor(Color.rgb(0xFF, 0xCC, 0x00))
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(hint)
        }

        // The WebView is opaque black already, so no window or layout background under it to draw every frame.
        window.setBackgroundDrawable(null)
        setContentView(FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(hintBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })
        showHint()
        main.postDelayed(giveUp, 20_000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                    js("if (player.getPlayerState() == 1) player.pauseVideo(); else player.playVideo();")
                KeyEvent.KEYCODE_MEDIA_PLAY -> js("player.playVideo();")
                KeyEvent.KEYCODE_MEDIA_PAUSE -> js("player.pauseVideo();")
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> js("player.seekTo(Math.max(0, player.getCurrentTime() - 10), true);")
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> js("player.seekTo(player.getCurrentTime() + 10, true);")
                else -> return super.dispatchKeyEvent(event)
            }
            showHint()
        }
        return true
    }

    // Pause when the TV goes to the home screen or sleeps, so the trailer isn't heard in the background.
    override fun onStop() {
        super.onStop()
        js("player.pauseVideo();")
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        TrailerWebView.release()
        super.onDestroy()
    }

    private fun js(code: String) = TrailerWebView.js(code)

    private fun showHint() {
        hint.text = when (state) {
            -1, 3 -> "Loading trailer…"
            2 -> "Paused  ·  OK to play  ·  ◀ ▶ skip 10s  ·  BACK to close"
            else -> "OK to pause  ·  ◀ ▶ skip 10s  ·  BACK to close"
        }
        hintBar.visibility = View.VISIBLE
        main.removeCallbacks(hideHint)
        if (state == 1) main.postDelayed(hideHint, 4_000)
    }

    private fun fallBack() {
        setResult(RESULT_FALLBACK)
        finish()
    }

    override fun onState(state: Int) {
        this.state = state
        if (state == 0) finish() else showHint()
    }

    override fun onError(code: Int) = fallBack()

    companion object {
        const val RESULT_FALLBACK = RESULT_FIRST_USER
        private const val EXTRA_VIDEO_ID = "videoId"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, videoId: String, title: String): Intent =
            Intent(context, TrailerActivity::class.java)
                .putExtra(EXTRA_VIDEO_ID, videoId)
                .putExtra(EXTRA_TITLE, title)
    }
}
