package io.nekohasekai.sagernet.fmt.openconnect

import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import io.nekohasekai.sagernet.ktx.wrapIPV6Host
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

// Link format (custom, self-consistent toUri/parseOpenConnect pair):
//   openconnect://user:pass@host:port?flavor=anyconnect&authgroup=x&insecure=1#name
// The sing-box "server" field is a single "host:port" string; the bean keeps
// serverAddress/serverPort split and buildSingBoxOutboundOpenConnectBean joins
// them. cookie is not carried by the link (edit screen / JSON import only).

fun parseOpenConnect(url: String): OpenConnectBean {
    val link = url.replace("openconnect://", "https://").toHttpUrlOrNull() ?: error(
        "invalid openconnect link $url"
    )
    return OpenConnectBean().apply {
        name = link.fragment ?: ""
        serverAddress = link.host
        serverPort = link.port
        username = link.username
        password = link.password
        link.queryParameter("flavor")?.let { flavor = it }
        (link.queryParameter("authgroup") ?: link.queryParameter("auth_group"))?.let {
            authGroup = it
        }
        link.queryParameter("insecure")?.let { insecure = it == "1" || it == "true" }
        (link.queryParameter("server_name") ?: link.queryParameter("serverName"))?.let {
            serverName = it
        }
        initializeDefaultValues()
    }
}

fun OpenConnectBean.toUri(): String {
    val builder = linkBuilder()
        .host(serverAddress)
        .port(serverPort)
    if (!username.isNullOrBlank()) builder.username(username)
    if (!password.isNullOrBlank()) builder.password(password)
    if (!flavor.isNullOrBlank()) builder.addQueryParameter("flavor", flavor)
    if (!authGroup.isNullOrBlank()) builder.addQueryParameter("authgroup", authGroup)
    if (insecure == true) builder.addQueryParameter("insecure", "1")
    if (!serverName.isNullOrBlank()) builder.addQueryParameter("server_name", serverName)
    if (!name.isNullOrBlank()) builder.encodedFragment(name.urlSafe())
    return builder.toLink("openconnect")
}

fun buildSingBoxOutboundOpenConnectBean(bean: OpenConnectBean): CustomSingBoxOption {
    val json = JSONObject().apply {
        put("type", "openconnect")
        put("server", "${bean.serverAddress.wrapIPV6Host()}:${bean.serverPort}")
        if (!bean.flavor.isNullOrBlank()) put("flavor", bean.flavor)
        if (!bean.username.isNullOrBlank()) put("username", bean.username)
        if (!bean.password.isNullOrBlank()) put("password", bean.password)
        if (!bean.authGroup.isNullOrBlank()) put("auth_group", bean.authGroup)
        if (!bean.cookie.isNullOrBlank()) put("cookie", bean.cookie)
        if (bean.insecure == true || !bean.serverName.isNullOrBlank()) {
            put("tls", JSONObject().apply {
                if (bean.insecure == true) put("insecure", true)
                if (!bean.serverName.isNullOrBlank()) put("server_name", bean.serverName)
            })
        }
    }
    return CustomSingBoxOption(json.toString())
}

fun parseOpenConnectJson(json: JSONObject): OpenConnectBean {
    return OpenConnectBean().apply {
        name = json.optString("name", json.optString("tag", ""))
        val server = json.optString("server", "")
        if (server.isNotBlank()) {
            if (server.startsWith("[")) {
                val end = server.indexOf(']')
                if (end > 0) {
                    serverAddress = server.substring(1, end)
                    serverPort = server.substring(end + 1).removePrefix(":").toIntOrNull()
                } else {
                    serverAddress = server
                }
            } else if (server.count { it == ':' } == 1) {
                serverAddress = server.substringBeforeLast(":")
                serverPort = server.substringAfterLast(":").toIntOrNull()
            } else {
                // bare host (or bare IPv6) without port
                serverAddress = server
            }
        }
        if (serverPort == null || serverPort == 0) {
            json.optInt("server_port", 0).takeIf { it > 0 }?.let { serverPort = it }
        }
        flavor = json.optString("flavor", "anyconnect")
        username = json.optString("username", "")
        password = json.optString("password", "")
        authGroup = json.optString("auth_group", "")
        cookie = json.optString("cookie", "")
        json.optJSONObject("tls")?.let { tls ->
            insecure = tls.optBoolean("insecure", false)
            serverName = tls.optString("server_name", "")
        }
        initializeDefaultValues()
    }
}
