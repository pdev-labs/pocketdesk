package dev.pocketdesk.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import dev.pocketdesk.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A newer build published on GitHub. */
data class UpdateInfo(
    val version: String,
    val versionCode: Int,
    val apkUrl: String,
    val size: Long,
    val sha256: String,
)

sealed interface CheckResult {
    data class Available(val info: UpdateInfo) : CheckResult
    data object UpToDate : CheckResult
    data class Failed(val message: String) : CheckResult
}

/**
 * Self-updates from the project's GitHub releases.
 *
 * `latest.json` (published with every release) is the contract:
 *
 * ```json
 * {"version":"0.4.0","versionCode":42,"apk":"pocketdesk.apk",
 *  "size":25817643,"sha256":"…"}
 * ```
 *
 * Two independent checks run before the installer is opened:
 *
 * 1. **SHA-256** of the downloaded file against `latest.json`.
 * 2. **Signing certificate** against the installed app. Android enforces this
 *    at install time anyway; failing early turns "App not installed" into a
 *    sentence that says why.
 */
class Updater(private val context: Context) {

    private val base = "https://github.com/pdev-labs/pocketdesk/releases/latest/download"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pocketdesk-update").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    private val userAgent = "PocketDesk/${BuildConfig.VERSION_NAME}"

    fun check(onResult: (CheckResult) -> Unit) {
        worker.execute {
            val result = try {
                fetch()
            } catch (t: Throwable) {
                CheckResult.Failed(t.message ?: "network error")
            }
            main.post { onResult(result) }
        }
    }

    private fun fetch(): CheckResult {
        // The timestamp keeps a CDN from serving yesterday's manifest.
        val url = "$base/latest.json?t=${System.currentTimeMillis()}"
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return CheckResult.Failed("GitHub returned HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            val versionCode = json.optInt("versionCode", 0)
            val version = json.optString("version")
            val apk = json.optString("apk", "pocketdesk.apk")

            if (versionCode <= BuildConfig.VERSION_CODE) {
                return CheckResult.UpToDate
            }

            return CheckResult.Available(
                UpdateInfo(
                    version = version.ifBlank { "unknown" },
                    versionCode = versionCode,
                    apkUrl = "$base/$apk",
                    size = json.optLong("size", 0L),
                    sha256 = json.optString("sha256"),
                ),
            )
        }
    }

    /** Downloads, verifies and returns the APK, or `null` plus a reason. */
    fun download(
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
        onDone: (File?, String?) -> Unit,
    ) {
        worker.execute {
            val (file, error) = try {
                downloadBlocking(info, onProgress)
            } catch (t: Throwable) {
                null to (t.message ?: "download failed")
            }
            main.post { onDone(file, error) }
        }
    }

    private fun downloadBlocking(
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
    ): Pair<File?, String?> {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // One update at a time: drop anything except the file we want.
        val target = File(dir, "pocketdesk-${info.version}.apk")
        dir.listFiles()?.forEach { if (it != target) it.delete() }

        val request = Request.Builder().url(info.apkUrl).header("User-Agent", userAgent).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return null to "GitHub returned HTTP ${response.code}"
            }
            val body = response.body ?: return null to "empty response"
            val total = body.contentLength().takeIf { it > 0 } ?: info.size

            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = (written * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                main.post { onProgress(percent) }
                            }
                        }
                    }
                }
            }
        }

        if (target.length() == 0L) {
            target.delete()
            return null to "the download was empty"
        }

        if (info.sha256.isNotBlank()) {
            val actual = sha256(target)
            if (!actual.equals(info.sha256, ignoreCase = true)) {
                target.delete()
                return null to "checksum mismatch (file is corrupt or not ours)"
            }
        }

        if (!sameSignature(target)) {
            target.delete()
            return null to "signed with a different key — uninstall the old app once, then update"
        }

        return target to null
    }

    /** Opens the system installer for a verified APK. */
    fun install(file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.updates",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** True when Android will let this app hand an APK to the installer. */
    fun canInstall(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun unknownSourcesSettings(): Intent {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    fun releasesPage(): Uri = Uri.parse("https://github.com/pdev-labs/pocketdesk/releases/latest")

    @Suppress("DEPRECATION")
    private fun sameSignature(apk: File): Boolean {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            ?: return false
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)

        val remote = archive.signatures?.firstOrNull()?.toByteArray() ?: return false
        val local = installed.signatures?.firstOrNull()?.toByteArray() ?: return false
        return MessageDigest.isEqual(remote, local)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
