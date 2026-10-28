package com.njdyson.blockbustertv.linklab

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.njdyson.blockbustertv.ui.NightNavy
import com.njdyson.blockbustertv.update.Updater
import com.njdyson.blockbustertv.ui.ShelfGrey
import com.njdyson.blockbustertv.ui.TicketYellow

/**
 * Milestone 1 harness: one row per streaming service with every candidate link for
 * a real film on it. Press a link, see what opens, come back, record the outcome.
 * The results screen shows a QR code of the report to send back.
 */
@Composable
fun LinkLabScreen(resumeCount: Int, extraTests: List<ServiceTest> = emptyList()) {
    val context = LocalContext.current
    // Extra tests (e.g. for the user's own Plex server) are built in the app, ahead of the fixed ones.
    val fixedTests = remember { loadLinkTests(context) }
    val tests = remember(extraTests) { extraTests + fixedTests }
    val store = remember { ResultStore(context) }
    val device = remember { deviceInfo() }
    var apps by remember { mutableStateOf(installedStreamingApps(context)) }
    var results by remember { mutableStateOf(store.all()) }
    var pending by remember { mutableStateOf(store.pending) }
    var awaitingReturn by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var focusedTest by remember { mutableStateOf<ServiceTest?>(null) }
    var inspecting by remember { mutableStateOf<String?>(null) }

    // Back from the other app: ask how it went.
    LaunchedEffect(resumeCount) {
        apps = installedStreamingApps(context)
        awaitingReturn = false
    }

    fun record(id: String, result: LinkResult) {
        store.put(id, result)
        results = store.all()
    }

    fun fire(id: String, title: String, handler: String?, launch: () -> String?) {
        val error = launch()
        if (error == null) {
            pending = Pending(id, title, handler).also { store.pending = it }
            awaitingReturn = true
            lastError = null
        } else {
            record(id, LinkResult(Outcome.NO_HANDLER, handler))
            lastError = "$title: $error"
        }
    }

    Box(Modifier.fillMaxSize().background(NightNavy)) {
        Crossfade(targetState = focusedTest?.backdrop, label = "backdrop") { url ->
            if (url != null) {
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = 0.35f,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.horizontalGradient(listOf(NightNavy, NightNavy.copy(alpha = 0.4f))),
                        ),
                    )
                }
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 27.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Header(device, apps, lastError, resumeCount, onReport = { showReport = true }, onClear = {
                    store.clear()
                    results = emptyMap()
                })
            }
            items(tests, key = { it.service }) { test ->
                ServiceRow(
                    modifier = Modifier.onFocusChanged { if (it.hasFocus) focusedTest = test },
                    test = test,
                    results = results,
                    installed = installedPackage(context, test.packages),
                    onLink = { i, link ->
                        val handler = resolveHandler(context, link)
                        fire(linkId(test, i), "${test.serviceName}: ${link.label}", handler) { launchLink(context, link) }
                    },
                    onInspect = { pkg -> inspecting = pkg },
                    onHome = { pkg ->
                        fire("${test.service}#home", "${test.serviceName}: app home", pkg) { launchAppHome(context, pkg) }
                    },
                )
            }
        }

        val p = pending
        if (p != null && !awaitingReturn) {
            OutcomeDialog(p, tests.firstOrNull { p.id.startsWith("${it.service}#") }, onPick = { outcome ->
                record(p.id, LinkResult(outcome, p.handler))
                pending = null
                store.pending = null
            }, onDismiss = {
                pending = null
                store.pending = null
            })
        }
        inspecting?.let { pkg -> InspectDialog(pkg, onDismiss = { inspecting = null }) }
        if (showReport) {
            ReportDialog(buildReport(device, tests, results), tests, results, onDismiss = { showReport = false })
        }
    }
}

@Composable
private fun Header(
    device: DeviceInfo,
    apps: List<String>,
    lastError: String?,
    resumeCount: Int,
    onReport: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "LINK LAB",
                color = TicketYellow,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.width(24.dp))
            Button(onClick = onReport) { Text("Show results QR") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onClear) { Text("Clear results") }
        }
        Text(
            "$device · build ${Updater.currentVersion} (${Updater.currentSha})",
            color = Color.White,
            fontSize = 14.sp,
        )
        Text(
            "Streaming apps found: " + apps.ifEmpty { listOf("none") }.joinToString(", "),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
        )
        Text(
            "Press a link. When the other app opens, check whether it landed on the film, then press Back or Home and reopen this app to record it.",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
        )
        lastError?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp) }
    }
}

@Composable
private fun ServiceRow(
    modifier: Modifier,
    test: ServiceTest,
    results: Map<String, LinkResult>,
    installed: String?,
    onLink: (Int, LinkSpec) -> Unit,
    onHome: (String) -> Unit,
    onInspect: (String) -> Unit,
) {
    val context = LocalContext.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Poster(test.poster, Modifier.width(100.dp).height(150.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(test.serviceName, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.width(12.dp))
                Text(test.film, fontSize = 16.sp, color = TicketYellow)
                Spacer(Modifier.width(12.dp))
                Text(
                    installed?.let { "installed: $it" } ?: "app not found",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                itemsIndexed(test.links) { i, link ->
                    val handler = remember(link) { resolveHandler(context, link) }
                    LinkCard(
                        title = "${i + 1}. ${link.label}",
                        detail = link.uri,
                        handler = "→ ${handlerName(handler)}",
                        result = results[linkId(test, i)],
                        onClick = { onLink(i, link) },
                    )
                }
                if (installed != null) {
                    item {
                        LinkCard(
                            title = "Fallback: open app home",
                            detail = installed,
                            handler = "→ $installed",
                            result = results["${test.service}#home"],
                            onClick = { onHome(installed) },
                        )
                    }
                    item {
                        LinkCard(
                            title = "Inspect: link formats",
                            detail = "Lists the deep links $installed says it accepts",
                            handler = "",
                            result = null,
                            onClick = { onInspect(installed) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkCard(title: String, detail: String, handler: String, result: LinkResult?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(280.dp).height(120.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = ShelfGrey,
            contentColor = Color.White,
            focusedContainerColor = TicketYellow,
            focusedContentColor = NightNavy,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(handler, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            if (result != null) {
                Text(
                    result.outcome.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .background(result.outcome.color, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun OutcomeDialog(pending: Pending, test: ServiceTest?, onPick: (Outcome) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier
                .width(640.dp)
                .background(ShelfGrey, RoundedCornerShape(12.dp))
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (test != null) Poster(test.poster, Modifier.width(160.dp).height(240.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Did it open this film?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TicketYellow)
                if (test != null) Text(test.film, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(pending.title, fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
                listOf(Outcome.TITLE, Outcome.APP_ONLY, Outcome.OTHER, Outcome.FAILED).forEachIndexed { i, outcome ->
                    Button(
                        onClick = { onPick(outcome) },
                        modifier = Modifier.fillMaxWidth().let { if (i == 0) it.focusRequester(first) else it },
                    ) { Text(outcome.label) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun ReportDialog(
    report: String,
    tests: List<ServiceTest>,
    results: Map<String, LinkResult>,
    onDismiss: () -> Unit,
) {
    val qr = remember(report) { qrCode(report) }
    val close = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier
                .padding(32.dp)
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    qr,
                    contentDescription = "Results QR code",
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(360.dp),
                )
                Text("Scan it, or just photograph this screen.", color = Color.Black, fontSize = 14.sp)
                Button(onClick = onDismiss, modifier = Modifier.focusRequester(close)) { Text("Close") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Results", color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                tests.forEach { test ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(test.serviceName, color = Color.Black, fontSize = 16.sp, modifier = Modifier.width(120.dp))
                        test.links.indices.forEach { i -> ResultChip("${i + 1}", results[linkId(test, i)]) }
                        ResultChip("Home", results["${test.service}#home"])
                    }
                }
                Text(
                    "Green: opened the film · Orange: app or other page · Red: failed · Grey: untested",
                    color = Color.DarkGray,
                    fontSize = 12.sp,
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
}

@Composable
private fun ResultChip(label: String, result: LinkResult?) {
    Text(
        label,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(result?.outcome?.color ?: Color.LightGray, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun Poster(url: String?, modifier: Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(ShelfGrey, RoundedCornerShape(6.dp)),
    )
}

@Composable
private fun InspectDialog(pkg: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lines = remember(pkg) {
        runCatching { deepLinkFilters(context, pkg).map { it.toString() } }
            .getOrElse { listOf("Could not read the manifest: ${it.message}") }
            .ifEmpty { listOf("No VIEW links declared") }
    }
    // Keep the QR scannable: the on-screen list is complete, the QR holds as much as fits.
    val report = remember(lines) {
        buildString {
            appendLine("BBTV inspect $pkg")
            for (line in lines) {
                if (length + line.length > 1800) { appendLine("…"); break }
                appendLine(line)
            }
        }
    }
    val qr = remember(report) { qrCode(report) }
    val close = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Row(
            Modifier
                .padding(32.dp)
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(qr, contentDescription = "Link formats QR code", filterQuality = FilterQuality.None, modifier = Modifier.size(300.dp))
                Button(onClick = onDismiss, modifier = Modifier.focusRequester(close)) { Text("Close") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(pkg, color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("${lines.size} link formats. Scroll with up/down.", color = Color.DarkGray, fontSize = 12.sp)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(lines) { line ->
                        var focused by remember { mutableStateOf(false) }
                        Text(
                            line,
                            color = Color.Black,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focused = it.isFocused }
                                .background(if (focused) TicketYellow else Color.Transparent)
                                .focusable()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { close.requestFocus() } }
}
