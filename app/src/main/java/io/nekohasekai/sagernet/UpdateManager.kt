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
 * BluBox 自动更新：只走 GitHub 正式版通道（releases/latest），
 * 下载走 GitHub release 附件直连，不经过代理内核。
 *
 * 注意：普通应用无法静默安装，系统安装确认页必须用户亲手点，
 * 这里只负责把安装包下好并调起系统安装器。
 */
object UpdateManager {

    private const val RELEASES_LATEST =
        "https://api.github.com/repos/BoxNest/BluBox/releases/latest"
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

    /** 查 releases/latest，有比本地新的正式版时返回 ReleaseInfo（无更新返回 null）。 */
    fun fetchLatest(): ReleaseInfo? {
        return try {
            fetchLatestViaGitHub()
        } catch (e: Exception) {
            // GitHub API 403 限流/网络异常时，降级走 jsDelivr 静态版本文件
            Logs.w("GitHub API 更新检查失败，降级 jsDelivr：${e.message}")
            try {
                fetchLatestViaJsDelivr()
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
            // 用 tag_name 比版本（去前导 v），标题与 tag 不一致时不乱
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
                // 按本机 ABI 优先挑包
                for (abi in Build.SUPPORTED_ABIS) {
                    if (assetName.contains(abi)) return info
                }
            }
            return fallback
        }
    }

    /** 从 Release 正文里找对应文件名的 SHA256（发版模板固定写文件名 + SHA256）。 */
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
        "https://cdn.jsdelivr.net/gh/BoxNest/BluBox@main/nb4a.properties"

    /**
     * jsDelivr 降级通道：拉仓库静态 nb4a.properties 比对版本，
     * 下载地址按发版规则 BluBox-<版本>-<abi>.apk 构造。
     */
    private fun fetchLatestViaJsDelivr(): ReleaseInfo? {
        val request = Request.Builder()
            .url(JSDELIVR_PROPS)
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
            val url = "https://github.com/BoxNest/BluBox/releases/download/v$version/$fileName"
            return ReleaseInfo(version, url, fileName)
        }
    }

    /**
     * 下载 APK 到应用私有目录：校验 content-length 与 Release 备注 SHA256，
     * 损坏/截断则删文件并抛异常。成功返回文件。
     */
    fun downloadApk(context: Context, release: ReleaseInfo): File? {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        // 清掉旧的下载残留（含补调改名后的 .prompted）
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
     * MainActivity.onResume 调用：用户去系统设置开了"未知来源"权限回来后，
     * 若有待安装的 APK 且已有权限，补调一次安装器。
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
        // 只补调一次：改名避免之后每次 onResume 重复弹窗
        apk.renameTo(File(dir, apk.name + ".prompted"))
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
