package com.dragosfotache.gymtracka.data.sync.api

/** Advisory device-to-service reachability, with no account or credential authority. */
internal enum class ServerCheckResult {
    CONNECTED,
    UNREACHABLE,
    SERVER_ERROR,
    UNEXPECTED_RESPONSE,
}

internal fun interface ServerChecker {
    suspend fun check(): ServerCheckResult
}
