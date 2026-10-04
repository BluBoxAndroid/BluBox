package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

// Convert a config generated in the sing-box <=1.12 format (current
// SingBoxOptions POJO) into the sing-box 1.14 format. Only the generated
// JSON is rewritten; stored profiles/settings are untouched.
fun migrateConfigToSingBox114(configJson: String): String {
    val root = try {
        JsonParser.parseString(configJson).takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: Exception) {
        null
    } ?: return configJson
    return try {
        migrateConfigObject114(root)
        root.toString()
    } catch (e: Exception) {
        configJson
    }
}

private fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arr(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonElement?.asStringList(): List<String> = when {
    this == null || isJsonNull -> emptyList()
    isJsonArray -> asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
    isJsonPrimitive -> listOf(asString)
    else -> emptyList()
}

private fun jsonArrayOf(strings: List<String>): JsonArray {
    val array = JsonArray()
    strings.forEach { array.add(it) }
    return array
}

// ---- DNS -----------------------------------------------------------------

private fun parseDnsAddress(address: String, server: JsonObject) {
    when {
        address == "local" -> server.addProperty("type", "local")
        address.startsWith("dhcp://") -> {
            server.addProperty("type", "dhcp")
            val iface = address.removePrefix("dhcp://")
            if (iface.isNotBlank() && iface != "auto") server.addProperty("interface", iface)
        }
        else -> {
            var scheme = "udp"
            var rest = address
            for ((prefix, type) in listOf(
                "tcp://" to "tcp", "tls://" to "tls", "https://" to "https",
                "quic://" to "quic", "h3://" to "h3", "udp://" to "udp"
            )) {
                if (address.startsWith(prefix)) {
                    scheme = type
                    rest = address.removePrefix(prefix)
                    break
                }
            }
            server.addProperty("type", scheme)
            if (scheme == "https" || scheme == "h3") {
                val noScheme = rest.substringBefore("?")
                val hostPort = noScheme.substringBefore("/")
                val path = noScheme.removePrefix(hostPort)
                val host = hostPort.substringBefore(":")
                server.addProperty("server", host)
                hostPort.removePrefix(host).removePrefix(":").toIntOrNull()
                    ?.let { server.addProperty("server_port", it) }
                if (path.isNotBlank() && path != "/") server.addProperty("path", path)
            } else {
                var host = rest
                var port: Int? = null
                if (rest.startsWith("[")) {
                    val end = rest.indexOf(']')
                    if (end > 0) {
                        host = rest.substring(1, end)
                        port = rest.substring(end + 1).removePrefix(":").toIntOrNull()
                    }
                } else if (rest.count { it == ':' } == 1) {
                    val p = rest.substringAfterLast(":")
                    if (p.toIntOrNull() != null) {
                        host = rest.substringBeforeLast(":")
                        port = p.toInt()
                    }
                }
                server.addProperty("server", host)
                port?.let { server.addProperty("server_port", it) }
            }
        }
    }
}

private fun migrateDns114(root: JsonObject) {
    val dns = root.obj("dns") ?: return
    val fakeip = dns.obj("fakeip")
    dns.remove("fakeip")
    dns.remove("independent_cache")

    val rcodeServers = mutableMapOf<String, String>()
    val strategies = mutableMapOf<String, String>()
    val newServers = JsonArray()
    dns.arr("servers")?.forEach { el ->
        val server = el.asJsonObject
        val address = server.str("address") ?: run {
            newServers.add(server)
            return@forEach
        }
        server.remove("address")
        val tag = server.str("tag")
        server.str("strategy")?.let { if (tag != null) strategies[tag] = it }
        server.remove("strategy")
        val resolver = server.str("address_resolver")
        server.remove("address_resolver")
        server.remove("address_strategy")
        server.remove("address_fallback_delay")
        when {
            address.startsWith("rcode://") -> {
                var rcode = address.removePrefix("rcode://").uppercase()
                if (rcode == "SUCCESS") rcode = "NOERROR"
                if (tag != null) rcodeServers[tag] = rcode
            }
            address == "fakeip" -> {
                server.addProperty("type", "fakeip")
                server.remove("detour")
                fakeip?.str("inet4_range")?.let { server.addProperty("inet4_range", it) }
                fakeip?.str("inet6_range")?.let { server.addProperty("inet6_range", it) }
                newServers.add(server)
            }
            else -> {
                parseDnsAddress(address, server)
                if (resolver != null) server.addProperty("domain_resolver", resolver)
                newServers.add(server)
            }
        }
    }
    dns.add("servers", newServers)

    // Legacy (<=1.12) DNS semantics: a remote DNS server without an explicit
    // detour dialed through the default outbound (route final). Upstream
    // 1.14 changed the no-detour default to a direct dialer, which breaks
    // the remote DNS (e.g. dns.google) wherever direct access to it is
    // unreachable. Restore the legacy behavior by pinning the detour to
    // the final outbound.
    val finalOutbound = root.obj("route")?.str("final")
        ?: root.arr("outbounds")?.let { outbounds ->
            val tags = outbounds.mapNotNull { it.asJsonObject.str("tag") }
            when {
                "proxy" in tags -> "proxy"
                tags.isNotEmpty() -> tags.first()
                else -> null
            }
        }
    if (finalOutbound != null) {
        newServers.forEach { el ->
            val server = el.asJsonObject
            val type = server.str("type")
            if ((type == "https" || type == "tls" || type == "tcp" ||
                    type == "udp" || type == "quic" || type == "h3") &&
                server.get("detour") == null
            ) {
                server.addProperty("detour", finalOutbound)
            }
        }
    }

    val newRules = JsonArray()
    dns.arr("rules")?.forEach { el ->
        val rule = el.asJsonObject
        val serverTag = rule.str("server")
        val rcode = serverTag?.let { rcodeServers[it] }
        if (rcode != null) {
            rule.remove("server")
            rule.addProperty("action", "predefined")
            rule.addProperty("rcode", rcode)
            rule.remove("disable_cache")
            rule.remove("strategy")
            rule.remove("rewrite_ttl")
            rule.remove("client_subnet")
            newRules.add(rule)
            return@forEach
        }
        val outbounds = rule.get("outbound").asStringList()
        if ("any" in outbounds) {
            rule.remove("outbound")
            if (serverTag != null) {
                val route = root.obj("route") ?: JsonObject().also { root.add("route", it) }
                if (route.get("default_domain_resolver") == null) {
                    route.add(
                        "default_domain_resolver",
                        JsonObject().apply { addProperty("server", serverTag) })
                }
            }
            val hasCondition = rule.keySet().any { it !in setOf("server", "action") }
            if (hasCondition) newRules.add(rule)
            return@forEach
        }
        if (serverTag != null && strategies.containsKey(serverTag) && rule.get("strategy") == null) {
            rule.addProperty("strategy", strategies[serverTag])
        }
        newRules.add(rule)
    }
    dns.add("rules", newRules)

    dns.str("final")?.let { finalTag ->
        strategies[finalTag]?.let { dns.addProperty("strategy", it) }
    }
}

// ---- Inbounds (legacy fields -> route rule actions) ----------------------

private fun migrateInbounds114(root: JsonObject) {
    val sniffRules = JsonArray()
    root.arr("inbounds")?.forEach { el ->
        val inbound = el.asJsonObject
        if (inbound.str("type") == "tun") {
            val address = jsonArrayOf(
                inbound.get("inet4_address").asStringList() +
                    inbound.get("inet6_address").asStringList()
            )
            inbound.remove("inet4_address")
            inbound.remove("inet6_address")
            if (address.size() > 0) inbound.add("address", address)
            for ((old, new) in listOf(
                "inet4_route_address" to "route_address",
                "inet6_route_address" to "route_address",
                "inet4_route_exclude_address" to "route_exclude_address",
                "inet6_route_exclude_address" to "route_exclude_address"
            )) {
                val values = inbound.get(old).asStringList()
                inbound.remove(old)
                if (values.isNotEmpty()) {
                    val merged = inbound.get(new).asStringList() + values
                    inbound.add(new, jsonArrayOf(merged))
                }
            }
            inbound.remove("gso")
            inbound.remove("endpoint_independent_nat")
        }
        val sniff = inbound.get("sniff")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val timeout = inbound.get("sniff_timeout")
        val domainStrategy = inbound.str("domain_strategy")
        inbound.remove("sniff")
        inbound.remove("sniff_override_destination")
        inbound.remove("sniff_timeout")
        inbound.remove("domain_strategy")
        inbound.remove("udp_disable_domain_unmapping")
        val tag = inbound.str("tag")
        if (tag != null) {
            if (domainStrategy != null) {
                sniffRules.add(JsonObject().apply {
                    add("inbound", jsonArrayOf(listOf(tag)))
                    addProperty("action", "resolve")
                    addProperty("strategy", domainStrategy)
                })
            }
            if (sniff) {
                sniffRules.add(JsonObject().apply {
                    add("inbound", jsonArrayOf(listOf(tag)))
                    addProperty("action", "sniff")
                    if (timeout != null && !timeout.isJsonNull) add("timeout", timeout)
                })
            }
        }
    }
    if (sniffRules.size() > 0) {
        val route = root.obj("route") ?: JsonObject().also { root.add("route", it) }
        val merged = JsonArray()
        sniffRules.forEach { merged.add(it) }
        route.arr("rules")?.forEach { merged.add(it) }
        route.add("rules", merged)
    }
}

// ---- Outbounds (wireguard -> endpoint, domain_strategy -> resolver) -----

private fun migrateOutbounds114(root: JsonObject) {
    val dnsTags = root.obj("dns")?.arr("servers")
        ?.mapNotNull { it.asJsonObject.str("tag") } ?: emptyList()
    val resolverTag = when {
        "dns-direct" in dnsTags -> "dns-direct"
        dnsTags.isNotEmpty() -> dnsTags.first()
        else -> null
    }

    fun convertDomainStrategy(obj: JsonObject) {
        val strategy = obj.str("domain_strategy")
        obj.remove("domain_strategy")
        if (strategy != null && resolverTag != null) {
            val resolver = when (val existing = obj.get("domain_resolver")) {
                null -> JsonObject().apply {
                    addProperty("server", resolverTag)
                }
                else -> if (existing.isJsonNull) {
                    JsonObject().apply { addProperty("server", resolverTag) }
                } else if (existing.isJsonPrimitive) {
                    JsonObject().apply { addProperty("server", existing.asString) }
                } else {
                    existing.asJsonObject
                }
            }
            if (resolver.get("server") == null) resolver.addProperty("server", resolverTag)
            resolver.addProperty("strategy", strategy)
            obj.add("domain_resolver", resolver)
        }
    }

    val endpoints = root.arr("endpoints") ?: JsonArray()
    var hasEndpoints = root.has("endpoints")
    val newOutbounds = JsonArray()
    root.arr("outbounds")?.forEach { el ->
        val outbound = el.asJsonObject
        if (outbound.str("type") == "wireguard") {
            val endpoint = JsonObject()
            endpoint.addProperty("type", "wireguard")
            outbound.str("tag")?.let { endpoint.addProperty("tag", it) }
            outbound.get("local_address")?.let { endpoint.add("address", it) }
            outbound.str("private_key")?.let { endpoint.addProperty("private_key", it) }
            outbound.get("mtu")?.let { endpoint.add("mtu", it) }
            if (outbound.get("system_interface")?.asBoolean == true) {
                endpoint.addProperty("system", true)
            }
            outbound.str("interface_name")?.let { endpoint.addProperty("name", it) }
            val peer = JsonObject()
            outbound.str("server")?.let { peer.addProperty("address", it) }
            outbound.get("server_port")?.let { peer.add("port", it) }
            outbound.str("peer_public_key")?.let { peer.addProperty("public_key", it) }
            outbound.str("pre_shared_key")?.let { peer.addProperty("pre_shared_key", it) }
            peer.add("allowed_ips", jsonArrayOf(listOf("0.0.0.0/0", "::/0")))
            when (val reserved = outbound.get("reserved")) {
                null -> {}
                else -> if (reserved.isJsonArray) {
                    peer.add("reserved", reserved)
                } else if (reserved.isJsonPrimitive && reserved.asJsonPrimitive.isString) {
                    try {
                        val bytes = Base64.getDecoder().decode(reserved.asString)
                        val array = JsonArray()
                        bytes.forEach { array.add(it.toInt() and 0xFF) }
                        peer.add("reserved", array)
                    } catch (e: Exception) {
                        // keep as-is; core will report an invalid value
                    }
                }
            }
            val peers = JsonArray()
            peers.add(peer)
            endpoint.add("peers", peers)
            for (key in listOf(
                "detour", "domain_strategy", "domain_resolver", "bind_interface",
                "inet4_bind_address", "inet6_bind_address", "connect_timeout",
                "tcp_fast_open", "tcp_multi_path", "udp_fragment", "routing_mark",
                "reuse_addr", "protect_path"
            )) {
                outbound.get(key)?.let { endpoint.add(key, it) }
            }
            convertDomainStrategy(endpoint)
            endpoints.add(endpoint)
            hasEndpoints = true
        } else {
            convertDomainStrategy(outbound)
            if (outbound.str("type") == "direct" && outbound.get("udp_fragment") == null) {
                // sing-box 1.14 rejects a detour to an "empty" direct outbound
                // (DetourDialer empty-direct check, which legacy DNS dialers
                // skipped in 1.12). Neko detours dns-local/dns-direct to the
                // bare "direct" outbound, so box start fails without this.
                // udp_fragment defaults to true, so setting it explicitly is
                // behavior-neutral but makes the outbound non-empty.
                outbound.addProperty("udp_fragment", true)
            }
            newOutbounds.add(outbound)
        }
    }
    root.add("outbounds", newOutbounds)
    if (hasEndpoints) root.add("endpoints", endpoints)

    // Default domain resolver (legacy "outbound any" DNS rule semantics).
    val route = root.obj("route")
    if (route != null && route.get("default_domain_resolver") == null &&
        resolverTag == "dns-direct"
    ) {
        route.add(
            "default_domain_resolver",
            JsonObject().apply { addProperty("server", "dns-direct") })
    }
    if (route != null && route.get("final") == null) {
        val tags = newOutbounds.mapNotNull { it.asJsonObject.str("tag") }
        val final = when {
            "proxy" in tags -> "proxy"
            tags.isNotEmpty() -> tags.first()
            else -> null
        }
        if (final != null) route.addProperty("final", final)
    }
}

fun migrateConfigObject114(root: JsonObject) {
    migrateDns114(root)
    migrateInbounds114(root)
    migrateOutbounds114(root)
}
