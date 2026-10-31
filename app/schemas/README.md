# Room schemas

The current runtime schema is **15**. Snapshots 1–15 live under
`com.dragosfotache.gymtracka.data.gym.GymDatabase/`.

Versions 1–7 are migration fixtures reconstructed from the checked-in SQL. Versions 8–15 are Room schema exports.
`MigrationTestHelper` uses them to create each supported starting schema.

[GymDatabaseMigrationTest](../src/androidTest/java/com/dragosfotache/gymtracka/data/gym/GymDatabaseMigrationTest.kt)
checks every version 1–14 through 15 and separately copies/migrates the real bundled version-1 catalogue asset.
That asset test keeps reconstructed fixtures from substituting for source-database coverage.
The 14→15 migration preserves existing outbox bytes and deadlines and defaults their retry cause
to unknown, so an older service-requested wait cannot become resettable.

[GymDatabase](../src/main/java/com/dragosfotache/gymtracka/data/gym/GymDatabase.kt) registers the data-preserving
migrations. KSP exports to this directory during compilation; destructive migration fallback is not configured.
See the [migration table](../../docs/architecture.md#room-database-and-migrations) for each schema change and
the [testing reference](../../docs/testing-strategy.md) for commands.
