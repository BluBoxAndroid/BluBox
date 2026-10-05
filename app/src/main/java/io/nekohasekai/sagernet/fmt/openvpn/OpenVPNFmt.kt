package io.nekohasekai.sagernet.fmt.openvpn

import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

// Link format (custom, self-consistent toUri/parseOpenVPN pair):
//   openvpn://user:pass@host:port?network=udp&cipher=x#name
// Optional query keys: data_ciphers (comma separated), auth, servers is NOT
// carried by the link. Certificate material (tls.certificate /
// client_certificate / client_key) is large text and is only carried by the
// edit screen and by sing-box JSON import (parseOpenVPNJson), never by link.

fun parseOpenVPN(url: String): OpenVPNBean {
    val link = url.replace("openvpn://", "https://").toHttpUrlOrNull() ?: error(
        "invalid openvpn link $url"
    )
    return OpenVPNBean().apply {
        name = link.fragment ?: ""
        serverAddress = link.host
        serverPort = link.port
        username = link.username
        password = link.password
        link.queryParameter("network")?.let { network = it }
        link.queryParameter("cipher")?.let { cipher = it }
        (link.queryParameter("data_ciphers") ?: link.queryParameter("data-ciphers"))?.let {
            dataCiphers = it
        }
        link.queryParameter("auth")?.let { auth = it }
        initializeDefaultValues()
    }
}

fun OpenVPNBean.toUri(): String {
    val builder = linkBuilder()
        .host(serverAddress)
        .port(serverPort)
    if (!username.isNullOrBlank()) builder.username(username)
    if (!password.isNullOrBlank()) builder.password(password)
    if (!network.isNullOrBlank()) builder.addQueryParameter("network", network)
    if (!cipher.isNullOrBlank()) builder.addQueryParameter("cipher", cipher)
    if (!dataCiphers.isNullOrBlank()) builder.addQueryParameter("data_ciphers", dataCiphers)
    if (!auth.isNullOrBlank()) builder.addQueryParameter("auth", auth)
    if (!name.isNullOrBlank()) builder.encodedFragment(name.urlSafe())
    return builder.toLink("openvpn")
}

fun buildSingBoxOutboundOpenVPNBean(bean: OpenVPNBean): CustomSingBoxOption {
    val json = JSONObject().apply {
        put("type", "openvpn-client")
        // Top-level server/server_port and servers[] are mutually exclusive in
        // sing-box 1.14.2; always generate only server/server_port.
        put("server", bean.serverAddress)
        put("server_port", bean.serverPort)
        if (!bean.username.isNullOrBlank()) put("username", bean.username)
        if (!bean.password.isNullOrBlank()) put("password", bean.password)
        if (!bean.network.isNullOrBlank()) put("network", bean.network)
        // sing-box 1.14.2 hard-rejects top-level "cipher" in TLS mode
        // ("`cipher` is only supported in `static_key` mode; use
        // data_ciphers"). Emit the user cipher as a single-element
        // data_ciphers array instead; merge with any explicit data_ciphers.
        val dataCipherList = mutableListOf<String>()
        if (!bean.dataCiphers.isNullOrBlank()) {
            dataCipherList.addAll(bean.dataCiphers.listByLineOrComma())
        }
        if (!bean.cipher.isNullOrBlank() && bean.cipher !in dataCipherList) {
            dataCipherList.add(bean.cipher)
        }
        if (dataCipherList.isNotEmpty()) {
            put("data_ciphers", JSONArray(dataCipherList))
        }
        if (!bean.auth.isNullOrBlank()) put("auth", bean.auth)
        // TLS mode requires a tls object; without certificate /
        // peer_fingerprint the core fails at start with "either
        // certificate-authority or peer-fingerprint must be configured".
        // Always emit tls (at least {}) so check surfaces that error clearly.
        put("tls", JSONObject().apply {
            if (!bean.certificate.isNullOrBlank()) {
                put("certificate", JSONArray(listOf(bean.certificate)))
            }
            if (!bean.clientCertificate.isNullOrBlank()) {
                put("client_certificate", JSONArray(listOf(bean.clientCertificate)))
            }
            if (!bean.clientKey.isNullOrBlank()) {
                put("client_key", JSONArray(listOf(bean.clientKey)))
            }
        })
    }
    return CustomSingBoxOption(json.toString())
}

private fun JSONObject.optListableString(key: String): String {
    return when (val v = opt(key)) {
        is JSONArray -> (0 until v.length()).map { v.optString(it) }.joinToString("\n")
        is String -> v
        else -> ""
    }
}

fun parseOpenVPNJson(json: JSONObject): OpenVPNBean {
    return OpenVPNBean().apply {
        name = json.optString("name", json.optString("tag", ""))
        serverAddress = json.optString("server", "")
        serverPort = json.optInt("server_port", 0).takeIf { it > 0 }
            ?: json.optInt("port", 0).takeIf { it > 0 }
        json.optJSONArray("servers")?.let { arr ->
            val lines = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val host = item.optString("server", "")
                val port = item.optInt("server_port", 0)
                val net = item.optString("network", "")
                if (host.isNotBlank() && port > 0) {
                    lines.add(if (net.isNotBlank()) "$host:$port $net" else "$host:$port")
                }
            }
            servers = lines.joinToString("\n")
        }
        username = json.optString("username", "")
        password = json.optString("password", "")
        network = json.optString("network", "udp")
        cipher = json.optString("cipher", "")
        dataCiphers = when (val dc = json.opt("data_ciphers")) {
            is JSONArray -> (0 until dc.length()).map { dc.optString(it) }.joinToString(",")
            is String -> dc
            else -> ""
        }
        auth = json.optString("auth", "")
        json.optJSONObject("tls")?.let { tls ->
            certificate = tls.optListableString("certificate")
            clientCertificate = tls.optListableString("client_certificate")
            clientKey = tls.optListableString("client_key")
        }
        initializeDefaultValues()
    }
}
