> **Historical split plan.** Outstanding R03d requirements live in the [current roadmap](../../remaining-work-roadmap.md); see the [archive boundary](../README.md).

# R03 — Android HTTP transport subtask split

Status: **R03a–c accepted; R03d deferred** (R03c acceptance 2026-10-04).
R03c passed unchanged auth10/10 and broad56/56 after separately owned backend precision correction;
historical incompatible accepted replay remains an explicit legacy limitation, not counted repaired.
The owner instructed “Go ahead and start work at task R03c”; see its [shared specification](R03c-task.md).
This authorization does not include later slices, commit/push or app activation.
The owner instructed “go ahead and start work at r03b”; its [specification](R03b-task.md) and
[review](R03b-review.md) record bound sync endpoints/shared errors and actual gates: 477 offline JVM, 46 real-backend
through production adapter/test TLS relay, three filtered API37 Android tests, compilation/assembly/lint/security checks.
The owner requested this split after R02d acceptance, then instructed “start work at R03a”. The detailed
[R03a shared specification](R03a-task.md) supplies its normative API/security contract, disjoint contributor allowlists
and verification gates. The [R03a review](R03a-review.md) accepts the secure primitive with 423 offline JVM tests,
five filtered Android transport tests and one signed-out startup smoke on API 37, plus build/lint/security gates.
Each later slice still needs separate authorization/specification and independent review.
The original split was documentation-only; R03a execution does not authorize commits/pushes or transport activation.

## Baseline and authority

- R02a–d are accepted: original-text duplicate checking, family identity validation, typed failures, challenge/auth codecs
  and immutable exact-body attempts. [R02d review](R02d-review.md): 340 offline JVM tests in 32 suites, build/lint gates.
  Those are earlier evidence, not a fresh R03 gate. R02d remains uncommitted; preserve all current changes in both trees.
- `GymSyncApi` covers push/pull/snapshot; `ServiceAuthApi` covers purpose-bound challenge, typed exchange/refresh and logout.
  R03a now supplies OkHttp 5.5.0, secure origin/scoped cancellable HTTP I/O and INTERNET/cleartext-refusal wiring.
  R03b adds the bound production sync endpoint adapter/shared strict errors. R03c now implements the unused auth adapter with
  [passing client and corrected-service gates](R03c-review.md). Historical incompatible accepted replay is preserved as
  a known limitation; fresh issuance/rotation is compatible. Configured origin, vault, engine and account activation remain absent.
- JVM-only `backend/HttpGymSyncApi.kt` includes a JDK loopback client and scripted seam. Its exact-request and outcome
  matching lessons are useful, but production must not import java.net.http, JDBC, fixture credentials or that adapter.
  In particular its error-code regex/length limit is test-only, not a production contract rule.
- [Roadmap](../../remaining-work-roadmap.md), [integration design](R01-design.md),
  [architecture](../../architecture.md), [testing strategy](../testing-history.md) and finalized shared
  [service protocol](../../../shared-docs/docs/backend-contract/service-protocol.md),
  [authentication](../../../shared-docs/docs/backend-contract/authentication.md),
  [client compatibility](../../../shared-docs/docs/backend-contract/client-compatibility.md),
  [account lifecycle](../../../shared-docs/docs/backend-contract/account-lifecycle.md) and
  [OpenAPI](../../../shared-docs/docs/backend-contract/openapi.yaml) supply the requirements.

## Split at a glance

| Slice | Deliverable | Depends on | Milestone / status |
|---|---|---|---|
| **R03a** | Secure origin/configuration and cancellable Android-compatible HTTP primitive | Accepted R02 failure/representation boundaries | Personal prerequisite; [accepted](R03a-review.md) |
| **R03b** | Production push/pull/snapshot adapter and shared service-error decoding | R03a, accepted R02a–c and existing durable/result codecs | Personal sync transport; [accepted](R03b-review.md) |
| **R03c** | Production challenge/exchange/refresh/logout adapter | R03a, R03b shared error support, accepted R02c/d | Personal auth transport; [accepted with legacy limitation](R03c-review.md) |
| **R03d** | Account export/delete/status transport and export response streaming | R03a–c, future R02e; lifecycle feature needs | Public/deferred; not started |

Default execution/review order: **a → b → c**, then stop with the personal R03 transport baseline. **d** comes with
export/deletion/status work, not automatically next. These are coherent review gates, not four concurrent assignments.
If one proves too large at detailed pickup, split it further explicitly rather than silently broadening a contributor task.

## R03a — Secure origin and cancellable HTTP primitive

**Goal:** establish one reusable Android-compatible transport and secure construction boundary, without endpoint adapters.

Included:
- Select the smallest supported HTTP dependency at pickup, verify minSdk 24/coroutine compatibility and document the choice.
  Do not select a version from stale planning text. Add only required build wiring and INTERNET permission.
- Define immutable configured service identity: HTTPS scheme/host/effective port, root-only origin, normal certificate and
  hostname verification; reject user-info, query, fragment, unexpected base paths and arbitrary caller absolute routes.
  Missing configuration must remain representable without breaking signed-out use; do not invent a URL or wire networking
  into the app shell. Credential binding to this identity is a later vault/session responsibility.
- Fixed-origin request construction, safe UTF-8 body/query/header encoding and an injectable narrow I/O seam. Preserve
  opaque query/token values without trimming/decoding; reject header/URL injection safely. Disable redirect following,
  cookies/caching and automatic request replay so credentials/bodies cannot move to another origin or execute twice.
- Suspend I/O, coroutine cancellation that cancels the actual call and closes responses, explicit timeouts and safe
  resource ownership on success/failure/late callback. No raw body/header/token/URL values in exceptions/logs/toString.
- Common bounded whole-second Retry-After parsing, with overflow-safe saturation to the existing five-minute cap;
  malformed/nonpositive/date hints fall back to caller backoff. This parses hints; it does not schedule retries.
- Decide response consumption/UTF-8/content-type/truncation policy in the detailed spec. No silent replacement/truncation
  or arbitrary permanent aggregate-size cap. Response ownership must permit later export streaming without a second
  client/security policy; R03a does not implement export or buffer an export as a String.

**Independent gate:** URL/origin/header/query/body tables, exact UTF-8 bytes, redirects that receive no forwarded request,
normal TLS validation, cancellation/late-response/closure, timeout and Retry-After bounds using scripted I/O and a local
TLS/HTTP test server as appropriate. Verify merged production manifest/cleartext policy and minSdk construction path.
Test-only loopback access/trust must remain test-owned: no permissive trust manager, release cleartext flag or production
"insecure mode". Resolve the concrete isolated-HTTP harness seam during this spec, not by weakening the secure factory.

**Stop:** reusable transport only. No GymSyncApi/ServiceAuthApi implementation, bearer refresh, domain/store writes,
Google SDK, vault, account UI or endpoint/deployment/OAuth configuration.

## R03b — Production sync endpoints and service-error boundary

**Goal:** implement GymSyncApi over R03a, bind it to an immutable service/account-session credential boundary, and prove
wire compatibility without implementing an engine.

Included:
- POST push and GET pull/snapshot routes using existing production codecs; frozen mutation objects are embedded unchanged
  in submitted order. Preserve push count/duplicate/envelope validation and outcome count/order/ID matching.
- Opaque query forwarding and existing page-limit defaults/bounds; decode complete canonical batches/results, including
  normal per-mutation rejection and empty pages. No null-token fallback, token decoding or pull checkpoint publication.
- Shared strict service-error reader that ignores presentation message and retains unknown nonempty string codes without
  the test adapter's regex/length whitelist. Reject malformed/duplicate/type-invalid error shapes safely.
- Operation + status routing for access vs foreground reauth, invalid vs expired read tokens, 413, 429/transient server
  faults, unknown permanent errors and malformed responses. Status precedence follows R02c; wrong-context token codes
  cannot become expiry. Reuse R03a hint parsing and R02 failure types; preserve original cancellation and closed causes.
- Select bearer once per explicit call under the supplied immutable session binding. Do not fetch a different account's
  current token midway through a bound operation. No refresh or automatic resend on 401; R04 owns that recovery.
- Adapt the Android-owned isolated interoperability harness to exercise the production adapter rather than only the JDK
  adapter. Preserve existing fixtures/guards and scenarios; coordinator owns shared-harness integration facts/changes.

**Independent gate:** recorded exact bytes/headers/queries, all three routes, complete/mismatched/truncated/malformed
responses, status/code/context tables, unknown codes and no-leak/cancellation/one-send behavior. Run isolated real-backend
sync interoperability through the actual production-compatible adapter where the harness permits, with actual XML counts
and explicit environment limits. A scripted pass or old JDK-adapter pass is not a new production HTTP pass.

**Stop:** network API only. No outbox claiming/acknowledgment, Room writes/migration, sync loop, snapshot staging/publication,
account activation, automatic refresh/retry or simulator. Transport failures never mean the service committed nothing.

## R03c — Production service-auth endpoints

**Goal:** implement ServiceAuthApi using R02d's purpose/codec/attempt boundaries and R03's shared secure transport.

Included:
- Challenge for all four purposes, exchange, refresh and logout, with correct routes/methods/statuses. All these routes
  authenticate their own input and do not require or automatically attach a service bearer credential.
- Send exchange/refresh `attempt.requestJson` as the complete UTF-8 body and the retained UUID as Idempotency-Key.
  Never rebuild from parsed fields, change the key, extend expiry or turn a lost response into a new attempt.
- Decode challenge/session grants with the approved codecs; accept original expired-access grants as representation,
  not as authorization. Logout succeeds only for a valid 204 response; its network failure does not block future local
  sign-out orchestration. No durable logout queue.
- Extend operation-aware failure routing for Google-proof rejection, invalid refresh/foreground reauthentication,
  idempotency conflict, malformed grants, size/rate/transient failures and unexpected statuses. Freeze the exact public
  category/status table in the detailed spec; do not conflate bootstrap proof rejection with service bearer expiry.
- Keep credentials entirely transient at this transport layer. Same-account refresh checks, serialized refresh, uncertain
  attempt persistence, stale-session grant fencing and offline sign-out state belong to R04/R09, not the adapter.

**Independent gate:** purpose/default mapping, no-bearer bootstrap requests, exact restored exchange/refresh bytes + keys
on repeated explicit calls, opaque-token/grant fidelity, bodyless logout, status/error/redaction, cancellation and single-send
behavior. Real challenge/logout/refresh tests can use only approved isolated harness credentials; verified Google exchange
requires an available test-provider seam or separate live authorization. Do not fabricate Google verification, create an
auth bypass or mark unexecutable external cases as passing/skipped success.

**Stop:** auth HTTP boundary only. No encrypted storage, refresh/access orchestration, Google SDK/UI, sessions selected in
Room, actual sign-in/sign-out flow, lifecycle proof requests or private configuration. The app remains unactivated.

## R03d — Deferred account-lifecycle transport and streaming

**Goal:** implement only the transport needed by R14/R15, once R02e supplies account-bound proof/export/receipt/status models.

Included:
- Fresh-proof export/delete/status routes, no bearer-only substitution; operation-specific response/failure validation.
- Exact deletion request body/key preservation and account-bound receipt/status results; no new-account inference from
  auth failure, generic 404 or an old receipt. No export/status replay key where the contract supplies none.
- Owned export response streaming/complete-download detection, safe cleanup/cancellation and no unbounded whole-export
  String buffering. Partial/malformed/unsupported-version output is never a successful export; restart requires fresh proof.
- Account-binding checks at the approved boundary; physical file publication/Android save UI belong to R14 and cache/
  credential cleanup/journaling to R15/R09. No destructive action or personal account test during adapter development.

**Independent gate:** strict proof/route/status/account tables, exact deletion identity, lost/truncated export/cleanup,
version handling and no leakage. Isolated-service lifecycle tests only where authorized test-proof setup exists; real Google,
user-save and irreversible account journeys remain separately authorized live gates. If export streaming and delete/status
are too large together, create reviewed R03d subplans when picked up; no speculative implementation contract now.

**Stop:** no UI export/delete action, domain cleanup, new signup flow, service-side purge/backups, deployment or public-release
claim. Acceptance of R03a–c does not mark R03d or the full future-public R03 requirement complete.

## Downstream dependency refinement

- R04 session/vault machinery needs accepted **R03a + R03c**; the default sequence already accepts b before c.
- R06 push requires **R03b + R04 + R05**; R07/R08 continue to use that same sync adapter. R10 tests the delivered engine,
  not just DTO/HTTP calls. R11 supplies foreground Google identity; R12 barriers still precede production activation.
- R14/R15 need the relevant **R02e + R03d** lifecycle surfaces and R11 fresh-proof orchestration. They do not block the
  personal R03a–c transport milestone.
- Actual phone/laptop HTTPS reachability on LAN and away, Google audience/signing setup and owner-approved account/data
  remain external R17 inputs. Deterministic R03 work can proceed; this split does not provision connectivity or OAuth.

## Per-slice workflow and common gate

Coordinator prepares one normative spec with public API/harness facts and exact disjoint allowlists. Worker reads/writes
production only; QA independently authors tests without production/Worker access. Fresh contributor sessions per authorized
slice; same sessions for review corrections. Coordinator owns full review, shared fixture/build/configuration integration
and serial Gradle slots. No assignments are launched by this document.

Each implementation slice needs relevant narrow tests plus forced offline JVM, Kotlin/Android-test compilation,
assembly/lint, executed XML inspection, both diff checks and updated Android docs. Add concrete-client TLS/Android execution
and isolated backend gates where relevant; document unavailable external gates separately, never claim them from source
compilation or cached tasks. Do not run backend source review/Maven suites or use personal configuration/databases.

**Original planning stop:** the split itself delivered documentation only. The owner subsequently authorized/accepted
R03a and R03b, then authorized/accepted [R03c](R03c-review.md). R04/R03d, commit/push and activation remain unauthorized.
The verification below records the original planning gate, not implementation evidence.

### Documentation verification

Coordinator checked the split against accepted R02 APIs, current production/test transport inventory, R01 dependencies
and the finalized v1 contract. Both repository diff checks, new-file whitespace and 117 local links across the five
planning documents passed. A 239-file current-tree inventory confirms only the four intended existing Android docs
changed plus this new plan; existing source/tests/build/schema/assets, R02d work and nested shared-docs are preserved.
No build, test, device, backend or Google verification was executed for this documentation-only task.
