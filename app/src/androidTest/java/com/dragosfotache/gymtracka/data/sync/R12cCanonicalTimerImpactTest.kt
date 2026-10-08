package com.dragosfotache.gymtracka.data.sync

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.session.AppSessionFixtures
import com.dragosfotache.gymtracka.data.sync.session.AppSessionStaleException
import com.dragosfotache.gymtracka.data.gym.equipmentFixture
import com.dragosfotache.gymtracka.data.gym.exerciseFixture
import com.dragosfotache.gymtracka.data.gym.muscleGroupFixture
import com.dragosfotache.gymtracka.data.sync.store.ProjectionStore
import com.dragosfotache.gymtracka.data.sync.store.RepositorySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R12c independent QA: canonical timer-impact reader (outcome 3).
 *
 * Hand-written from docs/remaining-work/R12c-task.md only (fixed TARGET
 * contract in data.sync.store): ProjectionStore.preservesTimer is an
 * ACTIVE-only read-only transactionally session/epoch/process-fenced
 * canonical-impact decision reusing existing validated canonical helpers.
 * Invalid/incomplete/stale data throws, never a permissive true or
 * destructive false. Real in-memory Room plus QA-owned R05b builders and
 * R05c seeding/barrier helpers; independently written literals only; no
 * sleeps, no production oracles.
 */
class R12cCanonicalTimerImpactTest : GymDatabaseTest() {
    private companion object {
        const val EPOCH = 11L
        const val WORKOUT = "c1200001-1111-4111-8111-111111111111"
        const val ENTRY = "c1200002-2222-4222-8222-222222222222"
        const val SET = "c1200003-3333-4333-8333-333333333333"
        const val REMOVED_ENTRY = "c1200004-4444-4111-8111-111111111111"
        const val REMOVED_SET = "c1200005-5555-4111-8111-111111111111"
        const val PROT_MUTATION = "c1200006-6666-4111-8111-111111111111"
        const val DANGLING_EXERCISE = "c1200007-7777-4111-8111-111111111111"
    }

    private fun store(): ProjectionStore =
        database.r05cStore(account = R05B_ACCOUNT, generation = R05C_GENERATION, barrier = r05cGrantBarrier())

    private fun activeWorkoutChange(
        workoutId: String = WORKOUT,
        entryId: String = ENTRY,
        setId: String = SET,
        setCompleted: Boolean = true,
        status: String = "active",
        finishedAt: String? = null,
        exerciseId: String = R05B_EXERCISE_REF,
        exerciseName: String = "Press",
    ) = r05bChange(
        R05B_TYPE_WORKOUT,
        workoutId,
        R05B_OP_UPSERT,
        1L,
        r05bWorkoutRecord(
            id = workoutId,
            name = "Timed",
            status = status,
            finishedAt = finishedAt,
            revision = 1L,
            exercises = r05bJsonArray(
                r05bWorkoutEntry(
                    id = entryId,
                    exerciseId = exerciseId,
                    exerciseName = exerciseName,
                    sets = r05bJsonArray(
                        r05bWorkoutSet(id = setId, isCompleted = setCompleted),
                    ),
                ),
            ),
        ),
    )

    private suspend fun seedCatalogue() {
        insertMuscleGroups(
            muscleGroupFixture(id = R05C_MUSCLE_PRIMARY, name = "Chest"),
            muscleGroupFixture(id = R05C_MUSCLE_SECONDARY, name = "Shoulders"),
        )
        insertEquipment(equipmentFixture(id = R05C_EQUIPMENT, name = "Dumbbell"))
        insertExercises(
            exerciseFixture(id = R05B_EXERCISE_REF, name = "Press"),
            exerciseFixture(id = R05B_EXERCISE_REF_2, name = "Press Variant"),
        )
    }

    private suspend fun seedTimedBaseline() {
        seedCatalogue()
        database.r05cPreparePublication(
            changes = r05bJsonArray(activeWorkoutChange()),
            cacheEpoch = EPOCH,
        )
    }

    @Test
    fun incompleteBaselineThrowsNeverPermissive() = runTestWithTimeout {
        database.r05cSeedDevice(R05B_ACCOUNT, R05C_GENERATION)
        database.r05cSeedCache(R05B_ACCOUNT, false, EPOCH, false, EPOCH)
        try {
            store().preservesTimer(EPOCH, WORKOUT, ENTRY, SET)
            fail("Incomplete baseline must throw, never a permissive true")
        } catch (_: Exception) {
        }
    }

    @Test
    fun staleEpochFenceThrows() = runTestWithTimeout {
        seedTimedBaseline()
        try {
            store().preservesTimer(EPOCH - 1, WORKOUT, ENTRY, SET)
            fail("Stale epoch must throw, never a permissive true")
        } catch (_: Exception) {
        }
        try {
            store().preservesTimer(EPOCH + 1, WORKOUT, ENTRY, SET)
            fail("Future epoch must throw, never a permissive true")
        } catch (_: Exception) {
        }
    }

    @Test
    fun validUnchangedTimerPreservesWithoutWrites() = runTestWithTimeout {
        seedTimedBaseline()
        val cacheBefore = database.r05cDump("sync_account_cache")
        val canonicalBefore = database.r05cDump("sync_canonical_roots")
        val domainBefore = database.r05cDump("workout_sets")
        val preserved = store().preservesTimer(EPOCH, WORKOUT, ENTRY, SET)
        assertTrue("Unchanged live entry/set must preserve the timer", preserved)
        assertEquals(cacheBefore, database.r05cDump("sync_account_cache"))
        assertEquals(canonicalBefore, database.r05cDump("sync_canonical_roots"))
        assertEquals(domainBefore, database.r05cDump("workout_sets"))
    }

    @Test
    fun changedExerciseReferenceAndNamePreserveValidTimer() = runTestWithTimeout {
        seedCatalogue()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                activeWorkoutChange(exerciseId = R05B_EXERCISE_REF_2, exerciseName = "Press Renamed"),
            ),
            cacheEpoch = EPOCH,
        )
        val cacheBefore = database.r05cDump("sync_account_cache")
        val canonicalBefore = database.r05cDump("sync_canonical_roots")
        // Same live entry/set IDs pointing at a changed catalogue reference
        // with a renamed exercise must not end an otherwise valid timer: the
        // decision keys on entry/set identity, not the referenced row.
        assertTrue(store().preservesTimer(EPOCH, WORKOUT, ENTRY, SET))
        assertEquals(cacheBefore, database.r05cDump("sync_account_cache"))
        assertEquals(canonicalBefore, database.r05cDump("sync_canonical_roots"))
    }

    @Test
    fun protectedLocalIntentReturnsTrueWithoutWrites() = runTestWithTimeout {
        seedTimedBaseline()
        database.r05cInsertOutbox(
            mutationId = PROT_MUTATION,
            account = R05B_ACCOUNT,
            aggregateType = R05B_TYPE_WORKOUT,
            aggregateId = WORKOUT,
        )
        val outboxBefore = database.r05cDump("sync_outbox")
        val cacheBefore = database.r05cDump("sync_account_cache")
        val canonicalBefore = database.r05cDump("sync_canonical_roots")
        // Account-wide outbox protection already prevents publication, so no
        // pending graph can invalidate the visible timer.
        assertTrue(store().preservesTimer(EPOCH, WORKOUT, ENTRY, SET))
        assertEquals(outboxBefore, database.r05cDump("sync_outbox"))
        assertEquals(cacheBefore, database.r05cDump("sync_account_cache"))
        assertEquals(canonicalBefore, database.r05cDump("sync_canonical_roots"))
    }

    @Test
    fun explicitlyUncompletedSetReturnsFalse() = runTestWithTimeout {
        seedCatalogue()
        database.r05cPreparePublication(
            changes = r05bJsonArray(activeWorkoutChange(setCompleted = false)),
            cacheEpoch = EPOCH,
        )
        assertFalse(store().preservesTimer(EPOCH, WORKOUT, ENTRY, SET))
    }

    @Test
    fun removedEntryReturnsFalse() = runTestWithTimeout {
        seedCatalogue()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                activeWorkoutChange(entryId = REMOVED_ENTRY, setId = REMOVED_SET),
            ),
            cacheEpoch = EPOCH,
        )
        assertFalse(store().preservesTimer(EPOCH, WORKOUT, ENTRY, SET))
    }

    @Test
    fun remotelyFinishedWorkoutReturnsFalse() = runTestWithTimeout {
        seedCatalogue()
        database.r05cPreparePublication(
            changes = r05bJsonArray(
                activeWorkoutChange(status = "finished", finishedAt = R05B_FINISHED_AT),
            ),
            cacheEpoch = EPOCH,
        )
        assertFalse(store().preservesTimer(EPOCH, WORKOUT, ENTRY, SET))
    }

    @Test
    fun protectedReadStillEnforcesLiveFences() = runTestWithTimeout {
        seedTimedBaseline()
        database.r05cInsertOutbox(
            mutationId = PROT_MUTATION,
            account = R05B_ACCOUNT,
            aggregateType = R05B_TYPE_WORKOUT,
            aggregateId = WORKOUT,
        )
        try {
            store().preservesTimer(EPOCH + 1, WORKOUT, ENTRY, SET)
            fail("Outbox protection never waives live epoch fences")
        } catch (_: Exception) {
        }
    }

    @Test
    fun unresolvedExerciseReferenceThrowsWithNoWrites() = runTestWithTimeout {
        seedCatalogue()
        // Valid staged shape whose entry points at an exercise absent from
        // both the immutable catalogue and the canonical custom graph: the
        // reader must fail closed instead of deciding on the broken graph.
        // (Writer-side family rejection stays covered by its own suite.)
        database.r05cPreparePublication(
            changes = r05bJsonArray(activeWorkoutChange(exerciseId = DANGLING_EXERCISE)),
            cacheEpoch = EPOCH,
        )
        try {
            store().preservesTimer(EPOCH, WORKOUT, ENTRY, SET)
            fail("Unresolved reference graph must throw, never decide")
        } catch (_: Exception) {
        }
        assertEquals(0L, database.r05cReadCache(R05B_ACCOUNT)?.materializedEpoch)
        assertTrue(database.r05cDump("sync_outbox").isEmpty())
    }

    @Test
    fun sessionGenerationDriftThrowsWithNoWrites() = runTestWithTimeout {
        seedTimedBaseline()
        val cacheBefore = database.r05cDump("sync_account_cache")
        val canonicalBefore = database.r05cDump("sync_canonical_roots")
        // Late authority drift: the device now binds a newer generation, so
        // this store's session is stale and the reader must fail closed.
        database.r05cSeedDevice(R05B_ACCOUNT, R05C_GENERATION + 1)
        try {
            store().preservesTimer(EPOCH, WORKOUT, ENTRY, SET)
            fail("Stale session must throw, never decide")
        } catch (_: Exception) {
        }
        assertEquals(cacheBefore, database.r05cDump("sync_account_cache"))
        assertEquals(canonicalBefore, database.r05cDump("sync_canonical_roots"))
    }

    @Test
    fun revokedLeasePublicationFailsClosedWithoutWrites() = runTestWithTimeout {
        database.r05cPreparePublication(
            changes = r05bJsonArray(activeWorkoutChange()),
            cacheEpoch = EPOCH,
        )
        val cacheBefore = database.r05cDump("sync_account_cache")
        val lease = R05cTestLease()
        val gated = database.r05cStore(
            account = R05B_ACCOUNT,
            generation = R05C_GENERATION,
            barrier = r05cGrantBarrier(lease),
        )
        lease.revoke()
        try {
            gated.publish(EPOCH)
            fail("Revoked-lease publication bypass must fail closed")
        } catch (_: Exception) {
        }
        assertEquals(cacheBefore, database.r05cDump("sync_account_cache"))
    }

    @Test
    fun revokedProcessCheckFailsClosedOnBothDecisionPaths() = runTestWithTimeout {
        seedTimedBaseline()
        val cacheBefore = database.r05cDump("sync_account_cache")
        val canonicalBefore = database.r05cDump("sync_canonical_roots")
        val revoked: () -> Unit = { throw IllegalStateException("r12c-process-revoked") }
        val guardedTrue = ProjectionStore(
            database,
            RepositorySession(R05B_ACCOUNT, R05C_GENERATION),
            r05cGrantBarrier(),
            revoked,
        )
        try {
            guardedTrue.preservesTimer(EPOCH, WORKOUT, ENTRY, SET)
            fail("Revoked process must fail closed on the preserve path")
        } catch (_: Exception) {
        }
        val guardedFalse = ProjectionStore(
            database,
            RepositorySession(R05B_ACCOUNT, R05C_GENERATION),
            r05cGrantBarrier(),
            revoked,
        )
        try {
            guardedFalse.preservesTimer(EPOCH, WORKOUT, REMOVED_ENTRY, REMOVED_SET)
            fail("Revoked process must fail closed on the invalidate path")
        } catch (_: Exception) {
        }
        assertEquals(cacheBefore, database.r05cDump("sync_account_cache"))
        assertEquals(canonicalBefore, database.r05cDump("sync_canonical_roots"))
    }

    @Test
    fun revokedProcessCheckAfterMetadataWriteRollsBackPublication() = runTestWithTimeout {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sawMetadataWrite = java.util.concurrent.atomic.AtomicBoolean(false)
        val armed = java.util.concurrent.atomic.AtomicBoolean(true)
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        val revokedOld = java.util.concurrent.atomic.AtomicBoolean(false)
        val queryExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val callbackDb: GymDatabase = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setQueryExecutor(queryExecutor)
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, _ ->
                    if (armed.get()) {
                        val lower = sql.lowercase()
                        val isMetadataWrite = lower.contains("update") && lower.contains("sync_account_cache")
                        if (!sawMetadataWrite.get() && isMetadataWrite) {
                            sawMetadataWrite.set(true)
                        } else if (sawMetadataWrite.getAndSet(false)) {
                            fired.set(true)
                            revokedOld.set(true)
                        }
                    }
                },
                java.util.concurrent.Executor { command -> command.run() },
            )
            .build()
        try {
            callbackDb.openHelper.writableDatabase.execSQL(
                "INSERT INTO muscle_groups (id, slug, name) VALUES (?, ?, ?)",
                arrayOf<Any?>(R05C_MUSCLE_PRIMARY, R05C_MUSCLE_PRIMARY, "Chest"),
            )
            callbackDb.openHelper.writableDatabase.execSQL(
                "INSERT INTO equipment (id, name, type, usage_type) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(R05C_EQUIPMENT, "Dumbbell", "weight", null),
            )
            callbackDb.exerciseDao().insertExercise(exerciseFixture(id = R05B_EXERCISE_REF, name = "Press"))
            callbackDb.r05cPreparePublication(
                changes = r05bJsonArray(activeWorkoutChange()),
                cacheEpoch = EPOCH,
            )
            val session = RepositorySession(R05B_ACCOUNT, R05C_GENERATION)
            val cacheBefore = callbackDb.r05cDump("sync_account_cache")
            val canonicalBefore = callbackDb.r05cDump("sync_canonical_roots")
            val outboxBefore = callbackDb.r05cDump("sync_outbox")
            val domainBefore = callbackDb.r05cDump("workouts") + callbackDb.r05cDump("workout_exercises") + callbackDb.r05cDump("workout_sets")
            val epochsBefore = callbackDb.r05cReadCache(R05B_ACCOUNT)

            val revokedStore = ProjectionStore(callbackDb, session, r05cGrantBarrier(), { if (revokedOld.get()) throw AppSessionStaleException() })
            try {
                revokedStore.publish(EPOCH)
                fail("Revoked process after the metadata write must fail closed")
            } catch (error: Exception) {
                AppSessionFixtures.assertStale(error)
            }
            assertTrue("hook must fire after the metadata write", fired.get())
            assertEquals(cacheBefore, callbackDb.r05cDump("sync_account_cache"))
            assertEquals(canonicalBefore, callbackDb.r05cDump("sync_canonical_roots"))
            assertEquals(outboxBefore, callbackDb.r05cDump("sync_outbox"))
            assertEquals(domainBefore, callbackDb.r05cDump("workouts") + callbackDb.r05cDump("workout_exercises") + callbackDb.r05cDump("workout_sets"))
            assertEquals(epochsBefore, callbackDb.r05cReadCache(R05B_ACCOUNT))

            // The retired armed callback and revoked old guard must never
            // revoke the newer exact handle: disarmed, a fresh guard retries
            // cleanly on the same generation.
            armed.set(false)
            val retry = ProjectionStore(callbackDb, session, r05cGrantBarrier(), null)
            val published = retry.publish(EPOCH)
            assertEquals(com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationStatus.PUBLISHED, published.status)
            assertTrue((callbackDb.r05cReadCache(R05B_ACCOUNT)?.materializedEpoch ?: -1L) > (epochsBefore?.materializedEpoch ?: -1L))
        } finally {
            runCatching { callbackDb.close() }
            queryExecutor.shutdownNow()
        }
    }

    private fun runTestWithTimeout(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(30_000L) { block() }
        }
    }
}
