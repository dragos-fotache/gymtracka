package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ForegroundSignInContextTest {
    private val origin = ServiceOrigin.parse("https://sync.example.test")
    private val account = "a0000001-1111-4111-8111-111111111111"
    private val savedOperation = "b0000001-1111-4111-8111-111111111111"
    private val freshOperation = "b0000002-2222-4222-8222-222222222222"

    private fun room(phase: RecoveryRoomPhase) = RecoveryRoomSnapshot(
        revision = 3L,
        sessionGeneration = 1L,
        cachedAccountId = account,
        phase = phase,
        identity = RecoveryIdentity(ServiceSessionBinding(origin, account, 1L), savedOperation),
        baselineComplete = phase == RecoveryRoomPhase.ACTIVE,
    )

    @Test
    fun foregroundSameAccountRequiresNewOperationAcrossOccupiedPhases() {
        for (phase in listOf(RecoveryRoomPhase.PREPARING, RecoveryRoomPhase.TRANSITIONING, RecoveryRoomPhase.ACTIVE)) {
            assertEquals(freshOperation, canonicalForegroundOperation(
                origin, ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION, room(phase), freshOperation,
            ))
            assertThrows(ForegroundSignInContextInvalidException::class.java) {
                canonicalForegroundOperation(
                    origin, ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION, room(phase), savedOperation,
                )
            }
        }
    }

    @Test
    fun freshOperationDoesNotPermitForeignOriginOrInitialSignInOverRetainedAccount() {
        for (phase in listOf(RecoveryRoomPhase.PREPARING, RecoveryRoomPhase.TRANSITIONING)) {
            assertThrows(ForegroundSignInContextInvalidException::class.java) {
                canonicalForegroundOperation(
                    ServiceOrigin.parse("https://foreign.example.test"),
                    ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION, room(phase), freshOperation,
                )
            }
            assertThrows(ForegroundSignInContextInvalidException::class.java) {
                canonicalForegroundOperation(
                    origin, ForegroundSignInIntent.INITIAL_SIGN_IN, room(phase), freshOperation,
                )
            }
        }
    }
}
