package com.dragosfotache.gymtracka.data.sync.http

import com.dragosfotache.gymtracka.data.sync.api.MAX_RETRY_AFTER_MILLIS
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Headers
import okhttp3.Response
import okio.BufferedSource

/** Explicit positive connect/read/write/whole-call timeouts in milliseconds. */
internal data class ServiceHttpTimeouts(
    val connectMillis: Long = 10_000L,
    val readMillis: Long = 30_000L,
    val writeMillis: Long = 30_000L,
    val callMillis: Long = 60_000L,
) {
    init {
        checkTimeout(connectMillis)
        checkTimeout(readMillis)
        checkTimeout(writeMillis)
        checkTimeout(callMillis)
    }
}

private fun checkTimeout(value: Long) {
    if (value < 1L || value > Int.MAX_VALUE) throw SyncModelException("Invalid HTTP timeout")
}

internal enum class ServiceHttpMethod { GET, POST }

/**
 * Immutable fixed-origin request. The path is `/v1/` plus ASCII segments; query order/repeats
 * survive; only Authorization/Idempotency-Key caller headers are allowed. GET is bodyless,
 * POST always carries its exact body. Invalid values fail safe before I/O.
 */
internal class ServiceHttpRequest(
    val method: ServiceHttpMethod,
    val path: String,
    query: List<Pair<String, String>> = emptyList(),
    headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    val query: List<Pair<String, String>>
    val headers: Map<String, String>

    init {
        if (!PATH_PATTERN.matches(path)) throw SyncModelException("Invalid request path")
        val queryCopy = ArrayList<Pair<String, String>>(query.size)
        for ((name, value) in query) {
            if (name.isEmpty()) throw SyncModelException("Invalid request query")
            checkPairedSurrogates(name)
            checkPairedSurrogates(value)
            queryCopy.add(Pair(name, value))
        }
        this.query = Collections.unmodifiableList(queryCopy)
        val seenNames = HashSet<String>()
        val headerCopy = LinkedHashMap<String, String>()
        for ((name, value) in headers) {
            if (!name.equals("Authorization", ignoreCase = true) &&
                !name.equals("Idempotency-Key", ignoreCase = true)
            ) {
                throw SyncModelException("Invalid request header")
            }
            if (!seenNames.add(name.lowercase(Locale.ROOT))) {
                throw SyncModelException("Invalid request header")
            }
            checkHeaderValue(value)
            headerCopy[name] = value
        }
        this.headers = Collections.unmodifiableMap(headerCopy)
        if (method == ServiceHttpMethod.GET && body != null) {
            throw SyncModelException("Invalid request body")
        }
        if (method == ServiceHttpMethod.POST && body == null) {
            throw SyncModelException("Invalid request body")
        }
        if (body != null) checkPairedSurrogates(body)
    }

    override fun toString(): String = "ServiceHttpRequest(redacted)"

    companion object {
        private val PATH_PATTERN = Regex("^/v1/([A-Za-z0-9_-]+)(/[A-Za-z0-9_-]+)*$")
    }
}

private fun checkHeaderValue(value: String) {
    if (value.isEmpty()) throw SyncModelException("Invalid request header")
    if (value.first() == ' ' || value.last() == ' ') throw SyncModelException("Invalid request header")
    for (element in value) {
        // Visible ASCII plus interior spaces only: rejects controls, tabs, non-ASCII and DEL.
        if (element < ' ' || element > '~') throw SyncModelException("Invalid request header")
    }
}

/** Rejects unpaired surrogates with a fixed safe failure, without echoing input. */
internal fun checkPairedSurrogates(value: String) {
    var index = 0
    while (index < value.length) {
        val element = value[index]
        if (element.isHighSurrogate()) {
            if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                throw SyncModelException("Invalid request text")
            }
            index += 2
        } else if (element.isLowSurrogate()) {
            throw SyncModelException("Invalid request text")
        } else {
            index += 1
        }
    }
}

internal interface ServiceHttpTransport {
    suspend fun <T> withResponse(
        request: ServiceHttpRequest,
        consume: suspend (ServiceHttpResponse) -> T,
    ): T
}

/**
 * Response scoped to one [ServiceHttpTransport.withResponse] consumer. Status/header metadata
 * is available for any status; body ownership ends when the consumer returns, throws or is
 * cancelled, after which reads fail safe without I/O. Exactly one read mode is allowed:
 * one-shot [readJsonBody] or sequential [readBodyChunk]; any misuse fails safe.
 */
internal class ServiceHttpResponse internal constructor(
    call: Call,
    response: Response,
) {
    val statusCode: Int = response.code
    val headers: Map<String, List<String>> = snapshotHeaders(response.headers)

    private val guard = Any()
    private var jsonMode = false
    private var chunkMode = false
    private var finished = false
    private var closeClaimed = false
    private var chunkHeadersChecked = false
    private var expectedLength: Long? = null
    private var chunkCount = 0L
    private val bodyLength: Long = response.body.contentLength()
    private val source: BufferedSource = response.body.source()
    private val okResponse: Response = response

    /** Consumes the complete body as strict UTF-8 JSON text; one-shot, no size cap. */
    suspend fun readJsonBody(): String {
        synchronized(guard) {
            if (finished || jsonMode || chunkMode) throw SyncModelException("Response body is unavailable")
            jsonMode = true
        }
        try {
            ensureJsonMediaType()
            ensureIdentityEncoding()
            val declared = declaredLength()
            val bytes = readAll()
            if (declared != null && bytes.size.toLong() != declared) {
                throw SyncApiException.MalformedResponse()
            }
            return decodeUtf8(bytes)
        } finally {
            closeAfterConsumer()
        }
    }

    /**
     * Incremental suspend read filling [buffer] from offset 0; returns bytes read,
     * -1 only on clean framing-verified EOF. The buffer must be nonempty.
     */
    suspend fun readBodyChunk(buffer: ByteArray): Int {
        if (buffer.isEmpty()) throw SyncModelException("Invalid read buffer")
        // Pure header validation runs under lock; suspension (close) stays outside it.
        var validationError: SyncApiException? = null
        val expected = synchronized(guard) {
            if (finished || jsonMode) throw SyncModelException("Response body is unavailable")
            if (!chunkHeadersChecked) {
                chunkHeadersChecked = true
                try {
                    ensureIdentityEncoding()
                    expectedLength = declaredLength()
                } catch (error: SyncApiException) {
                    validationError = error
                }
            }
            if (validationError == null) chunkMode = true
            expectedLength
        }
        if (validationError != null) {
            closeAfterConsumer()
            throw validationError
        }
        val read: Int = cancellableIo { source.read(buffer, 0, buffer.size) }
        if (read == -1) {
            val mismatch = synchronized(guard) { expected != null && chunkCount != expected }
            if (mismatch) malformedAndClose()
            return -1
        }
        synchronized(guard) {
            chunkCount += read.toLong()
        }
        val overrun = synchronized(guard) { expected != null && chunkCount > expected }
        if (overrun) malformedAndClose()
        return read
    }

    /**
     * Idempotent once-only safe cleanup. State is claimed under lock; the possibly blocking
     * close runs outside the lock, off Main. Later reads fail safe without touching I/O.
     */
    internal suspend fun closeAfterConsumer() {
        val shouldClose = synchronized(guard) {
            finished = true
            if (closeClaimed) {
                false
            } else {
                closeClaimed = true
                true
            }
        }
        if (shouldClose) {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        okResponse.close()
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun toString(): String = "ServiceHttpResponse(redacted)"

    private suspend fun malformedAndClose(): Nothing {
        closeAfterConsumer()
        throw SyncApiException.MalformedResponse()
    }

    private fun headerValues(name: String): List<String> {
        val matches = ArrayList<String>()
        for ((key, values) in headers) {
            if (key.equals(name, ignoreCase = true)) matches.addAll(values)
        }
        return matches
    }

    private fun ensureJsonMediaType() {
        val values = headerValues("Content-Type")
        if (values.size != 1) throw SyncApiException.MalformedResponse()
        parseJsonMediaType(values[0])
    }

    private fun ensureIdentityEncoding() {
        val values = headerValues("Content-Encoding")
        if (values.isEmpty()) return
        if (values.size != 1) throw SyncApiException.MalformedResponse()
        if (!stripOuter(values[0]).equals("identity", ignoreCase = true)) {
            throw SyncApiException.MalformedResponse()
        }
    }

    /**
     * Claimed complete-body length. A present Content-Length header is strict; it must agree
     * with ResponseBody metadata when both are present and nonnegative; full reads verify it.
     */
    private fun declaredLength(): Long? {
        val values = headerValues("Content-Length")
        val fromHeader = if (values.isEmpty()) {
            null
        } else {
            if (values.size != 1) throw SyncApiException.MalformedResponse()
            parseContentLength(values[0])
        }
        if (fromHeader != null && bodyLength >= 0L && fromHeader != bodyLength) {
            throw SyncApiException.MalformedResponse()
        }
        return fromHeader ?: if (bodyLength >= 0L) bodyLength else null
    }

    private suspend fun readAll(): ByteArray {
        val sink = okio.Buffer()
        while (true) {
            val read = cancellableIo { source.read(sink, SEGMENT_SIZE) }
            if (read == -1L) break
        }
        return sink.readByteArray()
    }

    private suspend fun <T> cancellableIo(block: () -> T): T {
        // The owning withResponse watcher cancels the call and closes the response the moment
        // the consumer scope cancels, which interrupts a blocked read; no per-read hooks here.
        return withContext(Dispatchers.IO) {
            try {
                coroutineContext.ensureActive()
                block()
            } catch (error: IOException) {
                // Preserve the original coroutine CancellationException instead of
                // converting cancellation-induced I/O into a transport failure.
                coroutineContext.ensureActive()
                throw mapBodyIo(error)
            }
        }
    }

    companion object {
        private const val SEGMENT_SIZE = 8192L
    }
}

private fun mapBodyIo(error: IOException): SyncApiException {
    if (error is ProtocolException || error is EOFException) return SyncApiException.MalformedResponse()
    return SyncApiException.Transient(connectionFailure = true)
}

private fun snapshotHeaders(headers: Headers): Map<String, List<String>> {
    val grouped = LinkedHashMap<String, MutableList<String>>()
    for (index in 0 until headers.size) {
        grouped.getOrPut(headers.name(index)) { ArrayList() }.add(headers.value(index))
    }
    val snapshot = LinkedHashMap<String, List<String>>()
    for ((name, values) in grouped) {
        snapshot[name] = Collections.unmodifiableList(values)
    }
    return Collections.unmodifiableMap(snapshot)
}

private fun stripOuter(value: String): String {
    var start = 0
    var end = value.length
    while (start < end && (value[start] == ' ' || value[start] == '\t')) start++
    while (end > start && (value[end - 1] == ' ' || value[end - 1] == '\t')) end--
    return value.substring(start, end)
}

private fun parseJsonMediaType(raw: String) {
    val parts = splitMediaParts(raw)
    if (!stripOuter(parts[0]).equals("application/json", ignoreCase = true)) {
        throw SyncApiException.MalformedResponse()
    }
    var charsetSeen = false
    for (index in 1 until parts.size) {
        val parameter = parts[index]
        val separator = parameter.indexOf('=')
        if (separator <= 0) throw SyncApiException.MalformedResponse()
        val name = stripOuter(parameter.substring(0, separator))
        val rawValue = stripOuter(parameter.substring(separator + 1))
        if (name.isEmpty() || !isTokenText(name)) throw SyncApiException.MalformedResponse()
        if (rawValue.isEmpty()) throw SyncApiException.MalformedResponse()
        val value = if (rawValue.length >= 2 && rawValue.startsWith('"') && rawValue.endsWith('"')) {
            unescapeQuoted(rawValue.substring(1, rawValue.length - 1))
        } else {
            if (!isTokenText(rawValue)) throw SyncApiException.MalformedResponse()
            rawValue
        }
        if (name.equals("charset", ignoreCase = true)) {
            if (charsetSeen) throw SyncApiException.MalformedResponse()
            charsetSeen = true
            if (!value.equals("utf-8", ignoreCase = true)) throw SyncApiException.MalformedResponse()
        }
    }
}

/** Splits a media value on unquoted semicolons; quoted pairs stay intact for valid parameters. */
private fun splitMediaParts(raw: String): List<String> {
    val parts = ArrayList<String>()
    val current = StringBuilder()
    var index = 0
    var inQuotes = false
    while (index < raw.length) {
        val element = raw[index]
        if (inQuotes) {
            if (element == '\\') {
                if (index + 1 >= raw.length) throw SyncApiException.MalformedResponse()
                val next = raw[index + 1]
                if ((next < ' ' && next != '\t') || next == '\u007F') {
                    throw SyncApiException.MalformedResponse()
                }
                current.append(element).append(next)
                index += 2
            } else if (element == '"') {
                inQuotes = false
                current.append(element)
                index++
            } else if ((element < ' ' && element != '\t') || element == '\u007F') {
                throw SyncApiException.MalformedResponse()
            } else {
                current.append(element)
                index++
            }
        } else if (element == '"') {
            inQuotes = true
            current.append(element)
            index++
        } else if (element == ';') {
            parts.add(current.toString())
            current.clear()
            index++
        } else {
            current.append(element)
            index++
        }
    }
    if (inQuotes) throw SyncApiException.MalformedResponse()
    parts.add(current.toString())
    return parts
}

private fun unescapeQuoted(inner: String): String {
    val unescaped = StringBuilder(inner.length)
    var index = 0
    while (index < inner.length) {
        val element = inner[index]
        if (element == '\\') {
            if (index + 1 >= inner.length) throw SyncApiException.MalformedResponse()
            val next = inner[index + 1]
            // Quoted pairs carry HTAB/SP/VCHAR; unescaped quotes, controls and DEL are rejected.
            if ((next < ' ' && next != '\t') || next == '\u007F') {
                throw SyncApiException.MalformedResponse()
            }
            unescaped.append(next)
            index += 2
        } else if (element == '"' || (element < ' ' && element != '\t') || element == '\u007F') {
            throw SyncApiException.MalformedResponse()
        } else {
            unescaped.append(element)
            index++
        }
    }
    return unescaped.toString()
}

private fun isTokenText(value: String): Boolean {
    for (element in value) {
        val valid = element in 'a'..'z' ||
            element in 'A'..'Z' ||
            element in '0'..'9' ||
            element == '!' ||
            element == '#' ||
            element == '$' ||
            element == '%' ||
            element == '&' ||
            element == '\'' ||
            element == '*' ||
            element == '+' ||
            element == '-' ||
            element == '.' ||
            element == '^' ||
            element == '_' ||
            element == '`' ||
            element == '|' ||
            element == '~'
        if (!valid) return false
    }
    return true
}

private fun parseContentLength(raw: String): Long {
    val text = stripOuter(raw)
    if (text.isEmpty() || text.any { it !in '0'..'9' }) throw SyncApiException.MalformedResponse()
    var value = 0L
    for (element in text) {
        val digit = (element - '0').toLong()
        if (value > (Long.MAX_VALUE - digit) / 10L) return Long.MAX_VALUE
        value = value * 10L + digit
    }
    return value
}

private fun decodeUtf8(bytes: ByteArray): String {
    try {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        throw SyncApiException.MalformedResponse()
    }
}

/**
 * Bounded whole-second Retry-After hint in milliseconds. Case-insensitive lookup requires
 * exactly one value; ASCII whole seconds saturate to [MAX_RETRY_AFTER_MILLIS] before
 * multiplication. Anything absent, multiple, empty, nonpositive or non-numeric yields null
 * so the caller applies its own backoff. Never schedules retries.
 */
internal fun retryAfterMillis(headers: Map<String, List<String>>): Long? {
    val values = ArrayList<String>()
    for ((name, entries) in headers) {
        if (name.equals("Retry-After", ignoreCase = true)) values.addAll(entries)
    }
    if (values.size != 1) return null
    val text = stripOuter(values[0])
    if (text.isEmpty() || text.any { it !in '0'..'9' }) return null
    val significant = text.trimStart('0')
    if (significant.isEmpty()) return null
    if (significant.length > 3) return MAX_RETRY_AFTER_MILLIS
    val seconds = significant.toInt()
    if (seconds >= 300) return MAX_RETRY_AFTER_MILLIS
    return seconds * 1000L
}
