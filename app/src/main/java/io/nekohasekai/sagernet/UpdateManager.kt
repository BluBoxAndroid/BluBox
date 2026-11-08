package io.nekohasekai.sagernet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * BluBox auto update: only uses the GitHub stable release channel (releases/latest),
 * downloads go directly to GitHub release assets, never through the proxy core.
 *
 * Note: regular apps cannot install silently; the system install confirmation must
 * be confirmed by the user, so this only downloads the APK and launches the system installer.
 */
object UpdateManager {

    private const val RELEASES_LATEST =
        "https://api.github.com/repos/BluBoxAndroid/BluBox/releases/latest"
    private const val AUTO_CHECK_INTERVAL = 24 * 60 * 60 * 1000L

    // Direct client: bypasses the proxy core to avoid the bootstrap problem of updating the proxy app through the proxy itself
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    data class ReleaseInfo(
        val versionName: String,
        val apkUrl: String,
        val fileName: String,
        val sha256: String? = null,
    )

    fun versionParts(s: String): List<Int>? {
        val m = Regex("""\d+(?:\.\d+)*""").find(s) ?: return null
        return m.value.split(".").map { it.toIntOrNull() ?: 0 }
    }

    fun isNewer(remote: String, local: String): Boolean {
        val r = versionParts(remote) ?: return remote != local
        val l = versionParts(local) ?: return remote != local
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService<ConnectivityManager>() ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isWifi(context: Context): Boolean {
        val cm = context.getSystemService<ConnectivityManager>() ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Query releases/latest; returns ReleaseInfo when a newer stable release exists (null when up to date). */
    fun fetchLatest(): ReleaseInfo? {
        return try {
            fetchLatestViaGitHub()
        } catch (e: Exception) {
            // When the GitHub API is rate-limited (403) or the network fails, fall back to the static version file (three-level fallback)
            Logs.w("GitHub API 更新检查失败，降级静态源：${e.message}")
            try {
                fetchLatestViaStatic()
            } catch (e2: Exception) {
                Logs.w(e2)
                null
            }
        }
    }

    private fun fetchLatestViaGitHub(): ReleaseInfo? {
        val request = Request.Builder()
            .url(RELEASES_LATEST)
            .header("User-Agent", "BluBox")
            .header("Accept", "application/vnd.github+json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("GitHub API: HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val release = JSONObject(body)
            // Compare versions by tag_name (strip the leading v); do not trust the release title when it differs from the tag
            val tag = release.optString("tag_name").trim().removePrefix("v")
            if (tag.isEmpty() || !isNewer(tag, BuildConfig.VERSION_NAME)) return null
            val releaseBody = release.optString("body").orEmpty()
            val assets = release.optJSONArray("assets") ?: return null
            var fallback: ReleaseInfo? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (!assetName.endsWith(".apk") || url.isEmpty()) continue
                val info = ReleaseInfo(tag, url, assetName, findSha256(releaseBody, assetName))
                if (fallback == null) fallback = info
                // Prefer the asset matching this device's ABI
                for (abi in Build.SUPPORTED_ABIS) {
                    if (assetName.contains(abi)) return info
                }
            }
            return fallback
        }
    }

    /** Find the SHA256 for a given file name in the release notes (the release template always lists file name + SHA256). */
    private fun findSha256(releaseBody: String, fileName: String): String? {
        val shaRe = Regex("[0-9a-fA-F]{64}")
        val lines = releaseBody.lines()
        for ((idx, line) in lines.withIndex()) {
            if (line.contains(fileName)) {
                for (j in idx..minOf(idx + 2, lines.lastIndex)) {
                    shaRe.find(lines[j])?.let { return it.value.lowercase() }
                }
            }
        }
        return null
    }

    private const val JSDELIVR_PROPS =
        "https://cdn.jsdelivr.net/gh/BluBoxAndroid/BluBox@main/nb4a.properties"
    private const val FASTLY_PROPS =
        "https://fastly.jsdelivr.net/gh/BluBoxAndroid/BluBox@main/nb4a.properties"
    private const val RAW_PROPS =
        "https://raw.githubusercontent.com/BluBoxAndroid/BluBox/main/nb4a.properties"

    /**
     * Static version file fallback: try jsDelivr, then fastly, then raw.githubusercontent,
     * fetch the repo's nb4a.properties to compare versions, and build the download URL as BluBox-<version>-<abi>.apk per the release naming rule.
     */
    private fun fetchLatestViaStatic(): ReleaseInfo? {
        val urls = listOf(JSDELIVR_PROPS, FASTLY_PROPS, RAW_PROPS)
        for (url in urls) {
            try {
                fetchLatestViaPropsUrl(url)?.let { return it }
            } catch (e: Exception) {
                Logs.w("静态源更新检查失败 $url：${e.message}")
            }
        }
        return null
    }

    private fun fetchLatestViaPropsUrl(propsUrl: String): ReleaseInfo? {
        val request = Request.Builder()
            .url(propsUrl)
            .header("User-Agent", "BluBox")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val props = response.body?.string().orEmpty()
            val version = props.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("VERSION_NAME=") }
                ?.substringAfter("=")
                ?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return null
            if (!isNewer(version, BuildConfig.VERSION_NAME)) return null
            val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
                ?: Build.SUPPORTED_ABIS.firstOrNull() ?: return null
            val fileName = "BluBox-$version-$abi.apk"
            val url = "https://github.com/BluBoxAndroid/BluBox/releases/download/v$version/$fileName"
            return ReleaseInfo(version, url, fileName)
        }
    }

    /**
     * Download the APK into the app-private directory: verify content-length and the SHA256 from the release notes;
     * delete the file and throw if it is corrupt or truncated. Returns the file on success.
     */
    fun downloadApk(context: Context, release: ReleaseInfo): File? {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        // Clear leftover downloads from previous runs (including renamed .prompted files)
        dir.listFiles()?.forEach { f ->
            if (f.isFile && (f.name.endsWith(".apk") || f.name.endsWith(".prompted"))) f.delete()
        }
        val out = File(dir, release.fileName)
        val request = Request.Builder()
            .url(release.apkUrl)
            .header("User-Agent", "BluBox")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("下载失败：HTTP ${response.code}")
            }
            val expectedLength = response.header("Content-Length")?.toLongOrNull()
            val body = response.body ?: throw IllegalStateException("下载内容为空")
            out.outputStream().use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output)
                }
            }
            val actualLength = out.length()
            if (actualLength <= 0 ||
                (expectedLength != null && expectedLength > 0 && actualLength != expectedLength)
            ) {
                out.delete()
                throw IllegalStateException("下载不完整（$actualLength/${expectedLength ?: "?"})")
            }
            val expectedSha = release.sha256
            if (!expectedSha.isNullOrBlank()) {
                val digest = MessageDigest.getInstance("SHA-256")
                out.inputStream().use { input ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) digest.update(buf, 0, n)
                }
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                    out.delete()
                    throw IllegalStateException("SHA256 校验失败")
                }
            }
        }
        return out
    }

    /**
     * Launch the system installer. Android 8+ requires the "install unknown apps" permission;
     * if it was never granted, jump to the system settings page so the user can grant it once.
     */
    fun promptInstall(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                activity, R.string.update_need_unknown_sources, Toast.LENGTH_LONG
            ).show()
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return
        }
        val uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.cache", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    /**
     * Called from MainActivity.onResume: after the user returns from system settings
     * with the "unknown sources" permission granted, relaunch the installer once if an APK is pending.
     */
    fun checkPendingInstall(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) return
        val dir = File(activity.cacheDir, "update")
        if (!dir.isDirectory) return
        val apk = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") && f.length() > 0 }
            ?.maxByOrNull { it.lastModified() } ?: return
        promptInstall(activity, apk)
        // Relaunch only once: rename the file so later onResume calls do not prompt again
        apk.renameTo(File(dir, apk.name + ".prompted"))
    }

    /**
     * Called at app startup: check at most once per day, and only when online;
     * when a new version is found and WiFi is connected, download it automatically and launch the system installer.
     */
    fun maybeAutoCheck(activity: Activity) {
        if (!DataStore.autoCheckUpdate) return
        val now = System.currentTimeMillis()
        if (now - DataStore.autoUpdateLastCheck < AUTO_CHECK_INTERVAL) return
        if (!isOnline(activity)) return
        runOnIoDispatcher {
            try {
                val release = fetchLatest()
                DataStore.autoUpdateLastCheck = now
                if (release == null) return@runOnIoDispatcher
                // Auto-download on WiFi only
                if (!isWifi(activity)) return@runOnIoDispatcher
                val apk = downloadApk(activity.applicationContext, release)
                    ?: return@runOnIoDispatcher
                onMainDispatcher {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        promptInstall(activity, apk)
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }
}
