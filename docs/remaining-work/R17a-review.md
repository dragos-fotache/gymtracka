# R17a — In-progress operational/live evidence

**Paused by owner; not accepted/closed.** Laptop/APK and scoped owner-observed Google/workout/offline/mobile recovery
checks passed. Other live/readback/operational gates remain. The transient delivery pause is preserved, not erased.
This is dated evidence, not a grant for additional effects or a backend source/test audit.
The [specification](R17a-task.md) owns scope and [handoff](../coordinator-handoff.md) owns current pickup.

## 2026-10-10 — Approved laptop setup

Owner approvals: current committed backend state; dedicated laptop Java27/PostgreSQL16 container setup, protected new
credentials, private Tailscale Serve/HTTPS with certificate-name publication, test-only operational restart/backup checks.
Phone reset/installation/account actions are not released. Desktop remains off Tailscale; laptop and phone are on it.

### Build input and artifact

Filename/status-only Git verification, without backend source/diff review:

- Backend and nested shared-docs working trees/indexes clean.
- Backend commit: `1d20cec9cf5c5465d52a2ca90a472f83dbf527f1`.
- Backend shared-docs commit: `f65abb82512c373ade9ab08699ef68971b2e8ceb`.
- Documented `sha256sum -c docs/shared-docs-inputs.sha256`: all entries OK.
- Desktop Java27; laptop Java26. Used isolated Java27 runtime rather than changing laptop system Java.
- Exclusive `flock -n /tmp/gt-backend-task7-build.lock` build: `./mvnw -q -DskipTests package`, exit0.
  Checked selected identities/clean state before and after. This is packaging, not a Maven-suite pass or clean-rebuild claim.
  Maven emitted Jansi/native-access, Unsafe/final-field warnings, not build errors.
- JAR SHA256: `41687b8ca223991aed4f6a88ee7cf8cb360ea2d2865cfcd867e157083489a369`.
  Transfer verified the identical checksum and mode400 on the laptop.

Resolved amd64 official image identities:

- `eclipse-temurin@sha256:387922dd341a11a06f39375e6b13f3cb865e09aa409789cd4f7a5a6577cd2290`.
- `postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea`.

### Operational setup and executed checks

Strict, noninteractive SSH used the owner-provided laptop target. Preflight: exact host/architecture, available port8080,
no conflicting deployment path/container/network/volume names, Docker29.7.2 accessible/active and already enabled at boot,
410GiB available home filesystem, Tailscale1.102.3 online and no existing Serve configuration.

New dedicated root: `~/.local/share/gymtracka-personal/`, with private artifacts/secrets/backups directories.
Independent stable database/auth/ledger secrets generated remotely into new mode600 files; values never printed.
Existing `~/.config/gymtracka-backend` files were not accessed on either host. Supplied Web audience configured explicitly;
actual Google acceptance is not established by configuration. Deletion purge set false per the operating runbook.

Dedicated resources carry `com.gymtracka.owner=r17a-personal`:

- Network `gymtracka-personal`; persistent volume `gymtracka-personal-pgdata`.
- PostgreSQL container `gymtracka-personal-postgres`, no published port; separate bootstrap/admin credentials and
  `gymtracka_personal` database owned by `gtapp`. SQL initialization/role check confirmed app role is neither superuser,
  role creator nor database creator. No pre-existing host database used as a fixture.
- Backend container `gymtracka-personal-backend`, user1000:1000, read-only root filesystem, all capabilities dropped,
  `no-new-privileges`, no privileged/host-network/socket mount. Only `127.0.0.1:8080` published on the host.
- Both dedicated containers have `unless-stopped` restart policy and local bounded-size logs. This is not evidence of
  backend-wide log redaction or time-based retention enforcement. No logs/bodies containing credentials were dumped.

Python-controlled HTTP smoke: POST `{}` to loopback `/v1/auth/google/challenge`, HTTP200, required UUID/nonblank nonce
and UTC expiry within the expected five-minute window. Challenge/proof values not displayed. This creates a short-lived
challenge, not a signed-in account, successful refresh, sync, complete snapshot or workout.

Approved restart of only the two dedicated containers: changed start timestamps, PostgreSQL/backend readiness and a
second valid challenge HTTP200; protected key/config files byte-identical. Host reboot/suspend and personal workout/session
continuity were not tested.

Initial PostgreSQL custom-format dump: `initial-20261010T125958Z.dump`, 305073bytes, mode600, protected laptop backup directory.
`pg_restore --list` succeeded; `pg_restore --exit-on-error --no-owner --no-acl` restored into a new PostgreSQL16 scratch
container with network `none`, tmpfs data and no published port. Owned scratch removed; live database/volume retained.
This is backup integrity, not safe live disaster restore, account/deletion reconciliation, off-machine backup/key custody
or seven-day automated backup operation. No personal workout data was used for failure injection.

### HTTPS stop and residual gates

Approved `tailscale serve --bg --https=443 http://127.0.0.1:8080` was bounded by a25-second subprocess timeout. It timed out;
post-command Serve JSON was empty. No blind retry or sudo. Read-only follow-up: MagicDNS true, exact node HTTPS capability
absent, and `tailscale serve status` reports no Serve config. A broad preliminary capability-string check was not a valid
HTTPS oracle and is not counted; the exact capability check supersedes it. No trusted HTTPS/publication success is claimed.
Owner admin DNS HTTPS enablement is the next checkpoint. Do not enable Funnel; preserve unrelated tailnet settings.

Still required:

- Actual private HTTPS configuration/certificate validation and phone LAN/away reachability.
- Configured Android build/APK signing/configuration/security checks and separately approved exact-device installation/reset.
- Actual Google/provider/service identity, complete activation, refresh/reauthentication and owner-guided app/sync/offline/
  reconnect/workout/timer observations. No phone/account effect has occurred.
- Protected ongoing database backup procedure/retention and owner-controlled secure off-machine stable-key/config custody.
- Honest laptop/network availability and safe recovery limits; no unattended/public/backend-audit claims.

Android source/tests and shared-docs unchanged. No Gradle/device gates, backend Maven suite, public deployment, global Java
change, host service restart, SSH/firewall/ACL change, desktop Tailscale enrollment or existing-data reset was performed.
Owning documentation whitespace/link checks passed; the slice remains open.

## 2026-10-10 — HTTPS enablement and permission stop

The owner enabled tailnet HTTPS. A follow-up attempted preflight with the assumed capability identifier
`https://tailscale.com/cap/https`; it was absent, so no configuration retry occurred in that attempt. This identifier
was an invalid oracle for the installed client. Bounded read-only capability-name inspection found the actual `https`
capability present in both capability representations; the coordinator disclosed/corrected the check. Neither the
earlier broad substring check nor the guessed exact identifier proves HTTPS enablement/absence.

With owner enablement, corrected `https` check and empty Serve state confirmed, the approved unprivileged command
`tailscale serve --bg --https=443 http://127.0.0.1:8080` exited1: operator permission denied. No sudo, blind retry,
persistent operator/ACL change or Funnel activation occurred. Private TLS/routing remain unverified. Next checkpoint
is owner-local execution or explicit permission for that one scoped noninteractive sudo Serve command.

## 2026-10-10 — Private HTTPS verified after owner-local setup

Owner reports executing the single scoped sudo Serve command locally; agent did not run sudo. Read-only Serve JSON
verified HTTPS443, exactly the expected hostname, root proxy `http://127.0.0.1:8080`, and no allowed Funnel entry.
Private origin: `https://omarchy.tail0dc65e.ts.net`.

From the laptop, Python `ssl.create_default_context()` with hostname checks and `CERT_REQUIRED`, proxies disabled,
POSTed `{}` to `/v1/auth/google/challenge`: HTTP200, unchanged response URL/no redirect, valid UUID/nonblank nonce and
UTC expiry in the five-minute bound. Response proof values not displayed. This confirms actual trusted TLS/proxy/backend
routing from the laptop, not phone LAN/away reachability or actual Google exchange. No self-signed exception, trust-all
client, public Funnel, desktop tailnet enrollment or persistent operator change.

The bounded laptop checkpoint's build/transfer/security/challenge/restart/initial-backup-integrity/private-HTTPS checks
are complete. At that checkpoint, R17a remained unaccepted with APK/device, phone/account and secure operational
handoff gates outstanding; no phone or Google account had been touched.

## 2026-10-10 — Configured debug APK build/audit; USB authorization pending

Owner explicitly approved configured assemble/lint/APK audit and read-only ADB device/model/API discovery, not install,
reset, user-data reads or account effects. Live Herdr inventory showed only this coordinator. Exclusive Gradle slot and
`/tmp/gymtracka-android-gradle.lock` held for `./gradlew --status` (9.8.0 daemon IDLE), followed by:

```sh
./gradlew :app:assembleDebug :app:lintDebug \
  -Pgymtracka.serviceOrigin=https://omarchy.tail0dc65e.ts.net \
  -Pgymtracka.googleServerClientId=1008907790562-jij1jafae66tki2c3c2gep0pr4qr12o7.apps.googleusercontent.com \
  --console=plain
```

Exit0,50 tasks/16 executed/34 up-to-date. BuildConfig generation, KSP/Kotlin/Java compilation, DEX/package/assembly and
lint analysis/report tasks executed. Source/test/build/schema paths clean before/after; Android HEAD
`70f4af94ec19fd235fea0b8697d15ed1cecd3382`. No new JVM/instrumentation test run or full native regression claim.
Lint XML:0 errors/fatals,2 `NewerVersionAvailable` dependency warnings (JSON/PostgreSQL). Experimental AGP/deprecation
warnings and apksigner native-access warnings were nonfatal. Gradle slot released after build.

Actual `app/build/outputs/apk/debug/app-debug.apk` SHA256:
`df2415efb9558dd73f11db066d7a4274bc74dc4c19d4c83b4c35b74600f8010e`.
SDK36 apksigner verification: v2 valid, one Android debug signer, SHA1
`ee279507d3b2110fff3f2f86e49e059f4eafc3b0`, matching owner-confirmed Google registration.

Binary manifest: exact app ID, minAPI24/target37, debug build, `usesCleartextTraffic=false`, INTERNET/VIBRATE/WAKE_LOCK.
Ordinary backup remains enabled; packaged legacy/cloud/device-transfer XML excludes the no-backup session-vault namespace,
consistent with accepted noBackupFilesDir storage. This is not an OEM/cloud-transfer test or release APK assertion.
Existing debug Compose tooling/test-manifest activities remain; do not claim a release distribution audit.

Actual DEX static BuildConfig fields verified exact supplied origin/Web audience/application ID/debug build. Parsed
class definitions in17 DEX files/35264 classes: inspected JDBC, JUnit, MockWebServer/TLS, backend-fixture and selected
app-helper namespaces absent. No private env/key/config filenames packaged. These are scoped exclusion checks, not
an exhaustive third-party code/security audit. Bundled SQLite asset byte-identical to source, user_version1, SHA256
`defa08e9c464f90f0f48d8e3441fe20151803b48948647a91742c1bb82fa9f7d`.

ADB list showed one USB serial `56061FDCH0017P`, unauthorized. A second bounded read-only list after audit still showed
unauthorized; no shell metadata/user-data/reset/install command or ADB-server restart issued. Owner must authorize the
desktop on the phone, then model/API and exact-device effect confirmation are required. No Google sign-in performed.

## 2026-10-10 — Owner-authorized USB identity

Owner reports approving the desktop on the phone. A bounded `adb devices -l` check found exactly the same single USB
serial `56061FDCH0017P` now in `device` state. Target-scoped `getprop` only: Google Pixel10Pro, Android17/API37.
No other device/user data queried; no install/reset/account action. Exact-device app-only destructive/install consent
is the next checkpoint, not inferred from earlier clean-slate planning or USB authorization.

## 2026-10-10 — Explicitly approved app-only reset/install/launch

Owner explicitly approved audited APK replace/install, GymTracka-only local data/session clear and initial account-screen
launch on the confirmed Pixel10Pro serial ending0017P; no other apps/accounts/cloud data or sign-in actions.

Before effects: APK SHA256 still matches audited `df2415ef...f8010e`; exact serial/device/model/manufacturer/API and active
primary profile0 checked. `adb -s <exact serial> install --user 0 -r <audited APK>` returned Success. Then targeted
`pm clear --user 0 com.dragosfotache.gymtracka` returned Success and `am start -W --user 0 -n
com.dragosfotache.gymtracka/.MainActivity` reported Status ok. No uninstall fallback, account manipulation, user-data/UI
capture or broader reset. Activity-launch success is not owner UI observation or complete account activation evidence.
At this checkpoint, first real separate-test-account sign-in remained unreleased/unexecuted.

## 2026-10-10 — Owner-observed account gate and first sign-in release

Owner observes Sign in with Google on the reset/installed app and confirms the separate test account is available.
Owner explicitly authorizes the first actual Google/service test-account journey, with provider UI performed locally.
Instruction: ensure phone Tailscale connected, tap sign-in once, select only test account, complete prompts, report tabs
or visible error; do not retry an error blindly. No tokens, passwords or unrelated phone/account data requested.
Outcome remains pending at this checkpoint; authorization/gate visibility is not completed authentication/activation evidence.

## 2026-10-10 — First actual Google/test-account activation observed

Owner reports signing in through the real Google UI and entering the normal app: "All looks fine". The approved fresh
Pixel10Pro installation, actual matching debug signing/Web audience, private laptop HTTPS service and default mandatory
account gate were used; no retry, reset or expiry workaround reported for this attempt. Default production access requires
service-confirmed credentials and complete activation; this is an owner-observed actual fresh-install journey, not a token
or backend-log capture or independent payload readback. No credentials requested or printed.

This pass does not retrospectively repair legacy auth replay or the earlier unexplained R12c private stop. Refresh,
cloud-workout payloads/fresh-cache readback, phone-away reachability, offline/reconnect/app/account-data restart and real
workout/timer behavior remain to be exercised. A disposable completed-workout/manual-sync checkpoint is proposed;
additional test-data effects awaited explicit bounded owner permission at that checkpoint. Operational/key-custody gate remains open.

## 2026-10-10 — Disposable workout/manual-sync release

Owner authorizes the proposed single test-account workout: R17a smoke test, one bundled exercise, one20kg×5 completed
set, default/configured30-second rest timer expiry, finish and manual sync. Independent expected volume100kg/one surviving
completed set. Observe timer expiry/vibration, recorded elapsed whole-second rest and account pending/last-success/error
status; account metadata alone is not independent cloud payload/readback evidence. Owner performs UI. No delete/reset/
account-switch or destructive recovery authorized. Actual observations pending at that checkpoint.

## 2026-10-10 — Owner-reported workout/manual-sync success

Owner reports "everything worked, no error" after the released single-workout/foreground30-second timer/finish/manual-sync
instructions. This is a real owner-observed app journey, not instrumented set/rest bytes or independent cloud payload
readback. Exact pending count, history rest value and individual summary figures were not separately quoted; obtain
explicit100kg/zero-pending confirmation before treating the account as quiescent for offline/expiry testing.
No error/retry/discard/reset workaround reported. Offline restart/edit/reconnect, natural refresh expiry, phone-away,
cloud fresh-cache readback and operational key/backup custody remain open.

## 2026-10-10 — Exact summary/pending confirmation and offline/expiry release

Owner explicitly confirms100kg summary and zero pending after manual sync. Owner chooses the combined offline/expiry
checkpoint: owner disables phone connectivity, coordinator restarts only the exact GymTracka app without wiping data,
then one disposable10kg×3 workout offline, at least16minutes offline, reconnect/manual sync. This uses the contract's
natural15-minute access lifetime; no server TTL, phone/server clock, signing/credential changes or auth bypass.
Offline start has not yet been reported; do not count time from permission issuance. No additional reset/deletion/
account-switch or personal-data action authorized. Existing app/history and queued work must be retained.

## 2026-10-10 — Confirmed offline no-wipe process restart and timing

Owner reports airplane/Wi-Fi-off. Target-scoped guards: same device/model/API/profile0, global airplane flag1 and
first Wi-Fi status line disabled (no network names dumped). Exact app `am force-stop` removed its process; targeted
`am start -W` returned Status ok with a new process. No app data clear, connectivity-setting write, sign-in or UI/user-data
capture. Activity/process success alone does not prove matching offline screen/history, which owner must observe.

Offline checkpoint start after restart: `2026-10-10T13:54:28.560025Z`.
Earliest reconnect after16minutes: `2026-10-10T14:10:28.560025Z` /17:10:28EEST; owner instruction rounds up to17:11EEST.
Do not count time from initial permission or reconnect early. No polling sleep/expiry manipulation. Owner performs
cached-history check and one additional completed10kg×3 test workout offline, expecting30kg and pending changes;
report outcomes before reconnect. Natural expiry and recovered delivery remain unverified until that later checkpoint.

## 2026-10-10 — Owner-observed offline access/editing and retained queue

Owner reports all requested offline access/history/second-workout instructions worked, with pending count5. This
supports matching offline access after an actual no-wipe process restart, retained first history and queued offline
editing. No re-login, discard, data reset or error workaround reported. The expected30kg summary was covered by the
owner's broad all-steps report, not separately quoted; no exact rest-byte/cloud-payload claim.

At `2026-10-10T14:00:49.327184Z`, the minimum offline/expiry deadline had not passed (579seconds remaining). Keep
connectivity off until the deadline. Natural credential refresh/reconnect delivery remains unverified.

## 2026-10-10 — Approved account-data service restart during offline wait

Owner explicitly approves a protected backup and restart of only the dedicated backend/database containers, retaining
all data/keys, and selects mobile-data reconnect with Wi-Fi off after the offline deadline. No phone connectivity
change is performed by the agent.

Target/source-image/ownership/port guards checked; new mode600 backup `pre-account-restart-20261010T140705Z.dump`,
316884bytes, `pg_dump` exit0 and archive listing valid. Current backup contents were not printed or independently fully
restored at this checkpoint; earlier initial isolated restore is not falsely counted as restoration of this new dump.
Only the dedicated containers stopped/restarted, with changed start timestamps; all protected key/config files
byte-identical. Persistent database volume/network retained. Trusted normal-CA/hostname-verified HTTPS challenge
HTTP200/no redirect, correct root proxy and Funnel absence passed after startup. No SQL data reset, key rotation,
source/config update, host reboot or other-service restart.

At `2026-10-10T14:07:12.446018Z`, the offline minimum deadline still had not passed. This is operational restart/health
with actual test-account data present, not authenticated session or payload-continuity proof; those observations need
the later phone reconnect and fresh-cache cloud readback. The phone's5 pending edits remain untouched.

## 2026-10-10 — Reconnect pause and owner-reported spontaneous recovery

Owner reports Delivery paused after reconnect/Sync now, with another press not changing the visible state. Coordinator
instructs no discard/reset/re-login/repeated retry. At `2026-10-10T14:18:06Z`, deadline is past; owner timing/route and
current queue need confirmation. Only owned-container status (backend/database running) and anonymous trusted-HTTPS
challenge health HTTP200 were checked from laptop. Body discarded; no account credentials, phone command, sync endpoint,
config change or restart was issued during this diagnostic interval. MCP symbol-tool metadata was described only;
no source navigation/edit or logs/private-state capture performed.

Owner next reports the app synced automatically. No claim that coordinator triggered it; a finishing foreground/manual
request or connection settling is plausible, not established. Exact cause of the transient pause remains unknown. Do not
rewrite the first paused witness as a clean first-attempt recovery or retrospectively repair known earlier limitations.
Request current zero-pending/no-error/no-re-login, both expected history volumes, actual mobile/Wi-Fi-off route and
continuous-offline-until-deadline confirmation before accepting the recovery observations.

## 2026-10-10 — Confirmed recovery and owner pause

Owner explicitly confirms all three:0 pending/no error/no additional Google sign-in; continuously offline until at least
17:11EEST, then mobile data with Wi-Fi off and Tailscale connected; both test workouts present once with100kg/30kg.
This completes the scoped owner-observed offline/edit/reconnect/away-route and timed-expiry recovery observations,
including authenticated continuity after the dedicated service restart. Normal refresh is inferred from the contract's
15-minute access lifetime and confirmed16-minute offline window, not a captured credential or refresh HTTP request.
The initial Delivery paused witness remains; exact cause unestablished. No coordinator phone sync/retry/reset/config
or account/workout effect was issued to make it recover, and no known earlier limitation is retrospectively repaired.
Both histories remain cached on the phone; independent fresh-cache/cloud readback is still unverified.

Owner now pauses guided testing because of multi-minute response latency, clarifying this is not an objection to the
flow and suggesting server overload (not independently verified). Owner will test independently and start a later
session to report issues. No further questions, device/server/build/control operations, code changes, cleanup or
automatic continuation. Only documentation closeout performed after the pause; app, service, data and keys left intact.

R17a remains open: fresh-cache readback, screen-off timer/other feature paths, reauthentication as needed and secure
ongoing/off-machine backup/key custody/practical host/recovery limits. No full native/JVM/backend suite, source audit,
public/background readiness, acceptance commit or push claimed. Active documents replace stale pending state and
point to this dated record. Existing source/tests/shared contracts remain unchanged.
