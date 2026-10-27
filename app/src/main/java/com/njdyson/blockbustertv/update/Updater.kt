package com.njdyson.blockbustertv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.njdyson.blockbustertv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Self-update from the "apk-latest" GitHub release that .github/workflows/build-apk.yml publishes. */
object Updater {
    private const val RELEASE = "https://github.com/njdyson/BlockbusterTV/releases/latest/download"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class RemoteVersion(val versionCode: Int, val sha: String)

    val currentVersion: Int get() = BuildConfig.VERSION_CODE
    val currentSha: String get() = BuildConfig.GIT_SHA

    suspend fun latest(): RemoteVersion = withContext(Dispatchers.IO) {
        json.decodeFromString<RemoteVersion>(open("$RELEASE/version.json").inputStream.bufferedReader().use { it.readText() })
    }

    /** Downloads the latest APK into the cache, reporting progress from 0 to 1. */
    suspend fun download(context: Context, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "BlockbusterTV.apk")
        val conn = open("$RELEASE/BlockbusterTV.apk")
        val total = conn.contentLength.toFloat()
        conn.inputStream.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    if (total > 0) onProgress(done / total)
                }
            }
        }
        file
    }

    /**
     * Opens the system installer for the downloaded APK. On Android 8+ the user must first
     * allow this app to install apps; if not, this opens that setting and returns false.
     */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    // GitHub redirects release downloads to its CDN; HttpURLConnection follows https -> https redirects.
    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Cache-Control", "no-cache")
            if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode for $url")
        }
}
