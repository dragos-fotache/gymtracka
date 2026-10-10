package com.dragosfotache.gymtracka.ui

import com.dragosfotache.gymtracka.data.sync.store.ProjectionLeaseInvalidException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FiniteEditCoordinatorTest {
    @Test
    fun normalAcceptanceRegistersBeforeOnAcceptedAndDrainsInOrder() = runTest {
        val coordinator = FiniteEditCoordinator(backgroundScope)
        assertEquals("counter starts at zero", 0L, coordinator.committedEdits.value)
        try {
            val order = mutableListOf<String>()
            val firstGate = CompletableDeferred<Unit>()
            val secondGate = CompletableDeferred<Unit>()

            val first = coordinator.launchEdit(
                key = "edit-first",
                onAccepted = { order += "accepted-first" },
            ) {
                firstGate.await()
                order += "work-first"
            }
            assertNotNull(first)
            // Acceptance is synchronous: onAccepted runs before the block completes.
            assertEquals(listOf("accepted-first"), order)

            val second = coordinator.launchEdit(
                key = "edit-second",
                onAccepted = { order += "accepted-second" },
            ) {
                secondGate.await()
                order += "work-second"
            }
            assertNotNull(second)
            assertEquals(listOf("accepted-first", "accepted-second"), order)

            // Draining waits for registered work in acceptance order.
            val drain = async { coordinator.awaitPendingEdits() }
            runCurrent()
            assertTrue(drain.isActive)

            // Release the second gate first: FIFO must still hold work-second
            // until the first predecessor completes, proving serialization.
            secondGate.complete(Unit)
            runCurrent()
            assertEquals(listOf("accepted-first", "accepted-second"), order)
            assertTrue(drain.isActive)

            firstGate.complete(Unit)
            runCurrent()
            drain.await()
            assertEquals(
                listOf("accepted-first", "accepted-second", "work-first", "work-second"),
                order,
            )
            assertEquals("two successes increment twice", 2L, coordinator.committedEdits.value)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun pausedAdmissionRefusesWithoutSideEffects() = runTest {
        val coordinator = FiniteEditCoordinator(backgroundScope)
        try {
            val lease = coordinator.pauseAndDrain()
            try {
                assertTrue(coordinator.isPaused)

                var accepted = false
                var workRan = false
                val refused = coordinator.launchEdit(
                    key = "late-edit",
                    onAccepted = { accepted = true },
                ) {
                    workRan = true
                }
                runCurrent()
                assertNull(refused)
                assertFalse(accepted)
                assertFalse(workRan)
                assertEquals("refusal increments nothing", 0L, coordinator.committedEdits.value)

                // Repeat drain still succeeds while paused; no phantom work appears.
                coordinator.awaitPendingEdits()
            } finally {
                lease.close()
            }
            runCurrent()
            assertFalse(coordinator.isPaused)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun failedEditIsRetainedUntilSameKeyRetryAndUnrelatedSuccessDoesNotClearIt() = runTest {
        val coordinator = FiniteEditCoordinator(backgroundScope)
        try {
            coordinator.launchEdit(key = "failing") { throw IllegalStateException("boom") }
            runCurrent()
            try {
                coordinator.awaitPendingEdits()
                fail("Drain must surface the retained failure")
            } catch (expected: IllegalStateException) {
                assertEquals("boom", expected.message)
            }
            assertEquals("failure increments nothing", 0L, coordinator.committedEdits.value)

            // Unrelated success must not clear the retained failure.
            coordinator.launchEdit(key = "unrelated") { }
            runCurrent()
            try {
                coordinator.awaitPendingEdits()
                fail("Unrelated success must not clear the failure")
            } catch (expected: IllegalStateException) {
                assertEquals("boom", expected.message)
            }
            assertEquals("unrelated success increments once", 1L, coordinator.committedEdits.value)

            // Same-key retry with success settles the retained failure.
            coordinator.launchEdit(key = "failing") { }
            runCurrent()
            coordinator.awaitPendingEdits()
            assertEquals("retry success increments again", 2L, coordinator.committedEdits.value)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun oldCloseCannotReopenNewerPauseAndInvalidLeasesThrow() = runTest {
        val coordinator = FiniteEditCoordinator(backgroundScope)
        try {
            val first = coordinator.pauseAndDrain()
            first.close()
            runCurrent()
            assertFalse(coordinator.isPaused)
            try {
                first.checkValid()
                fail("Closed lease must be invalid")
            } catch (expected: ProjectionLeaseInvalidException) {
                // Expected.
            }

            val second = coordinator.pauseAndDrain()
            try {
                assertTrue(coordinator.isPaused)
                // Old close is owner-specific and must not reopen the newer pause.
                first.close()
                runCurrent()
                assertTrue(coordinator.isPaused)
                second.checkValid()
            } finally {
                second.close()
            }
            runCurrent()
            assertFalse(coordinator.isPaused)
            try {
                second.checkValid()
                fail("Closed lease must be invalid")
            } catch (expected: ProjectionLeaseInvalidException) {
                // Expected.
            }
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun exclusiveEditDrainsPrecedingWorkAndRetainsOwnFailureForSameKeyRetry() = runTest {
        val coordinator = FiniteEditCoordinator(backgroundScope)
        try {
            val order = mutableListOf<String>()
            val precedingGate = CompletableDeferred<Unit>()
            coordinator.launchEdit(key = "preceding") {
                precedingGate.await()
                order += "preceding"
            }
            runCurrent()

            // Exclusive reserves admission synchronously and waits for preceding work only.
            val exclusive = coordinator.launchExclusiveEdit(key = "exclusive") {
                order += "exclusive"
                throw IllegalStateException("exclusive failed")
            }
            assertNotNull(exclusive)
            runCurrent()

            // Late normal admission is rejected while the exclusive transition owns the pause.
            assertNull(coordinator.launchEdit(key = "late") { order += "late" })

            precedingGate.complete(Unit)
            runCurrent()
            try {
                exclusive?.join()
            } catch (_: Exception) {
                // Failure is retained for drain observers; join alone is not the signal.
            }
            try {
                coordinator.awaitPendingEdits()
                fail("Exclusive failure must be retained")
            } catch (expected: IllegalStateException) {
                assertEquals("exclusive failed", expected.message)
            }
            assertEquals(listOf("preceding", "exclusive"), order)
            assertEquals("only the preceding success increments", 1L, coordinator.committedEdits.value)

            // Same-key retry may run despite its own previous retained failure.
            val retry = coordinator.launchExclusiveEdit(key = "exclusive") { order += "exclusive-retry" }
            assertNotNull(retry)
            runCurrent()
            retry?.join()
            runCurrent()
            coordinator.awaitPendingEdits()
            assertEquals(listOf("preceding", "exclusive", "exclusive-retry"), order)
            assertEquals("retry success increments again", 2L, coordinator.committedEdits.value)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun terminalEditRegistersWhilePausedAndInvalidatesLease() = runTest {
        val coordinator = FiniteEditCoordinator(backgroundScope)
        try {
            val lease = coordinator.pauseAndDrain()
            var terminalRan = false
            try {
                lease.checkValid()
                val terminal = coordinator.launchTerminalEdit(key = "timer-instance-1") {
                    terminalRan = true
                }
                assertNotNull(terminal)
                // Synchronous fencing: lease is invalid immediately at launch,
                // before any suspension or terminal work runs.
                try {
                    lease.checkValid()
                    fail("Terminal write during lease use must invalidate the lease")
                } catch (expected: ProjectionLeaseInvalidException) {
                    // Expected.
                }
                runCurrent()
                terminal?.join()
                runCurrent()
                assertTrue(terminalRan)
                assertEquals("terminal success increments", 1L, coordinator.committedEdits.value)
            } finally {
                lease.close()
            }
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun disposeRefusesNewWorkInvalidatesLeasesAndPreservesParentScope() = runTest {
        val coordinator = FiniteEditCoordinator(backgroundScope)
        val lease = coordinator.pauseAndDrain()
        coordinator.dispose()
        runCurrent()

        try {
            lease.checkValid()
            fail("Disposed coordinator must invalidate leases")
        } catch (expected: ProjectionLeaseInvalidException) {
            // Expected.
        }

        var accepted = false
        assertNull(
            coordinator.launchEdit(key = "after-dispose", onAccepted = { accepted = true }) { },
        )
        assertFalse(accepted)
        assertNull(coordinator.launchExclusiveEdit(key = "after-dispose-exclusive") { })
        assertNull(coordinator.launchTerminalEdit(key = "after-dispose-terminal") { })
        assertEquals("post-dispose refusals increment nothing", 0L, coordinator.committedEdits.value)

        // Parent scope is never cancelled by dispose.
        val parentProbe = CompletableDeferred<Unit>()
        backgroundScope.launch { parentProbe.complete(Unit) }
        runCurrent()
        assertTrue(parentProbe.isCompleted)
    }

    @Test
    fun pauseAndDrainCancellationReleasesPause() = runTest {
        val coordinator = FiniteEditCoordinator(backgroundScope)
        try {
            val precedingGate = CompletableDeferred<Unit>()
            coordinator.launchEdit(key = "preceding") { precedingGate.await() }
            runCurrent()
            val pause = async { coordinator.pauseAndDrain() }
            runCurrent()
            assertTrue(pause.isActive)
            assertTrue(coordinator.isPaused)
            pause.cancel()
            runCurrent()
            assertFalse(coordinator.isPaused)
            // New normal acceptance works again after the cancelled pause.
            assertNotNull(coordinator.launchEdit(key = "after-cancel") { })
            precedingGate.complete(Unit)
            runCurrent()
            coordinator.awaitPendingEdits()
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun unrelatedFailureBlocksExclusiveMutation() = runTest {
        val coordinator = FiniteEditCoordinator(backgroundScope)
        try {
            coordinator.launchEdit(key = "unrelated") { throw IllegalStateException("unrelated") }
            runCurrent()
            try {
                coordinator.awaitPendingEdits()
                fail("Unrelated failure must be retained")
            } catch (expected: IllegalStateException) {
                assertEquals("unrelated", expected.message)
            }
            // Exclusive with a different key cannot bypass the unresolved failure:
            // it may return a registered Job that fails pre-mutation drain, but the
            // block must never run and the pause must be released.
            var blockedRan = false
            val blocked = coordinator.launchExclusiveEdit(key = "exclusive-new") { blockedRan = true }
            runCurrent()
            if (blocked != null) {
                try {
                    blocked.join()
                } catch (_: Exception) {
                    // Pre-mutation drain failure is retained for drain observers.
                }
            }
            runCurrent()
            assertFalse(blockedRan)
            assertFalse(coordinator.isPaused)
            try {
                coordinator.awaitPendingEdits()
                fail("Unrelated failure must still surface")
            } catch (expected: IllegalStateException) {
                assertEquals("unrelated", expected.message)
            }
            // Same-key retry settles the unrelated failure, then exclusive works.
            coordinator.launchEdit(key = "unrelated") { }
            runCurrent()
            coordinator.awaitPendingEdits()
            var retryRan = false
            val exclusive = coordinator.launchExclusiveEdit(key = "exclusive-new") { retryRan = true }
            assertNotNull(exclusive)
            runCurrent()
            exclusive?.join()
            runCurrent()
            assertTrue(retryRan)
            coordinator.awaitPendingEdits()
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun terminalAndNormalEditsDrainInAcceptanceOrderBothDirections() = runTest {
        // Terminal claimed first: newer ordinary intent cannot overtake claimed rest.
        val firstCoordinator = FiniteEditCoordinator(backgroundScope)
        try {
            val order = mutableListOf<String>()
            val terminalGate = CompletableDeferred<Unit>()
            val terminal = firstCoordinator.launchTerminalEdit(key = "timer-first") {
                terminalGate.await()
                order += "terminal"
            }
            assertNotNull(terminal)
            val normal = firstCoordinator.launchEdit(key = "normal-second") { order += "normal" }
            assertNotNull(normal)
            runCurrent()
            assertTrue(order.isEmpty())
            terminalGate.complete(Unit)
            runCurrent()
            firstCoordinator.awaitPendingEdits()
            assertEquals(listOf("terminal", "normal"), order)
            assertEquals("terminal plus normal increment twice", 2L, firstCoordinator.committedEdits.value)
        } finally {
            firstCoordinator.dispose()
        }

        // Reverse acceptance: normal gated first, terminal second.
        val secondCoordinator = FiniteEditCoordinator(backgroundScope)
        try {
            val order = mutableListOf<String>()
            val normalGate = CompletableDeferred<Unit>()
            val normal = secondCoordinator.launchEdit(key = "normal-first") {
                normalGate.await()
                order += "normal"
            }
            assertNotNull(normal)
            val terminal = secondCoordinator.launchTerminalEdit(key = "timer-second") { order += "terminal" }
            assertNotNull(terminal)
            runCurrent()
            assertTrue(order.isEmpty())
            normalGate.complete(Unit)
            runCurrent()
            secondCoordinator.awaitPendingEdits()
            assertEquals(listOf("normal", "terminal"), order)
            assertEquals("normal plus terminal increment twice", 2L, secondCoordinator.committedEdits.value)
        } finally {
            secondCoordinator.dispose()
        }
    }

    @Test
    fun cancellationBeforeStartIsTrackedAsFailure() = runTest {
        val coordinator = FiniteEditCoordinator(backgroundScope)
        try {
            val gate = CompletableDeferred<Unit>()
            val job = coordinator.launchEdit(key = "cancelled") { gate.await() }
            assertNotNull(job)
            job?.cancel()
            gate.complete(Unit)
            runCurrent()
            try {
                coordinator.awaitPendingEdits()
                fail("Cancelled work must remain observable to drains")
            } catch (_: Exception) {
                // Cancellation evidence is retained; exact type is an implementation detail.
            }
            assertEquals("cancellation increments nothing", 0L, coordinator.committedEdits.value)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun sameKeySupersedingBeforeFirstFailureSettlesDrain() = runTest {
        val coordinator = FiniteEditCoordinator(backgroundScope)
        try {
            val gateA = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val first = coordinator.launchEdit(key = "same") {
                gateA.await()
                throw IllegalStateException("A")
            }
            assertNotNull(first)
            // Superseding same-key success is accepted BEFORE A fails.
            val second = coordinator.launchEdit(key = "same") { order += "B" }
            assertNotNull(second)
            runCurrent()
            gateA.complete(Unit)
            runCurrent()
            // Newer successful superseding write settles the drain.
            coordinator.awaitPendingEdits()
            assertEquals(listOf("B"), order)
            assertEquals("only the superseding success increments", 1L, coordinator.committedEdits.value)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun cancelledExclusiveBeforeStartReleasesPauseAndNeverMutates() = runTest {
        val coordinator = FiniteEditCoordinator(backgroundScope)
        try {
            var mutated = false
            val exclusive = coordinator.launchExclusiveEdit(key = "exclusive-cancel") { mutated = true }
            assertNotNull(exclusive)
            exclusive?.cancel()
            runCurrent()
            assertFalse(mutated)
            assertFalse(coordinator.isPaused)
            try {
                coordinator.awaitPendingEdits()
                fail("Cancelled exclusive must report cancellation evidence")
            } catch (_: Exception) {
                // Cancellation evidence is retained.
            }
            assertFalse(mutated)
            assertEquals("cancelled exclusive increments nothing", 0L, coordinator.committedEdits.value)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun externalDrainWaitsForGatedExclusiveWithoutSelfJoin() = runTest {
        val coordinator = FiniteEditCoordinator(backgroundScope)
        try {
            val precedingGate = CompletableDeferred<Unit>()
            val exclusiveGate = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            coordinator.launchEdit(key = "preceding") {
                precedingGate.await()
                order += "preceding"
            }
            runCurrent()
            val exclusive = coordinator.launchExclusiveEdit(key = "exclusive-drain") {
                exclusiveGate.await()
                // Internal drain must not self-join the running exclusive transition.
                coordinator.awaitPendingEdits()
                order += "exclusive"
            }
            assertNotNull(exclusive)
            val externalDrain = async { coordinator.awaitPendingEdits() }
            runCurrent()
            assertTrue(externalDrain.isActive)
            precedingGate.complete(Unit)
            runCurrent()
            // Exclusive is gated on its own gate, not on preceding anymore.
            assertTrue(externalDrain.isActive)
            exclusiveGate.complete(Unit)
            runCurrent()
            externalDrain.await()
            assertEquals(listOf("preceding", "exclusive"), order)
        } finally {
            coordinator.dispose()
        }
    }

    @Test
    fun reentrantTerminalAdmitsNewerNormalInSharedFifoOrder() = runTest {
        val immediateScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val coordinator = FiniteEditCoordinator(immediateScope)
        try {
            val order = mutableListOf<String>()
            var newer: Job? = null
            val terminal = coordinator.launchTerminalEdit(key = "timer-reentrant") {
                newer = coordinator.launchEdit(key = "newer-normal") { order += "normal" }
                order += "terminal"
            }
            assertNotNull(terminal)
            runCurrent()
            assertNotNull(newer)
            coordinator.awaitPendingEdits()
            assertEquals(listOf("terminal", "normal"), order)
        } finally {
            coordinator.dispose()
            immediateScope.cancel()
        }
    }

    @Test
    fun reentrantOnAcceptedKeepsAcceptanceOrder() = runTest {
        val coordinator = FiniteEditCoordinator(backgroundScope)
        try {
            val order = mutableListOf<String>()
            var second: kotlinx.coroutines.Job? = null
            val first = coordinator.launchEdit(
                key = "first",
                onAccepted = {
                    // Registration precedes the callback, so reentrant acceptance works.
                    second = coordinator.launchEdit(key = "second") { order += "second" }
                },
            ) {
                order += "first"
            }
            assertNotNull(first)
            runCurrent()
            assertNotNull(second)
            coordinator.awaitPendingEdits()
            assertEquals(listOf("first", "second"), order)
        } finally {
            coordinator.dispose()
        }
    }
}
