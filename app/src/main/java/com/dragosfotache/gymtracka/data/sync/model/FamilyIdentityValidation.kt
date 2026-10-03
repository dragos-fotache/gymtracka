package com.dragosfotache.gymtracka.data.sync.model

/**
 * Shared family-identity checks for workout/routine aggregates and clone/copy plans.
 *
 * Within one family (`workout` or `routine`), the root, entry and set UUIDs share a
 * single identity-reservation namespace: every member must be distinct across all
 * roles and parents. The workout and routine namespaces are independent, so equal
 * UUIDs across the two families stay representable. Catalogue `exerciseId`
 * references and `mutationId` values live in independent namespaces and never take
 * part in these checks. Failures use fixed context text without echoing UUIDs;
 * IDs are never generated or repaired here.
 */

/**
 * Rejects any aliasing within one full aggregate family: root versus entry, root
 * versus set, entry versus set (same or different entry), duplicate entries, or
 * duplicate sets within/across entries.
 */
internal fun requireAggregateFamilyDistinct(
    rootId: String,
    entryIds: List<String>,
    setIds: List<String>,
    where: String,
) {
    val all = ArrayList<String>(1 + entryIds.size + setIds.size)
    all.add(rootId)
    all.addAll(entryIds)
    all.addAll(setIds)
    if (all.toSet().size != all.size) {
        throw SyncModelException("$where has duplicate family IDs")
    }
}

/**
 * Rejects any aliasing within one copy-command source family: the source root
 * versus a source child, a source entry versus a source set (same or different
 * binding), or duplicated source children across bindings. Callers keep their
 * existing role-specific duplicate messages first; this catches the remaining
 * cross-role and source-root collisions.
 */
internal fun requireCopySourceFamilyDistinct(
    sourceRootId: String,
    sourceExercises: List<String>,
    sourceSets: List<String>,
    where: String,
) {
    val all = ArrayList<String>(1 + sourceExercises.size + sourceSets.size)
    all.add(sourceRootId)
    all.addAll(sourceExercises)
    all.addAll(sourceSets)
    if (all.toSet().size != all.size) {
        throw SyncModelException("$where has duplicate source IDs")
    }
}

/**
 * Rejects ANY UUID shared between the source family (source root plus every source
 * child) and the target family (new root plus every target child), across all
 * roles: root/children and off-diagonal entry/set overlaps alike. Used only for
 * same-family copies; cross-family commands skip it so independent namespaces may
 * overlap when each side remains internally valid.
 */
internal fun requireNoSameFamilyOverlap(
    sourceRootId: String,
    sourceExercises: List<String>,
    sourceSets: List<String>,
    targetRootId: String,
    targetExercises: List<String>,
    targetSets: List<String>,
    where: String,
) {
    val source = HashSet<String>(1 + sourceExercises.size + sourceSets.size)
    source.add(sourceRootId)
    source.addAll(sourceExercises)
    source.addAll(sourceSets)
    if (targetRootId in source ||
        targetExercises.any { it in source } ||
        targetSets.any { it in source }
    ) {
        throw SyncModelException("$where target reuses a source ID")
    }
}
