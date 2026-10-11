# R17a — Real personal setup and owner-guided journeys

## Status and authority

**Paused by the owner for independent app testing. R17a is partially verified, not accepted or closed.**
Do not automatically continue guided tests, ask another questionnaire, rotate coordinators, change code/configuration,
build, access the phone/service or clean up resources. Resume from the owner's next report/request, with concise,
batched interactions and fresh permission for any new effects. Earlier reset/installation permissions were consumed;
the phone now contains data. Never infer permission to reset, discard, uninstall, switch accounts or delete cloud data.

The owner pause concerns response latency, not rejection of the testing plan.
The [review](R17a-review.md) owns dated commands, artifact identities, failed/transient witnesses and evidence limits.
The [handoff](../coordinator-handoff.md) owns minimal future pickup. No application/test/schema/shared-contract changes
were made. Checked-in build defaults remain empty. R13a–d remain accepted; no new personal slice is implied.
The finite R17 plan remains a personal, b unattended after R16, c deferred public.

## Verified boundary

Automated operational/package checks and owner-observed phone checks are distinct:

- Private laptop deployment: pinned Java27 runtime and PostgreSQL16 Docker containers, dedicated persistent storage,
  non-admin app database role, non-root/read-only backend, loopback-only HTTP and private Tailscale Serve HTTPS.
  Normal certificate/hostname validation, no redirect/Funnel, container restart with stable keys and protected backups.
- Configured APK: executed assembly/KSP/Kotlin/Java/lint; zero lint errors and two dependency-update warnings. Actual APK
  signing matches Google registration; exact origin/audience, minAPI24/target37, cleartext disabled, vault backup exclusions,
  exact bundled v1 asset and scoped test-helper exclusions audited. No new JVM/instrumentation regression was run.
- Exact owner-approved Pixel10Pro, Android17/API37: fresh GymTracka-only reset/install, actual Google test-account sign-in
  and normal app access; one completed20kg×5 workout, expected100kg, foreground rest timer and manual sync to zero pending.
- Matching offline access after an actual no-wipe process restart; first history retained, second completed10kg×3 workout
  created offline, expected30kg and five queued changes.
- Owner confirms continuously offline beyond the measured16-minute deadline, then mobile data with Wi-Fi off and
  Tailscale connected. Following a transient Delivery paused state, app recovered without another Google sign-in:
  zero pending/no error, both workouts present once with100kg/30kg. Dedicated service containers had restarted while
  offline, retaining data/keys. This is owner-observed timed-expiry/reconnect/session-continuity evidence; the contract's
15-minute access expiry supports refresh inference, but no credential or refresh-packet capture was performed.

**Transient stop retained:** Delivery paused initially persisted after manual sync, then cleared automatically. During
that interval the coordinator only checked time, owned container state and anonymous laptop HTTPS health; no phone
sync command or account/workout/configuration change. Exact cause is unestablished; investigate recurrence rather
than presenting this as a clean first-attempt pass or repair of older auth/activation limitations.

## Current setup and ownership

- Development/build session is on the **desktop**, not the service laptop. Desktop is off Tailscale; laptop/phone are on it.
- Owner-supplied laptop SSH target: `dragos@laptop`. Rediscover live target/resource ownership after explicit resume.
- Service origin: `https://omarchy.tail0dc65e.ts.net`.
- Nonsecret Web/server audience:
  `1008907790562-jij1jafae66tki2c3c2gep0pr4qr12o7.apps.googleusercontent.com`.
  Same value configured in Android and backend. Web localhost JavaScript origin does not restrict native ID-token flow.
- Laptop system Java26 unchanged; desktop builds using Java27, deployed backend uses isolated Java27 runtime.
- Dedicated laptop root: `~/.local/share/gymtracka-personal/`; artifacts, private secrets, backups and deployment manifest.
  Credentials generated once into mode600 files, never printed. Existing `~/.config/gymtracka-backend` files untouched.
- Containers: `gymtracka-personal-backend`, `gymtracka-personal-postgres`; network `gymtracka-personal`;
  persistent volume `gymtracka-personal-pgdata`; ownership label `com.gymtracka.owner=r17a-personal`.
  Backend publishes only127.0.0.1:8080; database publishes no port. Both have `unless-stopped`; Docker already enabled at boot.
  **Persistent owner-approved resources, not disposable fixtures. Leave running; never remove their database/keys.**
- Backend deletion purge disabled per its runbook. No public exposure, global Java/package/SSH/firewall changes,
  host reboot/suspend changes or desktop Tailscale enrollment. Owner locally enabled HTTPS and ran the scoped sudo Serve
  command; agent did not run sudo or grant persistent operator rights.
- Phone remains signed into the separate test account with both test workouts. Exact device/artifact identities are in
  the review; verify again before any newly approved effects. Do not automatically change to a personal account.

## Goal, remaining verification and non-goals

Goal remains one safe personal phone against the private laptop service, with complete account activation, durable
foreground/manual sync, offline/reconnect/restart behavior and precise workout/rest persistence.

Outstanding or deliberately unverified:

- Independent fresh-cache/cloud payload readback (both existing histories are also cached locally). Any new clean-cache
  reset/reinstallation or sign-in requires explicit renewed owner consent; do not obtain credentials from private storage.
- Screen-off timer/wake/vibration observations; exact elapsed-rest bytes were not independently inspected.
- Other actual routines/custom-exercise/edit/recovery journeys and same-account foreground reauthentication as needed.
  Initial fresh installation is not live legacy-import evidence; accepted preservation/import foundations stay intact.
- Host reboot/suspend, practical service/network availability and safe disaster recovery. Container restart is not host
  reboot evidence. Initial isolated backup restore establishes integrity, not deletion-safe live recovery.
- Protected ongoing database backup/retention and owner-controlled secure off-machine stable-key/config custody before
  intended personal data. No automatic backup schedule/pruning or off-machine copy is established.

Background WorkManager/unattended delivery remains R16/R17b. General sign-out/switch/import-choice UI, export/deletion,
release signing/distribution, hosted CI/broader devices and public release remain deferred. No backend implementation,
source/private-config review, Maven-suite assignment, contract change, auth bypass or new TTL/clock/key configuration.
New passing journeys do not retrospectively repair legacy auth replay or the earlier unexplained R12c activation stop.

## Operational procedures after explicit resume

Backend is build/start plus black-box HTTP only. Follow its operating runbook, select/verify the exact source/artifact,
validate pinned shared inputs and use the documented exclusive Maven lock with `-DskipTests package`; do not deploy an
ambiguous dirty checkout or substitute a stale JAR. Preserve existing data/keys; do not rotate to fix unrelated errors.
No routine failure-injection tests against a personal database or phone. Do not print tokens/proofs/private logs.

Android configured build uses only explicit nonsecret Gradle `-Pgymtracka.serviceOrigin` and
`-Pgymtracka.googleServerClientId`. Reserve an exclusive Gradle slot; lint freezes source and tests. Reaudit the actual
APK before a separately authorized install. Build outputs can be overwritten; use review checksums and actual signing,
not filenames, as identity. No silent trust-all/cleartext fallback or personal-config discovery.

Manual backup procedure on the laptop, **only when owner releases operational work**:

```sh
umask 077
backup="$HOME/.local/share/gymtracka-personal/backups/manual-$(date -u +%Y%m%dT%H%M%SZ).dump"
(set -C; docker exec gymtracka-personal-postgres pg_dump -U gtbootstrap -d gymtracka_personal -Fc > "$backup")
```

Check exit status. Keep dumps private; retain at most seven daily copies and none older than seven days, reviewing/removing
only owned files. Never restore an old dump directly into live service: newer deletion/cursor/replay evidence must be
reconciled. Keep stable keys/config securely off-machine under owner control, never in Git/chat/APK.

## Verification and stop condition

This is a security/persistence-sensitive operational/live task, not a new implementation slice. Reuse accepted R13 tests;
no new unit tests by default. Record actual owner observations separately from tool checks and inferred protocol behavior.
No filtered/cached/build-only result substitutes for required actual live gates. Never mark R17a accepted on this partial
record or erase the transient pause. No acceptance commit, push or coordinator rotation is authorized by this owner pause.

If a reported defect requires checked-in code/automation changes, settle bounded scope and use fresh independent Worker/
QA assignments and risk-selected gates under [workflow](../coordinator-workflow.md). Never silently substitute models,
weaken gates, inspect backend source/private configuration or expand destructive scope. Missing permission, unknown
ownership, unavailable required gates, invalid TLS, secret exposure and unresolved destructive choices go to the owner.

## Authoritative references

- [Roadmap](../remaining-work-roadmap.md#r17--live-personal-and-public-journeys), [testing policy](../testing-strategy.md#proportional-coverage-and-verification-gates).
- [Startup](../sync/implementation.md#mandatory-startup-and-account-presentation), [foreground sync](../sync/implementation.md#foreground-synchronization-and-ordinary-recovery).
- [Personal scope](../sync/integration-design.md#personal-scope-boundary), [security](../sync/integration-design.md#9-security-and-stop-boundary).
- [Shared authentication](../../shared-docs/docs/backend-contract/authentication.md), [product behavior](../../shared-docs/docs/product-behavior.md#personal-account-scope).
- Backend operational runbook/limits: `/home/dragos/IdeaProjects/gt-backend/docs/personal-server.md`, `/home/dragos/IdeaProjects/gt-backend/docs/technical-debt.md`.
- [Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve), [certificate publication](https://tailscale.com/docs/how-to/set-up-https-certificates).
