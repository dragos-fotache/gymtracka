# Room schemas

Version 8 and later are exported by Room during compilation. Versions 1–7 were reconstructed from
the checked-in migration SQL because schema export was enabled only after version 8 already existed.
They are retained so `MigrationTestHelper` can create every supported historical schema.

The version 1 source asset is also copied and migrated in a separate instrumentation test, ensuring
the reconstructed fixture does not replace coverage of the real bundled catalogue database.
