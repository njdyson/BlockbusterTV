package com.njdyson.blockbustertv.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import com.njdyson.blockbustertv.linklab.appLaunchIntent

/**
 * Opens a film in its streaming app. Uses JustWatch's Fire TV link for services where
 * Link Lab confirmed it lands on the film; otherwise, or if the link would fall through to
 * the browser, opens the app's home screen and shows the title to search for.
 */
class FilmLauncher(private val context: Context, private val services: Services) {

    fun watch(film: Film, offer: Offer) {
        val service = services.byId(offer.serviceId) ?: return
        val appIntent = appLaunchIntent(context, service.`package`)
        if (appIntent == null) {
            toast("${service.name} isn't installed on this device")
            return
        }
        val titleIntent = offer.fireTvLink?.takeIf { service.opensTitle }?.let { link ->
            runCatching { Intent.parseUri(link, Intent.URI_INTENT_SCHEME) }.getOrNull()
        }?.apply {
            // Aim plain links at the service's app so Fire OS doesn't offer a chooser.
            if (`package` == null && component == null) setPackage(service.`package`)
        }
        if (titleIntent != null && opensInApp(titleIntent) && start(titleIntent)) return

        start(appIntent)
        toast("Opening ${service.name}: search for \"${film.title}\"")
    }

    /** A film bought on YouTube / Google TV: it's in the YouTube app's Library. */
    fun watchOwnedOnYouTube(film: Film) {
        val app = youTubePackage()?.let { appLaunchIntent(context, it) }
        if (app == null || !start(app)) {
            toast("YouTube isn't installed on this device")
            return
        }
        toast("Opening YouTube: find \"${film.title}\" in Library > Your movies")
    }

    /**
     * A film on the user's own Plex server. Opens the film's page in the Plex app (Link Lab
     * confirmed those links land on the film), which lists the server's copy as a way to watch.
     * Without a page link, opens Plex's home screen with the title to search for.
     */
    fun watchOnPlex(film: Film, pageUrl: String?) {
        val app = appLaunchIntent(context, Plex.PACKAGE)
        if (app == null) {
            toast("Plex isn't installed on this device")
            return
        }
        val page = pageUrl?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(Plex.PACKAGE) }
        if (page != null && opensInApp(page) && start(page)) {
            toast("Opening Plex: if it offers more than one way to watch, pick your own server")
            return
        }
        start(app)
        toast("Opening Plex: search for \"${film.title}\" in your library")
    }

    fun openYouTube() {
        youTubePackage()?.let { appLaunchIntent(context, it) }?.let { start(it) } ?: toast("YouTube isn't installed on this device")
    }

    private fun youTubePackage(): String? =
        YOUTUBE_PACKAGES.firstOrNull { appLaunchIntent(context, it) != null }

    fun trailer(videoId: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
        // https links work on Fire TV's YouTube app; vnd.youtube:// does not (Link Lab).
        youTubePackage()?.let { intent.setPackage(it) }
        if (!start(intent)) toast("Couldn't open YouTube")
    }

    /** False when nothing handles the link, or it would open the Silk browser. */
    private fun opensInApp(intent: Intent): Boolean {
        val handler = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return handler != null && handler != "com.amazon.cloud9"
    }

    private fun start(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    companion object {
        private val YOUTUBE_PACKAGES = listOf("com.amazon.firetv.youtube", "com.google.android.youtube.tv")

        /** Search link for the phone: films for sale show at the top of YouTube's results. */
        fun youTubeSearchUrl(film: Film): String =
            "https://www.youtube.com/results?search_query=" +
                Uri.encode(listOfNotNull(film.title, film.year?.toString()).joinToString(" "))

        const val YOUTUBE_ICON = "https://images.justwatch.com/icon/113378150/s100/youtubefree.png"
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
