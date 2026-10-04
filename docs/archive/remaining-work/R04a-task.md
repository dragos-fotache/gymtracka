> **Historical record, not current instructions.** See the [archive boundary](../README.md) and [current pickup](../../coordinator-handoff.md).

# R04a — Inert encrypted session vault

Status: **coordinator-accepted** (2026-10-04); [actual review/gates](R04a-review.md). Coordinator: gym-coordinator, successor w4:pQ.
Owner: “Let's start work on R04a then.” Parent [R04 split](R04-task.md). Stop after a; b/c require separate authorization.

## Goal / non-goals

Deliver an UNUSED storage boundary with a production Android construction path: Keystore AES/GCM, app-private
noBackupFilesDir ciphertext, whole-record atomic replacement, checked vault revision, immutable binding/grant/exact
uncertain-refresh records, safe failures, deterministic JVM and actual Android storage/Keystore tests.

No HTTP calls/refresh orchestration/access provider, Google SDK/proof persistence, Room writes/entities/migration,
account activation/visibility/sign-out orchestration, engine/background/UI/repository/state changes, private configuration,
backend operation/source/suite, dependency/build/manifest/asset changes, commit/push or subsequent slice.
Prepared/active below are stored journal labels only; neither authorizes any account scope or network request.

## Normative sources and current facts

Coordinator fully read root AGENTS, architecture, testing strategy, product behavior, R01 design, current handoff/roadmap,
R03c acceptance and the entire shared backend-contract README/map (including OpenAPI/fixtures). Read relevant sources
in full before later work. Contributor normative reads: this document, root AGENTS, R01 design §6, shared
backend-contract/authentication.md, client-compatibility.md and issues/auth-expiry-precision.md. No contributor reads
broader review reports or other role's source. Source/implementation declarations are not QA inputs.

Existing approved API facts, supplied by coordinator:
- Package `data.sync.api`: `OpaqueToken(value: String)` accepts any NONEMPTY exact opaque string; fixed redacted display.
  `ServiceAccountRef(accountId: String, displayName: String?)` validates exact canonical lowercase UUID.
  `ServiceSession(account: ServiceAccountRef, accessToken: OpaqueToken, accessExpiresAtMillis: Long,
  refreshToken: OpaqueToken, refreshExpiresAtMillis: Long)` checks inclusive wire-millis range
  [-62167219200000,253402300799999], not freshness/order/lifetime. Keep original returned expired access grants valid.
- `ServiceRefreshAttempt.fromJson(idempotencyKey: String, requestJson: String)` restores and retains EXACT original text
  after strict closed refresh-body/key validation; `.capture(key, refreshToken)` encodes once. Properties: key, body,
  parsed `refreshToken: OpaqueToken`. UUID key is header-only; `requestJson` is sensitive. No reserialization on restore.
- Package `data.sync.http`: internal `ServiceOrigin.parse(String)` accepts strictly root HTTPS origin, canonicalizes
  scheme/ASCII host/effective port; properties scheme/host/port; equality is origin identity, display redacted.
  Internal `ServiceSessionBinding(origin: ServiceOrigin, accountId: String, sessionGeneration: Long)` validates canonical
  account and POSITIVE generation including copy, equality covers all three, display redacted.
- Existing `SyncModelException` is fixed safe model failure; existing `requireWorkoutUuid(value, field)` /
  `requireWireMillis(value, field)` are reusable validation helpers. Existing approved production files remain unchanged.
- App min24/target37, core-library desugaring, coroutines/test/JUnit available; no new library is needed.
- Manifest allowBackup=true, fullBackupContent=@xml/backup_rules, dataExtractionRules=@xml/data_extraction_rules.
  Current XMLs are sample/defaults; ordinary user database backup must NOT be disabled for credential exclusion.
- Room runtime9 / bundled source asset1 unchanged. No current vault exists or is constructed by the app.

## Approved design and API (package data.sync.session; all internal)

Models must be immutable, constructor AND copy validated, with fixed redacted toString (including snapshot/exception).
No arbitrary length cap, normalization/trimming, clock, UUID generation, Google/exchange/proof field or raw error cause.

```kotlin
enum class VaultSessionPhase { PREPARED, ACTIVE }
data class VaultPendingRefresh(val operationId: String, val attempt: ServiceRefreshAttempt)
// immutable data classes with validating constructors/copy and redacted display
data class VaultSessionRecord(
    val binding: ServiceSessionBinding,
    val operationId: String, // durable activation/session operation, canonical UUID
    val phase: VaultSessionPhase,
    val session: ServiceSession,
    val pendingRefresh: VaultPendingRefresh? = null,
)
data class VaultSnapshot(val revision: Long, val record: VaultSessionRecord?)

enum class VaultFailure {
    INVALID_RECORD, UNREADABLE, UNSUPPORTED_VERSION, UNAVAILABLE, CONFLICT, REVISION_EXHAUSTED
}
class SessionVaultException(val failure: VaultFailure) : RuntimeException // fixed message; cause closed null

interface SessionVault {
    suspend fun load(): VaultSnapshot
    suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot
}
interface VaultFileStorage {
    fun read(): ByteArray? // null ONLY for genuinely absent committed file; no plaintext storage
    fun replace(bytes: ByteArray) // complete durable replacement or exception; outcome may be uncertain on failure
}
interface VaultCipher {
    fun seal(plaintext: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}
fun interface VaultKeyProvider {
    fun key(createIfMissing: Boolean): javax.crypto.SecretKey
}
class EncryptedSessionVault(
    storage: VaultFileStorage,
    cipher: VaultCipher,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    mutex: Mutex = Mutex(),
) : SessionVault
object VaultRecordCodec {
    fun encode(snapshot: VaultSnapshot): ByteArray
    fun decode(bytes: ByteArray): VaultSnapshot
}
class AesGcmVaultCipher(keyProvider: VaultKeyProvider) : VaultCipher
class AndroidKeystoreVaultKeyProvider(alias: String) : VaultKeyProvider

enum class VaultWriteStage { OPEN_TEMP, SYNC_TEMP, RENAME, SYNC_DIRECTORY }
// deterministic failure seam, default no-op; never owns or logs bytes
fun interface VaultWriteFaults { fun before(stage: VaultWriteStage) }
class AndroidVaultFileStorage(
    context: Context,
    namespace: String = "gymtracka-session-vault",
    faults: VaultWriteFaults = VaultWriteFaults {},
) : VaultFileStorage {
    val directory: File // context.applicationContext.noBackupFilesDir / namespace
    val file: File // directory / "session.vault"
}
object AndroidSessionVault {
    fun create(context: Context): EncryptedSessionVault
    fun createForTesting(context: Context, namespace: String): EncryptedSessionVault
}
```

Use exact class/constructor/property/method names above so QA does not inspect declarations. All three models are data
classes with validating copy/equality; attempt equality remains its approved identity semantics. Namespace is one safe path component
`[a-z][a-z0-9_-]{0,63}`; invalid aliases/names use safe errors. Test factory requires namespace prefix `gymtracka-test-`,
uses alias `com.dragosfotache.gymtracka.session.<namespace>`; production alias suffix is `gymtracka-session-vault`.
Default factory and repeated opens share ONE process-wide mutex (including test factory), no construction I/O on Main.
No multiprocess support/claim: later app owns one process singleton; injectable mutex only for independent storage tests.
Provider alias permits the stated safe app prefix/suffix only, so tests cannot touch unrelated keys.

### Model validation / compare-and-set

- Record account EXACTLY equals grant account; binding includes approved service origin/positive device generation.
  Both operation IDs are canonical UUIDs. Pending constructor/restore additionally reuses approved
  `parseServiceResponseObject(requestJson)` original-scalar fidelity BEFORE trusting the approved attempt representation:
  org.json numeric-overflow-to-string fallback must never satisfy a quoted refresh token. Valid exact text is unchanged.
  If pending exists, parsed attempt token equals the current stored grant's refresh token exactly. Prepared and active may both retain an uncertain attempt; no automatic state promotion.
- Snapshot revision nonnegative; revision0 requires null record. Empty/missing store returns (0,null).
  Clearing means atomically writing a **positive-revision encrypted tombstone** (null record), NOT unlink/reset to0:
  otherwise delayed old revision could reinstall credentials (ABA). It touches no Room/domain/cache or key deletion.
- CAS reads/decrypts/validates committed state under mutex, compares exact expected revision, increments once with checked
  Long overflow, encodes/encrypts and replaces one full snapshot. Mismatch -> CONFLICT, overflow -> REVISION_EXHAUSTED,
  invalid expected negative -> INVALID_RECORD. No write/crypto-key creation for these refusals. Return only after
  durable write succeeds. Re-read disk per operation; no success cache or implicit repair.
- Whole snapshot includes grant AND pending attempt. A replacement completing rotation can therefore change the grant
  and clear pending in ONE encrypted commit. It is caller-supplied storage only, not network rotation logic.
- Read corrupted/unsupported/missing-key data fails CLOSED, preserving original file/identity/attempt. Never infer missing
  store from an access error, authentication-tag failure or invalid contents. No old-backup fallback after malformed base.
- Failures never authorize a retry under a new key, grant expiry extension, revocation, account/cache deletion or activation.
  Legacy incompatible accepted replay remains incompatible; R04a never stores/rounds malformed service responses.

### Version1 binary record (independent fixture grammar)

Use big-endian signed Java DataInput/Output values, strict UTF-8 length-prefixed strings (Int byte length >=0, then bytes),
no Java modified-UTF8/writeUTF, replacement decoding, surplus/trailing bytes, negative lengths or recursive object graph.
Before allocating any string require length <= remaining bytes. Encode rejects unpaired UTF16 surrogates rather than
altering sensitive text. Every presence/phase tag is ONE byte exactly 0/1. No arbitrary content cap.

```text
Int magic 0x47545331 (GTS1); Int version1; Long revision; byte recordPresent
if recordPresent1:
  string canonical origin (https://host:port; IPv6 bracketed, explicit effective port)
  string accountId; Long sessionGeneration; string operationId; byte phase (0 PREPARED,1 ACTIVE)
  byte displayNamePresent; [string displayName]
  string accessToken; Long accessExpiresAtMillis; string refreshToken; Long refreshExpiresAtMillis
  byte pendingPresent
  if pendingPresent1: string pending operationId; string idempotencyKey; string ORIGINAL requestJson
```

Decode reconstructs approved models/attempts with validation, requires canonical stored origin spelling and complete EOF.
Bad magic/truncated/invalid/type/binding/token/UTF8/trailing -> UNREADABLE. Supported magic but other version ->
UNSUPPORTED_VERSION, never destructive upgrade. Direct model/encode invalid inputs -> INVALID_RECORD.
Exact valid requestJson (whitespace/order/escapes/nonASCII/zero/control escapes) survives encode/decode and ciphertext.
Tokens/display name retained verbatim; nullable name differs from empty. Preserve actual expiry bounds and original values.

### Encryption and Android I/O

- AES/GCM/NoPadding, AndroidKeystore nonexportable AES256 key with ENCRYPT|DECRYPT, GCM, no padding, randomized encryption
  required, no user-auth requirement (future background refresh), no StrongBox/hardware mandate/software-key fallback.
  Fresh provider-generated 12-byte IV per seal, 128-bit tag; decrypt key(false) never generates/replaces missing key.
  Key creation is lazy key(true) on seal ONLY; provider retrieval failures or occupied wrong-kind aliases are UNAVAILABLE,
  not regeneration/overwrite. Verify encryption output uses a 128-bit tag; never assume a different provider default.
- Sealed format: Int magic0x47545631 (GTV1), Int version1, exactly12 IV bytes, remaining ciphertext+16byte tag.
  AAD is the 8byte big-endian header (magic/version) followed by UTF8 `GymTracka.SessionVault`.
  Min length36; malformed magic/length/tag -> UNREADABLE, different supported-magic version -> UNSUPPORTED_VERSION.
  No raw crypto cause/IV/token/body in errors. Do not log/suppress unexpected causes. No key material in files/Room.
- Production uses credential-protected application noBackupFilesDir/namespace only; never external/device-protected dir,
  preferences/cache/filesDir. All final/temp/journal artifacts stay there. File names disclose no account/service IDs.
- Android replacement: write ciphertext to `session.vault.new`, sync FileDescriptor, close, atomic same-directory
  `android.system.Os.rename`, then fsync directory with Os.open(O_RDONLY|O_NOFOLLOW), Os.fstat directory-type verification, fsync/close.
  O_DIRECTORY is not exposed in the current public Android compile stub; do not invent a hidden flag. Check errors explicitly;
  android.util.AtomicFile.finishWrite swallows important fsync/rename failures and is not the gate. No plaintext temp.
  Ignore/unlink owned stale .new before future writes; never promote it on load. Before rename, failure keeps committed
  base unchanged; after rename failure is uncertain (old or complete new after crash), never success/automatic retry.
  Directory creation/permissions failures fail safe; fsync the noBackup parent on EVERY successful replacement
  (a prior failed first write may already have created the namespace). A successful first commit cannot lose that directory
  entry after crash. read null only absent final, not directory/unreadable file.
  Reject ORIGINAL base/namespace/final/temp symlinks or nonregular file shapes, not just canonical paths outside noBackup.
  Use checked original-path nonfollowing temp creation mode0600 and checked stale-temp unlink; no ignored File.delete failures.
  Namespace/alias invalid shapes use INVALID_RECORD safe errors. Enforce credential-protected context.
- Byte arrays supplied by storage/seam are owned defensively; no retained caller-mutable ciphertext/plaintext cache.
  Clear transient plaintext byte arrays best effort in finally; immutable strings/GC/flash secure erasure is NOT claimed.
- Run storage/crypto on injected IO dispatcher under mutex. Waiting/pre-I/O cancellation must not write. Preserve
  cancellation (including supplied CancellationException identity for active injected failures) without raw cause wrapping.
  Current caller cancellation dominates racing seam errors. If caller cancellation races synchronous commit, whole new
  file may exist: cancellation is NOT rollback proof. No
  unstructured coroutine/jobs/NonCancellable network. Later orchestration must reload/fence before transmitting.

## Backup exclusion

Worker replaces sample comments with focused explicit defense-in-depth `<exclude domain="root"
path="no_backup/gymtracka-session-vault"/>` in legacy full-backup-content and BOTH cloud-backup/device-transfer sections
in modern extraction XML. Keep allowBackup=true, no include-only rule or whole-database exclusion. noBackupFilesDir is
platform-excluded for all transports even if included; XML covers production namespace additionally. Do not add new iOS
transfer configuration or backup agent. Instrumentation checks actual path placement, manifest and packaged XML; coordinator
reviews merged debug/release manifests/APK. A real hosted-cloud/OEM device-transfer run is not claimed by these checks.

## Disjoint ownership

Worker READ/WRITE production allowlist ONLY:
- NEW files in `app/src/main/java/com/dragosfotache/gymtracka/data/sync/session/` matching responsibilities above.
- Existing `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml`.
- READ ONLY existing approved `data/sync/api`, `data/sync/http` origin/binding, `data/sync/model` validation and codecs
  as needed; relevant AGENTS/normative docs. No tests/fixtures/review reports/QA transcripts or bytecode from test outputs.

QA READ/WRITE TEST allowlist ONLY (NEW):
- `app/src/test/java/com/dragosfotache/gymtracka/data/sync/session/SessionVaultTest.kt`
- `.../VaultRecordCodecTest.kt`, `.../VaultCipherTest.kt`, `.../VaultTestFixtures.kt`
- `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/session/SessionVaultAndroidTest.kt`
No production source/declarations/diffs/bytecode, Worker transcripts, broad test reports or existing tests. Use supplied
normative API facts and ask coordinator for missing integration facts, never navigate through production definitions.
Own filtered test reports and normative docs are allowed. Coordinator supplies build/resource facts and reviews both.
Reports fire-and-forget `herdr agent prompt gym-coordinator "..."`, NO --wait/agent wait. No contributor backend/services,
emulator setup, global config changes, commits or other files. Fresh native sessions per slice, corrections same session.
Restricted Kotlin mcpls preferred; bounded unavailable/indexing fallback exact own-file reads/compiler only.

Gradle outputs shared: coordinator explicitly grants ONE slot at a time. Worker may compile production when granted;
QA may run own filtered JVM/compile tests when granted. No Gradle until assigned slot; no concurrent build/test tasks.

## Acceptance matrix / coordinator gates

JVM: both phases/null/name/token/expiry boundaries/validation+copies, exact independently written request bodies,
unsupported/corrupt formats/UTF8/truncation/length/trailing/unknown tags, empty versus tombstone/ABA, checked CAS overflow,
A/B/origin/generation binding, atomic whole grant+attempt states on restart, read/seal/write failures without silent empty,
cancellation/precommit/uncertain commit, concurrent CAS exactly one winner, redacted displays/closed causes.
Actual JCA crypto JVM: randomized IV/no plaintext, exact roundtrip, tampered header/IV/ciphertext/tag/truncation/wrong/missing
key, create flags, no key creation on read failure, ciphertext input ownership. No network/backend.
Android (test-owned isolated namespace/alias ONLY; clean own resources): real Keystore no exported key, settings/roundtrip/
reopen/exact attempts/empty tombstone, key deletion causes fail-closed no regeneration, actual atomic-file .new crash residue,
injected pre-rename/post-rename failures and restart/CAS, ciphertext no token/body, all path artifacts under noBackup,
packaged backup XML+manifest exclusions and ordinary backup preserved, production factory inert construction.

Coordinator independently reviews full production/tests/new files and boundaries; no count alone is acceptance.
Run focused JVM, then forced full offline JVM + production/Android-test Kotlin + assembleDebug/lintDebug serially.
Run filtered SessionVaultAndroidTest on owned emulator where available, inspect every actual XML testcase/failure/error/skip.
No personal phone or preexisting unrelated emulator/control. Validate APK helpers excluded, bundled asset hash unchanged,
Room9/schema/build/app-state baseline unchanged, entire shared checkout byte-preserved and both git diff checks clean.
Update architecture/testing/README/roadmap/handoff/parent and R04a-review with actual evidence/limits. No real-backend pass
is needed for an UNUSED storage-only vault; preserve earlier R03c evidence without claiming a rerun.

References researched by coordinator: Android Keystore system, Auto Backup (noBackup always excluded including D2D),
android.util.AtomicFile API (not a lock; own mutual exclusion). https://developer.android.com/privacy-and-security/keystore
https://developer.android.com/identity/data/autobackup https://developer.android.com/reference/android/util/AtomicFile
These do not claim hardware-backed security, OEM-transfer runtime or minimum-API execution.
