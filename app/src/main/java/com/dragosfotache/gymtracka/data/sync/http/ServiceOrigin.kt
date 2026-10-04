package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import java.net.URI
import java.net.URISyntaxException
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Immutable configured service identity: HTTPS scheme, canonical host and effective port.
 * Root-only origin; missing configuration is represented as null by [configured], never an
 * invented URL. Equality compares scheme/host/effective port only.
 */
internal class ServiceOrigin private constructor(
    val scheme: String,
    val host: String,
    val port: Int,
) {
    companion object {
        /** Strictly parses ORIGINAL configuration text; invalid input fails safe, never I/O. */
        fun parse(value: String): ServiceOrigin = parseOrigin(value)

        /** Null means unavailable; invalid non-null input fails like [parse]. */
        fun configured(value: String?): ServiceOrigin? {
            if (value == null) return null
            return parse(value)
        }

        private fun invalidOrigin(): Nothing = throw SyncModelException("Invalid service origin")

        private fun parseOrigin(value: String): ServiceOrigin {
            if (value.isEmpty()) invalidOrigin()
            // Strict ORIGINAL-text checks before any permissive normalization; never trim.
            for (element in value) {
                if (element <= '\u001F' || element == '\u007F' || element in '\u0080'..'\u009F') {
                    invalidOrigin()
                }
                if (element.isWhitespace()) invalidOrigin()
                if (element == '\\') invalidOrigin()
            }
            checkPairedSurrogates(value)
            val uri = try {
                URI(value)
            } catch (_: URISyntaxException) {
                invalidOrigin()
            }
            if (uri.scheme == null || !uri.scheme.equals("https", ignoreCase = true)) invalidOrigin()
            // Any user-info, including an empty one, is rejected.
            if (uri.rawUserInfo != null) invalidOrigin()
            val rawAuthority = uri.rawAuthority ?: invalidOrigin()
            // Percent-encoded authority/host and zone IDs are rejected;
            // '@' cannot appear without user-info.
            if (rawAuthority.contains('%') || rawAuthority.contains('@')) invalidOrigin()
            // An empty colon port hides behind port -1; reject it explicitly.
            if (rawAuthority.endsWith(':')) invalidOrigin()
            // java.net.URI keeps IPv6 brackets in the host; strip them for validation
            // and re-add only for canonicalization.
            val rawHost = uri.host ?: invalidOrigin()
            if (rawHost.isEmpty()) invalidOrigin()
            val bracketed = rawHost.startsWith('[')
            val host = if (bracketed) {
                if (!rawHost.endsWith(']') || rawHost.length <= 2) invalidOrigin()
                rawHost.substring(1, rawHost.length - 1)
            } else {
                if (rawHost.contains('[') || rawHost.contains(']')) invalidOrigin()
                rawHost
            }
            if (host.isEmpty()) invalidOrigin()
            if (host.any { it.code >= 128 }) invalidOrigin()
            if (host.contains('%')) invalidOrigin()
            val effectivePort = if (uri.port == -1) {
                443
            } else {
                if (uri.port < 1 || uri.port > 65535) invalidOrigin()
                uri.port
            }
            if (uri.rawQuery != null || uri.rawFragment != null) invalidOrigin()
            // Only an absent or exactly-"/" path is accepted; percent-encoded root paths fail here.
            val rawPath = uri.rawPath
            if (rawPath != null && rawPath != "" && rawPath != "/") invalidOrigin()
            validateHostShape(host, bracketed)
            // Canonicalize (ASCII lowercase, IPv6 compression) after strict original validation.
            val canonicalHost = try {
                val textual = if (bracketed) "[$host]" else host
                "https://$textual/".toHttpUrl().host
            } catch (_: IllegalArgumentException) {
                invalidOrigin()
            }
            return ServiceOrigin("https", canonicalHost, effectivePort)
        }

        /** Conventional DNS, IPv4 or IPv6-literal host shapes; ASCII only, no IDN conversion. */
        private fun validateHostShape(host: String, isV6: Boolean) {
            if (isV6) {
                if (!host.contains(':')) invalidOrigin()
                for (element in host) {
                    val valid = element in '0'..'9' ||
                        element in 'a'..'f' ||
                        element in 'A'..'F' ||
                        element == ':' ||
                        element == '.'
                    if (!valid) invalidOrigin()
                }
                return
            }
            if (isIPv4Shaped(host)) {
                val groups = host.split('.')
                for (group in groups) {
                    val number = group.toIntOrNull() ?: invalidOrigin()
                    if (number < 0 || number > 255) invalidOrigin()
                }
                return
            }
            // Conventional DNS name or single label such as localhost; punycode stays ASCII.
            if (host.length > 253 || host.endsWith('.')) invalidOrigin()
            val labels = host.split('.')
            for (label in labels) {
                if (label.isEmpty() || label.length > 63) invalidOrigin()
                if (label.startsWith('-') || label.endsWith('-')) invalidOrigin()
                for (element in label) {
                    val valid = element in 'a'..'z' ||
                        element in 'A'..'Z' ||
                        element in '0'..'9' ||
                        element == '-'
                    if (!valid) invalidOrigin()
                }
            }
        }

        private fun isIPv4Shaped(host: String): Boolean {
            val groups = host.split('.')
            if (groups.size != 4) return false
            return groups.all { it.isNotEmpty() && it.all { element -> element in '0'..'9' } }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ServiceOrigin &&
            scheme == other.scheme &&
            host == other.host &&
            port == other.port

    override fun hashCode(): Int {
        var result = scheme.hashCode()
        result = 31 * result + host.hashCode()
        result = 31 * result + port
        return result
    }

    override fun toString(): String = "ServiceOrigin(redacted)"
}
