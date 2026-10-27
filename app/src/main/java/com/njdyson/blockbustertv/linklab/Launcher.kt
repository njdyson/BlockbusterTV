package com.njdyson.blockbustertv.linklab

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/** Builds the intent for a link: `intent://` URIs are parsed as-is, anything else becomes ACTION_VIEW. */
fun buildIntent(link: LinkSpec): Intent {
    val intent = if (link.uri.startsWith("intent:")) {
        Intent.parseUri(link.uri, Intent.URI_INTENT_SCHEME)
    } else {
        Intent(Intent.ACTION_VIEW, Uri.parse(link.uri))
    }
    link.`package`?.let { intent.setPackage(it) }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return intent
}

/** Package of the activity that would handle the link, "android" for a chooser, or null for none. */
fun resolveHandler(context: Context, link: LinkSpec): String? = try {
    context.packageManager
        .resolveActivity(buildIntent(link), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName
} catch (e: Exception) {
    null
}

/** Fires the link. Returns null on success or an error message. */
fun launchLink(context: Context, link: LinkSpec): String? = try {
    context.startActivity(buildIntent(link))
    null
} catch (e: ActivityNotFoundException) {
    "No app handles this link"
} catch (e: Exception) {
    "${e.javaClass.simpleName}: ${e.message}"
}

fun installedPackage(context: Context, candidates: List<String>): String? =
    candidates.firstOrNull { appLaunchIntent(context, it) != null }

/** The app's TV launch intent (leanback first, then phone launcher), or null if not installed. */
fun appLaunchIntent(context: Context, pkg: String): Intent? {
    val pm = context.packageManager
    return pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
}

fun launchAppHome(context: Context, pkg: String): String? {
    val intent = appLaunchIntent(context, pkg) ?: return "$pkg is not installed"
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: Exception) {
        "${e.javaClass.simpleName}: ${e.message}"
    }
}

private val STREAMING_HINTS = listOf(
    "netflix", "amazon", "avod", "disney", "apple", "bbc", "iplayer", "itv", "channel4",
    "nowtv", "now", "sky", "plex", "youtube", "rakuten", "britbox", "mubi",
)

/** Installed launchable apps whose package name looks like a streaming service. */
fun installedStreamingApps(context: Context): List<String> {
    val pm = context.packageManager
    val launchable = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMap { category ->
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
            .map { it.activityInfo.packageName }
    }
    return launchable.distinct()
        .filter { pkg -> STREAMING_HINTS.any { pkg.contains(it, ignoreCase = true) } }
        .sorted()
}

data class DeviceInfo(val model: String, val android: String, val firmware: String) {
    override fun toString() = "$model · $android · $firmware"
}

fun deviceInfo(): DeviceInfo = DeviceInfo(
    model = "${Build.MANUFACTURER} ${Build.MODEL}",
    android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    // Fire OS reports its own version here, e.g. "Fire OS 6.2.9.5 (PS7295/4012)".
    firmware = systemProperty("ro.build.version.name").ifBlank { Build.DISPLAY },
)

private fun systemProperty(name: String): String = try {
    Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java)
        .invoke(null, name) as String
} catch (e: Exception) {
    ""
}
