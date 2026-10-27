package com.njdyson.blockbustertv.linklab

import android.content.Context
import android.content.res.Resources
import android.content.res.XmlResourceParser
import org.xmlpull.v1.XmlPullParser

/** One VIEW intent filter declared by an installed app: the link formats that activity accepts. */
data class LinkFilter(
    val component: String,
    val schemes: List<String>,
    val hosts: List<String>,
    val paths: List<String>,
) {
    override fun toString(): String {
        val host = hosts.joinToString("|").ifEmpty { "*" }
        val path = paths.joinToString("|")
        return "${schemes.joinToString("|")}://$host$path  [$component]"
    }
}

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/**
 * Reads another app's AndroidManifest.xml from its installed APK and lists every activity
 * that accepts ACTION_VIEW links, with the schemes, hosts and paths it declares. This is
 * how we find a service's deep-link format on Fire OS without a computer or ADB.
 */
fun deepLinkFilters(context: Context, pkg: String): List<LinkFilter> {
    val res = context.packageManager.getResourcesForApplication(pkg)
    val parser = openManifest(res, pkg)
    val filters = mutableListOf<LinkFilter>()
    var component = ""
    var inFilter = false
    var isView = false
    val schemes = mutableListOf<String>()
    val hosts = mutableListOf<String>()
    val paths = mutableListOf<String>()

    parser.use { p ->
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "activity", "activity-alias" ->
                        component = p.attr(res, "name").orEmpty().substringAfterLast('.')
                    "intent-filter" -> {
                        inFilter = true
                        isView = false
                        schemes.clear(); hosts.clear(); paths.clear()
                    }
                    "action" -> if (inFilter && p.attr(res, "name") == "android.intent.action.VIEW") isView = true
                    "data" -> if (inFilter) {
                        p.attr(res, "scheme")?.let { schemes += it }
                        p.attr(res, "host")?.let { hosts += it }
                        p.attr(res, "path")?.let { paths += it }
                        p.attr(res, "pathPrefix")?.let { paths += "$it*" }
                        p.attr(res, "pathPattern")?.let { paths += "~$it" }
                    }
                }
                XmlPullParser.END_TAG -> if (p.name == "intent-filter") {
                    if (isView && schemes.isNotEmpty()) {
                        filters += LinkFilter(component, schemes.distinct(), hosts.distinct(), paths.distinct())
                    }
                    inFilter = false
                }
            }
        }
    }
    return filters.distinct()
}

/**
 * The app's resources also include the system framework, which has its own manifest, so
 * try each loaded APK ("cookie") until we get the one whose package matches.
 */
@Suppress("DEPRECATION")
private fun openManifest(res: Resources, pkg: String): XmlResourceParser {
    for (cookie in 0..8) {
        val parser = runCatching { res.assets.openXmlResourceParser(cookie, "AndroidManifest.xml") }.getOrNull() ?: continue
        while (parser.next() != XmlPullParser.START_TAG && parser.eventType != XmlPullParser.END_DOCUMENT) Unit
        if (parser.name == "manifest" && parser.getAttributeValue(null, "package") == pkg) return parser
        parser.close()
    }
    error("Could not find $pkg's manifest")
}

/** Attribute value, following @string references, since compiled manifests often use them. */
private fun XmlResourceParser.attr(res: Resources, name: String): String? {
    val ref = getAttributeResourceValue(ANDROID_NS, name, 0)
    if (ref != 0) return runCatching { res.getString(ref) }.getOrNull()
    return getAttributeValue(ANDROID_NS, name)
}

/** Friendly names for handlers that are not the streaming app itself. */
fun handlerName(pkg: String?): String = when (pkg) {
    null -> "no handler found"
    "com.amazon.cloud9" -> "Silk browser (app ignores this link)"
    "android" -> "app chooser (several apps match)"
    else -> pkg
}
