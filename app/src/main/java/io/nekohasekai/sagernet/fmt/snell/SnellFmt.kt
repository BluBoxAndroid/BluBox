package io.nekohasekai.sagernet.fmt.snell

import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

// Link format (custom, self-consistent toUri/parseSnell pair):
//   snell://psk@host:port?version=4&obfs=tls&obfs-host=x&reuse=1#name
//   version=6 uses mode instead of obfs: ?version=6&mode=default
// Optional query keys: userkey, network (tcp,udp), obfs_mode alias of obfs.

fun parseSnell(url: String): SnellBean {
    val link = url.replace("snell://", "https://").toHttpUrlOrNull() ?: error(
        "invalid snell link $url"
    )
    return SnellBean().apply {
        name = link.fragment ?: ""
        serverAddress = link.host
        serverPort = link.port
        psk = link.username
        if (link.password.isNotBlank() && userkey.isNullOrBlank()) {
            // tolerate snell://psk:userkey@host form
            userkey = link.password
        }
        link.queryParameter("version")?.toIntOrNull()?.let { version = it }
        link.queryParameter("userkey")?.let { userkey = it }
        link.queryParameter("reuse")?.let { reuse = it == "1" || it == "true" }
        link.queryParameter("network")?.let { network = it }
        (link.queryParameter("obfs") ?: link.queryParameter("obfs_mode"))?.let { obfsMode = it }
        (link.queryParameter("obfs-host") ?: link.queryParameter("obfs_host"))?.let {
            obfsHost = it
        }
        link.queryParameter("mode")?.let { mode = it }
        initializeDefaultValues()
    }
}

fun SnellBean.toUri(): String {
    val builder = linkBuilder()
        .username(psk ?: "")
        .host(serverAddress)
        .port(serverPort)
    if (!userkey.isNullOrBlank()) builder.addQueryParameter("userkey", userkey)
    builder.addQueryParameter("version", (version ?: 4).toString())
    if (reuse == true) builder.addQueryParameter("reuse", "1")
    if (!network.isNullOrBlank()) builder.addQueryParameter("network", network)
    if ((version ?: 4) == 6) {
        if (!mode.isNullOrBlank()) builder.addQueryParameter("mode", mode)
    } else {
        if (!obfsMode.isNullOrBlank()) builder.addQueryParameter("obfs", obfsMode)
        if (!obfsHost.isNullOrBlank()) builder.addQueryParameter("obfs-host", obfsHost)
    }
    if (!name.isNullOrBlank()) builder.encodedFragment(name.urlSafe())
    return builder.toLink("snell")
}

fun buildSingBoxOutboundSnellBean(bean: SnellBean): CustomSingBoxOption {
    val json = JSONObject().apply {
        put("type", "snell")
        put("server", bean.serverAddress)
        put("server_port", bean.serverPort)
        put("version", bean.version ?: 4)
        put("psk", bean.psk ?: "")
        if (!bean.userkey.isNullOrBlank()) put("userkey", bean.userkey)
        if (bean.reuse == true) put("reuse", true)
        if (!bean.network.isNullOrBlank()) {
            val nets = bean.network.listByLineOrComma()
            if (nets.size == 1) put("network", nets[0]) else put("network", JSONArray(nets))
        }
        if ((bean.version ?: 4) == 6) {
            if (!bean.mode.isNullOrBlank()) put("mode", bean.mode)
        } else {
            if (!bean.obfsMode.isNullOrBlank()) put("obfs_mode", bean.obfsMode)
            if (!bean.obfsHost.isNullOrBlank()) put("obfs_host", bean.obfsHost)
        }
    }
    return CustomSingBoxOption(json.toString())
}

fun parseSnellJson(json: JSONObject): SnellBean {
    return SnellBean().apply {
        name = json.optString("name", json.optString("tag", ""))
        serverAddress = json.optString("server", "")
        serverPort = json.optInt("server_port", 0).takeIf { it > 0 }
            ?: json.optInt("port", 0).takeIf { it > 0 }
        version = json.optInt("version", 4)
        psk = json.optString("psk", "")
        userkey = json.optString("userkey", "")
        reuse = json.optBoolean("reuse", false)
        network = when (val n = json.opt("network")) {
            is JSONArray -> (0 until n.length()).map { n.optString(it) }.joinToString(",")
            is String -> n
            else -> ""
        }
        obfsMode = json.optString("obfs_mode", "")
        obfsHost = json.optString("obfs_host", "")
        mode = json.optString("mode", "")
        initializeDefaultValues()
    }
}
