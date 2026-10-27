package com.njdyson.blockbustertv.linklab

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.njdyson.blockbustertv.ui.Bad
import com.njdyson.blockbustertv.ui.Good
import com.njdyson.blockbustertv.ui.Partial

enum class Outcome(val code: String, val short: String, val label: String, val color: Color) {
    TITLE("TITLE", "T", "Opened the film", Good),
    APP_ONLY("APP", "A", "Opened the app, not the film", Partial),
    OTHER("OTHER", "O", "Opened something else", Partial),
    FAILED("FAIL", "F", "Nothing happened / error", Bad),
    NO_HANDLER("NOAPP", "N", "No app handles it", Bad),
}

data class LinkResult(val outcome: Outcome, val handler: String?)

/** Test outcomes, kept in SharedPreferences so they survive the app being killed while another app is open. */
class ResultStore(context: Context) {
    private val prefs = context.getSharedPreferences("link_lab_results", Context.MODE_PRIVATE)

    fun all(): Map<String, LinkResult> = prefs.all.mapNotNull { (id, value) ->
        val parts = (value as? String)?.split("|") ?: return@mapNotNull null
        val outcome = Outcome.entries.firstOrNull { it.code == parts[0] } ?: return@mapNotNull null
        id to LinkResult(outcome, parts.getOrNull(1)?.ifBlank { null })
    }.toMap()

    fun put(id: String, result: LinkResult) {
        prefs.edit().putString(id, "${result.outcome.code}|${result.handler.orEmpty()}").apply()
    }

    fun clear() = prefs.edit().clear().apply()

    // The link awaiting an answer is kept on disk too: old Fire Sticks often kill
    // this app while the streaming app is in front.
    var pending: Pending?
        get() = pendingPrefs.getString("pending", null)?.split("|", limit = 3)?.let {
            Pending(it[0], it.getOrElse(1) { "" }, it.getOrNull(2)?.ifBlank { null })
        }
        set(value) {
            pendingPrefs.edit().apply {
                if (value == null) remove("pending")
                else putString("pending", "${value.id}|${value.title}|${value.handler.orEmpty()}")
            }.apply()
        }

    private val pendingPrefs = context.getSharedPreferences("link_lab_pending", Context.MODE_PRIVATE)
}

/** A launched link waiting for the user to say what happened. */
data class Pending(val id: String, val title: String, val handler: String?)

/**
 * Compact report, one short line per service, so the QR code stays small enough to scan
 * off a TV screen. Codes: T film, A app only, O something else, F failed, N no app, - untested.
 */
fun buildReport(
    device: DeviceInfo,
    tests: List<ServiceTest>,
    results: Map<String, LinkResult>,
): String = buildString {
    appendLine("BBTV ${device.model} API${android.os.Build.VERSION.SDK_INT} ${device.firmware}")
    tests.forEach { test ->
        val codes = test.links.indices.map { i -> "${i + 1}${results[linkId(test, i)]?.outcome?.short ?: "-"}" }
        val home = results["${test.service}#home"]?.let { " H${it.outcome.short}" }.orEmpty()
        val handlers = test.links.indices.mapNotNull { results[linkId(test, it)]?.handler }.distinct()
        appendLine("${test.service} ${codes.joinToString(" ")}$home ${handlers.joinToString(",")}".trimEnd())
    }
}
