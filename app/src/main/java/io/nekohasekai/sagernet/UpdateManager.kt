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
import java.util.concurrent.TimeUnit

/**
 * BluBox 自动更新：只走 GitHub 正式版通道（releases/latest），
 * 下载走 GitHub release 附件直连，不经过代理内核。
 *
 * 注意：普通应用无法静默安装，系统安装确认页必须用户亲手点，
 * 这里只负责把安装包下好并调起系统安装器。
 */
object UpdateManager {

    private const val RELEASES_LATEST =
        "https://api.github.com/repos/BluBoxAndroid/BluBox/releases/latest"
    private const val AUTO_CHECK_INTERVAL = 24 * 60 * 60 * 1000L

    // 直连客户端：不走代理内核，避免"更新代理 App 本体却依赖代理"的自举问题
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

    /** 查 releases/latest，有比本地新的正式版时返回 ReleaseInfo（无更新返回 null）。 */
    fun fetchLatest(): ReleaseInfo? {
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
            val name = release.optString("name").trim()
            if (name.isEmpty() || !isNewer(name, BuildConfig.VERSION_NAME)) return null
            val assets = release.optJSONArray("assets") ?: return null
            var fallback: ReleaseInfo? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (!assetName.endsWith(".apk") || url.isEmpty()) continue
                val info = ReleaseInfo(name, url, assetName)
                if (fallback == null) fallback = info
                // 按本机 ABI 优先挑包
                for (abi in Build.SUPPORTED_ABIS) {
                    if (assetName.contains(abi)) return info
                }
            }
            return fallback
        }
    }

    /** 下载 APK 到应用私有目录，成功返回文件，失败返回 null。 */
    fun downloadApk(context: Context, release: ReleaseInfo): File? {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val out = File(dir, release.fileName)
        val request = Request.Builder()
            .url(release.apkUrl)
            .header("User-Agent", "BluBox")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("下载失败：HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("下载内容为空")
            out.outputStream().use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output)
                }
            }
        }
        return if (out.length() > 0) out else null
    }

    /**
     * 调起系统安装器。Android 8+ 需要"允许安装未知应用"，
     * 没开过就先跳系统设置页让用户开一次。
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
     * App 启动时调用：每天最多检查一次，有网才查；
     * 发现新版且当前是 WiFi 时自动下载，下载完调起系统安装器。
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
                // 仅 WiFi 下自动下载
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
