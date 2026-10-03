# Fake retirement coverage map

Status: **RETIRED** — the simulator files listed below were deleted in R3
(2026-10-02): `FakeSyncService.kt`, `FakeAccountSyncApi.kt`,
`FakeSyncFixtures.kt`, and all 12 fake-only command test classes (92 test
methods), plus the `sharedTest` source-set wiring. Final verified counts:
46 real-backend integration tests, 29 offline adapter tests, 23 offline
fixture-guard tests, 10 retained boundary tests; the ordinary offline JVM
suite totals 183 tests with zero failures/errors/skips. No fake or
backend-package classes remain in the ordinary suite.

This document maps every removed test to its transfer destination. At the
original transfer it was the only shared-docs file owned by the R2/R3 slices;
architecture, testing strategy, and status documents remained the coordinator's.
All these Android-only records now live in the Android repository's `docs/`.

Evidence key. Two evidence classes appear below and must not be confused:

- **Current black-box/client proof**: the 46 Android HTTP tests above,
executed green against the isolated real backend. They assert complete
hand-built canonical records through Android's production durable codec and
result decoders, plus opaque-identity, fencing, and no-side-effect checks.
- **Prior backend report evidence**: executed backend-suite method names from
the 2026-10-02 integration evidence (101 methods, zero failures/errors/skips).
These are cited by exact method name for exhaustive server-side edge behavior
not re-proven from Android; their bodies were not re-read in R2/R3 and the
citations record reported execution, not an independent re-verification.

The fake's finish-time rejection rule is superseded by a positive regression:
`client-compatibility.md` explicitly forbids rejecting a finish solely because
the supplied finish time precedes its start after a clock correction
("preserve valid supplied times"), and Android
`finishRemovesUnfinishedAndEmptyExercises` finishes before its start and asserts
the supplied time is preserved.

## New Android coverage (R2, all passing)

| Suite | Tests | What it proves |
|---|---|---|
| `data.sync.backend.RealBackendWorkoutTest` | 11 | Push envelopes (empty/100), start/upsert/finish journeys with complete hand-built records, sole-active replacement, finished immutability, no-completed-set rejection with full-record preservation, finish no-effect matrix (finished/deleted/missing/foreign), ID collision, custom rename label refresh with domain preservation, stale known revision and pre-start finish time accepted |
| `data.sync.backend.RealBackendCloneCommandsTest` | 6 | performAgain with/without prior active (exact 1/2-change batches), startWorkout, saveAsRoutine exact bindings, source preservation, completion/rest clearing, old active replacement, changed-binding and removed-target-ID rejections with no partial effects |
| `data.sync.backend.RealBackendHistoryTest` | 4 | History copy/defaults/order, same-finish tie break by workout ID, foreign/deleted-history fallback to full defaults, `history_shape_changed` with no writes |
| `data.sync.backend.RealBackendDeleteReplaceTest` | 4 | Reference replacement preservation, exercise/set deletion with full expected records, renumbering, last-set entry retention, emptied saved routine staying saved |
| `data.sync.backend.RealBackendRoutineCustomTest` | 5 | Draft-only creation, blank/empty autosave (draft and saved), promotion, blank-save rejection, routine identity reservations (root reuse, removed-child reuse), custom relationship/metadata fidelity with real catalogue names, unknown/bundled/foreign reference fencing |
| `data.sync.backend.RealBackendImportTest` | 4 | Import of all three root types with complete records, finished snapshot/rest fidelity, saved blank routine import, collision/tombstone fencing incl. colliding active import leaving the prior active untouched, dependency order with active survival, referenced-custom delete rejection then success |
| `data.sync.backend.RealBackendIsolationTest` | 3 | Foreign invisibility/immutability without disclosure, pull/snapshot token fencing, server-order last-write-wins with reversed clocks and stale revisions |
| `data.sync.backend.RealBackendRetryTest` | 6 | Accepted/rejected exact retry replay without new writes, `mutation_id_reused` for semantic and whitespace-only byte changes with evidence intact, winner-retry replay while newer winner stands, account-scoped dedup |
| `data.sync.backend.RealBackendPullSnapshotTest` | 3 | Batch-count pagination with indivisible replacement batches, frozen snapshot baselines across intervening commits, type/UUID snapshot order |
| `data.sync.HttpSyncApiTest` (offline) | 29 | Adapter envelope/URL/header/body mapping, fail-closed outcome checks, error mapping incl. saturated retry hints and strict error codes (plain, escaped-duplicate, wrong-type, secret-like), entry-signalled and real-socket cancellation, redacted string forms |
| `data.sync.RealBackendFixtureConfigTest` (offline) | 23 | Pure static-guard validation: explicit path, file/symlink/mode, strict JSON/keys, canonical run ID, loopback root-only origin, disposable-DB-only JDBC, key shape, causeless fixed messages |
| `data.sync.SyncApiBoundaryTest` (updated, offline) | 10 | Same 10 intents repointed at the HTTP adapter with scripted transport (see below) |

Backend citations below refer to executed methods from the 2026-10-02
integration evidence (`/tmp/gymtracka-real-backend/completed-backend-test-evidence.json`):
`PushPreflightTest` 24/24, `RoutineUpsertHttpIntegrationTest` 65/65,
`SyncPullHttpIntegrationTest` 6/6, `SyncSnapshotHttpIntegrationTest` 6/6, zero
failures/errors/skips, XML mtimes 20:43–20:45. As stated in the evidence key
above, these are prior report evidence by exact executed method name, not
re-read or re-verified bodies; mapping pairs them with current Android HTTP
proof where stated.

## Per-method map (92 fake methods)

### FakeSyncServiceTest (7) — simulator internals, no direct replacement

| Fake method | Destination / reason |
|---|---|
| `catalogueReferencesAreExplicit` | Internal: fake seed-map behavior. Real catalogue IDs resolve from the live seed in `RealBackendFixture`; every real-backend test uses them. No dedicated test needed. |
| `ownershipIsTypeScopedAndNeverOverwritten` | Internal map behavior. Observable half: Android `foreignRootsAreInvisibleAndUnchangeable` + backend `aggregateDeleteAndImportNeverDiscloseOrClaimOtherAccountsRootsAndReferences`. |
| `revisionCountersArePerAccount` | Internal counter. Observable revisions asserted in every Android accepted batch + backend revision assertions (e.g. `routineSavePromotesOnlyEligibleOwnedRoutineAndReplaysCanonicalRevision`). |
| `outcomesAreRecordedPerAccount` | Internal outcome map. Observable replay: Android `identicalAcceptedRetryReplaysWithoutNewWrites`, `identicalRejectedRetryReplaysOriginalCode`, `dedupIdentityIsAccountScoped` + backend `accountsHaveIndependentDeduplicationNamespaces`. |
| `appendedBatchesStayInspectableInOrder` | Internal log list. Observable order: Android `pullPagesCompleteBatchesWithReplacementIntact` + backend `limitOneTraversalHasNoGapsOrDuplicatesAndPreservesBatchAtomicity`. |
| `invalidSeededBatchIsRejected` | Internal seeding guard. Structural preflight: backend `PushPreflightTest` (24/24). |
| `adaptersBindAccountsAndRecordCallsSeparately` | Binding intent transferred: `HttpGymSyncApi` binds account via ctor token (offline `HttpSyncApiTest` bearer assertions); opaque recording kept in `SyncApiBoundaryTest` pull/snapshot tests. |

### FakeWorkoutCommandTest (12)

| Fake method | Destination |
|---|---|
| `start_createsExactlyEmptyActiveWithIndependentExpectedRecord` | Android `startCreatesExactlyEmptyActive` |
| `start_replacesPriorActiveAtomicallyDeleteFirst` | Android `secondStartReplacesActiveDeleteFirst` + backend `workoutStartAtomicallyReplacesActiveWithOrderedDeleteAndUpsertBatch`, `concurrentWorkoutStartsSerializeAndLeaveOneActiveRoot` |
| `start_rejectsEveryCollisionWithoutEffects` | Android `startRejectsIdCollisionWithoutEffects` + backend `concurrentCreateOnlyImportsForSameRootSerializeToOneWinner`, `workoutIdentityReservationsAllowSameParentRestoreButRejectReparentAndKeepFamiliesIndependent` |
| `upsert_replacesCompleteAggregateWithResolvedNamesNullsAndOrder` | Android `upsertReplacesCompleteAggregateWithResolvedNames` + backend `workoutUpsertReplacesActiveAggregateAndFinishCleansOnlyAfterCompletedSet` |
| `upsert_resolvesReferenceByExerciseIdNotEntryId` | Backend `invalidNestedReferenceRejectsWithoutAnyDomainOrBatchWritesAndLaterMutationCommits` |
| `upsert_rejectsIneligibleSourcesWithoutEffects` | Android `upsertRejectsFinishedHistoryWithoutEffects` + backend `newKindsRejectInvalidDomainWritesWithoutChangingRootsOrRevisions` |
| `upsert_rejectsCrossOwnerCustomReference` | Android `workoutUpsertRejectsForeignCustomReference` + backend `workoutAndRoutineForeignCustomReferencesAndWorkoutTargetGuardsAreNonDisclosing` |
| `upsert_rejectsChildCollisionAndReparenting` | Backend `workoutIdentityReservationsAllowSameParentRestoreButRejectReparentAndKeepFamiliesIndependent`, `workoutRootIdCannotClaimAnIdReservedForAnExistingWorkoutChild` |
| `upsert_removedChildIdsStayReservedForThisRoot` | Android `performAgainRejectsRemovedChildTargetIds` (removed workout child ID as semantic clone target → `id_collision`) + backend `workoutIdentityReservationsAllowSameParentRestoreButRejectReparentAndKeepFamiliesIndependent`, `addExercisesRejectsShapeAndReferenceErrorsAndHonorsRemovedChildReservations` (observable reservation behavior, not internals) |
| `upsert_laterCommitWinsDespiteReversedClocksAndStaleKnownRevision` | Android `laterServerCommitWinsDespiteReversedClocks` |
| `push_executesMutationsInOrderWithPerMutationAtomicity` | Android `hundredRequestPushExecutesInOrder` + backend `supportedMixedPushContinuesAfterDomainRejection`, `orderedRoutineBatchContinuesAfterDurableRejectionAndReplaysItAfterStateChanges` |
| `upsert_preservesStableIdsAcrossFullReplacement` | Android `upsertReplacesCompleteAggregateWithResolvedNames` (stable IDs in the expected record) + backend `workoutUpsertReplacesActiveAggregateAndFinishCleansOnlyAfterCompletedSet` |

### FakeWorkoutFinishCommandTest (6)

| Fake method | Destination |
|---|---|
| `finish_removesUnfinishedAndEmptyExercisesAndRenumbersSurvivors` | Android `finishRemovesUnfinishedAndEmptyExercises` (incl. stale `knownServerRevision` acceptance) + backend `finishPrunesUnfinishedRowsAndRenumbersMultipleSurvivingEntriesAndSets`, `workoutUpsertReplacesActiveAggregateAndFinishCleansOnlyAfterCompletedSet` |
| `finish_acceptsDespiteStaleKnownRevision` | Folded into Android `finishRemovesUnfinishedAndEmptyExercises` (`knownServerRevision = 1L`) |
| `finish_rejectsWithoutCompletedSets` | Android `finishRejectsWithoutCompletedSets` + backend `workoutUpsertReplacesActiveAggregateAndFinishCleansOnlyAfterCompletedSet` |
| `finish_rejectsFinishTimeBeforeStart` | **Superseded by positive regression.** `client-compatibility.md` explicitly forbids rejecting a finish solely because the supplied finish time precedes its start after a clock correction ("preserve valid supplied times"). Android `finishRemovesUnfinishedAndEmptyExercises` now finishes before its start and asserts the supplied time is preserved; the service accepts. The fake's `INVALID_FINISH_TIME` had no contract basis. |
| `finish_rejectsFinishedDeletedForeignAndMissingTargets` | Android `finishRejectsIneligibleTargetsWithoutEffects` (finished→`invalid_state`; deleted/missing/foreign→`not_found`, records and pull tails unchanged) + backend `childMutationsRejectFinishedMissingForeignTombstonedRootsAndCrossRootOrRemovedChildren`, `aggregateDeleteAndImportNeverDiscloseOrClaimOtherAccountsRootsAndReferences` |
| `finish_keepsChildReservationsAgainstReparenting` | Backend `workoutIdentityReservationsAllowSameParentRestoreButRejectReparentAndKeepFamiliesIndependent` |

### FakeWorkoutAddExercisesCommandTest (9)

| Fake method | Destination |
|---|---|
| `addExercises_copiesHistoryRestAndInputsAndAppendsInOrder` | Android `addExercisesCopiesHistoryRestAndInputsInOrder` |
| `addExercises_selectsNewestFinishedThenHighestWorkoutId` | Android `addExercisesBreaksSameFinishTieByWorkoutId` + backend `addExercisesUsesDeterministicFinishedHistoryAndFallbackAndReplaysOriginalBatch` |
| `addExercises_selectsFirstPositionAndPairsRestWithItsSets` | Backend `addExercisesUsesDeterministicFinishedHistoryAndFallbackAndReplaysOriginalBatch` |
| `addExercises_ignoresForeignAndDeletedHistory` | Android `addExercisesIgnoresForeignAndDeletedHistory` (foreign finished rows invisible, deleted history falls back to full defaults, foreign state unchanged) + backend `rejectedMissingChildIsReplayedAfterItBecomesPresentAndForeignOrTombstonedExerciseDoesNotMutate` |
| `addExercises_withoutHistoryCreatesOneEmptySetAndDefaultRest` | Android `addExercisesCopiesHistoryRestAndInputsInOrder` (no-history addition) + backend `addExercisesUsesDeterministicFinishedHistoryAndFallbackAndReplaysOriginalBatch` |
| `addExercises_rejectsMissingForeignDeletedAndFinishedTargets` | Backend `childMutationsRejectFinishedMissingForeignTombstonedRootsAndCrossRootOrRemovedChildren` |
| `addExercises_rejectsInvisibleExerciseReferences` | Backend `invalidNestedReferenceRejectsWithoutAnyDomainOrBatchWritesAndLaterMutationCommits` |
| `addExercises_rejectsChangedHistoryShape` | Android `addExercisesRejectsChangedHistoryShapeWithoutWrites` + backend `addExercisesRejectsShapeAndReferenceErrorsAndHonorsRemovedChildReservations`, `addExercisesRejectsWhenFinishedHistoryShapeChangesAfterPlanningWithoutEffects` |
| `addExercises_rejectsCollidingIdsAndKeepsBatchAtomic` | Backend `addExercisesRejectsShapeAndReferenceErrorsAndHonorsRemovedChildReservations`, `addExercisesFaultRollsBackRowsRevisionBatchAndReplayEvidence` |

### FakeWorkoutReplaceExerciseCommandTest (4)

| Fake method | Destination |
|---|---|
| `replaceExercise_changesOnlyReferenceAndNamePreservingEverything` | Android `replaceExercisePreservesEntryData` + backend `workoutReplacePreservesEntryAndAllSetValuesAndUpdatesCanonicalNameOnly` |
| `replaceExercise_resolvesOwnedCustomName` | Android `replaceExercisePreservesEntryData` (bundled name) + `customRenameRefreshesWorkoutReferenceName` (owned custom name) + backend `workoutReplacePreservesEntryAndAllSetValuesAndUpdatesCanonicalNameOnly` |
| `replaceExercise_rejectsBadTargetsAndReferencesWithoutStateChange` | Backend `childMutationsRejectFinishedMissingForeignTombstonedRootsAndCrossRootOrRemovedChildren`, `invalidNestedReferenceRejectsWithoutAnyDomainOrBatchWritesAndLaterMutationCommits` |
| `replaceExercise_rejectsDeletedRootWithoutStateChange` | Backend `childMutationsRejectFinishedMissingForeignTombstonedRootsAndCrossRootOrRemovedChildren` |

### FakeWorkoutDeleteCommandTest (7)

| Fake method | Destination |
|---|---|
| `deleteExercise_removesMiddleEntryCascadesSetsAndRenumbers` | Android `workoutDeleteExerciseRenumbersSurvivors` + backend `childDeleteCommandsRenumberFirstMiddleLastAndRetainFinalSetEntryOverHttp` |
| `deleteExercise_firstThenLastLeavesSingleRenumberedSurvivor` | Backend `childDeleteCommandsRenumberFirstMiddleLastAndRetainFinalSetEntryOverHttp` |
| `deleteSet_removesMiddleThenLastSetAndRenumbers` | Android `workoutDeleteSetRetainsEntryOnLastSet` + backend `childDeleteCommandsRenumberFirstMiddleLastAndRetainFinalSetEntryOverHttp` |
| `deleteSet_retainsExerciseWhenLastSetRemovedThenDeletesFirstSet` | Android `workoutDeleteSetRetainsEntryOnLastSet` |
| `deleteCommands_rejectBadTargetsWithoutStateChange` | Backend `childMutationsRejectFinishedMissingForeignTombstonedRootsAndCrossRootOrRemovedChildren` |
| `deleteExercise_rejectsDeletedRootWithoutStateChange` | Backend `childMutationsRejectFinishedMissingForeignTombstonedRootsAndCrossRootOrRemovedChildren` |
| `deleteExercise_retainsRemovedIdsAsCollisionEvidence` | Android `performAgainRejectsRemovedChildTargetIds` (removed workout child ID as clone target) + backend `workoutIdentityReservationsAllowSameParentRestoreButRejectReparentAndKeepFamiliesIndependent`, `cloneBindingsRequireCompleteOrderedSourceAndSemanticCopyCannotReuseRemovedChildIds` (observable reservation behavior, not internals) |

### FakePerformAgainCommandTest (5)

| Fake method | Destination |
|---|---|
| `performAgain_clonesFinishedSourceAndReplacesActiveAtomically` | Android `performAgainClonesFinishedSourceAndReplacesActive` + backend `performAgainCopiesFinishedWorkoutAndAtomicallyReplacesActiveWithCanonicalOrderedBatch`, `concurrentIdenticalPerformAgainCreatesOneCopyAndReplaysSameCanonicalBatch` |
| `performAgain_withoutPriorActive_emitsSingleUpsert` | Android `performAgainWithoutPriorActiveEmitsSingleUpsert` (parameterized journey asserting the exact single-UPSERT batch) |
| `performAgain_rejectsUnfinishedMissingDeletedAndForeignSources` | Backend `performAgainRejectsMissingIneligibleAndChangedSourceShapeWithoutEffect`, `childMutationsRejectFinishedMissingForeignTombstonedRootsAndCrossRootOrRemovedChildren` |
| `performAgain_rejectsBindingMismatches` | Android `changedSourceBindingRejectsWithoutPartialEffects` + backend `performAgainRejectsMissingIneligibleAndChangedSourceShapeWithoutEffect`, `cloneBindingsRequireCompleteOrderedSourceAndSemanticCopyCannotReuseRemovedChildIds` |
| `performAgain_rejectsCollidingAndReusedIdsThenAcceptsCleanRetry` | Backend `cloneBindingsRequireCompleteOrderedSourceAndSemanticCopyCannotReuseRemovedChildIds`, `concurrentIdenticalPerformAgainCreatesOneCopyAndReplaysSameCanonicalBatch` + Android `changedBytesUnderSameIdRejectWithEvidenceIntact` (ID-reuse mechanism) |

### FakeRoutineStartWorkoutCommandTest (6)

| Fake method | Destination |
|---|---|
| `startWorkout_startsFromSavedRoutineAndReplacesActiveAtomically` | Android `startWorkoutCopiesSavedRoutineWithoutTouchingSource` + backend `startWorkoutFromSavedRoutineCopiesCanonicalTemplateAndReplacesActiveInOrder` |
| `startWorkout_withoutPriorActive_emitsSingleUpsert` | Android `startWorkoutCopiesSavedRoutineWithoutTouchingSource` (no prior active; single UPSERT asserted) |
| `startWorkout_rejectsDraftAndInvalidSavedSources` | Backend `routineStartRejectsMissingForeignDraftShapeAndReservedIdsWithoutMutation` |
| `startWorkout_rejectsMissingDeletedForeignAndChangedSources` | Backend `routineStartRejectsMissingForeignDraftShapeAndReservedIdsWithoutMutation`, `childMutationsRejectFinishedMissingForeignTombstonedRootsAndCrossRootOrRemovedChildren` |
| `startWorkout_rejectsBindingMismatches` | Backend `routineStartRejectsMissingForeignDraftShapeAndReservedIdsWithoutMutation`, `cloneBindingsRequireCompleteOrderedSourceAndSemanticCopyCannotReuseRemovedChildIds` (+ Android `changedSourceBindingRejectsWithoutPartialEffects`, same mechanism) |
| `startWorkout_rejectsCollidingAndReusedIdsThenAcceptsCleanRetry` | Backend `routineStartRejectsMissingForeignDraftShapeAndReservedIdsWithoutMutation`, `cloneBindingsRequireCompleteOrderedSourceAndSemanticCopyCannotReuseRemovedChildIds` |

### FakeSaveAsRoutineCommandTest (5)

| Fake method | Destination |
|---|---|
| `saveAsRoutine_copiesFinishedSourceWithoutTouchingActiveOrSource` | Android `saveAsRoutineCopiesFinishedSourceWithoutTouchingActive` + backend `saveAsRoutineCopiesFinishedTemplateWithoutChangingSourceOrActiveWorkout` |
| `saveAsRoutine_rejectsBlankName` | Backend `saveAsRoutineRejectsInvalidSourcesBindingsNamesAndReservedIdsWithoutEffects` |
| `saveAsRoutine_rejectsUnfinishedMissingDeletedAndForeignSources` | Backend `saveAsRoutineRejectsInvalidSourcesBindingsNamesAndReservedIdsWithoutEffects` |
| `saveAsRoutine_rejectsBindingMismatches` | Backend `saveAsRoutineRejectsInvalidSourcesBindingsNamesAndReservedIdsWithoutEffects`, `cloneBindingsRequireCompleteOrderedSourceAndSemanticCopyCannotReuseRemovedChildIds` |
| `saveAsRoutine_rejectsCollidingAndReusedIdsThenAcceptsCleanRetry` | Backend `saveAsRoutineRejectsInvalidSourcesBindingsNamesAndReservedIdsWithoutEffects` |

### FakeRoutineCustomCommandTest (10)

| Fake method | Destination |
|---|---|
| `routineUpsert_createsDraftOnlyAndRejectsNonDraftCreate` | Android `routineDraftAutosaveAndPromotionSemantics` (draft creation) + backend `routineUpsertIsAtomicReplayableAndRoutineTransitionsAreEnforced` |
| `routineUpsert_autosavesDraftAndSavedIncludingBlankEmptySaved` | Android `routineDraftAutosaveAndPromotionSemantics` (draft autosave + saved blank autosave) + backend `routineUpsertIsAtomicReplayableAndRoutineTransitionsAreEnforced` |
| `routineUpsert_rejectsDemotionAndBadTargets` | Backend `routineUpsertIsAtomicReplayableAndRoutineTransitionsAreEnforced`, `newKindsRejectInvalidDomainWritesWithoutChangingRootsOrRevisions` |
| `routineUpsert_rejectsRootReuseByChildAndRegistryCollision` | Android `routineIdentityReservationsRejectReuseAndRemovedTargets` (routine root claiming a live entry ID; removed child ID reused in a create) + backend `workoutRootIdCannotClaimAnIdReservedForAnExistingWorkoutChild`, `routineUpsertIsAtomicReplayableAndRoutineTransitionsAreEnforced` (+ Android `startRejectsIdCollisionWithoutEffects`, same mechanism) |
| `routineSave_promotesWithSuppliedName` | Android `routineDraftAutosaveAndPromotionSemantics` + backend `routineSavePromotesOnlyEligibleOwnedRoutineAndReplaysCanonicalRevision` |
| `routineSave_refreshesCanonicalNamesAfterCustomRename` | Android `customRenameRefreshesWorkoutReferenceName` + backend `importAndCopyReplayKeepOriginalExerciseLabelsAcrossRenameAndLaterAutosave`, `customRelationshipReplacementPreservesNullableQuantityAndDoesNotReviseReferencingRoots` |
| `routineSave_rejectsInvalidPromotionAndBadTargets` | Android `routineDraftAutosaveAndPromotionSemantics` (blank-save rejection) + backend `routineSavePromotesOnlyEligibleOwnedRoutineAndReplaysCanonicalRevision` |
| `customUpsert_createsWithResolvedRelationshipsAndExactMetadata` | Android `customUpsertPreservesRelationshipsAndMetadata` + backend `customExerciseUpsertCreatesAndReplacesPrivateAggregateWithReplayBatch`, `customExerciseImportsPreserveOptionalMetadataNullEmptyAndZeroDistinctions` |
| `customUpsert_updatesAndReplacesRelationshipsExactly` | Backend `customExerciseUpsertCreatesAndReplacesPrivateAggregateWithReplayBatch`, `customRelationshipReplacementPreservesNullableQuantityAndDoesNotReviseReferencingRoots` (+ Android rename update path) |
| `customUpsert_rejectsIneligibleTargetsAndUnknownRefs` | Android `customUpsertRejectsUnknownAndBundledReferences` + backend `bundledExerciseMutationIsReadOnlyAndHasNoPersistentEffects` |

### FakeRoutineDeleteCommandTest (8)

| Fake method | Destination |
|---|---|
| `deleteExercise_removesMiddleEntryCascadesSetsAndRenumbers` | Android `routineDeletesRenumberAndEmptySavedStaysSaved` + backend `childDeleteCommandsRenumberFirstMiddleLastAndRetainFinalSetEntryOverHttp` |
| `deleteExercise_savedRoutineStaysSavedThroughFirstAndLastDeletion` | Android `routineDeletesRenumberAndEmptySavedStaysSaved` + backend `routineSetDeleteKeepsSavedStateAndRejectedRootChildReferenceAndExerciseAreNoEffect` |
| `deleteExercise_savedRoutineStaysSavedWhenEmptied` | Android `routineDeletesRenumberAndEmptySavedStaysSaved` |
| `deleteSet_removesMiddleThenLastSetAndRenumbers` | Android `routineDeletesRenumberAndEmptySavedStaysSaved` + backend `childDeleteCommandsRenumberFirstMiddleLastAndRetainFinalSetEntryOverHttp` |
| `deleteSet_retainsExerciseWhenLastSetRemoved` | Android `routineDeletesRenumberAndEmptySavedStaysSaved` |
| `deleteCommands_rejectBadTargetsWithoutStateChange` | Backend `childMutationsRejectFinishedMissingForeignTombstonedRootsAndCrossRootOrRemovedChildren`, `routineSetDeleteKeepsSavedStateAndRejectedRootChildReferenceAndExerciseAreNoEffect` |
| `deleteCommands_rejectDeletedRootWithoutStateChange` | Backend `childMutationsRejectFinishedMissingForeignTombstonedRootsAndCrossRootOrRemovedChildren` |
| `deleteExercise_retainsRemovedIdsAsCollisionEvidence` | Android `routineIdentityReservationsRejectReuseAndRemovedTargets` (removed routine child ID reused in a create) + backend `workoutIdentityReservationsAllowSameParentRestoreButRejectReparentAndKeepFamiliesIndependent`, `cloneBindingsRequireCompleteOrderedSourceAndSemanticCopyCannotReuseRemovedChildIds` (observable reservation behavior, not internals) |

### FakeImportDeleteCommandTest (13)

| Fake method | Destination |
|---|---|
| `import_createsAllThreeTypesWithIndependentExpectedRecords` | Android `importCreatesEveryRootTypeWithSnapshotFidelity` + backend `aggregateImportsAndDeletesCoverAllRootKindsAndTombstoneReplay`, `customExerciseImportsPreserveOptionalMetadataNullEmptyAndZeroDistinctions` |
| `import_preservesSavedSnapshotIncludingBlankEmpty` | Android `importCreatesEveryRootTypeWithSnapshotFidelity` (blank saved routine) |
| `import_rejectsEveryCollisionWithoutEffects` | Android `importNeverOverwritesLiveOrTombstonedRoots`, `importRejectsCollisionsAndMissingDependencies` + backend `concurrentCreateOnlyImportsForSameRootSerializeToOneWinner` |
| `import_activeReplacesPriorActiveWhileFinishedDoesNot` | Backend `importedFinishedWorkoutPreservesNestedSnapshotAndActiveImportReplacesInOrderedBatch` |
| `import_rejectedActiveImportLeavesPriorActiveUntouched` | Android `importNeverOverwritesLiveOrTombstonedRoots` (colliding active-import half: `id_collision`, prior active byte-identical, no new batches) |
| `import_rejectsUnknownReferenceAndMissingCustomDep` | Android `importRejectsCollisionsAndMissingDependencies` + backend `importsRespectDependencyOrderDeletedReferencesAndChildReservations` |
| `delete_removesLiveRootWithTombstoneEvidenceAndClearsActive` | Backend `aggregateImportsAndDeletesCoverAllRootKindsAndTombstoneReplay`, `deleteMissingRootIsDurablyRejectedAndExactRawInnerBytesAreCompared` |
| `delete_reDeleteEmitsNewDeletionBatchForEveryType` | Backend `aggregateImportsAndDeletesCoverAllRootKindsAndTombstoneReplay` |
| `delete_rejectsMissingAndForeignIdenticallyWithoutDisclosure` | Android `foreignRootsAreInvisibleAndUnchangeable` + backend `aggregateDeleteAndImportNeverDiscloseOrClaimOtherAccountsRootsAndReferences`, `deleteMissingRootIsDurablyRejectedAndExactRawInnerBytesAreCompared` |
| `delete_customReferencedBlocksUntilParentsDeleted` | Android `deleteRejectsReferencedCustomThenSucceeds` + backend `customExerciseInUseByAllLiveAggregateStatesCannotBeDeleted` |
| `implementedDeleteReplaceKinds_rejectUnknownRootsWithoutEffectsAndReadsStillThrow` | Rejections: backend `childMutationsRejectFinishedMissingForeignTombstonedRootsAndCrossRootOrRemovedChildren`. The "reads still throw" half pinned fake pull/snapshot stubs — superseded by real pull/snapshot suites; no replacement. |
| `accountIsolation_scopesReadsWritesAndTypeScopedRoots` | Android `foreignRootsAreInvisibleAndUnchangeable`, `pullAndSnapshotTokensAreAccountFenced` + backend `aggregateDeleteAndImportNeverDiscloseOrClaimOtherAccountsRootsAndReferences` |
| `storedState_isDetachedFromCallerMutationsAndReturnedValues` | **No replacement — simulator-internal.** Defensive-copy behavior of in-memory maps; no HTTP equivalent exists. |

## SyncApiBoundaryTest destinations (10, all retained and repointed)

| Test | Destination (same file, HTTP adapter + scripted transport) |
|---|---|
| `pushAcceptsEmptyBatchEnvelope` | Kept: asserts empty body over the wire and empty results |
| `pushAcceptsOneHundredDistinctValidRequests` | Kept: 100 valid envelopes reach transport; ordered outcomes |
| `pushRejectsOverOneHundredDistinctValidRequests` | Kept: client-side rejection, transport untouched |
| `pushRejectsDuplicateMutationIds` | Kept: client-side rejection, transport untouched |
| `pushRejectsMalformedWrapper` | Kept: text-mismatch rejection, transport untouched |
| `pageLimitsMatchOpenApiBounds` | Kept unchanged (pure production constants) |
| `opaquePullArgumentsAreRecordedVerbatim` | Kept: exact GET path/query assertions on the recorded call |
| `opaqueSnapshotArgumentsAreRecordedVerbatim` | Kept: exact GET path/query assertions on the recorded call |
| `cancellationPropagatesWithoutConversion` | Kept unchanged |
| `failuresUseFixedSafeTextAndBoundedHints` | Kept; rejection code updated from the fake's `owned_root_missing` to the contract's `not_found` |

## Explicitly not transferred

- Fake-only rules with no contract basis: `storedState_isDetachedFromCallerMutationsAndReturnedValues`
  (simulator defensive copies), fake seed/ownership/counter/log internals (7
  `FakeSyncServiceTest` methods, observable halves mapped above). The fake's
  finish-time rejection is superseded by the contract-explicit positive
  regression described above, not deleted silently.
- Fake pull/snapshot stub behavior (throws): superseded by real pull/snapshot
  suites on both sides.
- Original-result deduplication across restarts, 90-day pruning expiry, and
  process-crash races: backend-covered (`durableReplaySurvivesExecutorReplacementAfterLostHttpResponse`,
  `retryAfterRealHttpProcessRestartReplaysOriginalBatchAndCursor`,
  `childCommandDatabaseFaultAfterRowMutationRollsBackRootRevisionBatchAndEvidence`,
  pruning suites); not re-proven from Android in R2.
- Room convergence, remote name refresh into cache, sync engine, account
  lifecycle: future production slices, not claimed.

Counts need not remain 223: useful coverage, not numeric preservation, is the
goal. 92 deleted fake methods map to 46 current Android HTTP tests +
retained/updated boundary tests + cited executed backend methods as above.
The ordinary offline suite now totals 183 tests (275 − 92).
