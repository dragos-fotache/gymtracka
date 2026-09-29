package com.dragosfotache.gymtracka.testing.service

import com.dragosfotache.gymtracka.data.sync.api.GymSyncApi
import com.dragosfotache.gymtracka.data.sync.api.boundedPullLimit
import com.dragosfotache.gymtracka.data.sync.api.boundedSnapshotLimit
import com.dragosfotache.gymtracka.data.sync.api.requirePushBatch
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.PullResultModel
import com.dragosfotache.gymtracka.data.sync.model.PushResultModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel

/**
 * Test-only account-bound [GymSyncApi] recording adapter over
 * [FakeSyncService]. The account is bound through the constructor, exactly
 * like the future authenticated adapter: wire methods accept no
 * owner-controlled account fields. Every call records its opaque arguments
 * verbatim. Push executes the seventeen implemented fake commands in order through the
 * service after the approved envelope validation; pull and snapshot remain
 * explicit [UnsupportedOperationException] stubs owned by later assignments.
 */
class FakeAccountSyncApi(
    val accountId: String,
    val service: FakeSyncService,
) : GymSyncApi {
    /** Opaque pull call arguments: cursor verbatim, limit coerced to OpenAPI bounds. */
    data class PullCall(val cursor: String?, val limit: Int)

    /** Opaque snapshot call arguments: token verbatim, limit coerced to OpenAPI bounds. */
    data class SnapshotCall(val pageToken: String?, val limit: Int)

    private val _pullCalls = mutableListOf<PullCall>()
    private val _snapshotCalls = mutableListOf<SnapshotCall>()
    private val _pushSizes = mutableListOf<Int>()

    /** Recorded pull calls in call order. */
    val pullCalls: List<PullCall> get() = _pullCalls.toList()

    /** Recorded snapshot calls in call order. */
    val snapshotCalls: List<SnapshotCall> get() = _snapshotCalls.toList()

    /** Validated push batch sizes in call order. */
    val pushSizes: List<Int> get() = _pushSizes.toList()

    override suspend fun push(requests: List<DurableRequestModel>): PushResultModel {
        requirePushBatch(requests)
        _pushSizes += requests.size
        return service.executePush(accountId, requests)
    }

    override suspend fun pull(cursor: String?, limit: Int): PullResultModel {
        _pullCalls += PullCall(cursor, boundedPullLimit(limit))
        throw UnsupportedOperationException(
            "Fake pull read protocol is a later assignment; " +
                "this scaffold records only the opaque call arguments.",
        )
    }

    override suspend fun snapshot(pageToken: String?, limit: Int): SnapshotResultModel {
        _snapshotCalls += SnapshotCall(pageToken, boundedSnapshotLimit(limit))
        throw UnsupportedOperationException(
            "Fake snapshot read protocol is a later assignment; " +
                "this scaffold records only the opaque call arguments.",
        )
    }
}
