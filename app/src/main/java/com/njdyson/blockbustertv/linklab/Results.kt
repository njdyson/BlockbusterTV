package com.njdyson.blockbustertv.linklab

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.njdyson.blockbustertv.ui.Bad
import com.njdyson.blockbustertv.ui.Good
import com.njdyson.blockbustertv.ui.Partial

enum class Outcome(val code: String, val label: String, val color: Color) {
    TITLE("TITLE", "Opened the film", Good),
    APP_ONLY("APP", "Opened the app, not the film", Partial),
    OTHER("OTHER", "Opened something else", Partial),
    FAILED("FAIL", "Nothing happened / error", Bad),
    NO_HANDLER("NOAPP", "No app handles it", Bad),
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

/** Compact plain-text report, small enough for a QR code, to paste back into the chat. */
fun buildReport(
    device: DeviceInfo,
    apps: List<String>,
    tests: List<ServiceTest>,
    results: Map<String, LinkResult>,
): String = buildString {
    appendLine("BlockbusterTV Link Lab report")
    appendLine("device: $device")
    appendLine("apps: ${apps.joinToString(",")}")
    tests.forEach { test ->
        test.links.forEachIndexed { i, link ->
            val id = linkId(test, i)
            val r = results[id]
            appendLine("$id ${r?.outcome?.code ?: "-"} ${r?.handler ?: ""} | ${link.label}".trimEnd())
        }
        results["${test.service}#home"]?.let { appendLine("${test.service}#home ${it.outcome.code} ${it.handler.orEmpty()}") }
    }
}
