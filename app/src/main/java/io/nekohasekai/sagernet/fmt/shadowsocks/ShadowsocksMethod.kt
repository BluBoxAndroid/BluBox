package io.nekohasekai.sagernet.fmt.shadowsocks

import java.util.Locale

/**
 * Normalize a Shadowsocks cipher name to the canonical spelling accepted by
 * the sing-box kernel: trimmed and lowercased, with widely used aliases
 * mapped to the official method names. Unknown names are only lowercased,
 * never rewritten, so methods added by future kernels keep working.
 *
 * Kept free of Android and project dependencies on purpose: subscription
 * sources are inconsistent about case and aliases (for example Clash uses
 * "chacha20-poly1305" where sing-box wants "chacha20-ietf-poly1305"), and
 * the kernel rejects non-canonical names outright.
 */
fun normalizeShadowsocksMethod(method: String?): String {
    return when (val lower = method?.trim()?.lowercase(Locale.ROOT).orEmpty()) {
        "chacha20-poly1305" -> "chacha20-ietf-poly1305"
        "xchacha20-poly1305" -> "xchacha20-ietf-poly1305"
        else -> lower
    }
}
