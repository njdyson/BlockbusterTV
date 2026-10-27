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

    fun trailer(videoId: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
        // https links work on Fire TV's YouTube app; vnd.youtube:// does not (Link Lab).
        listOf("com.amazon.firetv.youtube", "com.google.android.youtube.tv")
            .firstOrNull { appLaunchIntent(context, it) != null }
            ?.let { intent.setPackage(it) }
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

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
