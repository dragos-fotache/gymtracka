# Android documentation

These documents describe the checked-in Android application. Each topic has one primary reference:

| Topic | Reference |
|---|---|
| Features, setup, build configuration | [Project README](../README.md) |
| UI structure, state, domain persistence, schema, timers | [Architecture](architecture.md) |
| Launcher artwork and generated icon assets | [Launcher icons](launcher-icons.md) |
| Sync capabilities and application integration | [Sync overview](sync/README.md) |
| Capture, outbox, canonical storage, snapshots, publication | [Sync storage](sync/storage.md) |
| Push, pull, snapshots, foreground triggers, recovery | [Sync engine](sync/engine.md) |
| Google sign-in, Room/vault authority, credentials, offline recovery | [Authentication and recovery](sync/auth-and-recovery.md) |
| Test source sets, commands, fixtures, CI | [Testing](testing-strategy.md) |
| Unimplemented features, known issues, verification gaps | [Future improvements](future-improvements.md) |
| Exported Room schemas and migration fixtures | [Schema reference](../app/schemas/README.md) |

## Scope

Android source is the authority for current implementation. Feature ideas, unresolved issues, and verification gaps
belong in [future-improvements.md](future-improvements.md). The references above describe stable behavior and constraints.

The nested `shared-docs/` repository owns [product behavior](../shared-docs/docs/product-behavior.md), the
[backend and sync contract](../shared-docs/docs/backend-contract/README.md), and the
[catalogue package](../shared-docs/catalogue/README.md). Local documents link to these references rather than copying
the service specification. Shared requirements do not imply that every Android feature is implemented.

## Implementation plans

Detailed Android work plans live in `implementation/`. Each plan records its approval and implementation status;
planned behavior is not evidence of the checked-in implementation.

- [Settings tab and account/server status](implementation/settings-tab.md) — All four slices complete and verified.
